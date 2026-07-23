package com.boykta.vpn.service

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * FULL BIDIRECTIONAL TUN → SOCKS5 Bridge — IPv4 + IPv6.
 *
 * Traffic path:
 *   Device app → TUN (raw IP packet) → TunBridge → SOCKS5:10808 → Xray → VPN Server → Internet
 *   Internet   → VPN Server → Xray → SOCKS5:10808 → TunBridge → crafted IP packet → TUN → Device
 *
 * Key features:
 *   • Full IPv4 and IPv6 TCP support (SOCKS5 ATYP 0x01 and 0x04)
 *   • UDP sessions use SOCKS5 UDP ASSOCIATE, preserving source/destination ports
 *   • DNS and game traffic both travel through the configured Xray outbound
 *   • TCP keep-alive + no-delay on every SOCKS5 socket
 *   • 256 KB socket buffers for gaming/streaming throughput
 *   • STALL DETECTION: 180 s idle → close + inject RST
 *   • Immediate ACK on data receipt — keeps device TCP window open
 */
class TunBridge(
    private val tunPfd: ParcelFileDescriptor,
    private val socksPort: Int,
    private val vpnService: VpnService,
) {

    private data class UdpReply(
        val address: ByteArray?,
        val port: Int,
        val payload: ByteArray
    )

    private data class SocksAddress(
        val address: InetAddress,
        val port: Int
    )

    companion object {
        private const val TAG = "TunBridge"

        // Read buffer — larger than MTU to handle any segmentation
        private const val READ_BUF = 8192

        // TCP flag bits
        private const val FLAG_FIN = 0x01
        private const val FLAG_SYN = 0x02
        private const val FLAG_RST = 0x04
        private const val FLAG_PSH = 0x08
        private const val FLAG_ACK = 0x10

        private const val PROTO_TCP  = 6
        private const val PROTO_UDP  = 17
        private const val IP4_HDR    = 20
        private const val IP6_HDR    = 40   // fixed IPv6 header
        private const val TCP_HDR    = 20

        // Close idle TCP/UDP sessions after 180 s. Game sessions regularly use
        // long periods with no datagrams, so this is deliberately not aggressive.
        private const val STALL_TIMEOUT_MS  = 180_000

        // Connect timeout: 15 s — Cloudflare edge may be slow to ACK under load
        private const val CONNECT_TIMEOUT_MS = 15_000
    }

    private val scope    = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sessions = ConcurrentHashMap<String, TcpSession>()
    private val udpSessions = ConcurrentHashMap<String, UdpSession>()
    private val tunIn    = FileInputStream(tunPfd.fileDescriptor)
    private val tunOut   = FileOutputStream(tunPfd.fileDescriptor)
    private val tunLock  = Any()

    @Volatile private var running = false

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    fun start() {
        running = true
        VpnLogManager.sys("TunBridge started → SOCKS5 127.0.0.1:$socksPort  (TCP+UDP, IPv4+IPv6)")
        scope.launch { readLoop() }
    }

    fun stop() {
        running = false
        scope.cancel()
        sessions.values.forEach { it.close() }
        udpSessions.values.forEach { it.close() }
        sessions.clear()
        udpSessions.clear()
        runCatching { tunIn.close() }
        runCatching { tunOut.close() }
        VpnLogManager.sys("TunBridge stopped")
    }

    // ── TUN read loop ─────────────────────────────────────────────────────────

    private suspend fun readLoop() = withContext(Dispatchers.IO) {
        val buf = ByteArray(READ_BUF)
        while (running && isActive) {
            try {
                val len = tunIn.read(buf)
                if (len < 1) continue
                dispatchPacket(buf.copyOf(len))
            } catch (e: Exception) {
                if (running) VpnLogManager.warn("TUN read error: ${e.message}")
                break
            }
        }
    }

    // ── Packet dispatcher — IPv4 and IPv6 ────────────────────────────────────

    private fun dispatchPacket(pkt: ByteArray) {
        if (pkt.isEmpty()) return
        when ((pkt[0].toInt() and 0xFF) ushr 4) {
            4 -> dispatchIPv4(pkt)
            6 -> dispatchIPv6(pkt)
        }
    }

    // ── IPv4 dispatcher ───────────────────────────────────────────────────────

    private fun dispatchIPv4(pkt: ByteArray) {
        val ihl = (pkt[0].toInt() and 0x0F) * 4
        if (pkt.size < ihl + 4) return
        val proto  = pkt[9].toInt() and 0xFF
        val srcIp4 = pkt.copyOfRange(12, 16)
        val dstIp4 = pkt.copyOfRange(16, 20)
        when (proto) {
            PROTO_TCP -> handleTcp4(pkt, ihl, srcIp4, dstIp4)
            PROTO_UDP -> handleUdp4(pkt, ihl, srcIp4, dstIp4)
        }
    }

    // ── IPv6 dispatcher ───────────────────────────────────────────────────────

    private fun dispatchIPv6(pkt: ByteArray) {
        if (pkt.size < IP6_HDR + 4) return
        // Skip extension headers for now — handle only well-known next-headers
        val nextHdr = pkt[6].toInt() and 0xFF
        val srcIp6  = pkt.copyOfRange(8, 24)
        val dstIp6  = pkt.copyOfRange(24, 40)
        when (nextHdr) {
            PROTO_TCP -> handleTcp6(pkt, srcIp6, dstIp6)
            PROTO_UDP -> handleUdp6(pkt, srcIp6, dstIp6)
            // ICMPv6, other: drop silently
        }
    }

    // ── IPv4 TCP handling ─────────────────────────────────────────────────────

    private fun handleTcp4(pkt: ByteArray, ihl: Int, srcIp: ByteArray, dstIp: ByteArray) {
        if (pkt.size < ihl + TCP_HDR) return
        val srcPort     = pkt.u16(ihl)
        val dstPort     = pkt.u16(ihl + 2)
        val seqNum      = pkt.u32L(ihl + 4)
        val flags       = pkt[ihl + 13].toInt() and 0xFF
        val tcpDataOff  = ((pkt[ihl + 12].toInt() and 0xFF) ushr 4) * 4
        val payload     = if (pkt.size > ihl + tcpDataOff) pkt.copyOfRange(ihl + tcpDataOff, pkt.size) else ByteArray(0)
        val key         = "4:${srcIp.ip4}:$srcPort→${dstIp.ip4}:$dstPort"

        dispatchTcpEvent(key, flags, seqNum, payload, dstIp, dstPort, srcIp, srcPort, isIPv6 = false)
    }

    // ── IPv6 TCP handling ─────────────────────────────────────────────────────

    private fun handleTcp6(pkt: ByteArray, srcIp: ByteArray, dstIp: ByteArray) {
        val tcpOff = IP6_HDR
        if (pkt.size < tcpOff + TCP_HDR) return
        val srcPort     = pkt.u16(tcpOff)
        val dstPort     = pkt.u16(tcpOff + 2)
        val seqNum      = pkt.u32L(tcpOff + 4)
        val flags       = pkt[tcpOff + 13].toInt() and 0xFF
        val tcpDataOff  = ((pkt[tcpOff + 12].toInt() and 0xFF) ushr 4) * 4
        val payload     = if (pkt.size > tcpOff + tcpDataOff) pkt.copyOfRange(tcpOff + tcpDataOff, pkt.size) else ByteArray(0)
        val key         = "6:${srcIp.ip6}:$srcPort→${dstIp.ip6}:$dstPort"

        dispatchTcpEvent(key, flags, seqNum, payload, dstIp, dstPort, srcIp, srcPort, isIPv6 = true)
    }

    private fun dispatchTcpEvent(
        key: String, flags: Int, seqNum: Long, payload: ByteArray,
        dstIp: ByteArray, dstPort: Int,
        srcIp: ByteArray, srcPort: Int,
        isIPv6: Boolean
    ) {
        when {
            flags and FLAG_RST != 0 -> sessions.remove(key)?.close()

            flags and FLAG_SYN != 0 && flags and FLAG_ACK == 0 -> {
                if (!sessions.containsKey(key)) {
                    val session = TcpSession(key, srcIp, srcPort, dstIp, dstPort, seqNum, isIPv6)
                    sessions[key] = session
                    scope.launch {
                        try {
                            session.connect()
                        } catch (e: Exception) {
                            if (running && !VpnLogManager.isReconnecting.get()) {
                                VpnLogManager.warn("TCP ${if (isIPv6) dstIp.ip6 else dstIp.ip4}:$dstPort failed: ${e.message}")
                            }
                            Log.w(TAG, "TCP connect error", e)
                            sessions.remove(key)
                            session.sendRst()
                        }
                    }
                }
            }

            flags and FLAG_FIN != 0 -> sessions.remove(key)?.onFin()

            payload.isNotEmpty() -> sessions[key]?.onData(payload, seqNum)

            flags and FLAG_ACK != 0 -> sessions[key]?.onAck(seqNum)
        }
    }

    // ── IPv4 UDP handling ─────────────────────────────────────────────────────

    private fun handleUdp4(pkt: ByteArray, ihl: Int, srcIp: ByteArray, dstIp: ByteArray) {
        if (pkt.size < ihl + 8) return
        val srcPort    = pkt.u16(ihl)
        val dstPort    = pkt.u16(ihl + 2)
        val payloadOff = ihl + 8
        if (pkt.size <= payloadOff) return
        val payload = pkt.copyOfRange(payloadOff, pkt.size)

        forwardUdp(srcIp, srcPort, dstIp, dstPort, payload, isIPv6 = false)
    }

    // ── IPv6 UDP handling ─────────────────────────────────────────────────────

    private fun handleUdp6(pkt: ByteArray, srcIp: ByteArray, dstIp: ByteArray) {
        val udpOff = IP6_HDR
        if (pkt.size < udpOff + 8) return
        val srcPort    = pkt.u16(udpOff)
        val dstPort    = pkt.u16(udpOff + 2)
        val payloadOff = udpOff + 8
        if (pkt.size <= payloadOff) return
        val payload = pkt.copyOfRange(payloadOff, pkt.size)

        forwardUdp(srcIp, srcPort, dstIp, dstPort, payload, isIPv6 = true)
    }

    /**
     * One SOCKS5 UDP association is kept per original 5-tuple. The previous
     * implementation created a protected socket for every packet and waited for
     * one reply, which dropped most game datagrams and changed the apparent
     * source port on every send.
     */
    private fun forwardUdp(
        srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, dstPort: Int,
        payload: ByteArray, isIPv6: Boolean
    ) {
        val key = "${if (isIPv6) 6 else 4}:${srcIp.toAddressKey()}:$srcPort→${dstIp.toAddressKey()}:$dstPort"
        val session = udpSessions[key] ?: synchronized(udpSessions) {
            udpSessions[key] ?: UdpSession(
                key = key,
                srcIp = srcIp.copyOf(),
                srcPort = srcPort,
                dstIp = dstIp.copyOf(),
                dstPort = dstPort,
                isIPv6 = isIPv6,
            ).also { udpSessions[key] = it }
        }
        session.offer(payload)
    }

    /**
     * SOCKS5 UDP association for one original UDP 5-tuple.
     *
     * RFC 1928 requires the TCP association to stay open while UDP packets
     * are exchanged. Keeping both sockets alive is important for games: it
     * preserves the local source port and allows replies to arrive in either
     * direction instead of waiting for exactly one response per packet.
     */
    private inner class UdpSession(
        private val key: String,
        private val srcIp: ByteArray,
        private val srcPort: Int,
        private val dstIp: ByteArray,
        private val dstPort: Int,
        private val isIPv6: Boolean,
    ) {
        private val queue = Channel<ByteArray>(capacity = 512)
        private val active = AtomicBoolean(true)
        private var controlSocket: Socket? = null
        private var udpSocket: DatagramSocket? = null
        @Volatile private var lastActivityMs = System.currentTimeMillis()

        private val job = scope.launch(Dispatchers.IO) { run() }

        fun offer(payload: ByteArray) {
            if (active.get()) {
                queue.trySend(payload.copyOf())
            }
        }

        fun close() {
            if (!active.compareAndSet(true, false)) return
            queue.close()
            runCatching { udpSocket?.close() }
            runCatching { controlSocket?.close() }
            job.cancel()
        }

        private suspend fun run() {
            try {
                val control = Socket()
                controlSocket = control
                vpnService.protect(control)
                control.connect(InetSocketAddress("127.0.0.1", socksPort), CONNECT_TIMEOUT_MS)
                control.soTimeout = 5_000

                val input = control.getInputStream()
                val output = control.getOutputStream()

                // Greeting: SOCKS5, one method, no authentication.
                output.write(byteArrayOf(0x05, 0x01, 0x00))
                output.flush()
                val greeting = input.readExact(2)
                if (greeting[0] != 0x05.toByte() || greeting[1] != 0x00.toByte()) {
                    throw IllegalStateException("SOCKS5 UDP auth rejected")
                }

                // UDP ASSOCIATE with an unspecified destination.
                output.write(byteArrayOf(0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()
                val response = input.readExact(4)
                if (response[1] != 0x00.toByte()) {
                    throw IllegalStateException(
                        "SOCKS5 UDP associate refused code=${response[1].toInt() and 0xFF}"
                    )
                }
                val relay = readSocksAddress(input, response[3].toInt() and 0xFF)

                val udp = DatagramSocket()
                udpSocket = udp
                vpnService.protect(udp)
                udp.connect(
                    if (relay.address.isAnyLocalAddress) {
                        InetAddress.getLoopbackAddress()
                    } else {
                        relay.address
                    },
                    relay.port
                )
                udp.soTimeout = 1_000

                VpnLogManager.info(
                    "UDP relay ${if (isIPv6) dstIp.ip6 else dstIp.ip4}:$dstPort via SOCKS5"
                )

                val receiver = scope.launch(Dispatchers.IO) {
                    receiveLoop(udp)
                }

                try {
                    for (payload in queue) {
                        if (!active.get()) break
                        if (System.currentTimeMillis() - lastActivityMs > STALL_TIMEOUT_MS) break
                        val packet = buildSocksUdpRequest(dstIp, dstPort, payload, isIPv6)
                        udp.send(DatagramPacket(packet, packet.size))
                        lastActivityMs = System.currentTimeMillis()
                    }
                } finally {
                    receiver.cancel()
                }
            } catch (e: Exception) {
                if (running && active.get() && !VpnLogManager.isReconnecting.get()) {
                    VpnLogManager.warn(
                        "UDP ${if (isIPv6) dstIp.ip6 else dstIp.ip4}:$dstPort failed: ${e.message}"
                    )
                }
            } finally {
                active.set(false)
                queue.close()
                runCatching { udpSocket?.close() }
                runCatching { controlSocket?.close() }
                udpSessions.remove(key, this)
            }
        }

        private suspend fun receiveLoop(udp: DatagramSocket) {
            val buffer = ByteArray(65_535)
            while (active.get() && running) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udp.receive(packet)
                    val reply = parseSocksUdpReply(packet.data, packet.length) ?: continue
                    lastActivityMs = System.currentTimeMillis()
                    if (reply.payload.isEmpty()) continue

                    val remoteIp = reply.address ?: dstIp
                    if (isIPv6) {
                        injectUdp6ToTun(remoteIp, reply.port, srcIp, srcPort, reply.payload)
                    } else {
                        injectUdp4ToTun(remoteIp, reply.port, srcIp, srcPort, reply.payload)
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    if (System.currentTimeMillis() - lastActivityMs > STALL_TIMEOUT_MS) {
                        active.set(false)
                        queue.close()
                        break
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }

        private fun buildSocksUdpRequest(
            address: ByteArray,
            port: Int,
            payload: ByteArray,
            ipv6: Boolean
        ): ByteArray {
            val addressPart = if (ipv6) address else address
            val packet = ByteArray(4 + addressPart.size + 2 + payload.size)
            // RSV(2), FRAG(0), ATYP(IPv4=1 / IPv6=4)
            packet[3] = if (ipv6) 0x04 else 0x01
            addressPart.copyInto(packet, 4)
            val portOffset = 4 + addressPart.size
            packet[portOffset] = (port ushr 8).toByte()
            packet[portOffset + 1] = (port and 0xFF).toByte()
            payload.copyInto(packet, portOffset + 2)
            return packet
        }

        private fun parseSocksUdpReply(data: ByteArray, length: Int): UdpReply? {
            if (length < 4 || data[2].toInt() != 0) return null // FRAG must be zero
            var offset = 4
            val atyp = data[3].toInt() and 0xFF
            val address = when (atyp) {
                0x01 -> {
                    if (length < offset + 4) return null
                    data.copyOfRange(offset, offset + 4).also { offset += 4 }
                }
                0x03 -> {
                    if (length < offset + 1) return null
                    val size = data[offset].toInt() and 0xFF
                    offset++
                    if (length < offset + size) return null
                    offset += size
                    null
                }
                0x04 -> {
                    if (length < offset + 16) return null
                    data.copyOfRange(offset, offset + 16).also { offset += 16 }
                }
                else -> return null
            }
            if (length < offset + 2) return null
            val port = ((data[offset].toInt() and 0xFF) shl 8) or
                (data[offset + 1].toInt() and 0xFF)
            offset += 2 // source port in the SOCKS5 UDP header
            if (offset > length) return null
            return UdpReply(address, port, data.copyOfRange(offset, length))
        }

        private fun readSocksAddress(input: InputStream, atyp: Int): SocksAddress {
            val address = when (atyp) {
                0x01 -> InetAddress.getByAddress(input.readExact(4))
                0x03 -> {
                    val length = input.readExact(1)[0].toInt() and 0xFF
                    InetAddress.getByName(String(input.readExact(length), Charsets.UTF_8))
                }
                0x04 -> InetAddress.getByAddress(input.readExact(16))
                else -> throw IllegalStateException("Unknown SOCKS5 address type: $atyp")
            }
            val portBytes = input.readExact(2)
            val port = ((portBytes[0].toInt() and 0xFF) shl 8) or
                (portBytes[1].toInt() and 0xFF)
            return SocksAddress(address, port)
        }

        private fun InputStream.readExact(size: Int): ByteArray {
            val result = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val read = read(result, offset, size - offset)
                if (read < 0) throw java.io.EOFException("EOF after $offset/$size")
                offset += read
            }
            return result
        }
    }

    // ── UDP injection back into TUN ───────────────────────────────────────────

    private fun injectUdp4ToTun(sIp: ByteArray, sPort: Int, dIp: ByteArray, dPort: Int, payload: ByteArray) {
        val udpLen = 8 + payload.size
        val total  = IP4_HDR + udpLen
        val buf    = ByteArray(total)
        buf[0] = 0x45.toByte(); buf[1] = 0x00
        buf.w16(2, total)
        buf.w16(4, (System.nanoTime() and 0xFFFFL).toInt())
        buf[6] = 0x40; buf[7] = 0x00
        buf[8] = 64; buf[9] = 17
        sIp.copyInto(buf, 12); dIp.copyInto(buf, 16)
        buf.w16(10, checksum(buf, 0, IP4_HDR))
        buf.w16(IP4_HDR, sPort); buf.w16(IP4_HDR + 2, dPort)
        buf.w16(IP4_HDR + 4, udpLen); buf.w16(IP4_HDR + 6, 0)
        payload.copyInto(buf, IP4_HDR + 8)
        sendToTun(buf)
    }

    private fun injectUdp6ToTun(sIp: ByteArray, sPort: Int, dIp: ByteArray, dPort: Int, payload: ByteArray) {
        val udpPayloadLen = 8 + payload.size
        val buf = ByteArray(IP6_HDR + udpPayloadLen)
        // IPv6 header
        buf[0] = 0x60.toByte(); buf[1] = 0; buf[2] = 0; buf[3] = 0
        buf.w16(4, udpPayloadLen)
        buf[6] = PROTO_UDP.toByte(); buf[7] = 64
        sIp.copyInto(buf, 8); dIp.copyInto(buf, 24)
        // UDP header
        val u = IP6_HDR
        buf.w16(u, sPort); buf.w16(u + 2, dPort)
        buf.w16(u + 4, udpPayloadLen); buf.w16(u + 6, 0)
        payload.copyInto(buf, u + 8)
        // UDP checksum (required for IPv6)
        buf.w16(u + 6, udpChecksumIPv6(sIp, dIp, buf, u, udpPayloadLen))
        sendToTun(buf)
    }

    // ── TCP Session — full bidirectional SOCKS5 relay ─────────────────────────

    private inner class TcpSession(
        val key: String,
        val srcIp: ByteArray, val srcPort: Int,
        val dstIp: ByteArray, val dstPort: Int,
        clientIsn: Long,
        private val isIPv6: Boolean,
    ) {
        private val socket  = Socket()
        private val toProxy = Channel<ByteArray>(capacity = 2048)

        @Volatile var mySeq: Long = (Random.nextLong() and 0x7FFF_FFFFL)
        @Volatile var myAck: Long = (clientIsn + 1) and 0xFFFF_FFFFL

        @Volatile private var alive = false

        suspend fun connect() = withContext(Dispatchers.IO) {
            vpnService.protect(socket)
            socket.connect(InetSocketAddress("127.0.0.1", socksPort), CONNECT_TIMEOUT_MS)

            socket.soTimeout         = STALL_TIMEOUT_MS
            socket.tcpNoDelay        = true
            socket.keepAlive         = true
            socket.receiveBufferSize = 262_144   // 256 KB
            socket.sendBufferSize    = 262_144   // 256 KB

            val inp = socket.getInputStream()
            val out = socket.getOutputStream()

            // SOCKS5 handshake — no-auth
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            inp.readExact(2)

            // SOCKS5 CONNECT: ATYP 0x01 (IPv4) or 0x04 (IPv6)
            if (isIPv6) {
                // 4 (header) + 16 (IPv6) + 2 (port) = 22 bytes
                val req = ByteArray(22)
                req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x04
                dstIp.copyInto(req, 4)
                req[20] = (dstPort ushr 8).toByte()
                req[21] = (dstPort and 0xFF).toByte()
                out.write(req); out.flush()
            } else {
                // 4 (header) + 4 (IPv4) + 2 (port) = 10 bytes
                val req = ByteArray(10)
                req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x01
                dstIp.copyInto(req, 4)
                req[8] = (dstPort ushr 8).toByte()
                req[9] = (dstPort and 0xFF).toByte()
                out.write(req); out.flush()
            }

            // Read SOCKS5 response header (4 bytes)
            val rHdr = inp.readExact(4)
            if (rHdr[1] != 0x00.toByte())
                throw Exception("SOCKS5 refused code=${rHdr[1].toInt() and 0xFF}")

            // Skip bound address in response
            when (rHdr[3].toInt() and 0xFF) {
                0x01 -> inp.readExact(6)   // IPv4 + port
                0x03 -> { val dlen = inp.readExact(1)[0].toInt() and 0xFF; inp.readExact(dlen + 2) }  // domain + port
                0x04 -> inp.readExact(18)  // IPv6 + port
            }

            alive = true
            val addrStr = if (isIPv6) dstIp.ip6 else dstIp.ip4
            VpnLogManager.success("TCP relay $addrStr:$dstPort via SOCKS5:$socksPort")

            // SYN-ACK → device
            sendToTun(buildTcp(dstIp, dstPort, srcIp, srcPort, mySeq, myAck, FLAG_SYN or FLAG_ACK))
            mySeq = (mySeq + 1) and 0xFFFF_FFFFL

            // Device → Proxy writer coroutine
            val writerJob = launch {
                try {
                    for (chunk in toProxy) { out.write(chunk); out.flush() }
                } catch (e: Exception) {
                    if (alive && !VpnLogManager.isReconnecting.get())
                        Log.w(TAG, "proxy write $addrStr:$dstPort — ${e.message}")
                }
            }

            // Proxy → Device reader
            val rbuf = ByteArray(65536)
            try {
                while (alive) {
                    val len = try {
                        inp.read(rbuf)
                    } catch (e: java.net.SocketTimeoutException) {
                        if (alive && !VpnLogManager.isReconnecting.get())
                            Log.d(TAG, "Stall timeout $addrStr:$dstPort — closing")
                        -1
                    }
                    if (len <= 0) break
                    val data = rbuf.copyOf(len)
                    sendToTun(buildTcp(dstIp, dstPort, srcIp, srcPort, mySeq, myAck, FLAG_PSH or FLAG_ACK, data))
                    mySeq = (mySeq + len) and 0xFFFF_FFFFL
                }
            } catch (e: Exception) {
                if (alive && !VpnLogManager.isReconnecting.get())
                    Log.d(TAG, "proxy read end $addrStr:$dstPort — ${e.message}")
            } finally {
                alive = false
                writerJob.cancel()
                toProxy.close()
                runCatching { sendToTun(buildTcp(dstIp, dstPort, srcIp, srcPort, mySeq, myAck, FLAG_FIN or FLAG_ACK)) }
                sessions.remove(key)
                runCatching { socket.close() }
            }
        }

        fun onData(payload: ByteArray, seqNum: Long) {
            myAck = (seqNum + payload.size) and 0xFFFF_FFFFL
            if (!alive) return
            // Immediate ACK keeps device TCP window open
            sendToTun(buildTcp(dstIp, dstPort, srcIp, srcPort, mySeq, myAck, FLAG_ACK))
            if (!toProxy.trySend(payload).isSuccess) {
                scope.launch { try { toProxy.send(payload) } catch (_: Exception) {} }
            }
        }

        fun onAck(seqNum: Long) { /* window management — no action needed */ }

        fun onFin() {
            alive = false
            toProxy.close()
            scope.launch { runCatching { socket.close() }; sessions.remove(key) }
        }

        fun close() {
            alive = false
            runCatching { toProxy.close() }
            runCatching { socket.close() }
        }

        fun sendRst() {
            sendToTun(buildTcp(dstIp, dstPort, srcIp, srcPort, mySeq, myAck, FLAG_RST or FLAG_ACK))
        }

        // Build a TCP packet (IPv4 or IPv6 based on isIPv6)
        private fun buildTcp(
            sIp: ByteArray, sPort: Int,
            dIp: ByteArray, dPort: Int,
            seq: Long, ack: Long, flags: Int,
            payload: ByteArray = ByteArray(0)
        ): ByteArray = if (isIPv6) buildTcp6(sIp, sPort, dIp, dPort, seq, ack, flags, payload)
                       else        buildTcp4(sIp, sPort, dIp, dPort, seq, ack, flags, payload)

        private fun InputStream.readExact(n: Int): ByteArray {
            val buf = ByteArray(n); var pos = 0
            while (pos < n) { val r = read(buf, pos, n - pos); if (r < 0) throw java.io.EOFException("EOF after $pos/$n"); pos += r }
            return buf
        }
    }

    // ── IPv4 TCP packet builder ───────────────────────────────────────────────

    private fun buildTcp4(
        sIp: ByteArray, sPort: Int,
        dIp: ByteArray, dPort: Int,
        seq: Long, ack: Long, flags: Int,
        payload: ByteArray = ByteArray(0)
    ): ByteArray {
        val total = IP4_HDR + TCP_HDR + payload.size
        val buf   = ByteArray(total)
        buf[0] = 0x45.toByte(); buf[1] = 0x00
        buf.w16(2, total)
        buf.w16(4, (System.nanoTime() and 0xFFFFL).toInt())
        buf[6] = 0x40; buf[7] = 0x00
        buf[8] = 64; buf[9] = 6
        sIp.copyInto(buf, 12); dIp.copyInto(buf, 16)
        buf.w16(10, checksum(buf, 0, IP4_HDR))
        val T = IP4_HDR
        buf.w16(T, sPort); buf.w16(T + 2, dPort)
        buf.w32(T + 4, seq); buf.w32(T + 8, ack)
        buf[T + 12] = 0x50; buf[T + 13] = flags.toByte()
        buf.w16(T + 14, 65535)
        payload.copyInto(buf, IP4_HDR + TCP_HDR)
        buf.w16(T + 16, tcpChecksum4(sIp, dIp, buf, T, TCP_HDR + payload.size))
        return buf
    }

    // ── IPv6 TCP packet builder ───────────────────────────────────────────────

    private fun buildTcp6(
        sIp: ByteArray, sPort: Int,
        dIp: ByteArray, dPort: Int,
        seq: Long, ack: Long, flags: Int,
        payload: ByteArray = ByteArray(0)
    ): ByteArray {
        val tcpLen = TCP_HDR + payload.size
        val buf    = ByteArray(IP6_HDR + tcpLen)
        // IPv6 fixed header
        buf[0] = 0x60.toByte(); buf[1] = 0; buf[2] = 0; buf[3] = 0
        buf.w16(4, tcpLen)
        buf[6] = PROTO_TCP.toByte()
        buf[7] = 64
        sIp.copyInto(buf, 8); dIp.copyInto(buf, 24)
        // TCP header
        val T = IP6_HDR
        buf.w16(T, sPort); buf.w16(T + 2, dPort)
        buf.w32(T + 4, seq); buf.w32(T + 8, ack)
        buf[T + 12] = 0x50; buf[T + 13] = flags.toByte()
        buf.w16(T + 14, 65535)
        payload.copyInto(buf, T + TCP_HDR)
        buf.w16(T + 16, tcpChecksum6(sIp, dIp, buf, T, tcpLen))
        return buf
    }

    // ── Checksum helpers ──────────────────────────────────────────────────────

    private fun checksum(buf: ByteArray, off: Int, len: Int): Int {
        var sum = 0L; var i = off
        while (i < off + len - 1) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (len and 1 != 0) sum += (buf[off + len - 1].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun tcpChecksum4(sIp: ByteArray, dIp: ByteArray, buf: ByteArray, tcpOff: Int, tcpLen: Int): Int {
        val ph = ByteArray(12 + tcpLen)
        sIp.copyInto(ph, 0); dIp.copyInto(ph, 4)
        ph[8] = 0x00; ph[9] = 0x06
        ph[10] = (tcpLen ushr 8).toByte(); ph[11] = (tcpLen and 0xFF).toByte()
        buf.copyInto(ph, 12, tcpOff, tcpOff + tcpLen)
        ph[12 + 16] = 0; ph[12 + 17] = 0
        return checksum(ph, 0, ph.size)
    }

    private fun tcpChecksum6(sIp: ByteArray, dIp: ByteArray, buf: ByteArray, tcpOff: Int, tcpLen: Int): Int {
        // IPv6 pseudo-header: src(16) + dst(16) + len(4) + zeros(3) + nextHdr(1)
        val ph = ByteArray(40 + tcpLen)
        sIp.copyInto(ph, 0); dIp.copyInto(ph, 16)
        ph[32] = 0; ph[33] = 0; ph[34] = (tcpLen ushr 8).toByte(); ph[35] = (tcpLen and 0xFF).toByte()
        ph[36] = 0; ph[37] = 0; ph[38] = 0; ph[39] = PROTO_TCP.toByte()
        buf.copyInto(ph, 40, tcpOff, tcpOff + tcpLen)
        ph[40 + 16] = 0; ph[40 + 17] = 0
        return checksum(ph, 0, ph.size)
    }

    private fun udpChecksumIPv6(sIp: ByteArray, dIp: ByteArray, buf: ByteArray, udpOff: Int, udpLen: Int): Int {
        val ph = ByteArray(40 + udpLen)
        sIp.copyInto(ph, 0); dIp.copyInto(ph, 16)
        ph[32] = 0; ph[33] = 0; ph[34] = (udpLen ushr 8).toByte(); ph[35] = (udpLen and 0xFF).toByte()
        ph[36] = 0; ph[37] = 0; ph[38] = 0; ph[39] = PROTO_UDP.toByte()
        buf.copyInto(ph, 40, udpOff, udpOff + udpLen)
        ph[40 + 6] = 0; ph[40 + 7] = 0
        return checksum(ph, 0, ph.size)
    }

    // ── TUN write ─────────────────────────────────────────────────────────────

    private fun sendToTun(pkt: ByteArray) {
        if (!running) return
        try {
            synchronized(tunLock) { tunOut.write(pkt); tunOut.flush() }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "TUN write error: ${e.message}")
        }
    }

    // ── ByteArray extension helpers ───────────────────────────────────────────

    private val ByteArray.ip4: String
        get() = "%d.%d.%d.%d".format(
            this[0].toInt() and 0xFF, this[1].toInt() and 0xFF,
            this[2].toInt() and 0xFF, this[3].toInt() and 0xFF
        )

    private val ByteArray.ip6: String
        get() = buildString {
            for (i in 0 until 8) {
                if (i > 0) append(':')
                val word = ((this@ip6[i * 2].toInt() and 0xFF) shl 8) or (this@ip6[i * 2 + 1].toInt() and 0xFF)
                append(word.toString(16))
            }
        }

    private fun ByteArray.toAddressKey(): String =
        joinToString(".") { (it.toInt() and 0xFF).toString(16) }

    private fun ByteArray.u16(off: Int): Int =
        ((this[off].toInt() and 0xFF) shl 8) or (this[off + 1].toInt() and 0xFF)

    private fun ByteArray.u32L(off: Int): Long =
        ((this[off].toLong() and 0xFF) shl 24) or
        ((this[off + 1].toLong() and 0xFF) shl 16) or
        ((this[off + 2].toLong() and 0xFF) shl 8) or
        (this[off + 3].toLong() and 0xFF)

    private fun ByteArray.w16(off: Int, v: Int) {
        this[off] = (v ushr 8 and 0xFF).toByte()
        this[off + 1] = (v and 0xFF).toByte()
    }

    private fun ByteArray.w32(off: Int, v: Long) {
        this[off]     = (v ushr 24 and 0xFF).toByte()
        this[off + 1] = (v ushr 16 and 0xFF).toByte()
        this[off + 2] = (v ushr  8 and 0xFF).toByte()
        this[off + 3] = (v         and 0xFF).toByte()
    }
}

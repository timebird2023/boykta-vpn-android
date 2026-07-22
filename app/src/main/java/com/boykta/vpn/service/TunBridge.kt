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
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * FULL BIDIRECTIONAL TUN → SOCKS5 Bridge.
 *
 * Implements a complete two-way data path so real internet works:
 *
 *   Device app → TUN (raw IPv4 packet) → TunBridge → SOCKS5:10808 → Xray → VPN Server → Internet
 *   Internet   → VPN Server → Xray → SOCKS5:10808 → TunBridge → crafted IP+TCP packet → TUN → Device
 *
 * Stability fixes:
 *   • TCP keep-alive enabled on every SOCKS5 socket (OS-level keepalive)
 *   • TCP no-delay enabled for low-latency streaming
 *   • 128 KB socket buffers for throughput
 *   • STALL DETECTION: idle socket read timeout (STALL_TIMEOUT_MS). If the proxy
 *     returns no data for that long, the session is closed and a RST injected.
 *     This prevents connections that SOCKS5 accepted but never forward data.
 *   • Immediate ACK on data receipt — keeps device TCP window open
 *   • 64 KB read buffer for proxy→device path (better throughput)
 */
class TunBridge(
    private val tunPfd: ParcelFileDescriptor,
    private val socksPort: Int,
    private val vpnService: VpnService,
) {

    companion object {
        private const val TAG = "TunBridge"
        private const val MTU = 1500

        // TCP flag bits
        private const val FLAG_FIN = 0x01
        private const val FLAG_SYN = 0x02
        private const val FLAG_RST = 0x04
        private const val FLAG_PSH = 0x08
        private const val FLAG_ACK = 0x10

        private const val PROTO_TCP = 6
        private const val PROTO_UDP = 17
        private const val IP_HDR   = 20
        private const val TCP_HDR  = 20

        // Stall detection: if a SOCKS5 connection sends no data for this long,
        // treat it as stalled and close it (triggers device-side reconnect).
        // 120 s instead of 60 s to avoid killing HTTP long-polling / SSE connections.
        private const val STALL_TIMEOUT_MS = 120_000   // 120 s idle → stalled

        // Connect timeout for each new SOCKS5 session
        private const val CONNECT_TIMEOUT_MS = 8_000
    }

    private val scope    = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val sessions = ConcurrentHashMap<String, TcpSession>()
    private val tunIn    = FileInputStream(tunPfd.fileDescriptor)
    private val tunOut   = FileOutputStream(tunPfd.fileDescriptor)
    private val tunLock  = Any()   // serialize writes to TUN

    @Volatile private var running = false

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    fun start() {
        running = true
        VpnLogManager.sys("TunBridge started → SOCKS5 127.0.0.1:$socksPort")
        scope.launch { readLoop() }
    }

    fun stop() {
        running = false
        scope.cancel()
        sessions.values.forEach { it.close() }
        sessions.clear()
        runCatching { tunIn.close() }
        runCatching { tunOut.close() }   // close write-end too — prevents FD leak
        VpnLogManager.sys("TunBridge stopped")
    }

    // ── TUN read loop ─────────────────────────────────────────────────────────

    private suspend fun readLoop() = withContext(Dispatchers.IO) {
        val buf = ByteArray(MTU)
        while (running && isActive) {
            try {
                val len = tunIn.read(buf)
                if (len < IP_HDR) continue
                dispatchPacket(buf.copyOf(len))
            } catch (e: Exception) {
                if (running) VpnLogManager.warn("TUN read error: ${e.message}")
                break
            }
        }
    }

    // ── IPv4 packet dispatcher ────────────────────────────────────────────────

    private fun dispatchPacket(pkt: ByteArray) {
        val version = (pkt[0].toInt() and 0xFF) ushr 4
        if (version != 4) return   // IPv4 only

        val ihl = (pkt[0].toInt() and 0x0F) * 4
        if (pkt.size < ihl + 4) return

        val proto = pkt[9].toInt() and 0xFF
        val srcIp = pkt.copyOfRange(12, 16)
        val dstIp = pkt.copyOfRange(16, 20)

        when (proto) {
            PROTO_TCP -> handleTcp(pkt, ihl, srcIp, dstIp)
            PROTO_UDP -> handleUdp(pkt, ihl, dstIp)
        }
    }

    // ── TCP dispatch ──────────────────────────────────────────────────────────

    private fun handleTcp(pkt: ByteArray, ihl: Int, srcIp: ByteArray, dstIp: ByteArray) {
        if (pkt.size < ihl + TCP_HDR) return

        val srcPort = pkt.u16(ihl)
        val dstPort = pkt.u16(ihl + 2)
        val seqNum  = pkt.u32L(ihl + 4)
        val flags   = pkt[ihl + 13].toInt() and 0xFF
        val tcpDataOff = ((pkt[ihl + 12].toInt() and 0xFF) ushr 4) * 4
        val payload = if (pkt.size > ihl + tcpDataOff)
            pkt.copyOfRange(ihl + tcpDataOff, pkt.size)
        else ByteArray(0)

        val key = "${srcIp.ip}:$srcPort→${dstIp.ip}:$dstPort"

        when {
            // RST — kill session immediately
            flags and FLAG_RST != 0 -> sessions.remove(key)?.close()

            // SYN (new connection, not SYN-ACK)
            flags and FLAG_SYN != 0 && flags and FLAG_ACK == 0 -> {
                if (!sessions.containsKey(key)) {
                    val session = TcpSession(key, srcIp, srcPort, dstIp, dstPort, seqNum)
                    sessions[key] = session
                    scope.launch {
                        try {
                            session.connect()
                        } catch (e: Exception) {
                            if (running) {
                                // Suppress repeated SOCKS5-refused during reconnect window
                                if (!VpnLogManager.isReconnecting.get()) {
                                    VpnLogManager.warn("TCP ${dstIp.ip}:$dstPort failed: ${e.message}")
                                }
                                Log.w(TAG, "TCP connect error", e)
                            }
                            sessions.remove(key)
                            session.sendRst()
                        }
                    }
                }
            }

            // FIN — graceful close
            flags and FLAG_FIN != 0 -> sessions.remove(key)?.onFin()

            // DATA — forward and ACK immediately to keep TCP window open
            payload.isNotEmpty() -> sessions[key]?.onData(payload, seqNum)

            // Pure ACK — update ack tracking
            flags and FLAG_ACK != 0 -> sessions[key]?.onAck(seqNum)
        }
    }

    // ── UDP dispatch — direct protected socket (DNS bypass) ───────────────────

    private fun handleUdp(pkt: ByteArray, ihl: Int, dstIp: ByteArray) {
        if (pkt.size < ihl + 8) return
        val srcIp   = pkt.copyOfRange(12, 16)
        val srcPort = pkt.u16(ihl)
        val dstPort = pkt.u16(ihl + 2)
        val payloadOff = ihl + 8
        if (pkt.size <= payloadOff) return
        val payload = pkt.copyOfRange(payloadOff, pkt.size)

        scope.launch(Dispatchers.IO) {
            try {
                java.net.DatagramSocket().use { sock ->
                    vpnService.protect(sock)
                    sock.soTimeout = 4_000
                    val addr = java.net.InetAddress.getByName(dstIp.ip)
                    sock.send(java.net.DatagramPacket(payload, payload.size, addr, dstPort))
                    val resp = java.net.DatagramPacket(ByteArray(2048), 2048)
                    try {
                        sock.receive(resp)
                        val udpPayload = resp.data.copyOfRange(0, resp.length)
                        injectUdpToTun(dstIp, dstPort, srcIp, srcPort, udpPayload)
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }

    /** Build and inject a UDP response packet into TUN (UDP→device direction). */
    private fun injectUdpToTun(
        sIp: ByteArray, sPort: Int,
        dIp: ByteArray, dPort: Int,
        payload: ByteArray
    ) {
        val udpLen = 8 + payload.size
        val total  = IP_HDR + udpLen
        val buf    = ByteArray(total)

        buf[0] = 0x45.toByte()
        buf[1] = 0x00
        buf.w16(2, total)
        buf.w16(4, (System.nanoTime() and 0xFFFFL).toInt())
        buf[6] = 0x40; buf[7] = 0x00
        buf[8] = 64
        buf[9] = 17   // UDP
        sIp.copyInto(buf, 12)
        dIp.copyInto(buf, 16)
        buf.w16(10, checksum(buf, 0, IP_HDR))

        buf.w16(IP_HDR,     sPort)
        buf.w16(IP_HDR + 2, dPort)
        buf.w16(IP_HDR + 4, udpLen)
        buf.w16(IP_HDR + 6, 0)

        payload.copyInto(buf, IP_HDR + 8)
        sendToTun(buf)
    }

    // ── TCP Session — FULL BIDIRECTIONAL relay ────────────────────────────────

    private inner class TcpSession(
        val key: String,
        val srcIp: ByteArray, val srcPort: Int,
        val dstIp: ByteArray, val dstPort: Int,
        clientIsn: Long,
    ) {
        private val socket   = Socket()
        private val toProxy  = Channel<ByteArray>(capacity = 1024)

        @Volatile var mySeq : Long = (Random.nextLong() and 0x7FFF_FFFFL)
        @Volatile var myAck : Long = (clientIsn + 1) and 0xFFFF_FFFFL

        @Volatile private var alive = false

        // ── Establish connection ───────────────────────────────────────────────

        suspend fun connect() = withContext(Dispatchers.IO) {
            vpnService.protect(socket)
            socket.connect(InetSocketAddress("127.0.0.1", socksPort), CONNECT_TIMEOUT_MS)

            // Socket tuning for stability and low-latency
            socket.soTimeout         = STALL_TIMEOUT_MS  // stall detection timeout
            socket.tcpNoDelay        = true               // disable Nagle for low latency
            socket.keepAlive         = true               // OS TCP keep-alive (prevents NAT drops)
            socket.receiveBufferSize = 131_072             // 128 KB receive buffer
            socket.sendBufferSize    = 131_072             // 128 KB send buffer

            val inp = socket.getInputStream()
            val out = socket.getOutputStream()

            // SOCKS5 negotiation — no-auth method
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            inp.readExact(2)

            // SOCKS5 CONNECT — ATYP=0x01 (IPv4)
            val req = ByteArray(10)
            req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x01
            dstIp.copyInto(req, 4)
            req[8] = (dstPort ushr 8).toByte()
            req[9] = (dstPort and 0xFF).toByte()
            out.write(req); out.flush()

            val rHdr = inp.readExact(4)
            if (rHdr[1] != 0x00.toByte())
                throw Exception("SOCKS5 refused code=${rHdr[1].toInt() and 0xFF}")
            when (rHdr[3].toInt() and 0xFF) {
                0x01 -> inp.readExact(6)
                0x03 -> { val dlen = inp.readExact(1)[0].toInt() and 0xFF; inp.readExact(dlen + 2) }
                0x04 -> inp.readExact(18)
            }

            alive = true
            VpnLogManager.success("TCP relay ${dstIp.ip}:$dstPort via SOCKS5:$socksPort")

            // ── SYN-ACK → device ──────────────────────────────────────────────
            sendToTun(buildTcp(
                sIp = dstIp, sPort = dstPort,
                dIp = srcIp, dPort = srcPort,
                seq = mySeq, ack = myAck,
                flags = FLAG_SYN or FLAG_ACK
            ))
            mySeq = (mySeq + 1) and 0xFFFF_FFFFL

            // ── Device→Proxy writer ───────────────────────────────────────────
            val writerJob = launch {
                try {
                    for (chunk in toProxy) {
                        out.write(chunk)
                        out.flush()
                    }
                } catch (e: Exception) {
                    if (alive && !VpnLogManager.isReconnecting.get()) {
                        Log.w(TAG, "proxy write ${dstIp.ip}:$dstPort — ${e.message}")
                    }
                }
            }

            // ── Proxy→Device reader ───────────────────────────────────────────
            // socket.soTimeout = STALL_TIMEOUT_MS means SocketTimeoutException after
            // STALL_TIMEOUT_MS of inactivity — connection declared stalled and closed.
            val rbuf = ByteArray(65536)
            try {
                while (alive) {
                    val len = try {
                        inp.read(rbuf)
                    } catch (e: java.net.SocketTimeoutException) {
                        // Stall detected — no data for STALL_TIMEOUT_MS
                        if (alive && !VpnLogManager.isReconnecting.get()) {
                            Log.d(TAG, "Stall timeout ${dstIp.ip}:$dstPort — closing session")
                        }
                        -1  // treat as EOF
                    }
                    if (len <= 0) break
                    val data = rbuf.copyOf(len)
                    sendToTun(buildTcp(
                        sIp = dstIp, sPort = dstPort,
                        dIp = srcIp, dPort = srcPort,
                        seq = mySeq, ack = myAck,
                        flags = FLAG_PSH or FLAG_ACK, payload = data
                    ))
                    mySeq = (mySeq + len) and 0xFFFF_FFFFL
                }
            } catch (e: Exception) {
                if (alive && !VpnLogManager.isReconnecting.get()) {
                    Log.d(TAG, "proxy read end ${dstIp.ip}:$dstPort — ${e.message}")
                }
            } finally {
                alive = false
                writerJob.cancel()
                toProxy.close()
                runCatching {
                    sendToTun(buildTcp(
                        sIp = dstIp, sPort = dstPort,
                        dIp = srcIp, dPort = srcPort,
                        seq = mySeq, ack = myAck,
                        flags = FLAG_FIN or FLAG_ACK
                    ))
                }
                sessions.remove(key)
                runCatching { socket.close() }
            }
        }

        // ── Called from TUN read loop ─────────────────────────────────────────

        /**
         * CRITICAL: Send a standalone ACK back immediately.
         * Without this the device's TCP send-window fills up → traffic stalls.
         */
        fun onData(payload: ByteArray, seqNum: Long) {
            myAck = (seqNum + payload.size) and 0xFFFF_FFFFL
            if (!alive) return

            // ① ACK immediately — keeps device TCP window open
            sendToTun(buildTcp(
                sIp = dstIp, sPort = dstPort,
                dIp = srcIp, dPort = srcPort,
                seq = mySeq, ack = myAck,
                flags = FLAG_ACK
            ))

            // ② Enqueue payload for proxy
            if (!toProxy.trySend(payload).isSuccess) {
                scope.launch {
                    try { toProxy.send(payload) } catch (_: Exception) {}
                }
            }
        }

        fun onAck(seqNum: Long) {
            // Window management — no explicit action needed for simple relay
        }

        fun onFin() {
            alive = false
            toProxy.close()
            scope.launch {
                runCatching { socket.close() }
                sessions.remove(key)
            }
        }

        fun close() {
            alive = false
            runCatching { toProxy.close() }
            runCatching { socket.close() }
        }

        fun sendRst() {
            sendToTun(buildTcp(
                sIp = dstIp, sPort = dstPort,
                dIp = srcIp, dPort = srcPort,
                seq = mySeq, ack = myAck,
                flags = FLAG_RST or FLAG_ACK
            ))
        }

        // ── InputStream helpers ───────────────────────────────────────────────

        private fun InputStream.readExact(n: Int): ByteArray {
            val buf = ByteArray(n)
            var pos = 0
            while (pos < n) {
                val r = read(buf, pos, n - pos)
                if (r < 0) throw java.io.EOFException("EOF after $pos/$n bytes")
                pos += r
            }
            return buf
        }
    }

    // ── IP + TCP packet builder ───────────────────────────────────────────────

    private fun buildTcp(
        sIp: ByteArray, sPort: Int,
        dIp: ByteArray, dPort: Int,
        seq: Long, ack: Long,
        flags: Int,
        payload: ByteArray = ByteArray(0)
    ): ByteArray {
        val total = IP_HDR + TCP_HDR + payload.size
        val buf   = ByteArray(total)

        buf[0] = 0x45.toByte()
        buf[1] = 0x00
        buf.w16(2, total)
        buf.w16(4, (System.nanoTime() and 0xFFFFL).toInt())
        buf[6] = 0x40; buf[7] = 0x00
        buf[8] = 64
        buf[9] = 6
        sIp.copyInto(buf, 12)
        dIp.copyInto(buf, 16)
        buf.w16(10, checksum(buf, 0, IP_HDR))

        val T = IP_HDR
        buf.w16(T,     sPort)
        buf.w16(T + 2, dPort)
        buf.w32(T + 4, seq)
        buf.w32(T + 8, ack)
        buf[T + 12] = 0x50
        buf[T + 13] = flags.toByte()
        buf.w16(T + 14, 65535)

        payload.copyInto(buf, IP_HDR + TCP_HDR)
        buf.w16(T + 16, tcpChecksum(sIp, dIp, buf, T, TCP_HDR + payload.size))

        return buf
    }

    // ── Checksum helpers ──────────────────────────────────────────────────────

    private fun checksum(buf: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        while (i < off + len - 1) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (len and 1 != 0) sum += (buf[off + len - 1].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun tcpChecksum(sIp: ByteArray, dIp: ByteArray, buf: ByteArray, tcpOff: Int, tcpLen: Int): Int {
        val ph = ByteArray(12 + tcpLen)
        sIp.copyInto(ph, 0)
        dIp.copyInto(ph, 4)
        ph[8] = 0x00; ph[9] = 0x06
        ph[10] = (tcpLen ushr 8).toByte()
        ph[11] = (tcpLen and 0xFF).toByte()
        buf.copyInto(ph, 12, tcpOff, tcpOff + tcpLen)
        ph[12 + 16] = 0; ph[12 + 17] = 0
        return checksum(ph, 0, ph.size)
    }

    // ── TUN write ─────────────────────────────────────────────────────────────

    private fun sendToTun(pkt: ByteArray) {
        if (!running) return
        try {
            synchronized(tunLock) {
                tunOut.write(pkt)
                tunOut.flush()
            }
        } catch (e: Exception) {
            if (running) Log.w(TAG, "TUN write error: ${e.message}")
        }
    }

    // ── ByteArray extension helpers ───────────────────────────────────────────

    private val ByteArray.ip: String
        get() = "%d.%d.%d.%d".format(
            this[0].toInt() and 0xFF, this[1].toInt() and 0xFF,
            this[2].toInt() and 0xFF, this[3].toInt() and 0xFF
        )

    private fun ByteArray.u16(off: Int): Int =
        ((this[off].toInt() and 0xFF) shl 8) or (this[off + 1].toInt() and 0xFF)

    private fun ByteArray.u32L(off: Int): Long =
        ((this[off].toLong() and 0xFF) shl 24) or
        ((this[off + 1].toLong() and 0xFF) shl 16) or
        ((this[off + 2].toLong() and 0xFF) shl 8) or
        (this[off + 3].toLong() and 0xFF)

    private fun ByteArray.w16(off: Int, v: Int) {
        this[off]     = (v ushr 8 and 0xFF).toByte()
        this[off + 1] = (v        and 0xFF).toByte()
    }

    private fun ByteArray.w32(off: Int, v: Long) {
        this[off]     = (v ushr 24 and 0xFF).toByte()
        this[off + 1] = (v ushr 16 and 0xFF).toByte()
        this[off + 2] = (v ushr  8 and 0xFF).toByte()
        this[off + 3] = (v         and 0xFF).toByte()
    }
}

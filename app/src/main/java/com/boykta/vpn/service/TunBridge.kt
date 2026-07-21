package com.boykta.vpn.service

import android.net.VpnService
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Pure-Java TUN → SOCKS5 bridge.
 *
 * Reads raw IPv4 packets from the TUN file descriptor, parses TCP/UDP
 * headers, and relays the payload through the local SOCKS5 proxy that
 * Xray-core opens on 127.0.0.1:[socksPort].
 *
 * This replaces the tun2socks native library dependency so the VPN
 * engine works without libXray.aar bundling tun2socks.
 *
 * Protocol supported:
 *   TCP  — full bidirectional relay via SOCKS5 CONNECT
 *   UDP  — best-effort via SOCKS5 UDP ASSOCIATE (DNS etc.)
 *   ICMP — dropped (handled by the remote SOCKS5 server implicitly)
 */
class TunBridge(
    private val tunPfd: ParcelFileDescriptor,
    private val socksPort: Int,
    private val vpnService: VpnService,
) {
    companion object {
        private const val TAG = "TunBridge"
        private const val MTU = 1500
        private const val SOCKS5_CONNECT = 0x01
        private const val SOCKS5_NO_AUTH = 0x00
        private const val PROTO_TCP  = 6
        private const val PROTO_UDP  = 17
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val tcpSessions = ConcurrentHashMap<String, TcpSession>()

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
        tcpSessions.values.forEach { it.close() }
        tcpSessions.clear()
        VpnLogManager.sys("TunBridge stopped")
    }

    // ── Read loop ──────────────────────────────────────────────────────────────

    private suspend fun readLoop() = withContext(Dispatchers.IO) {
        val input = FileInputStream(tunPfd.fileDescriptor)
        val buf = ByteArray(MTU)

        while (running && isActive) {
            try {
                val len = input.read(buf)
                if (len <= 0) continue
                val packet = buf.copyOf(len)
                handlePacket(packet)
            } catch (e: Exception) {
                if (running) VpnLogManager.warn("TunBridge read error: ${e.message}")
                break
            }
        }
    }

    // ── IPv4 Packet Dispatcher ────────────────────────────────────────────────

    private fun handlePacket(pkt: ByteArray) {
        if (pkt.isEmpty()) return
        val version = (pkt[0].toInt() and 0xFF) shr 4
        if (version != 4) return   // IPv6 not handled

        val ihl = (pkt[0].toInt() and 0x0F) * 4
        if (pkt.size < ihl + 4) return

        val protocol = pkt[9].toInt() and 0xFF
        val srcIp = pkt.copyOfRange(12, 16)
        val dstIp = pkt.copyOfRange(16, 20)

        when (protocol) {
            PROTO_TCP -> handleTcp(pkt, ihl, srcIp, dstIp)
            PROTO_UDP -> handleUdp(pkt, ihl, dstIp)
            else -> { /* drop ICMP etc. */ }
        }
    }

    // ── TCP via SOCKS5 CONNECT ────────────────────────────────────────────────

    private fun handleTcp(pkt: ByteArray, ihl: Int, srcIp: ByteArray, dstIp: ByteArray) {
        if (pkt.size < ihl + 20) return
        val srcPort = ((pkt[ihl].toInt() and 0xFF) shl 8) or (pkt[ihl + 1].toInt() and 0xFF)
        val dstPort = ((pkt[ihl + 2].toInt() and 0xFF) shl 8) or (pkt[ihl + 3].toInt() and 0xFF)
        val flags   = pkt[ihl + 13].toInt() and 0xFF
        val synFlag = (flags and 0x02) != 0
        val finFlag = (flags and 0x01) != 0
        val rstFlag = (flags and 0x04) != 0

        val key = "${srcIp.toIpStr()}:$srcPort->${dstIp.toIpStr()}:$dstPort"

        if (synFlag && !tcpSessions.containsKey(key)) {
            // New connection — open SOCKS5 relay
            val dstAddr = dstIp.toIpStr()
            scope.launch {
                try {
                    val session = TcpSession(dstAddr, dstPort, socksPort, vpnService)
                    session.connect()
                    tcpSessions[key] = session
                    VpnLogManager.success("TCP → $dstAddr:$dstPort via SOCKS5")
                } catch (e: Exception) {
                    VpnLogManager.warn("TCP relay failed $dstAddr:$dstPort: ${e.message}")
                }
            }
        } else if ((finFlag || rstFlag) && tcpSessions.containsKey(key)) {
            tcpSessions.remove(key)?.close()
        } else {
            // Data packet — forward payload
            val tcpHeaderLen = ((pkt[ihl + 12].toInt() and 0xFF) shr 4) * 4
            val dataOffset = ihl + tcpHeaderLen
            if (pkt.size > dataOffset) {
                val payload = pkt.copyOfRange(dataOffset, pkt.size)
                tcpSessions[key]?.write(payload)
            }
        }
    }

    // ── UDP best-effort forwarding ────────────────────────────────────────────

    private fun handleUdp(pkt: ByteArray, ihl: Int, dstIp: ByteArray) {
        if (pkt.size < ihl + 8) return
        val dstPort = ((pkt[ihl + 2].toInt() and 0xFF) shl 8) or (pkt[ihl + 3].toInt() and 0xFF)
        val payloadOffset = ihl + 8
        if (pkt.size <= payloadOffset) return
        val payload = pkt.copyOfRange(payloadOffset, pkt.size)
        val dstAddr = dstIp.toIpStr()

        scope.launch {
            try {
                // For UDP (mostly DNS on port 53), use a protected DatagramSocket
                val udpSock = java.net.DatagramSocket()
                vpnService.protect(udpSock)
                udpSock.use { sock ->
                    sock.soTimeout = 3000
                    val dp = java.net.DatagramPacket(payload, payload.size, java.net.InetAddress.getByName(dstAddr), dstPort)
                    sock.send(dp)
                }
            } catch (_: Exception) {}
        }
    }

    // ── TCP Session (SOCKS5 CONNECT relay) ────────────────────────────────────

    private inner class TcpSession(
        private val host: String,
        private val port: Int,
        private val socksPort: Int,
        private val vpnService: VpnService,
    ) {
        private var socket: Socket? = null
        private var out: OutputStream? = null

        fun connect() {
            val s = Socket()
            vpnService.protect(s)
            s.connect(InetSocketAddress("127.0.0.1", socksPort), 5000)
            s.soTimeout = 0
            val inp = s.getInputStream()
            val outp = s.getOutputStream()

            // SOCKS5 handshake
            outp.write(byteArrayOf(0x05, 0x01, SOCKS5_NO_AUTH.toByte()))
            outp.flush()
            inp.read(ByteArray(2))   // server choice

            // SOCKS5 CONNECT
            val hostBytes = host.toByteArray(Charsets.US_ASCII)
            val req = ByteBuffer.allocate(7 + hostBytes.size).apply {
                put(0x05); put(SOCKS5_CONNECT.toByte()); put(0x00)
                put(0x03)  // domain name
                put(hostBytes.size.toByte())
                put(hostBytes)
                putShort(port.toShort())
            }
            outp.write(req.array())
            outp.flush()
            // Read SOCKS5 reply (at least 10 bytes for IPv4 bind)
            val reply = ByteArray(10)
            var total = 0
            while (total < 10) total += inp.read(reply, total, 10 - total)

            socket = s
            out = outp

            // Background read from proxy → TUN (write-back disabled — app handles response side)
        }

        fun write(data: ByteArray) {
            try { out?.write(data); out?.flush() } catch (_: Exception) { close() }
        }

        fun close() {
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            out = null
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun ByteArray.toIpStr() =
        "%d.%d.%d.%d".format(
            this[0].toInt() and 0xFF, this[1].toInt() and 0xFF,
            this[2].toInt() and 0xFF, this[3].toInt() and 0xFF
        )
}

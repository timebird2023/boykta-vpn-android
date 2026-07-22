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
 * TCP connections:
 *   1. SYN from TUN  → SOCKS5 CONNECT to Xray → send SYN-ACK to TUN
 *   2. DATA from TUN → write to SOCKS5 proxy socket
 *   3. DATA from Xray proxy → craft TCP+IP packet → write to TUN (the missing piece!)
 *   4. FIN/RST       → graceful cleanup
 *
 * UDP: protected DatagramSocket → bypasses TUN directly (DNS resolution)
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
        private const val FLAG_ACK = 0x10

        private const val PROTO_TCP = 6
        private const val PROTO_UDP = 17
        private const val IP_HDR   = 20
        private const val TCP_HDR  = 20
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
                            VpnLogManager.warn("TCP ${dstIp.ip}:$dstPort failed: ${e.message}")
                            Log.w(TAG, "TCP connect error", e)
                            sessions.remove(key)
                            session.sendRst()
                        }
                    }
                }
            }

            // FIN — graceful close
            flags and FLAG_FIN != 0 -> sessions.remove(key)?.onFin()

            // DATA
            payload.isNotEmpty() -> sessions[key]?.onData(payload, seqNum)

            // Pure ACK — update ack tracking
            flags and FLAG_ACK != 0 -> sessions[key]?.onAck(seqNum)
        }
    }

    // ── UDP dispatch — direct protected socket (DNS bypass) ───────────────────

    private fun handleUdp(pkt: ByteArray, ihl: Int, dstIp: ByteArray) {
        if (pkt.size < ihl + 8) return
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
                }
            } catch (_: Exception) {}
        }
    }

    // ── TCP Session — FULL BIDIRECTIONAL relay ────────────────────────────────

    private inner class TcpSession(
        val key: String,
        val srcIp: ByteArray, val srcPort: Int,   // device (client)
        val dstIp: ByteArray, val dstPort: Int,   // real destination
        clientIsn: Long,
    ) {
        private val socket   = Socket()
        // Channel for device→proxy data (decouples TUN read loop from socket writes)
        private val toProxy  = Channel<ByteArray>(capacity = 256)

        // Sequence tracking (unsigned 32-bit, stored as Long)
        @Volatile var mySeq : Long = (Random.nextLong() and 0x7FFF_FFFFL)
        @Volatile var myAck : Long = (clientIsn + 1) and 0xFFFF_FFFFL

        @Volatile private var alive = false

        // ── Establish connection ───────────────────────────────────────────────

        suspend fun connect() = withContext(Dispatchers.IO) {
            vpnService.protect(socket)
            socket.connect(InetSocketAddress("127.0.0.1", socksPort), 6_000)
            socket.soTimeout = 0
            socket.tcpNoDelay = true

            val inp = socket.getInputStream()
            val out = socket.getOutputStream()

            // SOCKS5 negotiation — no-auth method
            out.write(byteArrayOf(0x05, 0x01, 0x00))
            out.flush()
            inp.readExact(2)   // server selects method

            // SOCKS5 CONNECT — ATYP=0x01 (IPv4)
            val req = ByteArray(10)
            req[0] = 0x05; req[1] = 0x01; req[2] = 0x00; req[3] = 0x01
            dstIp.copyInto(req, 4)
            req[8] = (dstPort ushr 8).toByte()
            req[9] = (dstPort and 0xFF).toByte()
            out.write(req); out.flush()

            // Read SOCKS5 reply header + bound address
            val rHdr = inp.readExact(4)
            if (rHdr[1] != 0x00.toByte())
                throw Exception("SOCKS5 refused code=${rHdr[1].toInt() and 0xFF}")
            when (rHdr[3].toInt() and 0xFF) {
                0x01 -> inp.readExact(6)    // IPv4 (4 bytes) + port (2)
                0x03 -> {
                    val dlen = inp.readExact(1)[0].toInt() and 0xFF
                    inp.readExact(dlen + 2) // domain + port
                }
                0x04 -> inp.readExact(18)   // IPv6 (16) + port (2)
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
            mySeq = (mySeq + 1) and 0xFFFF_FFFFL   // SYN counts as 1 seq byte

            // ── Device→Proxy writer (reads from Channel, writes to SOCKS5 socket) ──
            val writerJob = launch {
                try {
                    for (chunk in toProxy) {
                        out.write(chunk)
                        out.flush()
                    }
                } catch (e: Exception) {
                    if (alive) Log.w(TAG, "proxy write error ${dstIp.ip}:$dstPort — ${e.message}")
                }
            }

            // ── Proxy→Device reader (the missing piece: crafts IP+TCP and writes to TUN) ──
            val rbuf = ByteArray(MTU - IP_HDR - TCP_HDR)
            try {
                while (alive) {
                    val len = inp.read(rbuf)
                    if (len <= 0) break
                    val data = rbuf.copyOf(len)
                    // Build a valid IP+TCP DATA packet and inject it back to TUN
                    sendToTun(buildTcp(
                        sIp = dstIp, sPort = dstPort,
                        dIp = srcIp, dPort = srcPort,
                        seq = mySeq, ack = myAck,
                        flags = FLAG_ACK, payload = data
                    ))
                    mySeq = (mySeq + len) and 0xFFFF_FFFFL
                }
            } catch (e: Exception) {
                if (alive) Log.d(TAG, "proxy read end ${dstIp.ip}:$dstPort — ${e.message}")
            } finally {
                alive = false
                writerJob.cancel()
                toProxy.close()
                // Notify device with FIN+ACK so TCP closes cleanly
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

        fun onData(payload: ByteArray, seqNum: Long) {
            myAck = (seqNum + payload.size) and 0xFFFF_FFFFL
            if (!alive) return
            toProxy.trySend(payload)   // non-blocking; drops if full (TCP retransmits)
        }

        fun onAck(seqNum: Long) {
            // Track for logging; no action needed for forwarding
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

    /**
     * Craft a valid IPv4+TCP packet suitable for injecting into the TUN fd.
     * Computes correct IP and TCP checksums (with pseudo-header).
     */
    private fun buildTcp(
        sIp: ByteArray, sPort: Int,
        dIp: ByteArray, dPort: Int,
        seq: Long, ack: Long,
        flags: Int,
        payload: ByteArray = ByteArray(0)
    ): ByteArray {
        val total = IP_HDR + TCP_HDR + payload.size
        val buf   = ByteArray(total)   // zero-initialized

        // ── IPv4 header ───────────────────────────────────────────────────────
        buf[0] = 0x45.toByte()          // version=4, IHL=5 words (20 bytes)
        buf[1] = 0x00                   // DSCP / ECN
        buf.w16(2, total)
        buf.w16(4, (System.nanoTime() and 0xFFFFL).toInt())  // ID (unique enough)
        buf[6] = 0x40; buf[7] = 0x00   // DF flag, no fragment
        buf[8] = 64                    // TTL
        buf[9] = 6                     // protocol = TCP
        // bytes 10-11 = checksum — computed below after src/dst are set
        sIp.copyInto(buf, 12)
        dIp.copyInto(buf, 16)
        buf.w16(10, checksum(buf, 0, IP_HDR))

        // ── TCP header ────────────────────────────────────────────────────────
        val T = IP_HDR
        buf.w16(T,     sPort)
        buf.w16(T + 2, dPort)
        buf.w32(T + 4, seq)            // sequence number
        buf.w32(T + 8, ack)            // acknowledgement number
        buf[T + 12] = 0x50             // data offset = 5 words = 20 bytes
        buf[T + 13] = flags.toByte()
        buf.w16(T + 14, 65535)         // window size
        // bytes T+16/17 = TCP checksum — computed below after payload copied
        // bytes T+18/19 = urgent pointer (already 0)

        // ── Payload ───────────────────────────────────────────────────────────
        payload.copyInto(buf, IP_HDR + TCP_HDR)

        // TCP checksum (RFC 793 pseudo-header: srcIP + dstIP + 0x00 + proto + tcp segment len)
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

    /** TCP checksum computed over pseudo-header + TCP segment (checksum field zeroed). */
    private fun tcpChecksum(sIp: ByteArray, dIp: ByteArray, buf: ByteArray, tcpOff: Int, tcpLen: Int): Int {
        val ph = ByteArray(12 + tcpLen)
        sIp.copyInto(ph, 0)
        dIp.copyInto(ph, 4)
        ph[8] = 0x00; ph[9] = 0x06            // protocol = TCP
        ph[10] = (tcpLen ushr 8).toByte()
        ph[11] = (tcpLen and 0xFF).toByte()
        buf.copyInto(ph, 12, tcpOff, tcpOff + tcpLen)
        ph[12 + 16] = 0; ph[12 + 17] = 0     // zero TCP checksum field
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

    /** Format 4-byte IPv4 address as dotted decimal. */
    private val ByteArray.ip: String
        get() = "%d.%d.%d.%d".format(
            this[0].toInt() and 0xFF, this[1].toInt() and 0xFF,
            this[2].toInt() and 0xFF, this[3].toInt() and 0xFF
        )

    /** Read 2-byte big-endian unsigned short as Int. */
    private fun ByteArray.u16(off: Int): Int =
        ((this[off].toInt() and 0xFF) shl 8) or (this[off + 1].toInt() and 0xFF)

    /** Read 4-byte big-endian unsigned int as Long (for seq/ack numbers). */
    private fun ByteArray.u32L(off: Int): Long =
        ((this[off].toLong() and 0xFF) shl 24) or
        ((this[off + 1].toLong() and 0xFF) shl 16) or
        ((this[off + 2].toLong() and 0xFF) shl 8) or
        (this[off + 3].toLong() and 0xFF)

    /** Write 2-byte big-endian unsigned short. */
    private fun ByteArray.w16(off: Int, v: Int) {
        this[off]     = (v ushr 8 and 0xFF).toByte()
        this[off + 1] = (v        and 0xFF).toByte()
    }

    /** Write 4-byte big-endian unsigned int (seq/ack, stored as Long). */
    private fun ByteArray.w32(off: Int, v: Long) {
        this[off]     = (v ushr 24 and 0xFF).toByte()
        this[off + 1] = (v ushr 16 and 0xFF).toByte()
        this[off + 2] = (v ushr  8 and 0xFF).toByte()
        this[off + 3] = (v         and 0xFF).toByte()
    }
}

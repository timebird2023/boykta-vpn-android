package com.boykta.vpn.service

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * Authentic terminal-log diagnostics for the VPN tunnel.
 *
 * All values come from real system calls — no fake/hardcoded data.
 *
 * Sections:
 *   1. Device & system info   — Build.MODEL, VERSION, ABI
 *   2. Network interfaces     — real kernel iface list via NetworkInterface
 *   3. Tunnel ping            — real HTTPS GET through Xray SOCKS5 proxy
 *
 * Logging policy:
 *   ✅ Clean milestone messages (INFO / SUCCESS / WARN)
 *   ❌ Raw Java stack frames are NEVER shown in the terminal log.
 *      Instead: one-line error summary + root cause class/message.
 */
object TunnelPingChecker {

    private val ts get() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    // HTTPS endpoints — returns 204 No Content when internet is up
    private const val PING_URL_1 = "https://connectivitycheck.gstatic.com/generate_204"
    private const val PING_URL_2 = "https://www.gstatic.com/generate_204"
    private const val PING_URL_3 = "https://www.google.com/generate_204"

    // Timeouts — generous for high-latency VPN servers
    private const val HTTPS_TIMEOUT_MS = 12_000     // 12 s for full TLS round-trip
    private const val TCP_PROBE_TIMEOUT = 2_000      // 2 s for local loopback TCP check

    // ── 1. Device & system info ───────────────────────────────────────────────

    fun logDeviceInfo() {
        val model   = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val android = Build.VERSION.RELEASE
        val sdk     = Build.VERSION.SDK_INT
        val abis    = Build.SUPPORTED_ABIS.take(3).joinToString(" / ")
        VpnLogManager.device("Device  : ${Build.BRAND} $model")
        VpnLogManager.device("Android : $android  (API $sdk)   ABI: $abis")
    }

    // ── 2. Network interfaces ─────────────────────────────────────────────────

    fun logNetworkInterfaces() {
        try {
            val active = Collections.list(NetworkInterface.getNetworkInterfaces() ?: return)
                .filter { !it.isLoopback && it.isUp }
            if (active.isEmpty()) { VpnLogManager.sys("Network interfaces: none active"); return }
            for (iface in active) {
                val addrs = Collections.list(iface.inetAddresses)
                    .filter { !it.isLoopbackAddress }
                    .mapNotNull { it.hostAddress }
                    .joinToString(", ")
                VpnLogManager.sys("Interface [${iface.name}] → ${addrs.ifBlank { "(no address)" }}")
            }
        } catch (e: Exception) {
            VpnLogManager.warn("Interface enumeration failed: ${e.message}")
        }
    }

    // ── 3. TCP-level proxy liveness probe ─────────────────────────────────────

    /**
     * Quick TCP connect to 127.0.0.1:socksPort — confirms Xray is listening.
     * Much faster than a full HTTPS ping (completes in <200 ms on loopback).
     * Does NOT need protect() — loopback traffic bypasses the TUN interface.
     *
     * @return true if Xray accepted the connection, false if refused/timed-out.
     */
    fun isProxyAlive(socksPort: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", socksPort), TCP_PROBE_TIMEOUT)
                true
            }
        } catch (_: Exception) { false }
    }

    // ── 4. Tunnel ping via SOCKS5 proxy ──────────────────────────────────────

    /**
     * Execute a real HTTPS GET routed through the Xray SOCKS5 proxy.
     *
     * Routes via Proxy(SOCKS, 127.0.0.1:socksPort) so the request goes through
     * Xray-core rather than bypassing the tunnel (addDisallowedApplication).
     *
     * @return true if tunnel is healthy (HTTP 2xx/204), false on any failure.
     */
    suspend fun pingAndLog(socksPort: Int): Boolean = withContext(Dispatchers.IO) {
        val (code, ms) = pingThroughTunnel(socksPort)
        when {
            code in 200..299 || code == 204 -> true   // already logged as ✅
            code < 0 -> {
                VpnLogManager.warn("[WARN] Tunnel check failed — no response from SOCKS5:$socksPort")
                false
            }
            else -> {
                VpnLogManager.warn("[WARN] Tunnel replied HTTP $code — proxy may be filtering")
                false
            }
        }
    }

    private suspend fun pingThroughTunnel(socksPort: Int): Pair<Int, Long> =
        withContext(Dispatchers.IO) {
            for (url in listOf(PING_URL_1, PING_URL_2, PING_URL_3)) {
                val result = doHttpPing(url, socksPort)
                if (result.first > 0) return@withContext result
            }
            Pair(-1, -1L)
        }

    private fun doHttpPing(urlStr: String, socksPort: Int): Pair<Int, Long> {
        return try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            val conn  = java.net.URL(urlStr).openConnection(proxy) as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = HTTPS_TIMEOUT_MS
            conn.readTimeout    = HTTPS_TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")

            val t0   = System.currentTimeMillis()
            conn.connect()
            val code = conn.responseCode
            val ms   = System.currentTimeMillis() - t0

            runCatching { conn.inputStream?.close() }
            conn.disconnect()

            val label = when (code) {
                200      -> "OK"
                204      -> "No Content"
                301, 302 -> "Redirect"
                else     -> "HTTP $code"
            }
            // ✅ Clean milestone — no stack traces
            VpnLogManager.success("HTTP Ping $code $label (${ms}ms) [$ts]")
            Pair(code, ms)

        } catch (e: Exception) {
            // ── Clean one-line error summary — NO raw stack frames ──────────
            // Root cause only: exception class + message. Frames go to LogCat only.
            val cause = e.javaClass.simpleName
            val msg   = e.message?.substringBefore("\n")?.take(80) ?: "unknown"
            VpnLogManager.warn("[WARN] Ping via SOCKS5 failed → $cause: $msg [$ts]")
            Pair(-1, -1L)
        }
    }
}

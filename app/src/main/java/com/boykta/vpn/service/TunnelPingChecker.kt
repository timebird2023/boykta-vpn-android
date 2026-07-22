package com.boykta.vpn.service

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * Authentic terminal-log diagnostics for the VPN tunnel.
 *
 * All values logged here come from real system calls — no fake/hardcoded data.
 *
 * Sections:
 *   1. Device & system info   — Build.MODEL, VERSION, ABI
 *   2. Network interfaces     — real kernel iface list via NetworkInterface
 *   3. Tunnel ping            — real HTTPS GET through Xray SOCKS5 proxy
 *      Returns HTTP 204 from connectivitycheck.gstatic.com when tunnel is healthy.
 *      Stack traces are logged on failure for full diagnostics.
 */
object TunnelPingChecker {

    private val ts get() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    // HTTPS endpoints — returns 204 No Content when internet is up
    private const val PING_URL_1 = "https://connectivitycheck.gstatic.com/generate_204"
    private const val PING_URL_2 = "https://www.gstatic.com/generate_204"
    private const val PING_URL_3 = "https://www.google.com/generate_204"
    private const val TIMEOUT_MS = 8_000

    // ── 1. Device & system info ───────────────────────────────────────────────

    /**
     * Log real hardware + Android version info.
     * Called once when VPN starts so the terminal log has context.
     */
    fun logDeviceInfo() {
        val model   = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val brand   = Build.BRAND
        val android = Build.VERSION.RELEASE
        val sdk     = Build.VERSION.SDK_INT
        val abi     = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        val abis    = Build.SUPPORTED_ABIS.take(3).joinToString(" / ")

        VpnLogManager.device("═══════════════════════════════════")
        VpnLogManager.device("Device  : $brand $model")
        VpnLogManager.device("Android : $android  (API $sdk)")
        VpnLogManager.device("ABI     : $abis")
        VpnLogManager.device("═══════════════════════════════════")
    }

    // ── 2. Network interfaces ─────────────────────────────────────────────────

    /**
     * Log all active kernel network interfaces (tun0, wlan0, rmnet, lo, etc.).
     * Uses java.net.NetworkInterface for real kernel data.
     */
    fun logNetworkInterfaces() {
        try {
            val all: List<NetworkInterface> =
                Collections.list(NetworkInterface.getNetworkInterfaces() ?: return)
            val active = all.filter { !it.isLoopback && it.isUp }
            if (active.isEmpty()) {
                VpnLogManager.sys("Network interfaces: none active")
                return
            }
            for (iface in active) {
                val addrs = Collections.list(iface.inetAddresses)
                    .filter { !it.isLoopbackAddress }
                    .mapNotNull { it.hostAddress }
                    .joinToString(", ")
                val addr = if (addrs.isBlank()) "(no address)" else addrs
                VpnLogManager.sys("Interface [${iface.name}] → $addr")
            }
        } catch (e: Exception) {
            VpnLogManager.warn("Interface enumeration failed: ${e.message}")
        }
    }

    // ── 3. Tunnel ping via SOCKS5 proxy ──────────────────────────────────────

    /**
     * Execute a real HTTPS GET routed through the Xray SOCKS5 proxy.
     *
     * Why via SOCKS5 specifically?
     *   VpnService uses addDisallowedApplication(packageName) so our own app's traffic
     *   bypasses the TUN. A plain openConnection() would therefore NOT go through Xray.
     *   By connecting via Proxy(SOCKS, 127.0.0.1:socksPort), the request is explicitly
     *   handed to Xray-core, which routes it through the VPN server. This is genuine
     *   tunnel validation.
     *
     * Returns (httpStatusCode, latencyMs) or (-1, -1) on failure.
     */
    suspend fun pingThroughTunnel(socksPort: Int): Pair<Int, Long> = withContext(Dispatchers.IO) {
        // Try each URL in order — return first success
        for (url in listOf(PING_URL_1, PING_URL_2, PING_URL_3)) {
            val result = doHttpPing(url, socksPort)
            if (result.first > 0) return@withContext result
        }
        return@withContext Pair(-1, -1L)
    }

    private fun doHttpPing(urlStr: String, socksPort: Int): Pair<Int, Long> {
        return try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            val url   = java.net.URL(urlStr)
            val conn  = url.openConnection(proxy) as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout    = TIMEOUT_MS
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
            VpnLogManager.success("HTTP Ping $code $label (${ms}ms) [$ts]")
            Pair(code, ms)

        } catch (e: Exception) {
            // Full diagnostic message — no fake status
            VpnLogManager.error("HTTP Ping FAILED [$ts] → ${e.javaClass.simpleName}: ${e.message}")
            VpnLogManager.error("  URL: $urlStr | SOCKS5 127.0.0.1:$socksPort")
            // Log first few frames of stack for debugging
            val frames = e.stackTrace.take(5).joinToString("\n  ") { it.toString() }
            VpnLogManager.error("  Stack:\n  $frames")
            Pair(-1, -1L)
        }
    }

    /** Convenience: ping and log result. Used in keep-alive loop. */
    suspend fun pingAndLog(socksPort: Int) = withContext(Dispatchers.IO) {
        val (code, ms) = pingThroughTunnel(socksPort)
        when {
            code in 200..299 || code == 204 -> { /* already logged as success */ }
            code < 0 -> VpnLogManager.warn("Tunnel check failed — no response from SOCKS5:$socksPort [$ts]")
            else     -> VpnLogManager.warn("Tunnel responded HTTP $code — proxy may be blocking [$ts]")
        }
    }
}

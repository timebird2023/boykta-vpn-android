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
 * Performs REAL HTTP ping checks routed explicitly through the Xray SOCKS5 proxy.
 *
 * IMPORTANT: Android's VpnService uses addDisallowedApplication(packageName) to exclude
 * the app's own traffic from the TUN interface (prevents routing loops). Therefore a plain
 * openConnection() from within the app bypasses the tunnel. To validate tunnel health we
 * connect through the SOCKS5 proxy on 127.0.0.1:[socksPort] directly — this traffic travels
 * through Xray-core and out through the real upstream server.
 *
 * Target: http://connectivitycheck.gstatic.com/generate_204
 * Returns HTTP 204 when internet is reachable — the same check Android's framework uses.
 * No hardcoded fake status codes are ever output.
 */
object TunnelPingChecker {

    private val ts get() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    private const val PING_URL_PRIMARY  = "http://connectivitycheck.gstatic.com/generate_204"
    private const val PING_URL_FALLBACK = "http://www.gstatic.com/generate_204"
    private const val TIMEOUT_MS = 8_000

    // ── Device info ───────────────────────────────────────────────────────────

    /**
     * Logs real device hardware info and Android version to VpnLogManager.
     * Called once on VPN connection start.
     */
    fun logDeviceInfo() {
        val model   = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        val brand   = Build.BRAND
        val android = Build.VERSION.RELEASE
        val sdk     = Build.VERSION.SDK_INT
        val abi     = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

        VpnLogManager.sys("Device : $brand $model")
        VpnLogManager.sys("Android: $android (API $sdk) | ABI: $abi")
    }

    /**
     * Logs active network interfaces — tun0, rmnet0, wlan0, lo, etc.
     * Reads real kernel interfaces via java.net.NetworkInterface.
     */
    fun logNetworkInterfaces() {
        try {
            val enumeration = NetworkInterface.getNetworkInterfaces() ?: return
            val ifaceList: List<NetworkInterface> = Collections.list(enumeration)
            val active = ifaceList.filter { iface -> !iface.isLoopback && iface.isUp }
            if (active.isEmpty()) {
                VpnLogManager.sys("Interfaces: none active")
                return
            }
            for (iface in active) {
                val addrEnumeration = iface.inetAddresses
                val addrList: List<InetAddress> = Collections.list(addrEnumeration)
                val addrs = addrList
                    .filter { addr -> !addr.isLoopbackAddress }
                    .joinToString(", ") { addr -> addr.hostAddress ?: "?" }
                val addrStr = if (addrs.isBlank()) "(no address)" else addrs
                VpnLogManager.sys("Interface [${iface.name}] → $addrStr")
            }
        } catch (e: Exception) {
            VpnLogManager.warn("Interface enumeration failed: ${e.message}")
        }
    }

    // ── Real tunnel ping via SOCKS5 proxy ─────────────────────────────────────

    /**
     * Executes a real HTTP GET routed through the Xray SOCKS5 proxy.
     *
     * By connecting via [Proxy.Type.SOCKS] to 127.0.0.1:[socksPort], the request travels
     * through Xray-core and out via the upstream VPN server — this is genuine tunnel
     * validation, not a local bypass.
     *
     * Returns (httpStatusCode, latencyMs) or (-1, -1) on failure.
     * Logs: "HTTP Ping 204 No Content (117ms) [HH:mm:ss]"
     */
    suspend fun pingThroughTunnel(socksPort: Int): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val primary = runPing(PING_URL_PRIMARY, socksPort)
        if (primary.first > 0) primary else runPing(PING_URL_FALLBACK, socksPort)
    }

    private fun runPing(urlStr: String, socksPort: Int): Pair<Int, Long> {
        return try {
            // Route explicitly through Xray's SOCKS5 proxy — not through system network.
            // This ensures the request actually traverses the VPN tunnel.
            val socksProxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            val url  = java.net.URL(urlStr)
            val conn = url.openConnection(socksProxy) as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout    = TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.useCaches = false

            val start   = System.currentTimeMillis()
            conn.connect()
            val code    = conn.responseCode
            val elapsed = System.currentTimeMillis() - start

            try { conn.inputStream.close() } catch (_: Exception) {}
            conn.disconnect()

            val codeText = when (code) {
                204      -> "No Content"
                200      -> "OK"
                301, 302 -> "Redirect"
                else     -> "HTTP $code"
            }
            VpnLogManager.success("HTTP Ping $code $codeText (${elapsed}ms) [$ts]")
            Pair(code, elapsed)
        } catch (e: Exception) {
            VpnLogManager.error("HTTP Ping FAILED [$ts]: ${e.message}")
            Pair(-1, -1L)
        }
    }

    /**
     * Run a single ping through the tunnel and log the result.
     * Used in the keep-alive loop.
     */
    suspend fun pingAndLog(socksPort: Int) = withContext(Dispatchers.IO) {
        val (code, _) = pingThroughTunnel(socksPort)
        when {
            code == 204 || code in 200..299 -> { /* tunnel healthy */ }
            code < 0 -> VpnLogManager.warn("Tunnel connectivity check failed — no response")
            else     -> VpnLogManager.warn("Tunnel responded with HTTP $code")
        }
    }
}

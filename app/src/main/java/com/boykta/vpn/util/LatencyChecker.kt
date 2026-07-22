package com.boykta.vpn.util

import com.boykta.vpn.service.BoykVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Measures network latency (ping) by making a HEAD/GET request to a reliable endpoint.
 *
 * When the VPN is active, routes the request through the Xray SOCKS5 proxy so we measure
 * the actual VPN tunnel latency (not direct internet). This matches what TunnelPingChecker
 * does internally — the result shown in the ping badge now reflects real tunnel quality.
 *
 * When VPN is inactive, falls back to a direct connection.
 */
object LatencyChecker {

    private const val PING_URL_VPN    = "https://connectivitycheck.gstatic.com/generate_204"
    private const val PING_URL_DIRECT = "https://dns.google"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS    = 5_000

    /**
     * Measures round-trip latency.
     * @return Latency in milliseconds, or -1 if unreachable / timeout.
     */
    suspend fun measureMs(): Long = withContext(Dispatchers.IO) {
        try {
            if (BoykVpnService.isRunning) {
                // Route through VPN tunnel SOCKS5 proxy for accurate VPN latency
                measureViaSocks(PING_URL_VPN, BoykVpnService.LOCAL_SOCKS_PORT)
            } else {
                measureDirect(PING_URL_DIRECT)
            }
        } catch (_: Exception) { -1L }
    }

    private fun measureViaSocks(urlStr: String, socksPort: Int): Long {
        return try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
            val conn = URL(urlStr).openConnection(proxy) as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout    = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val t0 = System.currentTimeMillis()
            conn.connect()
            val code = conn.responseCode
            val ms = System.currentTimeMillis() - t0
            runCatching { conn.inputStream?.close() }
            conn.disconnect()
            if (code in 200..299 || code == 204) ms else -1L
        } catch (_: Exception) { -1L }
    }

    private fun measureDirect(urlStr: String): Long {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout    = READ_TIMEOUT_MS
            conn.instanceFollowRedirects = false
            val t0 = System.currentTimeMillis()
            conn.connect()
            val code = conn.responseCode
            val ms = System.currentTimeMillis() - t0
            conn.disconnect()
            if (code in 200..399) ms else -1L
        } catch (_: Exception) { -1L }
    }

    /** Formats a latency value for display. */
    fun format(ms: Long): String = if (ms < 0) "—" else "$ms ms"
}

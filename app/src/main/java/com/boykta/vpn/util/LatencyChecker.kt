package com.boykta.vpn.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Measures network latency (ping) by making a HEAD request to a reliable endpoint.
 * Uses https://dns.google as the target, which is lightweight and globally available.
 */
object LatencyChecker {

    private const val PING_URL = "https://dns.google"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 5_000

    /**
     * Measures round-trip latency to [PING_URL].
     * @return Latency in milliseconds, or -1 if unreachable / timeout.
     */
    suspend fun measureMs(): Long = withContext(Dispatchers.IO) {
        try {
            val url = URL(PING_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false

            val start = System.currentTimeMillis()
            connection.connect()
            val responseCode = connection.responseCode
            val elapsed = System.currentTimeMillis() - start

            connection.disconnect()

            if (responseCode in 200..399) elapsed else -1L
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * Formats a latency value for display on the server card.
     * @param ms Latency in milliseconds (-1 means timeout/unreachable)
     * @return Human-readable string like "32 ms" or "—"
     */
    fun format(ms: Long): String = if (ms < 0) "—" else "$ms ms"
}

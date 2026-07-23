package com.boykta.vpn.service

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Real-time traffic counter that reads /proc/net/dev for the VPN TUN interface.
 * Updates upload/download speed every second without blocking the main thread.
 *
 * Speed is smoothed with an exponential moving average (α = 0.3) to avoid
 * jerky UI values while staying responsive to bursts.
 */
object TrafficCounter {

    data class TrafficStats(
        val uploadSpeed   : String = "0 KB/s",
        val downloadSpeed : String = "0 KB/s",
        val totalUploaded : String = "0 B",
        val totalDownloaded: String = "0 B",
    )

    private val _stats = MutableStateFlow(TrafficStats())
    val stats: StateFlow<TrafficStats> = _stats

    private var job: Job? = null
    private var sessionStart = 0L
    private var sessionRxStart = 0L
    private var sessionTxStart = 0L

    private var prevRx = 0L
    private var prevTx = 0L
    private var smoothRx = 0.0
    private var smoothTx = 0.0

    private const val ALPHA = 0.35   // EMA smoothing factor
    // Dynamic detection: scan /proc/net/dev for any interface whose name starts
    // with "tun" or "vpn". Handles tun0, tun10, vpn0, vpntun, etc. without
    // a hardcoded list that misses interfaces assigned by different kernels/ROMs.

    fun start(scope: CoroutineScope) {
        stop()
        val (rxNow, txNow) = readTunBytes()
        sessionRxStart = rxNow
        sessionTxStart = txNow
        prevRx = rxNow
        prevTx = txNow
        smoothRx = 0.0
        smoothTx = 0.0
        sessionStart = System.currentTimeMillis()

        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(1_000)
                val (rx, tx) = readTunBytes()

                val dRx = (rx - prevRx).coerceAtLeast(0)
                val dTx = (tx - prevTx).coerceAtLeast(0)
                prevRx = rx
                prevTx = tx

                // Exponential moving average for smooth display
                smoothRx = ALPHA * dRx + (1 - ALPHA) * smoothRx
                smoothTx = ALPHA * dTx + (1 - ALPHA) * smoothTx

                _stats.value = TrafficStats(
                    uploadSpeed    = formatSpeed(smoothTx.toLong()),
                    downloadSpeed  = formatSpeed(smoothRx.toLong()),
                    totalUploaded  = formatBytes(tx - sessionTxStart),
                    totalDownloaded= formatBytes(rx - sessionRxStart),
                )
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _stats.value = TrafficStats()
        smoothRx = 0.0
        smoothTx = 0.0
    }

    /** Read RX and TX bytes from /proc/net/dev for the VPN TUN interface.
     *
     * Scans dynamically instead of matching a fixed list — correctly handles
     * tun0, tun1, tun10, vpn0, vpntun, and any name the kernel assigns.
     */
    private fun readTunBytes(): Pair<Long, Long> {
        return try {
            val lines = File("/proc/net/dev").readLines()
            for (line in lines) {
                val trimmed = line.trim()
                val colonIdx = trimmed.indexOf(':')
                if (colonIdx < 0) continue
                val name = trimmed.substring(0, colonIdx).trim()
                // Match any tun* or vpn* interface regardless of numeric suffix
                if (!name.startsWith("tun") && !name.startsWith("vpn")) continue
                val fields = trimmed.substring(colonIdx + 1).trim().split(Regex("\\s+"))
                // /proc/net/dev columns after interface name:
                // [0]=rx_bytes [1]=rx_packets ... [8]=tx_bytes [9]=tx_packets ...
                val rx = fields.getOrNull(0)?.toLongOrNull() ?: 0L
                val tx = fields.getOrNull(8)?.toLongOrNull() ?: 0L
                return Pair(rx, tx)
            }
            Pair(0L, 0L)
        } catch (_: Exception) {
            Pair(0L, 0L)
        }
    }

    private fun formatSpeed(bytesPerSec: Long): String = when {
        bytesPerSec >= 1_048_576 -> "%.1f MB/s".format(bytesPerSec / 1_048_576.0)
        bytesPerSec >= 1_024     -> "%.0f KB/s".format(bytesPerSec / 1_024.0)
        else                     -> "$bytesPerSec B/s"
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576     -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024         -> "%.0f KB".format(bytes / 1_024.0)
        else                   -> "$bytes B"
    }
}

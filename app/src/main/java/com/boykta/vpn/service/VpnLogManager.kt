package com.boykta.vpn.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Singleton log bus for the VPN pipeline.
 * Both BoykVpnService and XrayManager emit here; MainActivity subscribes.
 *
 * Log types:
 *   sys()     — system/lifecycle events (grey)
 *   info()    — informational (white)
 *   success() — positive outcomes (cyan)
 *   warn()    — warnings (yellow)
 *   error()   — errors (red/pink)
 *   device()  — hardware/environment info (grey-blue)
 *
 * Features:
 *   • Rate-limiting: identical [WARN]/[ERR] messages throttled to 1 per 3 seconds
 *   • Reconnect-quiet mode: suppresses SOCKS5 failure spam during auto-reconnect
 *   • Replay buffer: 120 entries for late subscribers (e.g. app relaunch)
 */
object VpnLogManager {

    private val _logs = MutableSharedFlow<String>(replay = 120, extraBufferCapacity = 300)
    val logs: SharedFlow<String> = _logs

    private val ts get() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    // ── Reconnect-quiet mode ───────────────────────────────────────────────────
    // When true, SOCKS5-connection-refused/timeout warnings are suppressed.
    // Set true just before tearing down the tunnel; cleared when new session starts.
    val isReconnecting = AtomicBoolean(false)

    // ── Rate-limiting (dedup) ──────────────────────────────────────────────────
    // Maps a normalized log key → last emit timestamp in ms.
    // Prevents identical WARN/ERR bursts from flooding the terminal.
    private val throttleMap = ConcurrentHashMap<String, Long>()
    private const val THROTTLE_MS = 3_000L   // 3 s per unique message
    private const val THROTTLE_CLEANUP_INTERVAL = 20   // clean map every N emits
    private var emitCount = 0

    private fun shouldThrottle(key: String): Boolean {
        val now = System.currentTimeMillis()
        val last = throttleMap[key] ?: 0L
        if (now - last < THROTTLE_MS) return true
        throttleMap[key] = now

        // Periodic cleanup to prevent unbounded map growth
        if (++emitCount % THROTTLE_CLEANUP_INTERVAL == 0) {
            val expired = throttleMap.entries.filter { now - it.value > 30_000L }.map { it.key }
            expired.forEach { throttleMap.remove(it) }
        }
        return false
    }

    private fun emit(msg: String) {
        _logs.tryEmit("[$ts] $msg")
    }

    fun log(msg: String) = emit(msg)

    fun info(msg: String)    = emit("[INFO] $msg")
    fun success(msg: String) = emit("[OK]   $msg")
    fun device(msg: String)  = emit("[DEV]  $msg")
    fun sys(msg: String)     = emit("[SYS]  $msg")

    fun warn(msg: String) {
        // Suppress SOCKS5 failure spam during reconnect window
        if (isReconnecting.get() && (msg.contains("SOCKS5") || msg.contains("10808") ||
                msg.contains("SocketException") || msg.contains("SocketTimeout"))) {
            return
        }
        // Rate-limit: same warning won't appear more than once per THROTTLE_MS
        val key = msg.take(80)
        if (shouldThrottle("W:$key")) return
        emit("[WARN] $msg")
    }

    fun error(msg: String) {
        val key = msg.take(80)
        if (shouldThrottle("E:$key")) return
        emit("[ERR]  $msg")
    }
}

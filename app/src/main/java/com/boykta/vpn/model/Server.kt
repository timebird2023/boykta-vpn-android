package com.boykta.vpn.model

import java.text.SimpleDateFormat
import java.util.*

data class Server(
    val id: Int,
    val name: String,      // Display name only — users never see the raw config
    val config: String,    // VLESS URI — decrypted from AES-256 API, never shown in UI
    val expiresAt: String, // ISO 8601 datetime
    val isActive: Boolean,
)

private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}
private val isoAlt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

fun Server.expiresAtMs(): Long {
    return try {
        iso.parse(expiresAt)?.time ?: isoAlt.parse(expiresAt)?.time ?: 0L
    } catch (_: Exception) { 0L }
}

fun Server.remainingMs(): Long = maxOf(0L, expiresAtMs() - System.currentTimeMillis())

fun Server.isExpired(): Boolean = remainingMs() <= 0L

fun Server.formattedRemaining(): String {
    val ms = remainingMs()
    if (ms <= 0L) return "منتهي الصلاحية"
    val h = ms / 3_600_000
    val m = (ms % 3_600_000) / 60_000
    val s = (ms % 60_000) / 1_000
    return "%02d:%02d:%02d".format(h, m, s)
}

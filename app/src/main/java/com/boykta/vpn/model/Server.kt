package com.boykta.vpn.model

import java.text.SimpleDateFormat
import java.util.*

data class Server(
    val id: Int,
    val name: String,           // Display name only — never show raw config
    val config: String,         // VLESS/Trojan/VMess URI (decrypted, never shown in UI)
    val expiresAt: String,      // ISO 8601 datetime
    val isActive: Boolean,
    val protocol: String = "vless",  // "vless" | "trojan" | "vmess" | "ss"
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

/**
 * Returns remaining time as "01d 12h:30m:15s" as required by the UI spec.
 */
fun Server.formattedRemaining(): String {
    val ms = remainingMs()
    if (ms <= 0L) return "منتهي الصلاحية"
    val totalSec = ms / 1_000
    val d = totalSec / 86_400
    val h = (totalSec % 86_400) / 3_600
    val m = (totalSec % 3_600) / 60
    val s = totalSec % 60
    return if (d > 0) "%02dd %02dh:%02dm:%02ds".format(d, h, m, s)
    else "%02dh:%02dm:%02ds".format(h, m, s)
}

/** Protocol badge label shown in the server card */
fun Server.protocolLabel(): String = protocol.uppercase()

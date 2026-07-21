package com.boykta.vpn.config

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Encrypted config model stored inside a .boykta file.
 *
 * Wire format (file bytes):
 *   Base64( IV[12] || AES-256-GCM(json) || AuthTag[16] )
 *
 * The raw fields (uuid, sni, host, path) are NEVER shown in the UI.
 * Only [name] and [expiresHours] are surface-level.
 */
data class BoykConfig(
    @SerializedName("v")    val version: Int = 1,
    @SerializedName("p")    val protocol: String,        // "vless" | "vmess" | "trojan"
    @SerializedName("n")    val name: String,            // Display name (shown to user)
    @SerializedName("id")   val uuid: String,            // UUID or password
    @SerializedName("h")    val host: String,            // Actual server host / IP
    @SerializedName("sni")  val sni: String,             // TLS SNI
    @SerializedName("hh")   val hostHeader: String,      // WS Host header
    @SerializedName("port") val port: Int,
    @SerializedName("path") val path: String = "/",
    @SerializedName("net")  val network: String = "ws",  // "ws" | "grpc" | "tcp"
    @SerializedName("sec")  val security: String = "tls",
    @SerializedName("exp")  val expiresHours: Long = 24, // hours from import time
) {
    companion object {
        private val gson = Gson()

        fun fromJson(json: String): BoykConfig = gson.fromJson(json, BoykConfig::class.java)
    }

    fun toJson(): String = gson.toJson(this)

    /** Build a VLESS URI from this config (used internally, never shown to user) */
    fun toVlessUri(): String = when (protocol.lowercase()) {
        "vless" -> "vless://$uuid@$host:$port" +
                "?type=$network&security=$security" +
                "&host=${hostHeader.ifBlank { host }}" +
                "&path=${path.ifBlank { "/" }}" +
                "&sni=${sni.ifBlank { host }}" +
                "&encryption=none#${name}"
        "trojan" -> "trojan://$uuid@$host:$port" +
                "?type=$network&security=$security" +
                "&sni=${sni.ifBlank { host }}#${name}"
        else -> "vmess://"  // vmess needs separate base64 format
    }

    /** Suggested filename for export */
    fun fileName(): String {
        val safe = name.replace(Regex("[^a-zA-Z0-9_\\-أ-ي ]"), "").trim().replace(' ', '_')
        return "$safe.boykta"
    }
}

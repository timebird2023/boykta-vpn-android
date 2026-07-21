package com.boykta.vpn.config

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Encrypted config model stored inside a .boykta file.
 *
 * Wire format (file bytes):
 *   Locked   → Base64( IV[12] || AES-256-GCM(json) || AuthTag[16] )
 *   Unlocked → raw JSON bytes (readable on import, not hidden)
 *
 * Raw fields (uuid, sni, host, path) are NEVER shown in the UI.
 * Only [name] and expiry information are surface-level.
 */
data class BoykConfig(
    @SerializedName("v")    val version: Int = 2,
    @SerializedName("p")    val protocol: String,          // "vless"|"vmess"|"trojan"|"ss"
    @SerializedName("n")    val name: String,              // Display name (shown to user)
    @SerializedName("id")   val uuid: String,              // UUID or password
    @SerializedName("h")    val host: String,              // Real server host/IP
    @SerializedName("sni")  val sni: String,               // TLS SNI
    @SerializedName("hh")   val hostHeader: String,        // WS Host header
    @SerializedName("port") val port: Int,
    @SerializedName("path") val path: String = "/",
    @SerializedName("net")  val network: String = "ws",    // "ws"|"grpc"|"tcp"
    @SerializedName("sec")  val security: String = "tls",
    @SerializedName("exp")  val expiresSeconds: Long = 86_400, // seconds from import time
    @SerializedName("locked") val locked: Boolean = true,  // AES-locked vs plain export
    @SerializedName("toast")  val customToast: String = "", // on-connect banner text
    @SerializedName("method") val ssMethod: String = "aes-256-gcm", // Shadowsocks cipher
) {
    companion object {
        private val gson = Gson()
        fun fromJson(json: String): BoykConfig = gson.fromJson(json, BoykConfig::class.java)
    }

    fun toJson(): String = gson.toJson(this)

    /** Suggested filename for export */
    fun fileName(): String {
        val safe = name.replace(Regex("[^a-zA-Z0-9_\\-أ-ي ]"), "").trim().replace(' ', '_')
        return "$safe.boykta"
    }

    /** Build the internal proxy URI (never shown to user) */
    fun toProxyUri(): String = when (protocol.lowercase()) {
        "vless" ->
            "vless://$uuid@$host:$port" +
            "?type=$network&security=$security" +
            "&host=${hostHeader.ifBlank { host }}" +
            "&path=${path.ifBlank { "/" }}" +
            "&sni=${sni.ifBlank { host }}" +
            "&encryption=none#$name"

        "trojan" ->
            "trojan://$uuid@$host:$port" +
            "?type=$network&security=$security" +
            "&sni=${sni.ifBlank { host }}#$name"

        "vmess" -> {
            // VMess uses base64-encoded JSON
            val inner = com.google.gson.JsonObject().apply {
                addProperty("v", "2"); addProperty("ps", name)
                addProperty("add", host); addProperty("port", port.toString())
                addProperty("id", uuid); addProperty("aid", "0")
                addProperty("net", network); addProperty("type", "none")
                addProperty("host", hostHeader.ifBlank { host })
                addProperty("path", path.ifBlank { "/" })
                addProperty("tls", if (security == "tls") "tls" else "")
                addProperty("sni", sni.ifBlank { host })
            }
            val encoded = android.util.Base64.encodeToString(
                inner.toString().toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )
            "vmess://$encoded"
        }

        "ss" ->
            // Shadowsocks SIP002 URI
            "ss://${android.util.Base64.encodeToString(
                "$ssMethod:$uuid".toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )}@$host:$port#$name"

        else -> "vless://$uuid@$host:$port?type=$network&security=$security#$name"
    }
}

/** Legacy alias used by BoykConfigManager */
fun BoykConfig.toVlessUri(): String = toProxyUri()

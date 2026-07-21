package com.boykta.vpn.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.boykta.vpn.model.Server
import java.text.SimpleDateFormat
import java.util.*

/**
 * Locally imported server (from .boykta file).
 * The vlessUri is encrypted at rest using Android Keystore-backed SharedPreferences
 * (simple approach: stored in encrypted EncryptedSharedPreferences, not raw DB).
 *
 * For this implementation we store the encrypted vlessUri in the DB column —
 * the field name intentionally looks harmless.
 */
@Entity(tableName = "local_servers")
data class LocalServer(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val displayName: String,          // Only this is shown to the user
    val encryptedUri: String,         // AES-256 encrypted VLESS URI
    val importedAt: Long = System.currentTimeMillis(),
    val expiresAt: Long,              // epoch millis
)

private val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

fun LocalServer.toServer(): Server = Server(
    id = id,
    name = displayName,
    config = "", // never exposed; MainViewModel fetches encrypted URI separately
    expiresAt = sdf.format(Date(expiresAt)),
    isActive = System.currentTimeMillis() < expiresAt,
)

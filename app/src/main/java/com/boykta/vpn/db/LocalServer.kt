package com.boykta.vpn.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.boykta.vpn.model.Server
import java.text.SimpleDateFormat
import java.util.*

/**
 * Locally imported server (from .boykta file).
 *
 * Locked configs:   encryptedUri contains AES-encrypted proxy URI.
 *                   configJson is blank. UI shows "كونفيغ مغلق" badge.
 *
 * Unlocked configs: encryptedUri still holds the proxy URI (may be plain or encrypted).
 *                   configJson holds the raw BoykConfig JSON so params can be shown in UI.
 *                   isLocked = false enables the unlocked-params panel.
 */
@Entity(tableName = "local_servers")
data class LocalServer(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val displayName: String,          // Only this is shown to the user for locked configs
    val encryptedUri: String,         // Encrypted or plain proxy URI
    val importedAt: Long = System.currentTimeMillis(),
    val expiresAt: Long,              // epoch millis
    val isLocked: Boolean = true,     // true = raw params hidden; false = params visible in UI
    val configJson: String = "",      // For unlocked configs: plain BoykConfig JSON for UI display
)

private val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

fun LocalServer.toServer(): Server = Server(
    id        = id,
    name      = displayName,
    config    = "",       // never exposed; fetched separately at connect time
    expiresAt = sdf.format(Date(expiresAt)),
    isActive  = System.currentTimeMillis() < expiresAt,
    protocol  = "local",
    isLocked  = isLocked,
    configJson = configJson,
)

package com.boykta.vpn.config

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.boykta.vpn.api.CryptoHelper
import java.io.File
import java.io.FileOutputStream

object BoykConfigManager {

    /**
     * Export a BoykConfig to a .boykta file in Downloads.
     *
     * If [config.locked] is true  → encrypted (raw fields hidden).
     * If [config.locked] is false → raw JSON (visible on import — "unlocked" mode).
     *
     * @return the saved File, or null on failure
     */
    fun export(context: Context, config: BoykConfig): File? {
        return try {
            val payload: String = if (config.locked) {
                CryptoHelper.encrypt(config.toJson())
            } else {
                // Unlocked: plain JSON, importable and human-readable
                config.toJson()
            }

            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            downloadsDir.mkdirs()
            val file = File(downloadsDir, config.fileName())
            FileOutputStream(file).use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Decrypt and parse a .boykta file from a URI.
     * Accepts both locked (encrypted) and unlocked (plain JSON) files.
     *
     * @return Pair(BoykConfig, isLocked) on success, null on failure
     */
    fun importWithLockInfo(context: Context, uri: Uri): Pair<BoykConfig, Boolean>? {
        return try {
            val raw = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return null

            val isLocked: Boolean
            val json = when {
                CryptoHelper.isEncrypted(raw) -> {
                    isLocked = true
                    CryptoHelper.decrypt(raw)
                }
                raw.trimStart().startsWith("{") -> {
                    isLocked = false
                    raw  // unlocked plain JSON
                }
                else -> return null
            }

            Pair(BoykConfig.fromJson(json), isLocked)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Legacy import without lock info — kept for compatibility.
     */
    fun import(context: Context, uri: Uri): BoykConfig? =
        importWithLockInfo(context, uri)?.first

    /** Convert a BoykConfig to a proxy URI for the VPN engine (internal use only). */
    fun configToVlessUri(config: BoykConfig): String = config.toProxyUri()
}

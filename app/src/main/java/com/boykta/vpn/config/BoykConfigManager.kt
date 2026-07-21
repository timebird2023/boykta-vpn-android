package com.boykta.vpn.config

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.boykta.vpn.api.CryptoHelper
import java.io.File
import java.io.FileOutputStream

object BoykConfigManager {

    /**
     * Encrypt a BoykConfig and save it as [name].boykta to Downloads.
     * @return the saved File, or null on failure
     */
    fun export(context: Context, config: BoykConfig): File? {
        return try {
            val encrypted = CryptoHelper.encrypt(config.toJson())

            // Save to Downloads folder
            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            downloadsDir.mkdirs()
            val file = File(downloadsDir, config.fileName())
            FileOutputStream(file).use { it.write(encrypted.toByteArray(Charsets.UTF_8)) }
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Decrypt and parse a .boykta file from a URI (intent or file picker).
     * @return BoykConfig on success, null on failure
     */
    fun import(context: Context, uri: Uri): BoykConfig? {
        return try {
            val raw = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return null

            val json = if (CryptoHelper.isEncrypted(raw)) {
                CryptoHelper.decrypt(raw)
            } else {
                return null  // reject plain-text files — must be encrypted
            }

            BoykConfig.fromJson(json)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Convert a BoykConfig to a Server-like object for use in the VPN engine.
     * The VLESS URI is internal only — never displayed.
     */
    fun configToVlessUri(config: BoykConfig): String = config.toVlessUri()
}

package com.boykta.vpn.api

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption/decryption.
 *
 * Key derivation: SHA-256("boykta_2nlkkh53DaYBmllnvb2026") → 32 bytes
 * Wire format: Base64(IV[12] || Ciphertext || AuthTag[16])
 */
object CryptoHelper {

    private val KEY_BYTES: ByteArray by lazy {
        MessageDigest.getInstance("SHA-256")
            .digest("boykta_2nlkkh53DaYBmllnvb2026".toByteArray(Charsets.UTF_8))
    }

    private val SECRET_KEY by lazy { SecretKeySpec(KEY_BYTES, "AES") }

    /**
     * Decrypt a Base64-encoded AES-256-GCM ciphertext.
     * @param encoded Base64(IV[12] + ciphertext + authTag[16])
     * @return Plaintext UTF-8 string
     */
    fun decrypt(encoded: String): String {
        val raw = Base64.decode(encoded.trim(), Base64.DEFAULT)
        require(raw.size > 12) { "Invalid ciphertext length" }

        val iv = raw.copyOfRange(0, 12)
        val ciphertext = raw.copyOfRange(12, raw.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SECRET_KEY, GCMParameterSpec(128, iv))
        val plaintext = cipher.doFinal(ciphertext)
        return String(plaintext, Charsets.UTF_8)
    }

    /**
     * Encrypt a plaintext string.
     * @return Base64(IV[12] + ciphertext + authTag[16])
     */
    fun encrypt(plaintext: String): String {
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SECRET_KEY, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = iv + ciphertext
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /** Returns true if the string is AES-GCM encrypted (not plain JSON) */
    fun isEncrypted(data: String): Boolean = !data.trimStart().startsWith("{")
}

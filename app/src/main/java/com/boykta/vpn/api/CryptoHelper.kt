package com.boykta.vpn.api

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption/decryption with per-blob HKDF-derived subkeys.
 *
 * ─── Security model ────────────────────────────────────────────────────────────
 *
 * v1 (legacy): Base64(IV[12] || AES-GCM-ciphertext || AuthTag[16])
 *   — single global key, no per-blob randomization beyond IV.
 *   — still supported for decryption of old .boykta files.
 *
 * v2 (current): Base64(0xB2 || Salt[16] || IV[12] || AES-GCM-ciphertext || AuthTag[16])
 *   — version byte 0xB2 identifies v2.
 *   — Salt is 16 random bytes generated per encryption.
 *   — SubKey = HKDF-Expand(masterKey, Salt, info="boykta-v2-config", len=32)
 *   — SubKey is used as the AES-256-GCM key for this blob only.
 *   — Even if two blobs have the same plaintext, Salt and IV randomization
 *     means the ciphertexts are completely different — defeating known-plaintext
 *     attacks and statistical analysis of .boykta files.
 *   — The HKDF expansion uses HMAC-SHA256 internally, ensuring the subkey
 *     is cryptographically independent from the master key.
 *
 * Total format overhead: 1 + 16 + 12 + 16 = 45 bytes over plaintext length.
 *
 * Key derivation:
 *   masterKeyBytes = SHA-256("boykta_2nlkkh53DaYBmllnvb2026")
 *   subKey         = HKDF(masterKeyBytes, Salt, "boykta-v2-config")
 *
 * This is compatible with the bot.py implementation — any change to the raw
 * key string breaks ALL existing .boykta files. Do NOT change it.
 */
object CryptoHelper {

    // Version marker for v2 blobs
    private const val V2_MARKER = 0xB2.toByte()

    private val MASTER_KEY_BYTES: ByteArray by lazy {
        MessageDigest.getInstance("SHA-256")
            .digest("boykta_2nlkkh53DaYBmllnvb2026".toByteArray(Charsets.UTF_8))
    }

    private val V1_SECRET_KEY by lazy { SecretKeySpec(MASTER_KEY_BYTES, "AES") }

    private val rng = SecureRandom()

    // ── HKDF (RFC 5869) — expand phase only (master key is already derived) ───

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0; var counter = 1
        while (pos < length) {
            hmac.reset()
            hmac.update(t)
            hmac.update(info)
            hmac.update(counter.toByte())
            t = hmac.doFinal()
            val copy = minOf(t.size, length - pos)
            t.copyInto(out, pos, 0, copy)
            pos += copy; counter++
        }
        return out
    }

    private fun deriveSubKey(salt: ByteArray): SecretKeySpec {
        val info = "boykta-v2-config".toByteArray(Charsets.UTF_8)
        // Use HKDF-Expand with master key as PRK and the per-blob salt as context
        val subKeyBytes = hkdfExpand(MASTER_KEY_BYTES + salt, info, 32)
        return SecretKeySpec(subKeyBytes, "AES")
    }

    // ── Encryption (always v2) ─────────────────────────────────────────────────

    /**
     * Encrypt plaintext.
     * @return Base64(0xB2 || Salt[16] || IV[12] || ciphertext || AuthTag[16])
     */
    fun encrypt(plaintext: String): String {
        val salt = ByteArray(16).also { rng.nextBytes(it) }
        val iv   = ByteArray(12).also { rng.nextBytes(it) }
        val key  = deriveSubKey(salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val blob = ByteArray(1 + 16 + 12 + ciphertext.size)
        blob[0] = V2_MARKER
        salt.copyInto(blob, 1)
        iv.copyInto(blob, 17)
        ciphertext.copyInto(blob, 29)

        return Base64.encodeToString(blob, Base64.NO_WRAP)
    }

    // ── Decryption (auto-detects v1 vs v2) ────────────────────────────────────

    /**
     * Decrypt a Base64-encoded blob produced by [encrypt].
     * Supports both v1 (legacy) and v2 (HKDF) formats.
     * @return Plaintext UTF-8 string
     */
    fun decrypt(encoded: String): String {
        val sanitized = encoded.trim()
            .replace('-', '+').replace('_', '/')
            .let { s -> val pad = (4 - s.length % 4) % 4; if (pad > 0) s + "=".repeat(pad) else s }
        val raw = Base64.decode(sanitized, Base64.NO_WRAP)
        require(raw.size > 12) { "Invalid ciphertext: too short (${raw.size} bytes)" }

        // v2 detection: first byte is 0xB2 and total length matches minimum.
        // IMPORTANT: ~1/256 v1 blobs have a first IV byte of 0xB2 by chance.
        // tryDecryptV2 returns null on any failure so we can safely fall back
        // to v1 rather than throwing and losing the file.
        return if (raw[0] == V2_MARKER && raw.size > 29) {
            tryDecryptV2(raw) ?: decryptV1(raw)
        } else {
            decryptV1(raw)
        }
    }

    /**
     * Attempt v2 decryption.
     *
     * Returns the plaintext string on success, or null on any failure.
     * Null lets the caller fall back to v1 without throwing.
     *
     * Why nullable instead of throwing:
     * A v1 blob whose first IV byte is 0xB2 (1/256 probability) will pass
     * the v2 marker check but fail here. Returning null instead of propagating
     * the exception ensures those legacy files still decrypt correctly via v1.
     */
    private fun tryDecryptV2(raw: ByteArray): String? = try {
        if (raw.size <= 29) return null
        val salt       = raw.copyOfRange(1, 17)
        val iv         = raw.copyOfRange(17, 29)
        val ciphertext = raw.copyOfRange(29, raw.size)
        val key        = deriveSubKey(salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    } catch (_: Exception) { null }

    private fun decryptV1(raw: ByteArray): String {
        val iv         = raw.copyOfRange(0, 12)
        val ciphertext = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, V1_SECRET_KEY, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    // ── Protocol URI detector ─────────────────────────────────────────────────

    /**
     * Returns true if the string is an encrypted blob (not plain JSON or a proxy URI).
     *
     * IMPORTANT: vless://, trojan://, vmess://, ss://, and plain JSON must NOT
     * be treated as encrypted — previously caused "bad base-64" crashes.
     */
    fun isEncrypted(data: String): Boolean {
        val t = data.trimStart()
        if (t.startsWith("{"))        return false  // plain JSON
        if (t.startsWith("vless://")) return false
        if (t.startsWith("trojan://"))return false
        if (t.startsWith("vmess://")) return false
        if (t.startsWith("ss://"))    return false
        if (t.startsWith("http://"))  return false
        if (t.startsWith("https://")) return false
        return true  // assume encrypted Base64 blob (v1 or v2)
    }
}

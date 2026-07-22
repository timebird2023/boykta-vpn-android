package com.boykta.vpn.util

import android.content.Context
import androidx.core.content.edit

/**
 * Persists the user's custom DNS server choice in SharedPreferences.
 * Applied to the VPN TUN builder at connection time.
 */
object DnsPreference {

    private const val PREFS_NAME = "boykta_prefs"
    private const val KEY_DNS    = "dns_choice"

    enum class DnsChoice(
        val label  : String,
        val servers: List<String>
    ) {
        SYSTEM(  "النظام (الافتراضي)", listOf("8.8.8.8", "8.8.4.4")),
        CLOUDFLARE("Cloudflare 1.1.1.1",  listOf("1.1.1.1", "1.0.0.1")),
        GOOGLE(  "Google 8.8.8.8",       listOf("8.8.8.8", "8.8.4.4")),
        ADGUARD( "AdGuard (حجب الإعلانات)", listOf("94.140.14.14", "94.140.15.15")),
    }

    fun save(context: Context, choice: DnsChoice) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_DNS, choice.name) }
    }

    fun load(context: Context): DnsChoice {
        val name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DNS, DnsChoice.CLOUDFLARE.name)
        return try { DnsChoice.valueOf(name ?: "") } catch (_: Exception) { DnsChoice.CLOUDFLARE }
    }
}

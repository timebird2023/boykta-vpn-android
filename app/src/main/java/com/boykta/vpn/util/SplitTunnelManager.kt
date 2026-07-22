package com.boykta.vpn.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.core.content.edit

/**
 * Manages the set of apps that bypass the VPN tunnel (split-tunneling).
 * Packages stored in SharedPreferences as a comma-separated string.
 *
 * When enabled, these packages use direct internet (not routed through Xray/TUN).
 */
object SplitTunnelManager {

    private const val PREFS_NAME    = "boykta_prefs"
    private const val KEY_BYPASSED  = "split_tunnel_packages"

    data class AppInfo(
        val packageName: String,
        val label      : String,
        val isBypassed : Boolean,
    )

    fun getBypassed(context: Context): Set<String> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BYPASSED, "") ?: ""
        return if (raw.isBlank()) emptySet()
        else raw.split(",").filter { it.isNotBlank() }.toSet()
    }

    fun setBypassed(context: Context, packages: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit { putString(KEY_BYPASSED, packages.joinToString(",")) }
    }

    fun toggleBypass(context: Context, packageName: String) {
        val current = getBypassed(context).toMutableSet()
        if (packageName in current) current.remove(packageName)
        else current.add(packageName)
        setBypassed(context, current)
    }

    /** Returns all installed user apps sorted by label, with bypass status. */
    fun getAllApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val bypassed = getBypassed(context)
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
            .map { info ->
                AppInfo(
                    packageName = info.packageName,
                    label       = pm.getApplicationLabel(info).toString(),
                    isBypassed  = info.packageName in bypassed,
                )
            }
            .sortedWith(compareByDescending<AppInfo> { it.isBypassed }.thenBy { it.label })
    }
}

package com.boykta.vpn.util

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings

object SecurityChecker {

    /** Known packet sniffer / proxy app package names */
    private val KNOWN_SNIFFERS = listOf(
        // HTTP debuggers / MITM proxies
        "com.httpdebugger.lite",
        "com.requestable.app",
        "com.charlesproxy.charles",
        "com.fiddler.android",
        "com.proxyman.NSProxy",
        "com.burpsuite.android",
        // Packet capture tools
        "com.packet.capture",
        "com.minhui.networkcapture",
        "com.minhui.networkcapture.pro",
        "com.hzy.packet.capture",
        "com.hzy.pcap",
        // PCAPdroid — open-source traffic capture
        "com.emanuelef.remote_capture",
        "com.emanuelef.remote_capture.debug",
        // Reqable — API testing + traffic capture
        "app.reqable.app",
        "com.reqable.android",
        // Network tools that can intercept
        "io.github.rockyzhang24.shadowsocksx",
        "org.sandroproxy.drony",
        "com.networktools.netmaster",
        "com.inkwired.droidinfo",
        "com.overlook.android.fing",
        // SSL Kill Switch / certificate bypass apps
        "com.htbridge.hackapp",
        "org.thoughtcrime.ssl.pinning",
        // HTTP Toolkit — advanced MITM proxy
        "tech.httptoolkit.android",
        // Frida / dynamic instrumentation indicators
        "com.sensepost.objection",
    )

    /**
     * Returns true if a known packet sniffer is installed or a global proxy is configured.
     */
    fun isSnifferDetected(context: Context): Boolean {
        return isSnifferInstalled(context) || isGlobalProxySet(context)
    }

    private fun isSnifferInstalled(context: Context): Boolean {
        val pm = context.packageManager
        for (pkg in KNOWN_SNIFFERS) {
            try {
                pm.getPackageInfo(pkg, 0)
                return true // found a sniffer
            } catch (_: PackageManager.NameNotFoundException) { /* not installed */ }
        }
        return false
    }

    private fun isGlobalProxySet(context: Context): Boolean {
        return try {
            val proxy = Settings.Global.getString(context.contentResolver, Settings.Global.HTTP_PROXY)
            !proxy.isNullOrBlank() && proxy != ":0"
        } catch (_: Exception) { false }
    }

    /** Basic emulator detection */
    fun isRunningInEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk", ignoreCase = true)
                || Build.MODEL.contains("Emulator", ignoreCase = true)
                || Build.MODEL.contains("Android SDK built for x86", ignoreCase = true)
                || Build.MANUFACTURER.contains("Genymotion", ignoreCase = true)
                || Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")
                || "google_sdk" == Build.PRODUCT)
    }
}

package com.boykta.vpn.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Singleton log bus for the VPN pipeline.
 * Both BoykVpnService and XrayManager emit here; MainActivity subscribes.
 */
object VpnLogManager {

    private val _logs = MutableSharedFlow<String>(replay = 120, extraBufferCapacity = 300)
    val logs: SharedFlow<String> = _logs

    private val ts get() = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    fun log(msg: String) {
        _logs.tryEmit("[$ts] $msg")
    }

    fun info(msg: String)    = log("ℹ $msg")
    fun success(msg: String) = log("✅ $msg")
    fun warn(msg: String)    = log("⚠ $msg")
    fun error(msg: String)   = log("❌ $msg")
    fun sys(msg: String)     = log("⚙ $msg")
}

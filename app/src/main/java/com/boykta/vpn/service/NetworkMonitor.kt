package com.boykta.vpn.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Monitors internet connectivity and fires callbacks on network events.
 *
 * Used by BoykVpnService to:
 *  - Detect Wi-Fi ↔ mobile data switches and re-establish the VPN tunnel
 *  - Detect when internet is restored after a dropout
 *
 * Events:
 *  onNetworkAvailable  — a new internet-capable network came online
 *  onNetworkLost       — the internet connection dropped
 */
class NetworkMonitor(
    context: Context,
    private val onNetworkAvailable: () -> Unit,
    private val onNetworkLost: () -> Unit = {}
) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            onNetworkAvailable()
        }
        override fun onLost(network: Network) {
            onNetworkLost()
        }
    }

    fun start() {
        if (registered) return
        try {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(req, callback)
            registered = true
        } catch (e: Exception) {
            VpnLogManager.warn("NetworkMonitor: failed to register — ${e.message}")
        }
    }

    fun stop() {
        if (!registered) return
        try {
            cm.unregisterNetworkCallback(callback)
            registered = false
        } catch (_: Exception) {}
    }
}

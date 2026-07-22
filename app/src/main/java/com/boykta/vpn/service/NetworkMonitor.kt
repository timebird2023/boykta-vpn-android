package com.boykta.vpn.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.atomic.AtomicLong

/**
 * Monitors internet connectivity and fires callbacks on network events.
 *
 * Used by BoykVpnService to:
 *  - Detect Wi-Fi ↔ mobile data switches and re-establish the VPN tunnel
 *  - Detect when internet is restored after a dropout
 *
 * Debounce: onAvailable fires multiple times per network switch (Android behaviour).
 * We enforce a DEBOUNCE_MS cooldown between consecutive onNetworkAvailable()
 * invocations so BoykVpnService only sees ONE reconnect trigger per event.
 *
 * Events:
 *  onNetworkAvailable  — a new internet-capable network came online (debounced)
 *  onNetworkLost       — the internet connection dropped
 */
class NetworkMonitor(
    context: Context,
    private val onNetworkAvailable: () -> Unit,
    private val onNetworkLost: () -> Unit = {}
) {
    companion object {
        private const val DEBOUNCE_MS = 4_000L
    }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var registered = false

    // Debounce: track last time onNetworkAvailable was forwarded
    private val lastAvailableMs = AtomicLong(0L)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val now  = System.currentTimeMillis()
            val last = lastAvailableMs.get()
            if (now - last > DEBOUNCE_MS) {
                if (lastAvailableMs.compareAndSet(last, now)) {
                    onNetworkAvailable()
                }
            }
            // else: duplicate event within debounce window — silently ignored
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

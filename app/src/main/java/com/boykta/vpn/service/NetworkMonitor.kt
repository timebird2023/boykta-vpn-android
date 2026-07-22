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
 * Critical fix: NetworkRequest MUST include:
 *   - NET_CAPABILITY_INTERNET   — only real internet-capable networks
 *   - NET_CAPABILITY_NOT_VPN    — EXCLUDE the VPN TUN interface itself
 *
 * Without NET_CAPABILITY_NOT_VPN, when the TUN interface comes up Android fires
 * onAvailable() for the VPN network → triggers an instant reconnect loop.
 *
 * Debounce: even with the VPN filter, Android can fire multiple onAvailable()
 * per real network switch. We enforce an 8-second cooldown.
 */
class NetworkMonitor(
    context: Context,
    private val onNetworkAvailable: () -> Unit,
    private val onNetworkLost: () -> Unit = {}
) {
    companion object {
        private const val DEBOUNCE_MS = 8_000L   // ignore duplicate events within 8 s
    }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var registered = false

    private val lastAvailableMs = AtomicLong(0L)

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Extra safety: verify this is NOT a VPN network
            val caps = cm.getNetworkCapabilities(network)
            if (caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                // This is a VPN network — skip it entirely
                return
            }

            val now  = System.currentTimeMillis()
            val last = lastAvailableMs.get()
            if (now - last > DEBOUNCE_MS) {
                if (lastAvailableMs.compareAndSet(last, now)) {
                    onNetworkAvailable()
                }
            }
        }

        override fun onLost(network: Network) {
            // Only react to non-VPN network loss
            val caps = cm.getNetworkCapabilities(network)
            if (caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return
            onNetworkLost()
        }
    }

    fun start() {
        if (registered) return
        try {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) // ← THE KEY FIX
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

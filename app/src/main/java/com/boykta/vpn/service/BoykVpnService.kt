package com.boykta.vpn.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.boykta.vpn.MainActivity
import com.boykta.vpn.R
import com.boykta.vpn.model.Server
import com.boykta.vpn.util.DnsPreference
import com.boykta.vpn.util.SplitTunnelManager
import kotlinx.coroutines.*
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Boykta VPN — Foreground VPN Service with Sequential Connection State Machine.
 *
 * Connection lifecycle (ordered steps):
 *   1. Physical network check  → "Waiting internet connection..."
 *   2. WakeLock acquisition    → "Wakelock acquired"
 *   3. Local IP identification → "Local ip, 10.x.x.x"
 *   4. Xray-core launch        → "Connecting to v2ray server..." → "Connection established"
 *   5. Verification ping       → "Checking internet connection..."
 *   6. UI sync                 → CONNECTED state on HTTP 200/204
 *
 * Stability guarantees:
 *   • Ping failures NEVER kill the VPN tunnel — only Xray crashes trigger reconnect.
 *   • NetworkMonitor excludes VPN TUN interface (NET_CAPABILITY_NOT_VPN).
 *   • WakeLock released on stop to prevent battery drain.
 *   • DNS servers, Split-tunnel app bypass, and Traffic counter all applied here.
 */
class BoykVpnService : VpnService() {

    companion object {
        private const val TAG = "BoykVpnService"

        const val CHANNEL_ID        = "boykta_vpn_channel"
        const val NOTIFICATION_ID   = 1001
        const val ACTION_CONNECT    = "com.boykta.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.boykta.vpn.DISCONNECT"
        const val EXTRA_SERVER_ID   = "server_id"

        const val LOCAL_SOCKS_PORT  = 10808
        const val LOCAL_HTTP_PORT   = 10809

        // Keep-alive tuning — PING NEVER RECONNECTS; only crashes do.
        private const val PING_INTERVAL_MS    = 8_000L
        private const val RECONNECT_BASE_MS   = 3_000L
        private const val RECONNECT_MAX_MS    = 30_000L

        private const val XRAY_READY_TIMEOUT_MS = 10_000L
        private const val XRAY_READY_POLL_MS    = 300L

        // Network check: wait up to 30 s for physical connection
        private const val NET_CHECK_TIMEOUT_MS = 30_000L
        private const val NET_CHECK_POLL_MS    = 1_000L

        @Volatile var isRunning = false
            private set
    }

    private val binder         = LocalBinder()
    private var vpnInterface   : ParcelFileDescriptor? = null
    private var tunBridge      : TunBridge? = null
    private var currentServer  : Server? = null
    private val isConnected    = AtomicBoolean(false)
    private val isReconnecting = AtomicBoolean(false)
    private val reconnectCount = AtomicInteger(0)
    private val serviceScope   = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var keepAliveJob     : Job? = null
    private var networkChangeJob : Job? = null
    private var trafficJob       : Job? = null

    // CopyOnWriteArrayList: safe for concurrent add/remove from binder thread
    // and forEach iteration from coroutines without ConcurrentModificationException
    private val listeners        = CopyOnWriteArrayList<VpnStateListener>()
    private var networkMonitor   : NetworkMonitor? = null

    // WakeLock to prevent CPU sleep during VPN session
    private var wakeLock: PowerManager.WakeLock? = null

    interface VpnStateListener {
        fun onConnected(serverName: String)
        fun onDisconnected()
        fun onError(message: String)
    }

    inner class LocalBinder : Binder() {
        fun getService() = this@BoykVpnService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT    -> currentServer?.let { startVpn(it) }
            ACTION_DISCONNECT -> stopVpn()
        }
        return START_STICKY
    }

    fun connectToServer(server: Server) {
        currentServer = server
        startVpn(server)
    }

    // ── VPN startup: sequential state machine ─────────────────────────────────

    private fun startVpn(server: Server) {
        serviceScope.launch {
            try {
                withContext(Dispatchers.Main) {
                    startForeground(NOTIFICATION_ID, buildNotification("جارٍ الاتصال…"))
                }

                VpnLogManager.isReconnecting.set(false)
                TunnelPingChecker.logDeviceInfo()
                VpnLogManager.sys("━━━━━━━━ Boykta VPN SESSION START ━━━━━━━━")
                VpnLogManager.sys("Server   : ${server.name}")
                VpnLogManager.sys("Protocol : ${server.protocol.uppercase()}")

                // ── STEP 1: Physical network check ────────────────────────────
                VpnLogManager.sys("Waiting internet connection...")
                val networkReady = waitForPhysicalNetwork()
                if (!networkReady) {
                    VpnLogManager.warn("No physical network after ${NET_CHECK_TIMEOUT_MS / 1000}s — proceeding anyway")
                } else {
                    VpnLogManager.success("Physical network detected — ready to tunnel")
                }

                // ── STEP 2: WakeLock acquisition ──────────────────────────────
                acquireWakeLock()
                VpnLogManager.sys("Wakelock acquired")

                // ── STEP 3: Local IP identification ───────────────────────────
                logLocalIp()

                // ── STEP 4: Load user preferences + stop stale Xray ──────────
                // Load DNS choice BEFORE starting Xray so it can configure DoH + routing
                val dnsChoice = DnsPreference.load(this@BoykVpnService)
                XrayManager.forceStop()
                val portFree = waitForPort(LOCAL_SOCKS_PORT, timeoutMs = 3_000)
                if (!portFree) VpnLogManager.warn("Port $LOCAL_SOCKS_PORT still in use — proceeding")

                // ── STEP 4b: Start Xray-core ──────────────────────────────────
                VpnLogManager.sys("Connecting to v2ray server...")
                val xrayOk = XrayManager.start(server.config, LOCAL_SOCKS_PORT, LOCAL_HTTP_PORT, dnsChoice)
                if (!xrayOk) {
                    notifyError("فشل تشغيل محرك VPN")
                    VpnLogManager.error("Xray engine failed to start — aborting")
                    return@launch
                }

                // Wait for SOCKS5 to be ready
                val xrayReady = waitForXrayReady(LOCAL_SOCKS_PORT)
                if (!xrayReady) {
                    VpnLogManager.warn("Xray SOCKS5 slow to respond — continuing anyway")
                } else {
                    VpnLogManager.success("Connection established — SOCKS5 127.0.0.1:$LOCAL_SOCKS_PORT")
                }

                // ── STEP 5: Establish TUN interface ───────────────────────────
                val bypassedApps = SplitTunnelManager.getBypassed(this@BoykVpnService)
                VpnLogManager.sys("DNS: ${dnsChoice.label}  — Split-tunnel: ${bypassedApps.size} apps bypassed")

                val builder = Builder()
                    .setSession("Boykta VPN — ${server.name}")
                    // MTU 1380 — leaves headroom for WS framing (~10B) + TLS record (~29B)
                    // over the Cloudflare edge, preventing IP fragmentation that kills throughput.
                    .setMtu(1380)
                    // IPv4 TUN address + default route
                    .addAddress("10.88.0.1", 30)
                    .addRoute("0.0.0.0", 0)
                    // IPv6 TUN address + default route — captures IPv6 traffic so it is proxied
                    // through Xray instead of leaking or failing. TunBridge handles IPv6 TCP/UDP
                    // and blocks QUIC (UDP/443) on both address families.
                    .addAddress("fd00::1", 128)
                    .addRoute("::", 0)
                    .addDisallowedApplication(packageName)   // always exclude self

                // Apply user-selected DNS
                for (dns in dnsChoice.servers) {
                    builder.addDnsServer(dns)
                }

                // Apply split-tunnel bypass (allowed apps go direct, not through VPN)
                for (pkg in bypassedApps) {
                    try { builder.addDisallowedApplication(pkg) } catch (_: Exception) {}
                }

                val tunPfd = builder.establish()
                    ?: throw IllegalStateException("VPN permission revoked or TUN establish failed")

                vpnInterface = tunPfd
                isRunning    = true
                isConnected.set(true)
                reconnectCount.set(0)

                VpnLogManager.success("TUN interface ready → 10.88.0.1/30 + fd00::1/128  MTU 1380")
                VpnLogManager.info("DNS: ${dnsChoice.servers.joinToString(" + ")}   Route 0.0.0.0/0")
                TunnelPingChecker.logNetworkInterfaces()

                // ── STEP 5b: Start TUN ↔ SOCKS5 bridge ───────────────────────
                val bridge = TunBridge(tunPfd, LOCAL_SOCKS_PORT, this@BoykVpnService)
                tunBridge = bridge
                bridge.start()
                VpnLogManager.success("TUN↔SOCKS5 bridge active — traffic routing through proxy")

                // ── STEP 6: Verification ping ─────────────────────────────────
                VpnLogManager.sys("Checking internet connection...")
                delay(2_500)
                val pingOk = TunnelPingChecker.pingAndLog(LOCAL_SOCKS_PORT)

                // ── STEP 7: Set CONNECTED state ───────────────────────────────
                if (pingOk) {
                    VpnLogManager.success("Internet verified — Boykta VPN CONNECTED")
                } else {
                    VpnLogManager.warn("Initial ping inconclusive — VPN tunnel still active")
                }

                withContext(Dispatchers.Main) {
                    updateNotification("متصل: ${server.name}")
                    listeners.forEach { it.onConnected(server.name) }
                }

                // ── STEP 8: Start traffic counter ─────────────────────────────
                TrafficCounter.start(serviceScope)

                // ── STEP 9: Start network change listener ─────────────────────
                // IMPORTANT: NetworkMonitor is OBSERVE-ONLY while the VPN is active.
                // A healthy running tunnel MUST NEVER be killed by network change events.
                // Auto-reconnect is triggered exclusively by Xray crash detection in the
                // keep-alive loop (Step 10 below). Physical network changes are logged only.
                networkMonitor?.stop()
                networkMonitor = NetworkMonitor(
                    context = this@BoykVpnService,
                    onNetworkAvailable = {
                        // Log the event — DO NOT reconnect. Tunnel remains active.
                        if (isConnected.get()) {
                            VpnLogManager.info("Physical network changed — tunnel remains active (no action taken)")
                        }
                    },
                    onNetworkLost = {
                        if (isConnected.get()) VpnLogManager.warn("Internet lost — waiting for recovery…")
                    }
                )
                serviceScope.launch {
                    delay(10_000)
                    if (isConnected.get() && !isReconnecting.get()) {
                        networkMonitor?.start()
                    }
                }

                // ── STEP 10: Keep-alive ping loop ─────────────────────────────
                // NOTE: Ping failures NEVER kill the tunnel.
                // Only Xray crash or Xray process stopping triggers reconnect.
                keepAliveJob?.cancel()
                keepAliveJob = serviceScope.launch {
                    while (isConnected.get() && isActive) {
                        delay(PING_INTERVAL_MS)
                        if (!isConnected.get() || !isActive) break

                        // TCP probe — is Xray even listening?
                        val proxyAlive = TunnelPingChecker.isProxyAlive(LOCAL_SOCKS_PORT)
                        if (!proxyAlive) {
                            if (!isReconnecting.get()) {
                                VpnLogManager.warn("Xray proxy not responding — crash detected")
                                triggerAutoReconnect("Xray crash")
                            }
                            break
                        }

                        // Xray internal state check
                        if (!XrayManager.isRunning()) {
                            if (!isReconnecting.get()) {
                                VpnLogManager.warn("Xray core stopped unexpectedly")
                                triggerAutoReconnect("Xray stopped")
                            }
                            break
                        }

                        // Full tunnel ping — log result only, NEVER reconnect on failure
                        val ok = TunnelPingChecker.pingAndLog(LOCAL_SOCKS_PORT)
                        if (!ok) {
                            VpnLogManager.warn("HTTP Ping Timeout — tunnel active, monitoring continues")
                            // NO reconnect on ping failure — tunnel stays alive
                        }
                        // else: logged inside pingAndLog as "HTTP Ping 200 OK (xxxms)"
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "VPN startup failed", e)
                VpnLogManager.isReconnecting.set(false)
                VpnLogManager.error("Connection failed: ${e.javaClass.simpleName} — ${e.message?.take(120)}")
                notifyError("فشل الاتصال: ${e.message?.take(80)}")
                stopVpn()
            }
        }
    }

    // ── Step 1: Wait for a real physical internet connection ──────────────────

    private suspend fun waitForPhysicalNetwork(): Boolean = withContext(Dispatchers.IO) {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val deadline = System.currentTimeMillis() + NET_CHECK_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val net = cm.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            val hasInternet = caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            if (hasInternet) return@withContext true
            delay(NET_CHECK_POLL_MS)
        }
        false
    }

    // ── Step 2: WakeLock ──────────────────────────────────────────────────────

    @SuppressLint("WakelockTimeout")   // intentionally no timeout — VPN sessions are unbounded
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "BoyktaVPN:TunnelWakeLock"
            ).also {
                it.acquire()   // no timeout — released explicitly in releaseWakeLock()
            }
        } catch (e: Exception) {
            VpnLogManager.warn("WakeLock failed: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        } catch (_: Exception) {}
    }

    // ── Step 3: Log local IP ──────────────────────────────────────────────────

    private fun logLocalIp() {
        try {
            val ifaces = Collections.list(NetworkInterface.getNetworkInterfaces() ?: return)
            for (iface in ifaces) {
                if (iface.isLoopback || !iface.isUp) continue
                val name = iface.name
                if (name.startsWith("tun") || name.startsWith("vpn")) continue
                val addrs = Collections.list(iface.inetAddresses)
                    .filter { !it.isLoopbackAddress && it is java.net.Inet4Address }
                    .mapNotNull { it.hostAddress }
                if (addrs.isNotEmpty()) {
                    VpnLogManager.sys("Local ip, ${addrs.first()}")
                    return
                }
            }
            VpnLogManager.sys("Local ip, (unknown)")
        } catch (e: Exception) {
            VpnLogManager.sys("Local ip, (error: ${e.message})")
        }
    }

    // ── Wait for Xray SOCKS5 to be ready ─────────────────────────────────────

    private suspend fun waitForXrayReady(socksPort: Int): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + XRAY_READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (TunnelPingChecker.isProxyAlive(socksPort)) return@withContext true
            delay(XRAY_READY_POLL_MS)
        }
        false
    }

    // ── Auto-reconnect (only on Xray crash, NOT on ping failures) ─────────────

    private fun triggerAutoReconnect(reason: String) {
        if (!isReconnecting.compareAndSet(false, true)) return

        serviceScope.launch {
            val server = currentServer ?: run {
                isReconnecting.set(false)
                VpnLogManager.isReconnecting.set(false)
                return@launch
            }

            val attempt = reconnectCount.incrementAndGet()
            val backoffMs = minOf(
                RECONNECT_BASE_MS * (1L shl (attempt - 1).coerceAtMost(4)),
                RECONNECT_MAX_MS
            )

            VpnLogManager.sys("━━━ AUTO-RECONNECT #$attempt (reason: $reason) ━━━")
            VpnLogManager.sys("Waiting ${backoffMs / 1000}s before retry…")

            VpnLogManager.isReconnecting.set(true)

            keepAliveJob?.cancel();    keepAliveJob = null
            networkChangeJob?.cancel(); networkChangeJob = null

            isRunning = false
            isConnected.set(false)
            TrafficCounter.stop()
            networkMonitor?.stop()
            tunBridge?.stop();    tunBridge = null
            XrayManager.forceStop()
            try { vpnInterface?.close(); vpnInterface = null } catch (_: Exception) {}
            releaseWakeLock()

            withContext(Dispatchers.Main) {
                updateNotification("إعادة الاتصال… (محاولة $attempt)")
            }

            delay(backoffMs)

            isReconnecting.set(false)
            startVpn(server)
        }
    }

    // ── VPN teardown ──────────────────────────────────────────────────────────

    fun stopVpn() {
        serviceScope.launch {
            VpnLogManager.isReconnecting.set(true)

            isRunning = false
            isConnected.set(false)
            isReconnecting.set(false)
            reconnectCount.set(0)

            keepAliveJob?.cancel();    keepAliveJob = null
            networkChangeJob?.cancel(); networkChangeJob = null

            TrafficCounter.stop()
            networkMonitor?.stop();  networkMonitor = null
            tunBridge?.stop();       tunBridge = null
            XrayManager.forceStop()
            releaseWakeLock()

            try { vpnInterface?.close(); vpnInterface = null } catch (e: Exception) {
                Log.e(TAG, "Error closing VPN interface", e)
            }

            VpnLogManager.isReconnecting.set(false)
            VpnLogManager.sys("VPN stopped — all tunnels closed")

            withContext(Dispatchers.Main) {
                listeners.forEach { it.onDisconnected() }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /**
     * Internal reconnect — tears down the current tunnel without stopping the service
     * (i.e. does NOT call stopSelf()). Safe to call from the binder thread.
     *
     * Bug fixed: the old implementation called stopVpn() → stopSelf() → onDestroy()
     * → serviceScope.cancel(), which would cancel the deferred startVpn() coroutine
     * scheduled 2 seconds later, leaving the service in a dead state.
     */
    fun reconnect() {
        val server = currentServer ?: return
        if (!isReconnecting.compareAndSet(false, true)) return   // prevent double-reconnect

        serviceScope.launch {
            VpnLogManager.sys("Manual reconnect requested — tearing down current tunnel…")

            keepAliveJob?.cancel();     keepAliveJob = null
            networkChangeJob?.cancel(); networkChangeJob = null
            isRunning    = false
            isConnected.set(false)
            TrafficCounter.stop()
            networkMonitor?.stop();  networkMonitor = null
            tunBridge?.stop();       tunBridge = null
            XrayManager.forceStop()
            releaseWakeLock()
            try { vpnInterface?.close(); vpnInterface = null } catch (_: Exception) {}

            withContext(Dispatchers.Main) {
                listeners.forEach { it.onDisconnected() }
                updateNotification("إعادة الاتصال…")
            }

            delay(1_500)
            isReconnecting.set(false)
            VpnLogManager.isReconnecting.set(false)
            startVpn(server)
        }
    }

    fun addListener(l: VpnStateListener)    = listeners.add(l)
    fun removeListener(l: VpnStateListener) = listeners.remove(l)

    private fun notifyError(message: String) {
        serviceScope.launch(Dispatchers.Main) {
            listeners.forEach { it.onError(message) }
        }
    }

    // ── Port availability check ───────────────────────────────────────────────

    private suspend fun waitForPort(port: Int, timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val free = try { java.net.ServerSocket(port).use { true } } catch (_: Exception) { false }
            if (free) { VpnLogManager.sys("Port $port confirmed free"); return@withContext true }
            delay(150)
        }
        false
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, "Boykta VPN", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "حالة اتصال Boykta VPN"; setShowBadge(false) }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotification(status: String): Notification {
        val mainIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val disconnectIntent = PendingIntent.getService(
            this, 1,
            Intent(this, BoykVpnService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Boykta VPN")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(mainIntent)
            .addAction(R.drawable.ic_close, "قطع الاتصال", disconnectIntent)
            .setOngoing(true)
            .setColor(0xFF00F2FE.toInt())
            .build()
    }

    private fun updateNotification(status: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    override fun onRevoke() {
        super.onRevoke()
        VpnLogManager.warn("VPN permission revoked by system")
        stopVpn()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        releaseWakeLock()
        XrayManager.forceStop()
    }
}

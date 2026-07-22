package com.boykta.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.boykta.vpn.MainActivity
import com.boykta.vpn.R
import com.boykta.vpn.model.Server
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * VPN foreground service.
 *
 * Lifecycle:
 *   startVpn() →
 *     1. forceStop any existing Xray + wait for port 10808 to free
 *     2. Start Xray-core (SOCKS5 :10808, HTTP :10809)
 *     3. Establish TUN interface (MTU 1500, DNS 8.8.8.8/1.1.1.1, route 0.0.0.0/0)
 *     4. Start TunBridge (bidirectional TUN ↔ SOCKS5 relay)
 *     5. Initial ping check after 2.5 s
 *     6. Keep-alive ping every PING_INTERVAL_MS
 *     7. Auto-reconnect after MAX_PING_FAILS consecutive failures
 *     8. NetworkMonitor triggers re-establish on Wi-Fi ↔ cellular switch
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

        // Keep-alive tuning
        private const val PING_INTERVAL_MS    = 15_000L   // ping every 15 s
        private const val MAX_PING_FAILS      = 3          // auto-reconnect after 3 misses
        private const val RECONNECT_BASE_MS   = 3_000L     // initial backoff
        private const val RECONNECT_MAX_MS    = 30_000L    // cap at 30 s

        /** True while a VPN session is active (read from other components). */
        @Volatile var isRunning = false
            private set
    }

    private val binder        = LocalBinder()
    private var vpnInterface  : ParcelFileDescriptor? = null
    private var tunBridge     : TunBridge? = null
    private var currentServer : Server? = null
    private val isConnected   = AtomicBoolean(false)
    private val isReconnecting= AtomicBoolean(false)
    private val reconnectCount= AtomicInteger(0)
    private val serviceScope  = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val listeners      = mutableListOf<VpnStateListener>()
    private var networkMonitor : NetworkMonitor? = null

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

    // ── VPN startup ───────────────────────────────────────────────────────────

    private fun startVpn(server: Server) {
        serviceScope.launch {
            try {
                withContext(Dispatchers.Main) {
                    startForeground(NOTIFICATION_ID, buildNotification("جارٍ الاتصال…"))
                }

                // ── Step 0: Log device + system environment ───────────────────
                TunnelPingChecker.logDeviceInfo()
                VpnLogManager.sys("━━━━━━━━ VPN SESSION START ━━━━━━━━")
                VpnLogManager.sys("Server   : ${server.name}")
                VpnLogManager.sys("Protocol : ${server.protocol.uppercase()}")

                // ── Step 1: Stop any stale Xray instance ─────────────────────
                XrayManager.forceStop()

                val portFree = waitForPort(LOCAL_SOCKS_PORT, timeoutMs = 3_000)
                if (!portFree) VpnLogManager.warn("Port $LOCAL_SOCKS_PORT still in use — proceeding")

                // ── Step 2: Start Xray-core ───────────────────────────────────
                val xrayOk = XrayManager.start(server.config, LOCAL_SOCKS_PORT, LOCAL_HTTP_PORT)
                if (!xrayOk) {
                    notifyError("فشل تشغيل محرك VPN")
                    VpnLogManager.error("Xray engine failed to start — aborting")
                    return@launch
                }
                VpnLogManager.success("Xray core started → SOCKS5 :$LOCAL_SOCKS_PORT")

                // ── Step 3: Establish TUN interface ───────────────────────────
                val tunPfd = Builder()
                    .setSession("Boykta VPN — ${server.name}")
                    .setMtu(1500)
                    .addAddress("10.88.0.1", 30)
                    .addDnsServer("8.8.8.8")
                    .addDnsServer("1.1.1.1")
                    .addRoute("0.0.0.0", 0)
                    .addDisallowedApplication(packageName)
                    .establish()
                    ?: throw IllegalStateException("VPN permission revoked or TUN establish failed")

                vpnInterface = tunPfd
                isRunning    = true
                isConnected.set(true)
                reconnectCount.set(0)   // reset on clean connect

                VpnLogManager.success("TUN interface ready → 10.88.0.1/30  MTU 1500")
                VpnLogManager.info("DNS 8.8.8.8 + 1.1.1.1   Route 0.0.0.0/0")
                TunnelPingChecker.logNetworkInterfaces()

                // ── Step 4: Start TUN ↔ SOCKS5 bridge ────────────────────────
                val bridge = TunBridge(tunPfd, LOCAL_SOCKS_PORT, this@BoykVpnService)
                tunBridge = bridge
                bridge.start()
                VpnLogManager.success("TUN↔SOCKS5 bridge active — traffic routing through proxy")

                // ── Step 5: Notify UI ─────────────────────────────────────────
                withContext(Dispatchers.Main) {
                    updateNotification("متصل: ${server.name}")
                    listeners.forEach { it.onConnected(server.name) }
                }

                // ── Step 6: Start network change listener ─────────────────────
                networkMonitor?.stop()
                networkMonitor = NetworkMonitor(
                    context        = this@BoykVpnService,
                    onNetworkAvailable = {
                        // Network restored or switched → re-establish tunnel
                        serviceScope.launch {
                            if (isConnected.get() && !isReconnecting.get()) {
                                VpnLogManager.info("Network changed — re-establishing tunnel…")
                                delay(2_000) // let the new network stabilize
                                if (!isReconnecting.get()) triggerAutoReconnect("network change")
                            }
                        }
                    },
                    onNetworkLost = {
                        if (isConnected.get()) VpnLogManager.warn("Internet connection lost — waiting for recovery…")
                    }
                )
                networkMonitor?.start()

                // ── Step 7: Initial ping (give Xray 2.5 s to fully init) ──────
                delay(2_500)
                TunnelPingChecker.pingAndLog(LOCAL_SOCKS_PORT)

                // ── Step 8: Keep-alive ping every PING_INTERVAL_MS ───────────
                serviceScope.launch {
                    var failCount = 0
                    while (isConnected.get()) {
                        delay(PING_INTERVAL_MS)
                        if (!isConnected.get()) break

                        // First: quick TCP probe — is Xray even listening?
                        val proxyAlive = TunnelPingChecker.isProxyAlive(LOCAL_SOCKS_PORT)
                        if (!proxyAlive) {
                            VpnLogManager.warn("[WARN] Xray proxy not responding — crash detected")
                            triggerAutoReconnect("Xray crash")
                            break
                        }

                        // Also verify Xray's own internal state
                        if (!XrayManager.isRunning()) {
                            VpnLogManager.warn("[WARN] Xray core stopped unexpectedly")
                            triggerAutoReconnect("Xray stopped")
                            break
                        }

                        // Full HTTPS tunnel ping
                        val ok = TunnelPingChecker.pingAndLog(LOCAL_SOCKS_PORT)
                        if (ok) {
                            failCount = 0
                        } else {
                            failCount++
                            val remaining = MAX_PING_FAILS - failCount
                            if (remaining > 0) {
                                VpnLogManager.warn("[WARN] Tunnel check failed ($failCount/$MAX_PING_FAILS) — retrying…")
                            } else {
                                VpnLogManager.warn("[WARN] Tunnel unresponsive after $MAX_PING_FAILS checks — auto-recovery…")
                                triggerAutoReconnect("ping timeout × $MAX_PING_FAILS")
                                break
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "VPN startup failed", e)
                // Clean error message — no raw stack trace shown to user
                VpnLogManager.error("[ERROR] Connection failed: ${e.javaClass.simpleName} — ${e.message?.take(120)}")
                notifyError("فشل الاتصال: ${e.message?.take(80)}")
                stopVpn()
            }
        }
    }

    // ── Auto-reconnect ────────────────────────────────────────────────────────

    /**
     * Gracefully tears down the current tunnel and re-establishes it with
     * exponential backoff. Safe to call from any coroutine.
     */
    private fun triggerAutoReconnect(reason: String) {
        if (!isReconnecting.compareAndSet(false, true)) return  // already in progress

        serviceScope.launch {
            val server = currentServer ?: run {
                isReconnecting.set(false)
                return@launch
            }

            val attempt = reconnectCount.incrementAndGet()
            val backoffMs = minOf(
                RECONNECT_BASE_MS * (1L shl (attempt - 1).coerceAtMost(4)),
                RECONNECT_MAX_MS
            )

            VpnLogManager.sys("━━━ AUTO-RECONNECT #$attempt (reason: $reason) ━━━")
            VpnLogManager.sys("Waiting ${backoffMs / 1000}s before retry…")

            // Tear down without calling stopVpn() (that would notify onDisconnected)
            isRunning = false
            isConnected.set(false)
            networkMonitor?.stop()
            tunBridge?.stop(); tunBridge = null
            XrayManager.forceStop()
            try { vpnInterface?.close(); vpnInterface = null } catch (_: Exception) {}

            withContext(Dispatchers.Main) {
                updateNotification("إعادة الاتصال… (محاولة $attempt)")
            }

            delay(backoffMs)
            isReconnecting.set(false)
            startVpn(server)
        }
    }

    // ── VPN teardown ─────────────────────────────────────────────────────────

    fun stopVpn() {
        serviceScope.launch {
            isRunning = false
            isConnected.set(false)
            isReconnecting.set(false)
            reconnectCount.set(0)

            networkMonitor?.stop()
            networkMonitor = null

            tunBridge?.stop()
            tunBridge = null

            XrayManager.forceStop()

            try {
                vpnInterface?.close()
                vpnInterface = null
            } catch (e: Exception) {
                Log.e(TAG, "Error closing VPN interface", e)
            }

            VpnLogManager.sys("VPN stopped — all tunnels closed")

            withContext(Dispatchers.Main) {
                listeners.forEach { it.onDisconnected() }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    fun reconnect() {
        currentServer?.let { server ->
            stopVpn()
            serviceScope.launch {
                delay(2_000)
                startVpn(server)
            }
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

    // ── Notification ─────────────────────────────────────────────────────────

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
        XrayManager.forceStop()
    }
}

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
 *     6. Keep-alive ping every 30 s
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

        /** True while a VPN session is active (read from other components). */
        @Volatile var isRunning = false
            private set
    }

    private val binder       = LocalBinder()
    private var vpnInterface : ParcelFileDescriptor? = null
    private var tunBridge    : TunBridge? = null
    private var currentServer: Server? = null
    private val isConnected  = AtomicBoolean(false)
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val listeners = mutableListOf<VpnStateListener>()

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
                VpnLogManager.sys("VpnService starting → ${server.name}")
                VpnLogManager.sys("Protocol: ${server.protocol.uppercase()} | Config len: ${server.config.length} chars")

                // ── Step 1: Stop any stale Xray instance ─────────────────────
                XrayManager.forceStop()

                // Wait for port 10808 to be released (up to 3 s)
                val portFree = waitForPort(LOCAL_SOCKS_PORT, timeoutMs = 3_000)
                if (!portFree) {
                    VpnLogManager.warn("Port $LOCAL_SOCKS_PORT still in use — proceeding anyway")
                }

                // ── Step 2: Start Xray-core ───────────────────────────────────
                val xrayOk = XrayManager.start(server.config, LOCAL_SOCKS_PORT, LOCAL_HTTP_PORT)
                if (!xrayOk) {
                    notifyError("فشل تشغيل محرك VPN")
                    VpnLogManager.error("Xray engine failed to start — aborting connection")
                    return@launch
                }

                // ── Step 3: Establish TUN interface ───────────────────────────
                //   MTU 1500, virtual address 10.88.0.1/30
                //   DNS: 8.8.8.8 + 1.1.1.1
                //   Route 0.0.0.0/0 — ALL IPv4 traffic through TUN
                //   Exclude our own app to prevent routing loop
                val tunPfd = Builder()
                    .setSession("Boykta VPN — ${server.name}")
                    .setMtu(1500)
                    .addAddress("10.88.0.1", 30)
                    .addDnsServer("8.8.8.8")
                    .addDnsServer("1.1.1.1")
                    .addRoute("0.0.0.0", 0)
                    .addDisallowedApplication(packageName)  // exempt our own traffic → no loop
                    .establish()
                    ?: throw IllegalStateException("VPN permission revoked or TUN establish failed")

                vpnInterface = tunPfd
                isRunning    = true
                isConnected.set(true)

                VpnLogManager.success("TUN interface established → addr 10.88.0.1/30, MTU 1500")
                VpnLogManager.info("DNS 8.8.8.8 | 1.1.1.1   Route 0.0.0.0/0")

                // ── Step 4: Log active network interfaces ─────────────────────
                TunnelPingChecker.logNetworkInterfaces()

                // ── Step 5: Start TUN ↔ SOCKS5 bidirectional bridge ──────────
                val bridge = TunBridge(tunPfd, LOCAL_SOCKS_PORT, this@BoykVpnService)
                tunBridge = bridge
                bridge.start()
                VpnLogManager.success("TUN↔SOCKS5 bridge active — all traffic routed through proxy")

                // ── Step 6: Notify UI ─────────────────────────────────────────
                withContext(Dispatchers.Main) {
                    updateNotification("متصل: ${server.name}")
                    listeners.forEach { it.onConnected(server.name) }
                }

                // ── Step 7: First ping check (give Xray 2.5 s to fully init) ─
                delay(2_500)
                TunnelPingChecker.pingAndLog(LOCAL_SOCKS_PORT)

                // ── Step 8: Keep-alive ping every 30 s ───────────────────────
                serviceScope.launch {
                    while (isConnected.get()) {
                        delay(30_000)
                        if (!isConnected.get()) break
                        if (XrayManager.isRunning()) {
                            TunnelPingChecker.pingAndLog(LOCAL_SOCKS_PORT)
                        } else {
                            VpnLogManager.warn("Xray core stopped unexpectedly — disconnecting")
                            stopVpn()
                            break
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "VPN startup failed", e)
                VpnLogManager.error("Connection failed: ${e.message}")
                VpnLogManager.error("Stack: ${e.stackTraceToString().take(400)}")
                notifyError("فشل الاتصال: ${e.message}")
                stopVpn()
            }
        }
    }

    // ── VPN teardown ─────────────────────────────────────────────────────────

    fun stopVpn() {
        serviceScope.launch {
            isRunning = false
            isConnected.set(false)

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

    /**
     * Polls [port] on 127.0.0.1 until it accepts a new ServerSocket bind (i.e. is free),
     * or until [timeoutMs] elapses. Returns true if port was confirmed free.
     */
    private suspend fun waitForPort(port: Int, timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val free = try {
                java.net.ServerSocket(port).use { true }
            } catch (_: Exception) { false }
            if (free) {
                VpnLogManager.sys("Port $port confirmed free")
                return@withContext true
            }
            delay(150)
        }
        false
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID, "Boykta VPN", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "حالة اتصال Boykta VPN"
            setShowBadge(false)
        }
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

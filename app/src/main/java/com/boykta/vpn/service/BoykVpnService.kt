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

class BoykVpnService : VpnService() {

    companion object {
        private const val TAG = "BoykVpnService"
        const val CHANNEL_ID = "boykta_vpn_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_CONNECT    = "com.boykta.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.boykta.vpn.DISCONNECT"
        const val EXTRA_SERVER_ID   = "server_id"

        const val LOCAL_SOCKS_PORT = 10808
        const val LOCAL_HTTP_PORT  = 10809

        @Volatile var isRunning = false
            private set
    }

    private val binder = LocalBinder()
    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunBridge: TunBridge? = null
    private var currentServer: Server? = null
    private val isConnected = AtomicBoolean(false)
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

                // Log device hardware and Android version (real info)
                TunnelPingChecker.logDeviceInfo()
                VpnLogManager.sys("VpnService starting — target: ${server.name}")

                // 1. Force-stop any stale Xray instance (prevents "already running" crash)
                //    XrayManager.start() also calls forceStop(), but being explicit here
                //    ensures the TUN interface is always opened fresh.
                XrayManager.forceStop()
                delay(200) // brief settle time

                // 2. Start Xray-core with the proxy config URI
                val xrayStarted = XrayManager.start(server.config, LOCAL_SOCKS_PORT, LOCAL_HTTP_PORT)
                if (!xrayStarted) {
                    notifyError("فشل تشغيل محرك VPN")
                    VpnLogManager.error("Xray engine failed to start — aborting")
                    return@launch
                }

                // 3. Build the TUN interface
                //    MTU 1500, DNS 8.8.8.8 + 1.1.1.1, route all IPv4 traffic
                val tunPfd = Builder()
                    .setSession("Boykta VPN — ${server.name}")
                    .setMtu(1500)
                    .addAddress("10.88.0.1", 30)          // TUN local address
                    .addDnsServer("8.8.8.8")
                    .addDnsServer("1.1.1.1")
                    .addRoute("0.0.0.0", 0)               // Route all IPv4 traffic
                    .addDisallowedApplication(packageName) // Don't route our own traffic (prevents loop)
                    .establish()
                    ?: throw IllegalStateException("Failed to establish TUN interface")

                vpnInterface = tunPfd
                isRunning    = true
                isConnected.set(true)

                VpnLogManager.success("TUN interface established (MTU 1500, addr 10.88.0.1/30)")
                VpnLogManager.info("DNS: 8.8.8.8, 1.1.1.1 | Route: 0.0.0.0/0")

                // 4. Log real network interface assignments
                TunnelPingChecker.logNetworkInterfaces()

                // 5. Start TUN → SOCKS5 bridge
                val bridge = TunBridge(tunPfd, LOCAL_SOCKS_PORT, this@BoykVpnService)
                tunBridge = bridge
                bridge.start()

                VpnLogManager.success("TUN→SOCKS5 bridge active — routing all traffic via proxy")

                withContext(Dispatchers.Main) {
                    updateNotification("متصل: ${server.name}")
                    listeners.forEach { it.onConnected(server.name) }
                }

                // 6. Initial real HTTP ping via SOCKS5 proxy to verify tunnel is working
                delay(2_500) // give Xray time to fully initialize
                TunnelPingChecker.pingAndLog(LOCAL_SOCKS_PORT)

                // 7. Keep-alive: real HTTP ping every 30 s (via SOCKS5 proxy)
                serviceScope.launch {
                    var ticks = 0
                    while (isConnected.get()) {
                        delay(30_000)
                        ticks++
                        if (XrayManager.isRunning()) {
                            TunnelPingChecker.pingAndLog(LOCAL_SOCKS_PORT)
                        } else {
                            VpnLogManager.warn("Xray core stopped unexpectedly — reconnecting…")
                            stopVpn()
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "VPN connection failed", e)
                VpnLogManager.error("Connection failed: ${e.message}")
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

            VpnLogManager.sys("VpnService stopped — all tunnels closed")

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
                delay(2000)
                startVpn(server)
            }
        }
    }

    fun addListener(listener: VpnStateListener) = listeners.add(listener)
    fun removeListener(listener: VpnStateListener) = listeners.remove(listener)

    private fun notifyError(message: String) {
        serviceScope.launch(Dispatchers.Main) {
            listeners.forEach { it.onError(message) }
        }
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Boykta VPN", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "حالة اتصال Boykta VPN"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
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

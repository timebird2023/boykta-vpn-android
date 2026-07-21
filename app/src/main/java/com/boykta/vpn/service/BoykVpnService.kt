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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.atomic.AtomicBoolean

class BoykVpnService : VpnService() {

    companion object {
        private const val TAG = "BoykVpnService"
        const val CHANNEL_ID = "boykta_vpn_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_CONNECT = "com.boykta.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.boykta.vpn.DISCONNECT"
        const val EXTRA_SERVER_ID = "server_id"

        // Local SOCKS5 proxy port that Xray-core listens on
        const val LOCAL_SOCKS_PORT = 10808
        const val LOCAL_HTTP_PORT = 10809

        var isRunning = false
            private set
    }

    private val binder = LocalBinder()
    private var vpnInterface: ParcelFileDescriptor? = null
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
            ACTION_CONNECT -> {
                val serverId = intent.getIntExtra(EXTRA_SERVER_ID, -1)
                // Server is set via binder before this intent is sent
                currentServer?.let { startVpn(it) }
            }
            ACTION_DISCONNECT -> stopVpn()
        }
        return START_STICKY
    }

    fun connectToServer(server: Server) {
        currentServer = server
        startVpn(server)
    }

    private fun startVpn(server: Server) {
        serviceScope.launch {
            try {
                withContext(Dispatchers.Main) {
                    startForeground(NOTIFICATION_ID, buildNotification("جارٍ الاتصال…"))
                }

                // 1. Start Xray-core with VLESS config
                val xrayStarted = XrayManager.start(server.config, LOCAL_SOCKS_PORT, LOCAL_HTTP_PORT)
                if (!xrayStarted) {
                    notifyError("فشل تشغيل محرك VPN")
                    return@launch
                }

                // 2. Build VPN interface (TUN)
                val builder = Builder()
                    .setSession("Boykta VPN")
                    .addAddress("10.0.0.2", 24)
                    .addDnsServer("1.1.1.1")
                    .addDnsServer("8.8.8.8")
                    .addRoute("0.0.0.0", 0) // Route all traffic
                    .setMtu(1500)

                vpnInterface = builder.establish()
                    ?: throw IllegalStateException("فشل إنشاء واجهة VPN")

                isRunning = true
                isConnected.set(true)

                withContext(Dispatchers.Main) {
                    updateNotification("متصل بـ: ${server.name}")
                    listeners.forEach { it.onConnected(server.name) }
                }

                // 3. Start tun2socks packet forwarding
                startPacketForwarding()

            } catch (e: Exception) {
                Log.e(TAG, "VPN connection failed", e)
                notifyError("فشل الاتصال: ${e.message}")
                stopVpn()
            }
        }
    }

    private suspend fun startPacketForwarding() {
        // Hand off to XrayManager's tun2socks engine
        XrayManager.startTun2Socks(
            tunFd = vpnInterface!!.fd,
            socksPort = LOCAL_SOCKS_PORT
        )
    }

    fun stopVpn() {
        serviceScope.launch {
            isRunning = false
            isConnected.set(false)

            XrayManager.stop()

            try {
                vpnInterface?.close()
                vpnInterface = null
            } catch (e: Exception) {
                Log.e(TAG, "Error closing VPN interface", e)
            }

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

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Boykta VPN",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "حالة اتصال Boykta VPN"
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        val intent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
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
            .setContentIntent(intent)
            .addAction(R.drawable.ic_close, "قطع الاتصال", disconnectIntent)
            .setOngoing(true)
            .setColor(0xFF00D4FF.toInt())
            .build()
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    override fun onRevoke() {
        super.onRevoke()
        stopVpn()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        XrayManager.stop()
    }
}

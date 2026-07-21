package com.boykta.vpn

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.boykta.vpn.api.CryptoHelper
import com.boykta.vpn.config.BoykConfig
import com.boykta.vpn.config.BoykConfigManager
import com.boykta.vpn.db.LocalDatabase
import com.boykta.vpn.db.LocalServer
import com.boykta.vpn.model.Server
import com.boykta.vpn.model.Announcement
import com.boykta.vpn.model.formattedRemaining
import com.boykta.vpn.model.isExpired
import com.boykta.vpn.model.protocolLabel
import com.boykta.vpn.service.BoykVpnService
import com.boykta.vpn.ui.AdDialog
import com.boykta.vpn.ui.ConfigExportDialog
import com.boykta.vpn.ui.ImportResultDialog
import com.boykta.vpn.ui.LogAdapter
import com.boykta.vpn.ui.PrivacyPolicyDialog
import com.boykta.vpn.ui.ServerSelectSheet
import com.boykta.vpn.util.SecurityChecker
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

@AndroidEntryPoint
class MainActivity : AppCompatActivity(), BoykVpnService.VpnStateListener {

    private val viewModel: MainViewModel by viewModels()

    // ── Views ──────────────────────────────────────────────────────────────────
    private lateinit var tvStatus: TextView
    private lateinit var dotStatus: View
    private lateinit var tvUpSpeed: TextView
    private lateinit var tvDownSpeed: TextView

    // Connect button (FrameLayout circle)
    private lateinit var btnConnectMain: View
    private lateinit var tvConnectIcon: TextView
    private lateinit var tvConnectLabel: TextView

    // Selected server card
    private lateinit var cardSelectedServer: MaterialCardView
    private lateinit var tvSelectedName: TextView
    private lateinit var tvProtocolBadge: TextView
    private lateinit var tvSelectedCountdown: TextView
    private lateinit var tvPingBadge: TextView
    private lateinit var tvServerFlag: TextView
    private lateinit var layoutEmpty: View
    private lateinit var progressBar: ProgressBar

    // Bottom bar buttons
    private lateinit var btnBarUpdate: View
    private lateinit var btnBarLogs: View
    private lateinit var btnBarKey: View
    private lateinit var btnBarTelegram: View
    private lateinit var btnBarExit: View

    // Log terminal
    private lateinit var cardLogTerminal: View
    private lateinit var rvLogs: RecyclerView
    private lateinit var btnCloseLog: TextView
    private lateinit var logAdapter: LogAdapter

    // VPN service binding
    private var vpnService: BoykVpnService? = null
    private var serviceBound = false
    private var isVpnConnected = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            vpnService = (service as BoykVpnService.LocalBinder).getService()
            vpnService?.addListener(this@MainActivity)
            serviceBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            vpnService = null
            serviceBound = false
        }
    }

    private var pendingServer: Server? = null

    private val vpnPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingServer?.let { startVpnConnection(it) }
        } else {
            showSnack("يجب منح إذن VPN")
        }
        pendingServer = null
    }

    private val importFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleImportUri(it) }
    }

    private var selectedCountdownJob: Job? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Security check
        if (SecurityChecker.isSnifferDetected(this)) {
            Toast.makeText(this, "⚠️ تحذير: تم اكتشاف تطبيق مشبوه", Toast.LENGTH_LONG).show()
        }

        bindViews()
        setupClickListeners()
        setupObservers()
        setupLogTerminal()

        // Handle file intent
        intent?.let { handleIntent(it) }

        // Initial data load
        viewModel.loadServers()
        viewModel.loadAnnouncement()
        viewModel.startAutoPing()
        viewModel.startNotificationPolling()

        bindVpnService()

        // Request notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 99)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        selectedCountdownJob?.cancel()
        viewModel.stopAutoPing()
        if (serviceBound) {
            vpnService?.removeListener(this)
            unbindService(connection)
        }
    }

    // ── View binding ──────────────────────────────────────────────────────────

    private fun bindViews() {
        tvStatus          = findViewById(R.id.tvStatus)
        dotStatus         = findViewById(R.id.dotStatus)
        tvUpSpeed         = findViewById(R.id.tvUpSpeed)
        tvDownSpeed       = findViewById(R.id.tvDownSpeed)

        btnConnectMain    = findViewById(R.id.btnConnectMain)
        tvConnectIcon     = findViewById(R.id.tvConnectIcon)
        tvConnectLabel    = findViewById(R.id.tvConnectLabel)

        cardSelectedServer = findViewById(R.id.cardSelectedServer)
        tvSelectedName    = findViewById(R.id.tvSelectedName)
        tvProtocolBadge   = findViewById(R.id.tvProtocolBadge)
        tvSelectedCountdown = findViewById(R.id.tvSelectedCountdown)
        tvPingBadge       = findViewById(R.id.tvPingBadge)
        tvServerFlag      = findViewById(R.id.tvServerFlag)
        layoutEmpty       = findViewById(R.id.layoutEmpty)
        progressBar       = findViewById(R.id.progressBar)

        btnBarUpdate      = findViewById(R.id.btnBarUpdate)
        btnBarLogs        = findViewById(R.id.btnBarLogs)
        btnBarKey         = findViewById(R.id.btnBarKey)
        btnBarTelegram    = findViewById(R.id.btnBarTelegram)
        btnBarExit        = findViewById(R.id.btnBarExit)

        cardLogTerminal   = findViewById(R.id.cardLogTerminal)
        rvLogs            = findViewById(R.id.rvLogs)
        btnCloseLog       = findViewById(R.id.btnCloseLog)
    }

    // ── Click listeners ────────────────────────────────────────────────────────

    private fun setupClickListeners() {
        // Main connect/disconnect button
        btnConnectMain.setOnClickListener {
            if (isVpnConnected) {
                disconnectVpn()
            } else {
                val server = viewModel.selectedServer.value
                if (server == null) {
                    showSnack("اختر سيرفراً أولاً")
                    openServerSelectSheet()
                } else if (server.isExpired()) {
                    showSnack("انتهت صلاحية هذا السيرفر")
                } else {
                    requestVpnPermissionAndConnect(server)
                }
            }
        }

        // Server card → open bottom sheet
        cardSelectedServer.setOnClickListener { openServerSelectSheet() }

        // Bottom bar buttons
        btnBarUpdate.setOnClickListener {
            viewModel.loadServers()
            showSnack("جارِ تحديث السيرفرات…")
        }

        btnBarLogs.setOnClickListener {
            val visible = cardLogTerminal.visibility == View.VISIBLE
            cardLogTerminal.visibility = if (visible) View.GONE else View.VISIBLE
        }

        btnBarKey.setOnClickListener {
            val dialog = ConfigExportDialog.newInstance()
            // Wire "Connect Directly" — saves config and immediately connects
            dialog.onConnectDirectly = { config ->
                val server = Server(
                    id        = -1,
                    name      = config.name,
                    config    = config.toProxyUri(),   // plain URI used in-memory only
                    expiresAt = java.text.SimpleDateFormat(
                        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US
                    ).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                        .format(java.util.Date(System.currentTimeMillis() + config.expiresSeconds * 1000L)),
                    isActive  = true,
                    protocol  = config.protocol,
                )
                viewModel.selectServer(server)
                requestVpnPermissionAndConnect(server)
            }
            dialog.show(supportFragmentManager, "config_export")
        }

        btnBarTelegram.setOnClickListener {
            val url = getString(R.string.telegram_channel_url)
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        btnBarExit.setOnClickListener {
            if (isVpnConnected) {
                disconnectVpn()
            }
            finishAndRemoveTask()
        }

        // Close log terminal
        btnCloseLog.setOnClickListener {
            cardLogTerminal.visibility = View.GONE
        }
    }

    // ── Observers ──────────────────────────────────────────────────────────────

    private fun setupObservers() {
        // Server list changes
        lifecycleScope.launch {
            viewModel.allServers.collectLatest { servers ->
                val empty = servers.isEmpty()
                cardSelectedServer.visibility = if (empty) View.GONE else View.VISIBLE
                layoutEmpty.visibility = if (empty) View.VISIBLE else View.GONE

                // Auto-select first server if none selected
                if (!empty && viewModel.selectedServer.value == null) {
                    viewModel.selectServer(servers.first())
                }
            }
        }

        // Selected server card
        lifecycleScope.launch {
            viewModel.selectedServer.collectLatest { server ->
                if (server != null) {
                    updateServerCard(server)
                    startCountdownFor(server)
                }
            }
        }

        // Live ping badge (updated every 1 second)
        lifecycleScope.launch {
            viewModel.pingMs.collectLatest { ms ->
                val text = when {
                    ms == null -> "…ms"
                    ms < 0    -> "timeout"
                    else      -> "$ms ms"
                }
                tvPingBadge.text = text
            }
        }

        // Loading
        lifecycleScope.launch {
            viewModel.isLoading.collectLatest { loading ->
                progressBar.visibility = if (loading) View.VISIBLE else View.GONE
            }
        }

        // Errors
        lifecycleScope.launch {
            viewModel.error.collectLatest { err ->
                if (!err.isNullOrBlank()) showSnack(err)
            }
        }

        // Announcement / Ad dialog
        lifecycleScope.launch {
            viewModel.announcement.collectLatest { ann ->
                if (ann != null && ann.mediaUrls.isNotEmpty()) {
                    val dialog = AdDialog.newInstance(ann)
                    dialog.onAdClosed = {}
                    dialog.show(supportFragmentManager, "ad_dialog")
                    viewModel.announcement.value = null
                }
            }
        }
    }

    // ── Log terminal ──────────────────────────────────────────────────────────

    private fun setupLogTerminal() {
        logAdapter = LogAdapter()
        rvLogs.layoutManager = LinearLayoutManager(this).also { it.stackFromEnd = true }
        rvLogs.adapter = logAdapter

        lifecycleScope.launch {
            viewModel.vpnLogs.collect { line ->
                logAdapter.addLine(line)
                rvLogs.scrollToPosition(logAdapter.itemCount - 1)
            }
        }
    }

    // ── Server card UI ────────────────────────────────────────────────────────

    private fun updateServerCard(server: Server) {
        tvSelectedName.text = server.name
        tvProtocolBadge.text = server.protocolLabel()
        tvServerFlag.text = flagEmoji(server.name)
    }

    private fun startCountdownFor(server: Server) {
        selectedCountdownJob?.cancel()
        selectedCountdownJob = lifecycleScope.launch {
            while (isActive) {
                tvSelectedCountdown.text = server.formattedRemaining()
                delay(1_000L)
            }
        }
    }

    private fun flagEmoji(name: String): String {
        val n = name.lowercase()
        return when {
            n.contains("germany") || n.contains("de ") || n.contains("deutsch") -> "🇩🇪"
            n.contains("usa") || n.contains("us ") || n.contains("united states") -> "🇺🇸"
            n.contains("uk") || n.contains("united kingdom") || n.contains("britain") -> "🇬🇧"
            n.contains("france") || n.contains("fr ") -> "🇫🇷"
            n.contains("netherlands") || n.contains("nl") || n.contains("dutch") -> "🇳🇱"
            n.contains("japan") || n.contains("jp") -> "🇯🇵"
            n.contains("singapore") || n.contains("sg") -> "🇸🇬"
            n.contains("turkey") || n.contains("tr ") -> "🇹🇷"
            n.contains("russia") || n.contains("ru ") -> "🇷🇺"
            n.contains("canada") || n.contains("ca ") -> "🇨🇦"
            n.contains("australia") || n.contains("au ") -> "🇦🇺"
            n.contains("iran") || n.contains("ir ") -> "🇮🇷"
            n.contains("uae") || n.contains("dubai") -> "🇦🇪"
            n.contains("sweden") || n.contains("se ") -> "🇸🇪"
            n.contains("finland") || n.contains("fi ") -> "🇫🇮"
            n.contains("poland") || n.contains("pl ") -> "🇵🇱"
            else -> "🌐"
        }
    }

    // ── Server selection sheet ─────────────────────────────────────────────────

    private fun openServerSelectSheet() {
        val servers = viewModel.allServers.value
        if (servers.isEmpty()) {
            showSnack("لا توجد سيرفرات — اضغط تحديث")
            return
        }
        ServerSelectSheet.newInstance(
            servers = servers,
            selectedId = viewModel.selectedServer.value?.id
        ) { server ->
            viewModel.selectServer(server)
            if (isVpnConnected) {
                // Switch server: disconnect then reconnect
                disconnectVpn()
                lifecycleScope.launch {
                    delay(800)
                    requestVpnPermissionAndConnect(server)
                }
            }
        }.show(supportFragmentManager, "server_select")
    }

    // ── VPN connection ────────────────────────────────────────────────────────

    private fun requestVpnPermissionAndConnect(server: Server) {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            pendingServer = server
            vpnPermLauncher.launch(intent)
        } else {
            startVpnConnection(server)
        }
    }

    private fun startVpnConnection(server: Server) {
        lifecycleScope.launch {
            // Resolve config URI: local servers (protocol="local") store an AES-encrypted
            // URI in DB — decrypt before handing to VPN engine. Remote/inline servers
            // already carry their plain URI in Server.config.
            val resolvedServer = if (server.protocol == "local" && server.config.isBlank()) {
                val db = LocalDatabase.get(this@MainActivity)
                val encrypted = db.localServerDao().getEncryptedUri(server.id)
                if (encrypted.isNullOrBlank()) {
                    showSnack("خطأ: تعذّر قراءة تكوين السيرفر")
                    return@launch
                }
                val plainUri = try {
                    if (CryptoHelper.isEncrypted(encrypted)) CryptoHelper.decrypt(encrypted)
                    else encrypted  // stored unencrypted (legacy / unlocked)
                } catch (e: Exception) {
                    showSnack("خطأ في فك تشفير السيرفر: ${e.message}")
                    return@launch
                }
                server.copy(config = plainUri)
            } else {
                server
            }

            val svc = Intent(this@MainActivity, BoykVpnService::class.java).apply {
                action = BoykVpnService.ACTION_CONNECT
            }
            startForegroundService(svc)
            vpnService?.connectToServer(resolvedServer) ?: run {
                bindVpnService()
                delay(300)
                vpnService?.connectToServer(resolvedServer)
            }
        }
    }

    private fun disconnectVpn() {
        startService(Intent(this, BoykVpnService::class.java).apply {
            action = BoykVpnService.ACTION_DISCONNECT
        })
        vpnService?.stopVpn()
    }

    // ── VPN state callbacks ────────────────────────────────────────────────────

    override fun onConnected(serverName: String) {
        runOnUiThread {
            isVpnConnected = true
            updateConnectUi(true)
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            isVpnConnected = false
            updateConnectUi(false)
        }
    }

    override fun onError(message: String) {
        runOnUiThread { showSnack(message) }
    }

    private fun updateConnectUi(connected: Boolean) {
        if (connected) {
            tvConnectIcon.text = "⏹"
            tvConnectLabel.text = "DISCONNECT"
            btnConnectMain.background = getDrawable(R.drawable.bg_connect_button_disconnect)
            dotStatus.setBackgroundColor(0xFF00F2FE.toInt())
            tvStatus.text = "متصل — ${viewModel.selectedServer.value?.name ?: ""}"
            tvStatus.setTextColor(0xFF00F2FE.toInt())
        } else {
            tvConnectIcon.text = "▶"
            tvConnectLabel.text = "CONNECT"
            btnConnectMain.background = getDrawable(R.drawable.bg_connect_button)
            dotStatus.setBackgroundColor(0xFFFF0055.toInt())
            tvStatus.text = "غير متصل"
            tvStatus.setTextColor(0xFFFF0055.toInt())
        }
    }

    // ── Import handling ────────────────────────────────────────────────────────

    private fun handleIntent(intent: Intent) {
        val uri = intent.data ?: return
        if (intent.action == Intent.ACTION_VIEW) {
            handleImportUri(uri)
        }
    }

    private fun handleImportUri(uri: Uri) {
        try {
            val config: BoykConfig = BoykConfigManager.import(this, uri) ?: run {
                showSnack("خطأ: ملف .boykta غير صالح أو تالف")
                return
            }
            ImportResultDialog.newInstance(config) {
                // On confirm: save to local DB and refresh
                val db = LocalDatabase.get(this)
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val expiresAt = System.currentTimeMillis() + config.expiresSeconds * 1000L
                    // Encrypt the proxy URI before storing at rest
                    val encryptedUri = CryptoHelper.encrypt(config.toProxyUri())
                    db.localServerDao().insert(
                        LocalServer(
                            displayName  = config.name,
                            encryptedUri = encryptedUri,
                            expiresAt    = expiresAt,
                        )
                    )
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        viewModel.loadServers()
                        if (!config.customToast.isNullOrBlank()) {
                            Toast.makeText(this@MainActivity, config.customToast, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }.show(supportFragmentManager, "import_result")
        } catch (e: Exception) {
            showSnack("خطأ في الاستيراد: ${e.message}")
        }
    }

    // ── Service binding ────────────────────────────────────────────────────────

    private fun bindVpnService() {
        bindService(
            Intent(this, BoykVpnService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun showSnack(msg: String) =
        Snackbar.make(btnConnectMain, msg, Snackbar.LENGTH_LONG).show()
}

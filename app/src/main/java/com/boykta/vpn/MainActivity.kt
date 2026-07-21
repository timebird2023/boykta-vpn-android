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
import android.widget.LinearLayout
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

    // Unlocked config panel
    private lateinit var cardUnlockedConfig: View
    private lateinit var tvUnlockedHost: TextView
    private lateinit var tvUnlockedPath: TextView
    private lateinit var tvUnlockedSni: TextView
    private lateinit var tvUnlockedHostHeader: TextView

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
            Toast.makeText(this, "تحذير: تم اكتشاف تطبيق مشبوه", Toast.LENGTH_LONG).show()
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

        cardUnlockedConfig   = findViewById(R.id.cardUnlockedConfig)
        tvUnlockedHost       = findViewById(R.id.tvUnlockedHost)
        tvUnlockedPath       = findViewById(R.id.tvUnlockedPath)
        tvUnlockedSni        = findViewById(R.id.tvUnlockedSni)
        tvUnlockedHostHeader = findViewById(R.id.tvUnlockedHostHeader)

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
            dialog.onConnectDirectly = { config ->
                val server = Server(
                    id        = -1,
                    name      = config.name,
                    config    = config.toProxyUri(),
                    expiresAt = java.text.SimpleDateFormat(
                        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US
                    ).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                        .format(java.util.Date(System.currentTimeMillis() + config.expiresSeconds * 1000L)),
                    isActive  = true,
                    protocol  = config.protocol,
                    isLocked  = config.locked,
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
                    updateUnlockedPanel(server)
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

        // Protocol badge: show "كونفيغ مغلق" for locked local configs
        tvProtocolBadge.text = when {
            server.protocol == "local" && server.isLocked  -> "كونفيغ مغلق"
            server.protocol == "local" && !server.isLocked -> "UNLOCKED"
            else                                           -> server.protocolLabel()
        }

        tvServerFlag.text = flagEmoji(server.name)
    }

    /**
     * Show/hide the unlocked-config panel depending on the selected server.
     * Unlocked local servers and the built-in test server reveal their params.
     * Locked servers never show raw connection details.
     */
    private fun updateUnlockedPanel(server: Server) {
        val show = !server.isLocked && (server.protocol == "local" || server.protocol == "vless"
                || server.protocol == "trojan" || server.protocol == "vmess" || server.protocol == "ss")
                && server.config.isNotBlank()

        if (show) {
            // Parse visible params from the config URI
            val params = parseVisibleParams(server)
            if (params != null) {
                cardUnlockedConfig.visibility = View.VISIBLE
                tvUnlockedHost.text       = params.host
                tvUnlockedPath.text       = params.path
                tvUnlockedSni.text        = params.sni
                tvUnlockedHostHeader.text = params.hostHeader
            } else {
                cardUnlockedConfig.visibility = View.GONE
            }
        } else if (server.protocol == "local" && !server.isLocked && server.configJson.isNotBlank()) {
            // Unlocked local config stored as JSON
            try {
                val cfg = BoykConfig.fromJson(server.configJson)
                cardUnlockedConfig.visibility = View.VISIBLE
                tvUnlockedHost.text       = cfg.host
                tvUnlockedPath.text       = cfg.path
                tvUnlockedSni.text        = cfg.sni
                tvUnlockedHostHeader.text = cfg.hostHeader
            } catch (_: Exception) {
                cardUnlockedConfig.visibility = View.GONE
            }
        } else {
            cardUnlockedConfig.visibility = View.GONE
        }
    }

    private data class VisibleParams(val host: String, val path: String, val sni: String, val hostHeader: String)

    private fun parseVisibleParams(server: Server): VisibleParams? {
        return try {
            val uri = server.config
            when {
                uri.startsWith("vless://") -> {
                    val afterScheme = uri.removePrefix("vless://")
                    val atIdx = afterScheme.indexOf('@')
                    val afterAt = afterScheme.substring(atIdx + 1)
                    val qIdx = afterAt.indexOf('?')
                    val hashIdx = afterAt.indexOf('#')
                    val hostPort = if (qIdx != -1) afterAt.substring(0, qIdx)
                                   else afterAt.substring(0, hashIdx.takeIf { it != -1 } ?: afterAt.length)
                    val lastColon = hostPort.lastIndexOf(':')
                    val realHost = hostPort.substring(0, lastColon)
                    val queryStr = if (qIdx != -1) {
                        val end = if (hashIdx != -1 && hashIdx > qIdx) hashIdx else afterAt.length
                        afterAt.substring(qIdx + 1, end)
                    } else ""
                    val params = queryStr.split("&").associate {
                        val kv = it.split("=")
                        kv[0] to if (kv.size > 1) java.net.URLDecoder.decode(kv[1], "UTF-8") else ""
                    }
                    VisibleParams(
                        host       = realHost,
                        path       = params["path"] ?: "/",
                        sni        = params["sni"] ?: realHost,
                        hostHeader = params["host"] ?: realHost,
                    )
                }
                else -> null
            }
        } catch (_: Exception) { null }
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
            n.contains("germany") || n.contains("de ") || n.contains("deutsch") -> "\uD83C\uDDE9\uD83C\uDDEA"
            n.contains("usa") || n.contains("us ") || n.contains("united states") -> "\uD83C\uDDFA\uD83C\uDDF8"
            n.contains("uk") || n.contains("united kingdom") || n.contains("britain") -> "\uD83C\uDDEC\uD83C\uDDE7"
            n.contains("france") || n.contains("fr ") -> "\uD83C\uDDEB\uD83C\uDDF7"
            n.contains("netherlands") || n.contains("nl") || n.contains("dutch") -> "\uD83C\uDDF3\uD83C\uDDF1"
            n.contains("japan") || n.contains("jp") -> "\uD83C\uDDEF\uD83C\uDDF5"
            n.contains("singapore") || n.contains("sg") -> "\uD83C\uDDF8\uD83C\uDDEC"
            n.contains("turkey") || n.contains("tr ") -> "\uD83C\uDDF9\uD83C\uDDF7"
            n.contains("russia") || n.contains("ru ") -> "\uD83C\uDDF7\uD83C\uDDFA"
            n.contains("canada") || n.contains("ca ") -> "\uD83C\uDDE8\uD83C\uDDE6"
            n.contains("australia") || n.contains("au ") -> "\uD83C\uDDE6\uD83C\uDDFA"
            n.contains("iran") || n.contains("ir ") -> "\uD83C\uDDEE\uD83C\uDDF7"
            n.contains("uae") || n.contains("dubai") -> "\uD83C\uDDE6\uD83C\uDDEA"
            n.contains("sweden") || n.contains("se ") -> "\uD83C\uDDF8\uD83C\uDDEA"
            n.contains("finland") || n.contains("fi ") -> "\uD83C\uDDEB\uD83C\uDDEE"
            n.contains("poland") || n.contains("pl ") -> "\uD83C\uDDF5\uD83C\uDDF1"
            n.contains("test") || n.contains("khaled") -> "\uD83E\uDDEA"
            else -> "\uD83C\uDF10"
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
            // Resolve config URI: local servers store an encrypted URI in DB
            val resolvedServer = if (server.protocol == "local" && server.config.isBlank()) {
                val db = LocalDatabase.get(this@MainActivity)
                val encrypted = db.localServerDao().getEncryptedUri(server.id)
                if (encrypted.isNullOrBlank()) {
                    showSnack("خطأ: تعذّر قراءة تكوين السيرفر")
                    return@launch
                }
                val plainUri = try {
                    if (CryptoHelper.isEncrypted(encrypted)) CryptoHelper.decrypt(encrypted)
                    else encrypted  // stored unencrypted (unlocked)
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
            val result: Pair<BoykConfig, Boolean>? = BoykConfigManager.importWithLockInfo(this, uri)
            if (result == null) {
                showSnack("خطأ: ملف .boykta غير صالح أو تالف")
                return
            }
            val (config, isLocked) = result

            ImportResultDialog.newInstance(config) {
                val db = LocalDatabase.get(this)
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val expiresAt = System.currentTimeMillis() + config.expiresSeconds * 1000L
                    val encryptedUri = if (isLocked) {
                        CryptoHelper.encrypt(config.toProxyUri())
                    } else {
                        config.toProxyUri()  // store plain for unlocked
                    }
                    db.localServerDao().insert(
                        LocalServer(
                            displayName  = config.name,
                            encryptedUri = encryptedUri,
                            expiresAt    = expiresAt,
                            isLocked     = isLocked,
                            configJson   = if (!isLocked) config.toJson() else "",
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

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
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
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

    // ── Drawer ─────────────────────────────────────────────────────────────────
    private lateinit var drawerLayout: DrawerLayout

    // ── Views ──────────────────────────────────────────────────────────────────
    private lateinit var tvStatus: TextView
    private lateinit var dotStatus: View
    private lateinit var ivStatusShield: ImageView

    // Connect button (FrameLayout circle)
    private lateinit var btnConnectMain: View
    private lateinit var ivConnectIcon: ImageView   // vector drawable — no emoji
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
    private lateinit var btnReconnect: View

    // Bottom bar buttons (Telegram + Exit moved to drawer)
    private lateinit var btnBarUpdate: View
    private lateinit var btnBarLogs: View
    private lateinit var btnBarKey: View

    // Glow rings
    private lateinit var ringOuter: View
    private lateinit var ringMid: View

    // Log terminal
    private lateinit var cardLogTerminal: View
    private lateinit var rvLogs: RecyclerView
    private lateinit var btnCloseLog: TextView
    private lateinit var btnClearLogs: View
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
            // ── STATE SYNC FIX ──────────────────────────────────────────────────
            // If the VPN was already running before this bind (e.g. app relaunch
            // while connected), the onConnected() callback fired before addListener
            // was called — so we missed it. Sync UI now.
            if (BoykVpnService.isRunning) {
                isVpnConnected = true
                updateConnectUi(true)
            }
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

    companion object {
        private const val CONNECTING = 2   // intermediate state sentinel
    }

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

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
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
        drawerLayout     = findViewById(R.id.drawerLayout)

        tvStatus          = findViewById(R.id.tvStatus)
        dotStatus         = findViewById(R.id.dotStatus)
        ivStatusShield    = findViewById(R.id.ivStatusShield)

        btnConnectMain    = findViewById(R.id.btnConnectMain)
        ivConnectIcon     = findViewById(R.id.ivConnectIcon)   // ImageView, no emoji
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
        btnReconnect         = findViewById(R.id.btnReconnect)

        btnBarUpdate      = findViewById(R.id.btnBarUpdate)
        btnBarLogs        = findViewById(R.id.btnBarLogs)
        btnBarKey         = findViewById(R.id.btnBarKey)

        ringOuter         = findViewById(R.id.ringOuter)
        ringMid           = findViewById(R.id.ringMid)

        cardLogTerminal   = findViewById(R.id.cardLogTerminal)
        rvLogs            = findViewById(R.id.rvLogs)
        btnCloseLog       = findViewById(R.id.btnCloseLog)
        btnClearLogs      = findViewById(R.id.btnClearLogs)
    }

    // ── Click listeners ────────────────────────────────────────────────────────

    private fun setupClickListeners() {
        // Hamburger — open side drawer
        findViewById<View>(R.id.btnMenuDrawer).setOnClickListener {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                drawerLayout.openDrawer(GravityCompat.START)
            }
        }

        // ── Drawer items ───────────────────────────────────────────────────────

        // Developer Channel
        findViewById<View>(R.id.drawerItemTelegram).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            val url = getString(R.string.telegram_channel_url)
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }

        // Import Offline Config
        findViewById<View>(R.id.drawerItemImport).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            importFileLauncher.launch("*/*")
        }

        // Live Terminal Logs
        findViewById<View>(R.id.drawerItemLogs).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            val visible = cardLogTerminal.visibility == View.VISIBLE
            cardLogTerminal.visibility = if (visible) View.GONE else View.VISIBLE
        }

        // Check Updates
        findViewById<View>(R.id.drawerItemUpdate).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            viewModel.loadServers()
            showSnack("جارِ التحقق من التحديثات…")
        }

        // Exit (drawer)
        findViewById<View>(R.id.drawerItemExit).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            if (isVpnConnected) disconnectVpn()
            finishAndRemoveTask()
        }

        // ── Main connect/disconnect button ────────────────────────────────────
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

        // Reconnect (unlocked config panel)
        btnReconnect.setOnClickListener {
            val server = viewModel.selectedServer.value ?: return@setOnClickListener
            if (isVpnConnected) {
                disconnectVpn()
                lifecycleScope.launch {
                    delay(800)
                    requestVpnPermissionAndConnect(server)
                }
            } else {
                requestVpnPermissionAndConnect(server)
            }
        }

        // ── Bottom bar buttons ─────────────────────────────────────────────────
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

        // Close log terminal
        btnCloseLog.setOnClickListener {
            cardLogTerminal.visibility = View.GONE
        }

        // Clear logs
        btnClearLogs.setOnClickListener {
            logAdapter.clearAll()
            com.boykta.vpn.service.VpnLogManager.clearLogs()
            rvLogs.scrollToPosition(0)
        }

        // Share App
        findViewById<View>(R.id.drawerItemShare).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Boykta VPN")
                putExtra(Intent.EXTRA_TEXT, "جرب Boykta VPN — خصوصية حقيقية وسرعة عالية\nhttps://t.me/boykta")
            }
            startActivity(Intent.createChooser(intent, "مشاركة التطبيق"))
        }

        // Privacy Policy
        findViewById<View>(R.id.drawerItemPrivacy).setOnClickListener {
            drawerLayout.closeDrawer(GravityCompat.START)
            PrivacyPolicyDialog().show(supportFragmentManager, "privacy")
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

        // Live ping badge (updated every 1 second) — IO-safe via ViewModel
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

        // Protocol badge: show "كونفيغ مغلق" for locked — NO encryption algorithm names
        tvProtocolBadge.text = when {
            server.protocol == "local" && server.isLocked  -> "كونفيغ مغلق"
            server.protocol == "local" && !server.isLocked -> "UNLOCKED"
            else                                           -> server.protocolLabel()
        }

        // Country code badge — NO emoji, plain 2-letter code
        tvServerFlag.text = countryCode(server.name)
    }

    /**
     * Show/hide the unlocked-config panel depending on the selected server.
     * Unlocked configs show full params; locked configs show nothing.
     */
    private fun updateUnlockedPanel(server: Server) {
        val show = !server.isLocked && (server.protocol == "local" || server.protocol == "vless"
                || server.protocol == "trojan" || server.protocol == "vmess" || server.protocol == "ss")
                && server.config.isNotBlank()

        if (show) {
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
            try {
                val cfg = com.boykta.vpn.config.BoykConfig.fromJson(server.configJson)
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
                uri.startsWith("trojan://") -> {
                    val withoutScheme = uri.removePrefix("trojan://")
                    val atIdx = withoutScheme.indexOf('@')
                    val rest = withoutScheme.substring(atIdx + 1)
                    val qIdx = rest.indexOf('?')
                    val hashIdx = rest.indexOf('#')
                    val hostPort = if (qIdx != -1) rest.substring(0, qIdx)
                                   else rest.substring(0, if (hashIdx != -1) hashIdx else rest.length)
                    val lastColon = hostPort.lastIndexOf(':')
                    val realHost = hostPort.substring(0, lastColon)
                    val queryStr = if (qIdx != -1) {
                        val end = if (hashIdx != -1 && hashIdx > qIdx) hashIdx else rest.length
                        rest.substring(qIdx + 1, end)
                    } else ""
                    val params = queryStr.split("&").associate {
                        val kv = it.split("=")
                        kv[0] to if (kv.size > 1) java.net.URLDecoder.decode(kv[1], "UTF-8") else ""
                    }
                    VisibleParams(
                        host       = realHost,
                        path       = params["path"] ?: "/",
                        sni        = params["sni"] ?: realHost,
                        hostHeader = params["host"]?.takeIf { it.isNotBlank() } ?: realHost,
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

    /**
     * Returns a 2-letter country code (NO emoji) based on server name.
     * Shown in the server flag badge inside the server card.
     */
    private fun countryCode(name: String): String {
        val n = name.lowercase()
        return when {
            n.contains("germany") || n.contains("de ") || n.contains("deutsch") -> "DE"
            n.contains("usa") || n.contains("us ") || n.contains("united states") -> "US"
            n.contains("uk") || n.contains("united kingdom") || n.contains("britain") -> "UK"
            n.contains("france") || n.contains("fr ") -> "FR"
            n.contains("netherlands") || n.contains("nl") || n.contains("dutch") -> "NL"
            n.contains("japan") || n.contains("jp") -> "JP"
            n.contains("singapore") || n.contains("sg") -> "SG"
            n.contains("turkey") || n.contains("tr ") -> "TR"
            n.contains("russia") || n.contains("ru ") -> "RU"
            n.contains("canada") || n.contains("ca ") -> "CA"
            n.contains("australia") || n.contains("au ") -> "AU"
            n.contains("iran") || n.contains("ir ") -> "IR"
            n.contains("uae") || n.contains("dubai") -> "AE"
            n.contains("sweden") || n.contains("se ") -> "SE"
            n.contains("finland") || n.contains("fi ") -> "FI"
            n.contains("poland") || n.contains("pl ") -> "PL"
            n.contains("test") || n.contains("diag") -> "!!"
            else -> "??"
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
        // Show connecting state immediately on UI thread
        runOnUiThread { updateConnectUi(CONNECTING) }

        lifecycleScope.launch(Dispatchers.IO) {
            // Resolve config URI: local servers store an encrypted URI in DB
            val resolvedServer = if (server.protocol == "local" && server.config.isBlank()) {
                val db = LocalDatabase.get(this@MainActivity)
                val raw = db.localServerDao().getEncryptedUri(server.id)
                if (raw.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        updateConnectUi(false)
                        showSnack("خطأ: تعذّر قراءة تكوين السيرفر")
                    }
                    return@launch
                }
                // ── BAD BASE64 FIX ─────────────────────────────────────────────
                // Use server.isLocked as the authoritative gate, NOT content detection.
                // CryptoHelper.isEncrypted() checks for '{' but a plain vless:// URI
                // also doesn't start with '{' → decrypt() would throw "bad base-64".
                val plainUri = if (server.isLocked) {
                    try {
                        CryptoHelper.decrypt(raw)
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            updateConnectUi(false)
                            showSnack("خطأ في فك تشفير السيرفر: ${e.message}")
                        }
                        return@launch
                    }
                } else {
                    raw   // plain URI — no decryption needed
                }
                server.copy(config = plainUri)
            } else {
                server
            }

            withContext(Dispatchers.Main) {
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
    }

    private fun disconnectVpn() {
        isVpnConnected = false
        updateConnectUi(false)
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
        runOnUiThread {
            // Reset UI to disconnected on error
            if (!isVpnConnected) updateConnectUi(false)
            showSnack(message)
        }
    }

    /**
     * Update connect button, status bar, and dot.
     * @param state  true = connected | false = disconnected | CONNECTING (2) = in-progress
     */
    private fun updateConnectUi(state: Any) {
        val connected  = state == true || state == 1
        val connecting = state == CONNECTING

        when {
            connected -> {
                // ── CONNECTED: cyan theme ──────────────────────────────────────
                ivConnectIcon.setImageResource(R.drawable.ic_stop)
                tvConnectLabel.text = "DISCONNECT"
                btnConnectMain.background = getDrawable(R.drawable.bg_connect_button_disconnect)
                tvConnectLabel.setTextColor(0xFF050508.toInt())    // dark text on cyan
                dotStatus.setBackgroundColor(0xFF00F2FE.toInt())
                tvStatus.text = "متصل — ${viewModel.selectedServer.value?.name ?: ""}"
                tvStatus.setTextColor(0xFF00F2FE.toInt())
                ivStatusShield.setColorFilter(0xFF00F2FE.toInt(),
                    android.graphics.PorterDuff.Mode.SRC_IN)
                // Glow rings: cyan halo
                ringOuter.alpha = 1.0f
                ringMid.alpha   = 1.0f
            }
            connecting -> {
                // ── CONNECTING: amber theme ───────────────────────────────────
                ivConnectIcon.setImageResource(R.drawable.ic_play)
                tvConnectLabel.text = "CONNECTING"
                btnConnectMain.background = getDrawable(R.drawable.bg_connect_button)
                tvConnectLabel.setTextColor(0xFFFFFFFF.toInt())
                dotStatus.setBackgroundColor(0xFFFFCC00.toInt())
                tvStatus.text = "جارٍ الاتصال…"
                tvStatus.setTextColor(0xFFFFCC00.toInt())
                ivStatusShield.setColorFilter(0xFFFFCC00.toInt(),
                    android.graphics.PorterDuff.Mode.SRC_IN)
                ringOuter.alpha = 0.5f
                ringMid.alpha   = 0.5f
            }
            else -> {
                // ── DISCONNECTED: red theme ───────────────────────────────────
                ivConnectIcon.setImageResource(R.drawable.ic_play)
                tvConnectLabel.text = "CONNECT"
                btnConnectMain.background = getDrawable(R.drawable.bg_connect_button)
                tvConnectLabel.setTextColor(0xFFFFFFFF.toInt())
                dotStatus.setBackgroundColor(0xFFFF0055.toInt())
                tvStatus.text = "غير متصل"
                tvStatus.setTextColor(0xFFFF0055.toInt())
                ivStatusShield.setColorFilter(0xFFFF0055.toInt(),
                    android.graphics.PorterDuff.Mode.SRC_IN)
                ringOuter.alpha = 0.35f
                ringMid.alpha   = 0.35f
            }
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
                lifecycleScope.launch(Dispatchers.IO) {
                    val expiresAt = System.currentTimeMillis() + config.expiresSeconds * 1000L
                    val encryptedUri = if (isLocked) {
                        CryptoHelper.encrypt(config.toProxyUri())
                    } else {
                        config.toProxyUri()
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
                    withContext(Dispatchers.Main) {
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

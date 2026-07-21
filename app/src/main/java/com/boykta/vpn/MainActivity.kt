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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.boykta.vpn.config.BoykConfig
import com.boykta.vpn.config.BoykConfigManager
import com.boykta.vpn.model.Server
import com.boykta.vpn.model.Announcement
import com.boykta.vpn.service.BoykVpnService
import com.boykta.vpn.ui.AdDialog
import com.boykta.vpn.ui.ConfigExportDialog
import com.boykta.vpn.ui.ImportResultDialog
import com.boykta.vpn.ui.ServerAdapter
import com.boykta.vpn.util.SecurityChecker
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

@AndroidEntryPoint
class MainActivity : AppCompatActivity(), ServerAdapter.Listener, BoykVpnService.VpnStateListener {

    private val viewModel: MainViewModel by viewModels()

    // Views
    private lateinit var rvServers: RecyclerView
    private lateinit var btnUpdate: MaterialButton
    private lateinit var btnImport: MaterialButton
    private lateinit var tvStatus: TextView
    private lateinit var dotStatus: View
    private lateinit var layoutEmpty: View
    private lateinit var fabExport: FloatingActionButton

    private lateinit var adapter: ServerAdapter

    // VPN service binding
    private var vpnService: BoykVpnService? = null
    private var serviceBound = false

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

    // VPN permission launcher
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

    // File picker for import
    private val importFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { handleImportUri(it) }
    }

    // Storage permission launcher (Android < 13)
    private val storagePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* result handled in requestExportWithPermission */ }

    private var pendingServer: Server? = null
    private var pendingAnnouncement: Announcement? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (SecurityChecker.isSnifferDetected(this)) {
            showSnack("⚠️ تم رصد تطبيق اعتراض الشبكة. تم رفض التشغيل.")
            return
        }

        initViews()
        setupRecyclerView()
        observeViewModel()
        bindVpnService()
        viewModel.loadServers()
        viewModel.loadAnnouncement()
        startNotificationPolling()

        // Handle .boykta file opened from external app (Telegram, Files, etc.)
        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    // ── Views ──────────────────────────────────────────────────────────────────

    private fun initViews() {
        rvServers  = findViewById(R.id.rvServers)
        btnUpdate  = findViewById(R.id.btnUpdate)
        tvStatus   = findViewById(R.id.tvStatus)
        dotStatus  = findViewById(R.id.dotStatus)
        layoutEmpty = findViewById(R.id.layoutEmpty)
        btnImport  = findViewById(R.id.btnImport)
        fabExport  = findViewById(R.id.fabExport)

        btnUpdate.setOnClickListener {
            viewModel.loadServers()
            viewModel.loadAnnouncement()
        }

        // Import: open file picker
        btnImport.setOnClickListener {
            importFileLauncher.launch("*/*")
        }

        // Export FAB: admin-mode form
        fabExport.setOnClickListener {
            ConfigExportDialog.newInstance()
                .show(supportFragmentManager, "export_dialog")
        }
    }

    private fun setupRecyclerView() {
        adapter = ServerAdapter(this)
        rvServers.layoutManager = LinearLayoutManager(this)
        rvServers.adapter = adapter
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.allServers.collectLatest { servers ->
                adapter.submitList(servers)
                layoutEmpty.visibility = if (servers.isEmpty()) View.VISIBLE else View.GONE
                rvServers.visibility   = if (servers.isEmpty()) View.GONE  else View.VISIBLE
            }
        }
        lifecycleScope.launch {
            viewModel.isLoading.collectLatest { loading ->
                btnUpdate.isEnabled = !loading
                btnUpdate.text = if (loading) "جارٍ التحميل…" else getString(R.string.update)
            }
        }
        lifecycleScope.launch {
            viewModel.error.collectLatest { it?.let { msg -> showSnack(msg) } }
        }
        lifecycleScope.launch {
            viewModel.announcement.collectLatest { pendingAnnouncement = it }
        }
    }

    // ── .boykta Intent handling ────────────────────────────────────────────────

    private fun handleIncomingIntent(intent: Intent?) {
        intent ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return

        // Validate extension when available
        val uriStr = uri.toString()
        val lastSegment = uri.lastPathSegment ?: ""
        if (!uriStr.endsWith(".boykta", ignoreCase = true) &&
            !lastSegment.endsWith(".boykta", ignoreCase = true) &&
            intent.type != "application/octet-stream") return

        handleImportUri(uri)
    }

    private fun handleImportUri(uri: Uri) {
        val config = BoykConfigManager.import(this, uri)
        if (config == null) {
            showSnack("❌ ملف غير صالح أو تالف — تأكد أنه .boykta أصلي")
            return
        }

        // Show confirmation — only name + expiry, never raw config
        ImportResultDialog.newInstance(config) {
            viewModel.importLocalServer(config)
            showSnack("✅ تم إضافة السيرفر: ${config.name}")
        }.show(supportFragmentManager, "import_confirm")
    }

    // ── ServerAdapter.Listener ─────────────────────────────────────────────────

    override fun onConnect(server: Server) {
        if (server.isExpired()) { showSnack("انتهت صلاحية هذا السيرفر"); return }
        if (SecurityChecker.isSnifferDetected(this)) {
            showSnack("⚠️ تم رصد تطبيق اعتراض. الاتصال مرفوض."); return
        }

        val announcement = pendingAnnouncement
        if (announcement != null) showAdThenConnect(server, announcement)
        else requestVpnPermissionAndConnect(server)
    }

    override fun onDisconnect() {
        vpnService?.stopVpn()
        adapter.setConnectedServer(null)
        updateStatusUi(connected = false, name = null)
    }

    // ── Ad flow ────────────────────────────────────────────────────────────────

    private fun showAdThenConnect(server: Server, announcement: Announcement) {
        val dialog = AdDialog.newInstance(announcement)
        dialog.onAdClosed = { requestVpnPermissionAndConnect(server) }
        dialog.show(supportFragmentManager, "ad_dialog")
    }

    // ── VPN permission & connection ────────────────────────────────────────────

    private fun requestVpnPermissionAndConnect(server: Server) {
        val permIntent = VpnService.prepare(this)
        if (permIntent != null) {
            pendingServer = server
            vpnPermLauncher.launch(permIntent)
        } else {
            startVpnConnection(server)
        }
    }

    private fun startVpnConnection(server: Server) {
        startForegroundService(
            Intent(this, BoykVpnService::class.java).apply {
                action = BoykVpnService.ACTION_CONNECT
            }
        )
        lifecycleScope.launch {
            delay(500)
            // For local servers, decrypt the URI before passing it
            val vlessUri = viewModel.resolveVlessUri(server)
            if (vlessUri == null) { showSnack("فشل فكّ تشفير كونفيغ السيرفر"); return@launch }
            val resolved = server.copy(config = vlessUri)
            vpnService?.connectToServer(resolved) ?: showSnack("فشل الاتصال بخدمة VPN")
        }
    }

    // ── VpnStateListener ──────────────────────────────────────────────────────

    override fun onConnected(serverName: String) = runOnUiThread {
        adapter.setConnectedServer(serverName)
        updateStatusUi(connected = true, name = serverName)
    }

    override fun onDisconnected() = runOnUiThread {
        adapter.setConnectedServer(null)
        updateStatusUi(connected = false, name = null)
    }

    override fun onError(message: String) = runOnUiThread { showSnack(message) }

    // ── UI helpers ─────────────────────────────────────────────────────────────

    private fun updateStatusUi(connected: Boolean, name: String?) {
        val color = if (connected) 0xFF00FF88.toInt() else 0xFFFF4444.toInt()
        dotStatus.setBackgroundColor(color)
        tvStatus.text = if (connected) "متصل${name?.let { " — $it" } ?: ""}" else "غير متصل"
        tvStatus.setTextColor(color)
    }

    private fun showSnack(msg: String) =
        Snackbar.make(rvServers, msg, Snackbar.LENGTH_LONG).show()

    // ── Notification polling ───────────────────────────────────────────────────

    private fun startNotificationPolling() {
        lifecycleScope.launch {
            while (isActive) {
                delay(60_000L)
                viewModel.checkNotification()
            }
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

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            vpnService?.removeListener(this)
            unbindService(connection)
        }
    }
}

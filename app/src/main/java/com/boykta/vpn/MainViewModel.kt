package com.boykta.vpn

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.boykta.vpn.api.ApiClient
import com.boykta.vpn.db.LocalDatabase
import com.boykta.vpn.db.toServer
import com.boykta.vpn.model.Announcement
import com.boykta.vpn.model.Server
import com.boykta.vpn.model.isExpired
import com.boykta.vpn.service.TrafficCounter
import com.boykta.vpn.service.VpnLogManager
import com.boykta.vpn.util.LatencyChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
) : AndroidViewModel(application) {

    private val api = ApiClient.service
    private val db  = LocalDatabase.get(application)
    private val dao = db.localServerDao()

    // ── Built-in test server (DEBUG builds only) ──────────────────────────────
    private val builtInTestServers: List<Server> = if (BuildConfig.DEBUG) {
        val cfg = debugTestServerConfig()
        if (cfg != null) listOf(cfg) else emptyList()
    } else {
        emptyList()
    }

    private fun debugTestServerConfig(): Server? {
        return try {
            val app = getApplication<Application>()
            val res = app.resources
            val pkgName = app.packageName
            fun str(name: String) = res.getString(res.getIdentifier(name, "string", pkgName))
            val uri  = str("debug_test_server_uri")
            val name = str("debug_test_server_name")
            if (uri.isBlank() || name.isBlank()) return null
            Server(
                id        = -999,
                name      = name,
                config    = uri,
                expiresAt = "2099-12-31T23:59:59.000Z",
                isActive  = true,
                protocol  = "vless",
                isLocked  = false,
            )
        } catch (_: Exception) { null }
    }

    // Remote servers from API
    private val _remoteServers = MutableStateFlow<List<Server>>(emptyList())

    // Local servers from Room
    private val _localServers: Flow<List<Server>> = dao.getAll().map { list ->
        list.filter { System.currentTimeMillis() < it.expiresAt }.map { it.toServer() }
    }

    /** Merged server list: local (top) + built-in test (DEBUG) + remote */
    val allServers: StateFlow<List<Server>> = combine(_localServers, _remoteServers) { local, remote ->
        local + builtInTestServers + remote
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val announcement  = MutableStateFlow<Announcement?>(null)
    val isLoading     = MutableStateFlow(false)
    val error         = MutableStateFlow<String?>(null)
    val pingMs        = MutableStateFlow<Long?>(null)
    val selectedServer= MutableStateFlow<Server?>(null)

    /** VPN log stream (replay 120 entries) */
    val vpnLogs: SharedFlow<String> = VpnLogManager.logs

    /** Real-time traffic stats from TrafficCounter */
    val trafficStats = TrafficCounter.stats

    private var lastNotifId = -1
    private var lastAnnouncementId = -1
    private var autoPingJob: Job? = null

    // ── Remote ────────────────────────────────────────────────────────────────

    fun loadServers() {
        viewModelScope.launch {
            isLoading.value = true
            error.value = null
            try {
                VpnLogManager.sys("Fetching server list…")
                val response = api.getServers()
                _remoteServers.value = response.servers.filter { !it.isExpired() }
                VpnLogManager.success("Loaded ${_remoteServers.value.size} remote servers")
            } catch (e: Exception) {
                error.value = "فشل تحميل السيرفرات: ${e.message}"
                VpnLogManager.error("Server fetch failed: ${e.message}")
            } finally {
                isLoading.value = false
            }
        }
    }

    fun loadAnnouncement() {
        viewModelScope.launch {
            try {
                val next = api.getActiveAnnouncement().announcement
                if (next != null && next.id != lastAnnouncementId) {
                    lastAnnouncementId = next.id
                    announcement.value = next
                }
            } catch (_: Exception) {}
        }
    }

    fun checkNotification() {
        viewModelScope.launch {
            try {
                val notif = api.getLatestNotification().notification ?: return@launch
                if (notif.id != lastNotifId) {
                    lastNotifId = notif.id
                    Toast.makeText(getApplication(), "[!] ${notif.title}: ${notif.message}", Toast.LENGTH_LONG).show()
                }
            } catch (_: Exception) {}
        }
    }

    // ── Auto Ping (every 1 s) ─────────────────────────────────────────────────

    fun startAutoPing() {
        if (autoPingJob?.isActive == true) return
        autoPingJob = viewModelScope.launch {
            while (isActive) {
                val ms = LatencyChecker.measureMs()
                pingMs.value = ms
                // 3 s interval — fast enough for live display, gentle on battery
                delay(3_000L)
            }
        }
    }

    fun stopAutoPing() { autoPingJob?.cancel(); autoPingJob = null }

    fun measurePing() {
        viewModelScope.launch { pingMs.value = LatencyChecker.measureMs() }
    }

    // ── Notifications polling ─────────────────────────────────────────────────

    fun startNotificationPolling() {
        viewModelScope.launch {
            while (isActive) {
                delay(60_000L)
                checkNotification()
                loadAnnouncement()
            }
        }
    }

    // ── Server selection ──────────────────────────────────────────────────────

    fun selectServer(server: Server) { selectedServer.value = server }

    override fun onCleared() {
        super.onCleared()
        stopAutoPing()
    }
}

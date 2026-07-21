package com.boykta.vpn

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.boykta.vpn.api.ApiClient
import com.boykta.vpn.config.BoykConfigManager
import com.boykta.vpn.db.LocalDatabase
import com.boykta.vpn.db.toServer
import com.boykta.vpn.model.Announcement
import com.boykta.vpn.model.Server
import com.boykta.vpn.model.isExpired
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

    // Remote servers from API
    private val _remoteServers = MutableStateFlow<List<Server>>(emptyList())

    // Local servers from Room
    private val _localServers: Flow<List<Server>> = dao.getAll().map { list ->
        list.filter { System.currentTimeMillis() < it.expiresAt }
            .map { it.toServer() }
    }

    /** Merged server list: local (top) + remote */
    val allServers: StateFlow<List<Server>> = combine(_localServers, _remoteServers) { local, remote ->
        local + remote
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val announcement  = MutableStateFlow<Announcement?>(null)
    val isLoading     = MutableStateFlow(false)
    val error         = MutableStateFlow<String?>(null)

    /** Live ping latency in ms, null = not yet measured, -1 = timeout */
    val pingMs = MutableStateFlow<Long?>(null)

    /** Currently selected/active server for the server card display */
    val selectedServer = MutableStateFlow<Server?>(null)

    /** VPN log stream forwarded from VpnLogManager (replay 120 entries) */
    val vpnLogs: SharedFlow<String> = VpnLogManager.logs

    private var lastNotifId = -1
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
                VpnLogManager.success("Loaded ${_remoteServers.value.size} servers")
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
                announcement.value = api.getActiveAnnouncement().announcement
            } catch (_: Exception) {}
        }
    }

    fun checkNotification() {
        viewModelScope.launch {
            try {
                val notif = api.getLatestNotification().notification ?: return@launch
                if (notif.id != lastNotifId) {
                    lastNotifId = notif.id
                    Toast.makeText(
                        getApplication(),
                        "🔔 ${notif.title}: ${notif.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } catch (_: Exception) {}
        }
    }

    // ── Auto Ping (every 1 second) ────────────────────────────────────────────

    /**
     * Starts a background coroutine that pings https://dns.google every 1000 ms
     * and emits the result into [pingMs]. Call once from MainActivity.
     */
    fun startAutoPing() {
        if (autoPingJob?.isActive == true) return
        autoPingJob = viewModelScope.launch {
            while (isActive) {
                val ms = LatencyChecker.measureMs()
                pingMs.value = ms
                delay(1_000L)
            }
        }
    }

    fun stopAutoPing() {
        autoPingJob?.cancel()
        autoPingJob = null
    }

    /** One-shot manual ping (used for initial measurement before loop starts) */
    fun measurePing() {
        viewModelScope.launch {
            pingMs.value = LatencyChecker.measureMs()
        }
    }

    // ── Notifications polling ──────────────────────────────────────────────────

    fun startNotificationPolling() {
        viewModelScope.launch {
            while (isActive) {
                delay(60_000L)
                checkNotification()
            }
        }
    }

    // ── Server selection ──────────────────────────────────────────────────────

    fun selectServer(server: Server) {
        selectedServer.value = server
    }

    override fun onCleared() {
        super.onCleared()
        stopAutoPing()
    }
}

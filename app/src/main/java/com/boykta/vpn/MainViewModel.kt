package com.boykta.vpn

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.boykta.vpn.api.ApiClient
import com.boykta.vpn.api.CryptoHelper
import com.boykta.vpn.config.BoykConfig
import com.boykta.vpn.config.BoykConfigManager
import com.boykta.vpn.db.LocalDatabase
import com.boykta.vpn.db.LocalServer
import com.boykta.vpn.db.toServer
import com.boykta.vpn.model.Announcement
import com.boykta.vpn.model.Server
import com.boykta.vpn.model.isExpired
import com.boykta.vpn.service.VpnLogManager
import com.boykta.vpn.util.LatencyChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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

    // ── Ping ──────────────────────────────────────────────────────────────────

    fun measurePing() {
        viewModelScope.launch {
            pingMs.value = null
            val latency = LatencyChecker.measureMs()
            pingMs.value = latency
            val label = if ((latency ?: -1) < 0) "timeout" else "${latency}ms"
            VpnLogManager.info("Ping dns.google → $label")
        }
    }

    // ── Local (.boykta import) ────────────────────────────────────────────────

    fun importLocalServer(config: BoykConfig) {
        viewModelScope.launch {
            val vlessUri   = BoykConfigManager.configToVlessUri(config)
            val encryptedUri = CryptoHelper.encrypt(vlessUri)
            val expiresAt  = System.currentTimeMillis() + config.expiresSeconds * 1_000L

            dao.insert(
                LocalServer(
                    displayName  = config.name,
                    encryptedUri = encryptedUri,
                    expiresAt    = expiresAt,
                )
            )
            dao.deleteExpired()
            VpnLogManager.success("Imported server: ${config.name}")

            // Show custom toast if the config has one
            if (config.customToast.isNotBlank()) {
                Toast.makeText(getApplication(), config.customToast, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Resolve the actual proxy URI before connecting.
     * - Remote servers: URI already in server.config (decrypted by API interceptor)
     * - Local servers: decrypt from Room DB
     */
    suspend fun resolveVlessUri(server: Server): String? {
        return if (server.config.isNotBlank()) {
            server.config
        } else {
            val encryptedUri = dao.getEncryptedUri(server.id) ?: return null
            try { CryptoHelper.decrypt(encryptedUri) } catch (_: Exception) { null }
        }
    }

    fun selectServer(server: Server) {
        selectedServer.value = server
    }
}

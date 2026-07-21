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

    // Local servers from Room (imported from .boykta files)
    private val _localServers: Flow<List<Server>> = dao.getAll().map { list ->
        list.filter { System.currentTimeMillis() < it.expiresAt }
            .map { it.toServer() }
    }

    /** Merged list shown in RecyclerView: local (top) + remote */
    val allServers: StateFlow<List<Server>> = combine(_localServers, _remoteServers) { local, remote ->
        local + remote
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val announcement  = MutableStateFlow<Announcement?>(null)
    val isLoading     = MutableStateFlow(false)
    val error         = MutableStateFlow<String?>(null)

    private var lastNotifId = -1

    // ── Remote ────────────────────────────────────────────────────────────────

    fun loadServers() {
        viewModelScope.launch {
            isLoading.value = true
            error.value = null
            try {
                val response = api.getServers()
                _remoteServers.value = response.servers.filter { !it.isExpired() }
            } catch (e: Exception) {
                error.value = "فشل تحميل السيرفرات: ${e.message}"
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

    // ── Local (.boykta import) ────────────────────────────────────────────────

    fun importLocalServer(config: BoykConfig) {
        viewModelScope.launch {
            // Encrypt the VLESS URI before storing (double-encrypted at rest)
            val vlessUri = BoykConfigManager.configToVlessUri(config)
            val encryptedUri = CryptoHelper.encrypt(vlessUri)
            val expiresAt = System.currentTimeMillis() + config.expiresHours * 3_600_000L

            dao.insert(
                LocalServer(
                    displayName  = config.name,
                    encryptedUri = encryptedUri,
                    expiresAt    = expiresAt,
                )
            )
            // Purge any stale entries
            dao.deleteExpired()
        }
    }

    /**
     * Resolve the actual VLESS URI for a server before connecting.
     * - Remote servers: URI is in server.config (decrypted by API interceptor)
     * - Local servers (id mapped to Room): decrypt encryptedUri from DB
     */
    suspend fun resolveVlessUri(server: Server): String? {
        // Local server ids are Room auto-generated (small positive ints)
        // Remote server ids come from PostgreSQL (could overlap — use a prefix convention)
        // Simple heuristic: if server.config is blank, it's local
        return if (server.config.isNotBlank()) {
            server.config
        } else {
            val encryptedUri = dao.getEncryptedUri(server.id) ?: return null
            try { CryptoHelper.decrypt(encryptedUri) } catch (_: Exception) { null }
        }
    }
}

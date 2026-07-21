package com.boykta.vpn.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.widget.doAfterTextChanged
import com.boykta.vpn.R
import com.boykta.vpn.config.BoykConfig
import com.boykta.vpn.config.BoykConfigManager
import com.boykta.vpn.api.CryptoHelper
import com.boykta.vpn.db.LocalDatabase
import com.boykta.vpn.db.LocalServer
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Admin-mode bottom sheet: create and export a .boykta config file.
 *
 * v3 additions:
 *  - CONNECT DIRECTLY button: saves config to local DB then connects immediately
 *  - Locked fields show [ENCRYPTED & LOCKED] when locked is ON
 *  - Shadowsocks protocol support
 *  - Duration unit selector
 */
class ConfigExportDialog : BottomSheetDialogFragment() {

    companion object {
        fun newInstance() = ConfigExportDialog()
    }

    /** Called when user taps "Connect Directly" with a valid config */
    var onConnectDirectly: ((BoykConfig) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_config_export, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val spinnerProtocol      = view.findViewById<Spinner>(R.id.spinnerProtocol)
        val spinnerDurUnit       = view.findViewById<Spinner>(R.id.spinnerDurUnit)
        val etName               = view.findViewById<TextInputEditText>(R.id.etConfigName)
        val etHost               = view.findViewById<TextInputEditText>(R.id.etHost)
        val etSni                = view.findViewById<TextInputEditText>(R.id.etSni)
        val etHostHeader         = view.findViewById<TextInputEditText>(R.id.etHostHeader)
        val etPort               = view.findViewById<TextInputEditText>(R.id.etPort)
        val etUuid               = view.findViewById<TextInputEditText>(R.id.etUuid)
        val etPath               = view.findViewById<TextInputEditText>(R.id.etPath)
        val etExpiry             = view.findViewById<TextInputEditText>(R.id.etExpiry)
        val etCustomToast        = view.findViewById<TextInputEditText>(R.id.etCustomToast)
        val etSsMethod           = view.findViewById<TextInputEditText>(R.id.etSsMethod)
        val rowSsMethod          = view.findViewById<View>(R.id.rowSsMethod)
        val switchLocked         = view.findViewById<Switch>(R.id.switchLocked)
        val tvLockStatus         = view.findViewById<TextView>(R.id.tvLockStatus)
        val btnExport            = view.findViewById<MaterialButton>(R.id.btnExport)
        val btnConnectDirectly   = view.findViewById<MaterialButton>(R.id.btnConnectDirectly)
        val btnCancel            = view.findViewById<MaterialButton>(R.id.btnCancel)

        // ── Spinners ──────────────────────────────────────────────────────────
        val protocols = listOf("VLESS", "VMess", "Trojan", "Shadowsocks")
        spinnerProtocol.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, protocols
        )
        spinnerProtocol.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                rowSsMethod.visibility = if (pos == 3) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val durUnits = listOf("ثواني", "دقائق", "ساعات", "أيام")
        spinnerDurUnit.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, durUnits
        )
        spinnerDurUnit.setSelection(2) // default: hours

        // ── Auto-fill SNI / Host Header ───────────────────────────────────────
        etHost.doAfterTextChanged { text ->
            if (etSni.text.isNullOrBlank()) etSni.setText(text)
            if (etHostHeader.text.isNullOrBlank()) etHostHeader.setText(text)
        }

        // ── Lock toggle — locked fields show [ENCRYPTED & LOCKED] ─────────────
        switchLocked.isChecked = true
        updateLockUi(etUuid, etSni, etHost, etPath, tvLockStatus, true)
        switchLocked.setOnCheckedChangeListener { _, checked ->
            updateLockUi(etUuid, etSni, etHost, etPath, tvLockStatus, checked)
        }

        btnCancel.setOnClickListener { dismiss() }

        // ── Export ────────────────────────────────────────────────────────────
        btnExport.setOnClickListener {
            buildConfig(
                etName, etHost, etSni, etHostHeader, etPort, etUuid, etPath,
                etExpiry, etCustomToast, etSsMethod, protocols, spinnerProtocol,
                spinnerDurUnit, switchLocked, view
            )?.let { config ->
                val file = BoykConfigManager.export(requireContext(), config)
                if (file != null) {
                    val lockTag = if (config.locked) "🔒 مشفر" else "🔓 مكشوف"
                    Toast.makeText(
                        requireContext(),
                        "✅ تم الحفظ ($lockTag): ${file.name}\nفي مجلد Downloads",
                        Toast.LENGTH_LONG
                    ).show()
                    dismiss()
                } else {
                    showError(view, "❌ فشل التصدير — تحقق من أذونات التخزين")
                }
            }
        }

        // ── Connect Directly ──────────────────────────────────────────────────
        btnConnectDirectly.setOnClickListener {
            buildConfig(
                etName, etHost, etSni, etHostHeader, etPort, etUuid, etPath,
                etExpiry, etCustomToast, etSsMethod, protocols, spinnerProtocol,
                spinnerDurUnit, switchLocked, view
            )?.let { config ->
                // Save to local DB so it appears in the server list
                val db = LocalDatabase.get(requireContext())
                CoroutineScope(Dispatchers.IO).launch {
                    val expiresAt = System.currentTimeMillis() + config.expiresSeconds * 1000L
                    val encryptedUri = CryptoHelper.encrypt(config.toProxyUri())
                    db.localServerDao().insert(
                        LocalServer(
                            displayName  = config.name,
                            encryptedUri = encryptedUri,
                            expiresAt    = expiresAt,
                        )
                    )
                }
                onConnectDirectly?.invoke(config)
                dismiss()
            }
        }
    }

    // ── Lock / Unlock sensitive fields ────────────────────────────────────────

    private fun updateLockUi(
        etUuid: TextInputEditText,
        etSni: TextInputEditText,
        etHost: TextInputEditText,
        etPath: TextInputEditText,
        tvLockStatus: TextView,
        locked: Boolean
    ) {
        if (locked) {
            tvLockStatus.text = "🔒 مشفر (Locked)"
            tvLockStatus.setTextColor(0xFF00F2FE.toInt())
            // Show lock hints on sensitive fields
            etUuid.hint = getString(R.string.locked_field)
            etSni.hint  = getString(R.string.locked_field)
        } else {
            tvLockStatus.text = "🔓 مكشوف (Unlocked)"
            tvLockStatus.setTextColor(0xFFFFCC00.toInt())
            etUuid.hint = "UUID / Password"
            etSni.hint  = "SNI (TLS Server Name)"
        }
    }

    // ── Config builder (shared by Export & Connect Directly) ──────────────────

    private fun buildConfig(
        etName: TextInputEditText,
        etHost: TextInputEditText,
        etSni: TextInputEditText,
        etHostHeader: TextInputEditText,
        etPort: TextInputEditText,
        etUuid: TextInputEditText,
        etPath: TextInputEditText,
        etExpiry: TextInputEditText,
        etCustomToast: TextInputEditText,
        etSsMethod: TextInputEditText,
        protocols: List<String>,
        spinnerProtocol: Spinner,
        spinnerDurUnit: Spinner,
        switchLocked: Switch,
        view: View
    ): BoykConfig? {
        val name        = etName.text?.toString()?.trim() ?: ""
        val host        = etHost.text?.toString()?.trim() ?: ""
        val sni         = etSni.text?.toString()?.trim().orEmpty()
        val hostHeader  = etHostHeader.text?.toString()?.trim().orEmpty()
        val portStr     = etPort.text?.toString()?.trim() ?: ""
        val uuid        = etUuid.text?.toString()?.trim() ?: ""
        val path        = etPath.text?.toString()?.trim()?.ifBlank { "/" } ?: "/"
        val expiryStr   = etExpiry.text?.toString()?.trim() ?: "24"
        val customToast = etCustomToast.text?.toString()?.trim().orEmpty()
        val ssMethod    = etSsMethod.text?.toString()?.trim()?.ifBlank { "aes-256-gcm" } ?: "aes-256-gcm"
        val protocol    = protocols[spinnerProtocol.selectedItemPosition].lowercase()
        val locked      = switchLocked.isChecked

        val durValue = expiryStr.toLongOrNull() ?: 24L
        val durMultiplier = when (spinnerDurUnit.selectedItemPosition) {
            0 -> 1L; 1 -> 60L; 2 -> 3_600L; 3 -> 86_400L; else -> 3_600L
        }
        val expiresSeconds = durValue * durMultiplier

        if (name.isBlank())  { showError(view, "أدخل اسم السيرفر"); return null }
        if (host.isBlank())  { showError(view, "أدخل عنوان السيرفر"); return null }
        if (uuid.isBlank())  { showError(view, "أدخل UUID / Password"); return null }
        val port = portStr.toIntOrNull() ?: 0
        if (port <= 0)       { showError(view, "أدخل port صحيح"); return null }

        return BoykConfig(
            protocol       = protocol,
            name           = name,
            uuid           = uuid,
            host           = host,
            sni            = sni.ifBlank { host },
            hostHeader     = hostHeader.ifBlank { host },
            port           = port,
            path           = path,
            expiresSeconds = expiresSeconds,
            locked         = locked,
            customToast    = customToast,
            ssMethod       = ssMethod,
        )
    }

    private fun showError(view: View, msg: String) {
        Snackbar.make(view, msg, Snackbar.LENGTH_SHORT).show()
    }
}

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
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText

/**
 * Admin-mode bottom sheet: create and export a .boykta config file.
 *
 * Features added in v2:
 *  - Shadowsocks protocol support
 *  - Locked (AES-256-GCM) vs Unlocked (plain JSON) export toggle
 *  - Duration unit selector: seconds / minutes / hours / days
 *  - Custom on-connect banner/toast message
 */
class ConfigExportDialog : BottomSheetDialogFragment() {

    companion object {
        fun newInstance() = ConfigExportDialog()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_config_export, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val spinnerProtocol  = view.findViewById<Spinner>(R.id.spinnerProtocol)
        val spinnerDurUnit   = view.findViewById<Spinner>(R.id.spinnerDurUnit)
        val etName           = view.findViewById<TextInputEditText>(R.id.etConfigName)
        val etHost           = view.findViewById<TextInputEditText>(R.id.etHost)
        val etSni            = view.findViewById<TextInputEditText>(R.id.etSni)
        val etHostHeader     = view.findViewById<TextInputEditText>(R.id.etHostHeader)
        val etPort           = view.findViewById<TextInputEditText>(R.id.etPort)
        val etUuid           = view.findViewById<TextInputEditText>(R.id.etUuid)
        val etPath           = view.findViewById<TextInputEditText>(R.id.etPath)
        val etExpiry         = view.findViewById<TextInputEditText>(R.id.etExpiry)
        val etCustomToast    = view.findViewById<TextInputEditText>(R.id.etCustomToast)
        val etSsMethod       = view.findViewById<TextInputEditText>(R.id.etSsMethod)
        val rowSsMethod      = view.findViewById<View>(R.id.rowSsMethod)
        val switchLocked     = view.findViewById<Switch>(R.id.switchLocked)
        val tvLockStatus     = view.findViewById<TextView>(R.id.tvLockStatus)
        val btnExport        = view.findViewById<MaterialButton>(R.id.btnExport)
        val btnCancel        = view.findViewById<MaterialButton>(R.id.btnCancel)

        // ── Spinners ──────────────────────────────────────────────────────────
        val protocols = listOf("VLESS", "VMess", "Trojan", "Shadowsocks")
        spinnerProtocol.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, protocols
        )
        spinnerProtocol.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                // Show SS cipher field only for Shadowsocks
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

        // ── Lock toggle ───────────────────────────────────────────────────────
        switchLocked.isChecked = true
        switchLocked.setOnCheckedChangeListener { _, checked ->
            tvLockStatus.text = if (checked) "🔒 مشفر (Locked)" else "🔓 مكشوف (Unlocked)"
            tvLockStatus.setTextColor(
                if (checked) 0xFF00F2FE.toInt() else 0xFFFFCC00.toInt()
            )
        }

        btnCancel.setOnClickListener { dismiss() }

        // ── Export ────────────────────────────────────────────────────────────
        btnExport.setOnClickListener {
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

            // Duration conversion → seconds
            val durValue = expiryStr.toLongOrNull() ?: 24L
            val durMultiplier = when (spinnerDurUnit.selectedItemPosition) {
                0 -> 1L          // seconds
                1 -> 60L         // minutes
                2 -> 3_600L      // hours
                3 -> 86_400L     // days
                else -> 3_600L
            }
            val expiresSeconds = durValue * durMultiplier

            // Validation
            if (name.isBlank())  { showError(view, "أدخل اسم السيرفر"); return@setOnClickListener }
            if (host.isBlank())  { showError(view, "أدخل عنوان السيرفر"); return@setOnClickListener }
            if (uuid.isBlank())  { showError(view, "أدخل UUID / Password"); return@setOnClickListener }
            val port = portStr.toIntOrNull() ?: 0
            if (port <= 0)       { showError(view, "أدخل port صحيح"); return@setOnClickListener }

            val config = BoykConfig(
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

            val file = BoykConfigManager.export(requireContext(), config)
            if (file != null) {
                val lockTag = if (locked) "🔒 مشفر" else "🔓 مكشوف"
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

    private fun showError(view: View, msg: String) {
        Snackbar.make(view, msg, Snackbar.LENGTH_SHORT).show()
    }
}

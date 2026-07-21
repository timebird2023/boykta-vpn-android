package com.boykta.vpn.ui

import android.app.Dialog
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
import com.google.android.material.textfield.TextInputLayout

/**
 * Admin-mode bottom sheet for creating and exporting a .boykta config file.
 * All fields map to BoykConfig. The user never sees the raw VLESS URI.
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

        val spinnerProtocol = view.findViewById<Spinner>(R.id.spinnerProtocol)
        val etName         = view.findViewById<TextInputEditText>(R.id.etConfigName)
        val etHost         = view.findViewById<TextInputEditText>(R.id.etHost)
        val etSni          = view.findViewById<TextInputEditText>(R.id.etSni)
        val etHostHeader   = view.findViewById<TextInputEditText>(R.id.etHostHeader)
        val etPort         = view.findViewById<TextInputEditText>(R.id.etPort)
        val etUuid         = view.findViewById<TextInputEditText>(R.id.etUuid)
        val etPath         = view.findViewById<TextInputEditText>(R.id.etPath)
        val etExpiry       = view.findViewById<TextInputEditText>(R.id.etExpiry)
        val btnExport      = view.findViewById<MaterialButton>(R.id.btnExport)
        val btnCancel      = view.findViewById<MaterialButton>(R.id.btnCancel)

        // Protocol spinner
        val protocols = listOf("VLESS", "VMess", "Trojan")
        spinnerProtocol.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            protocols
        )

        // Auto-fill SNI from host
        etHost.doAfterTextChanged { text ->
            if (etSni.text.isNullOrBlank()) etSni.setText(text)
            if (etHostHeader.text.isNullOrBlank()) etHostHeader.setText(text)
        }

        btnCancel.setOnClickListener { dismiss() }

        btnExport.setOnClickListener {
            val name       = etName.text?.toString()?.trim() ?: ""
            val host       = etHost.text?.toString()?.trim() ?: ""
            val sni        = etSni.text?.toString()?.trim().orEmpty()
            val hostHeader = etHostHeader.text?.toString()?.trim().orEmpty()
            val portStr    = etPort.text?.toString()?.trim() ?: ""
            val uuid       = etUuid.text?.toString()?.trim() ?: ""
            val path       = etPath.text?.toString()?.trim()?.ifBlank { "/" } ?: "/"
            val expiryStr  = etExpiry.text?.toString()?.trim() ?: "24"
            val protocol   = protocols[spinnerProtocol.selectedItemPosition].lowercase()

            // Validation
            if (name.isBlank())  { showError(view, "أدخل اسم السيرفر"); return@setOnClickListener }
            if (host.isBlank())  { showError(view, "أدخل عنوان السيرفر"); return@setOnClickListener }
            if (uuid.isBlank())  { showError(view, "أدخل UUID / Password"); return@setOnClickListener }
            val port = portStr.toIntOrNull() ?: 0
            if (port <= 0)       { showError(view, "أدخل port صحيح"); return@setOnClickListener }
            val expiry = expiryStr.toLongOrNull() ?: 24L

            val config = BoykConfig(
                protocol    = protocol,
                name        = name,
                uuid        = uuid,
                host        = host,
                sni         = sni.ifBlank { host },
                hostHeader  = hostHeader.ifBlank { host },
                port        = port,
                path        = path,
                expiresHours = expiry,
            )

            val file = BoykConfigManager.export(requireContext(), config)
            if (file != null) {
                Toast.makeText(
                    requireContext(),
                    "✅ تم الحفظ: ${file.name}\nفي مجلد Downloads",
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

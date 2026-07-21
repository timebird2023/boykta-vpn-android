package com.boykta.vpn.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.boykta.vpn.R
import com.boykta.vpn.config.BoykConfig
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton

/**
 * Confirmation dialog shown after successfully parsing a .boykta file.
 * Only shows [ServerName] + expiry duration — raw config is NEVER displayed.
 */
class ImportResultDialog : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_NAME   = "name"
        private const val ARG_EXPIRY = "expiry"

        fun newInstance(config: BoykConfig, onConfirm: () -> Unit): ImportResultDialog {
            return ImportResultDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_NAME, config.name)
                    putLong(ARG_EXPIRY, config.expiresHours)
                }
                this.onConfirm = onConfirm
            }
        }
    }

    var onConfirm: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_import_result, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val name   = arguments?.getString(ARG_NAME) ?: ""
        val expiry = arguments?.getLong(ARG_EXPIRY) ?: 24L

        view.findViewById<TextView>(R.id.tvImportName).text = name
        view.findViewById<TextView>(R.id.tvImportExpiry).text = "ينتهي خلال $expiry ساعة"

        view.findViewById<MaterialButton>(R.id.btnConfirmImport).setOnClickListener {
            onConfirm?.invoke()
            dismiss()
        }
        view.findViewById<MaterialButton>(R.id.btnCancelImport).setOnClickListener {
            dismiss()
        }
    }
}

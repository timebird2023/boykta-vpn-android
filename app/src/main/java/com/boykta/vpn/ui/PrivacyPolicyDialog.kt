package com.boykta.vpn.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.boykta.vpn.R
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton

/**
 * Bottom sheet that displays Privacy Policy & Terms of Use for Boykta VPN.
 */
class PrivacyPolicyDialog : BottomSheetDialogFragment() {

    companion object {
        fun newInstance() = PrivacyPolicyDialog()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_privacy_policy, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<MaterialButton>(R.id.btnClose)?.setOnClickListener { dismiss() }
    }
}

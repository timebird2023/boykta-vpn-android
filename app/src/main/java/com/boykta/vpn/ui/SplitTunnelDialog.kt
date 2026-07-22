package com.boykta.vpn.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.boykta.vpn.R
import com.boykta.vpn.util.SplitTunnelManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.*

/**
 * Bottom sheet showing installed user apps with toggle switches
 * to bypass the VPN tunnel (split-tunneling).
 */
class SplitTunnelDialog : BottomSheetDialogFragment() {

    companion object {
        fun newInstance() = SplitTunnelDialog()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.dialog_split_tunnel, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val listView   = view.findViewById<ListView>(R.id.listSplitTunnel)
        val progressBar= view.findViewById<ProgressBar>(R.id.progressSplitTunnel)
        val tvTitle    = view.findViewById<TextView>(R.id.tvSplitTunnelTitle)
        val btnClose   = view.findViewById<View>(R.id.btnSplitTunnelClose)

        tvTitle.text = "Split Tunneling — تجاوز VPN"
        btnClose.setOnClickListener { dismiss() }

        progressBar.visibility = View.VISIBLE
        listView.visibility    = View.GONE

        val ctx = requireContext()
        CoroutineScope(Dispatchers.IO).launch {
            val apps = SplitTunnelManager.getAllApps(ctx)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                progressBar.visibility = View.GONE
                listView.visibility    = View.VISIBLE

                val labels   = apps.map { it.label }.toTypedArray()
                val bypassed = apps.map { it.isBypassed }.toBooleanArray()

                // Simple ArrayAdapter with checkboxes
                val adapter = object : ArrayAdapter<String>(
                    ctx, android.R.layout.simple_list_item_multiple_choice, labels
                ) {}
                listView.adapter       = adapter
                listView.choiceMode    = ListView.CHOICE_MODE_MULTIPLE

                apps.forEachIndexed { i, app ->
                    listView.setItemChecked(i, app.isBypassed)
                }

                listView.setOnItemClickListener { _, _, position, _ ->
                    val pkg = apps[position].packageName
                    SplitTunnelManager.toggleBypass(ctx, pkg)
                    bypassed[position] = !bypassed[position]
                    val count = SplitTunnelManager.getBypassed(ctx).size
                    tvTitle.text = "Split Tunneling ($count تطبيق متجاوز)"
                }
            }
        }
    }
}

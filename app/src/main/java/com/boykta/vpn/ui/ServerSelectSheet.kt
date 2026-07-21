package com.boykta.vpn.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.boykta.vpn.R
import com.boykta.vpn.model.Server
import com.boykta.vpn.model.formattedRemaining
import com.boykta.vpn.model.protocolLabel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.textfield.TextInputEditText

/**
 * Bottom sheet for selecting the active server.
 * Shows a search bar + radio-style list. Tapping a server auto-dismisses the sheet.
 */
class ServerSelectSheet : BottomSheetDialogFragment() {

    companion object {
        fun newInstance(
            servers: List<Server>,
            selectedId: Int?,
            onSelected: (Server) -> Unit
        ) = ServerSelectSheet().also { sheet ->
            sheet.servers = servers
            sheet.selectedId = selectedId
            sheet.onSelected = onSelected
        }
    }

    var servers: List<Server> = emptyList()
    var selectedId: Int? = null
    var onSelected: ((Server) -> Unit)? = null

    private var filtered: List<Server> = emptyList()
    private lateinit var listAdapter: SheetAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_server_select, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        filtered = servers
        listAdapter = SheetAdapter(filtered, selectedId) { server ->
            onSelected?.invoke(server)
            dismiss()
        }

        val rv = view.findViewById<RecyclerView>(R.id.rvServerList)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = listAdapter

        val etSearch = view.findViewById<TextInputEditText>(R.id.etSearch)
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim().lowercase()
                filtered = if (query.isEmpty()) servers
                           else servers.filter { it.name.lowercase().contains(query) }
                listAdapter.update(filtered)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    // ── Inner adapter ──────────────────────────────────────────────────────────

    private inner class SheetAdapter(
        private var list: List<Server>,
        private var activeId: Int?,
        private val onClick: (Server) -> Unit
    ) : RecyclerView.Adapter<SheetAdapter.VH>() {

        fun update(newList: List<Server>) {
            list = newList
            notifyDataSetChanged()
        }

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvServerName)
            val tvProtocol: TextView = view.findViewById(R.id.tvServerProtocol)
            val tvExpiry: TextView = view.findViewById(R.id.tvServerExpiry)
            val tvCheck: TextView = view.findViewById(R.id.tvCheck)
            val tvFlag: TextView = view.findViewById(R.id.tvFlag)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_server_select, parent, false)
            return VH(v)
        }

        override fun getItemCount() = list.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val server = list[position]
            val isSelected = server.id == activeId

            holder.tvName.text = server.name
            holder.tvProtocol.text = server.protocolLabel()
            holder.tvExpiry.text = server.formattedRemaining()
            holder.tvCheck.visibility = if (isSelected) View.VISIBLE else View.GONE

            // Simple country flag from name
            holder.tvFlag.text = flagEmoji(server.name)

            // Highlight selected item
            holder.itemView.alpha = if (isSelected) 1f else 0.85f
            holder.tvName.setTextColor(
                if (isSelected) 0xFF00F2FE.toInt() else 0xFFFFFFFF.toInt()
            )

            holder.itemView.setOnClickListener {
                activeId = server.id
                notifyDataSetChanged()
                onClick(server)
            }
        }

        private fun flagEmoji(name: String): String {
            val n = name.lowercase()
            return when {
                n.contains("germany") || n.contains("de") || n.contains("deutsch") -> "🇩🇪"
                n.contains("usa") || n.contains("us ") || n.contains("united states") -> "🇺🇸"
                n.contains("uk") || n.contains("united kingdom") || n.contains("britain") -> "🇬🇧"
                n.contains("france") || n.contains("fr ") -> "🇫🇷"
                n.contains("netherlands") || n.contains("nl") || n.contains("dutch") -> "🇳🇱"
                n.contains("japan") || n.contains("jp") -> "🇯🇵"
                n.contains("singapore") || n.contains("sg") -> "🇸🇬"
                n.contains("turkey") || n.contains("tr ") -> "🇹🇷"
                n.contains("russia") || n.contains("ru ") -> "🇷🇺"
                n.contains("canada") || n.contains("ca ") -> "🇨🇦"
                n.contains("australia") || n.contains("au ") -> "🇦🇺"
                n.contains("iran") || n.contains("ir ") -> "🇮🇷"
                n.contains("uae") || n.contains("dubai") -> "🇦🇪"
                n.contains("sweden") || n.contains("se ") -> "🇸🇪"
                n.contains("finland") || n.contains("fi ") -> "🇫🇮"
                n.contains("poland") || n.contains("pl ") -> "🇵🇱"
                else -> "🌐"
            }
        }
    }
}

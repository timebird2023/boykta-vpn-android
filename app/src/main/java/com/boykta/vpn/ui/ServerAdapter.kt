package com.boykta.vpn.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.boykta.vpn.R
import com.boykta.vpn.model.Server
import com.boykta.vpn.model.formattedRemaining
import com.boykta.vpn.model.isExpired
import com.boykta.vpn.model.protocolLabel
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*

class ServerAdapter(private val listener: Listener) :
    ListAdapter<Server, ServerAdapter.VH>(DIFF) {

    interface Listener {
        fun onConnect(server: Server)
        fun onDisconnect()
    }

    private var connectedServerName: String? = null

    fun setConnectedServer(name: String?) {
        connectedServerName = name
        notifyDataSetChanged()
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Server>() {
            override fun areItemsTheSame(a: Server, b: Server) = a.id == b.id
            override fun areContentsTheSame(a: Server, b: Server) = a == b
        }
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView        = view.findViewById(R.id.tvName)
        val tvCountdown: TextView   = view.findViewById(R.id.tvCountdown)
        val tvProtocol: TextView    = view.findViewById(R.id.tvProtocolBadge)
        val btnConnect: MaterialButton = view.findViewById(R.id.btnConnect)
        val dotStatus: View         = view.findViewById(R.id.dotStatus)

        private var countdownJob: Job? = null

        fun bind(server: Server) {
            val isConnected = server.name == connectedServerName

            // Name — never show raw config/URI
            tvName.text = server.name

            // Protocol badge
            tvProtocol.text = server.protocolLabel()

            // Countdown ticker
            countdownJob?.cancel()
            countdownJob = CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
                while (isActive) {
                    tvCountdown.text = server.formattedRemaining()
                    delay(1_000L)
                }
            }

            // Status dot
            val dotColor = when {
                isConnected      -> 0xFF00F2FE.toInt()
                server.isExpired() -> 0xFFFF0055.toInt()
                else             -> 0xFF3A4A6A.toInt()
            }
            dotStatus.setBackgroundColor(dotColor)

            // Connect / Disconnect button
            if (isConnected) {
                btnConnect.text = itemView.context.getString(R.string.disconnect)
                btnConnect.setBackgroundColor(0xFFFF0055.toInt())
                btnConnect.setTextColor(0xFFFFFFFF.toInt())
                btnConnect.setOnClickListener { listener.onDisconnect() }
            } else {
                btnConnect.text = itemView.context.getString(R.string.connect)
                btnConnect.setBackgroundColor(0xFF00F2FE.toInt())
                btnConnect.setTextColor(0xFF000000.toInt())
                btnConnect.isEnabled = !server.isExpired()
                btnConnect.alpha = if (server.isExpired()) 0.35f else 1f
                btnConnect.setOnClickListener { listener.onConnect(server) }
            }
        }

        fun recycle() { countdownJob?.cancel() }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_server, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) { holder.bind(getItem(position)) }
    override fun onViewRecycled(holder: VH) { super.onViewRecycled(holder); holder.recycle() }
}

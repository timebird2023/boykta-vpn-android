package com.boykta.vpn.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.boykta.vpn.R

/**
 * Terminal-style log adapter for the real-time VPN log viewer.
 * Keeps at most MAX_LINES entries to avoid unbounded memory growth.
 */
class LogAdapter : RecyclerView.Adapter<LogAdapter.VH>() {

    companion object { private const val MAX_LINES = 300 }

    private val lines = ArrayDeque<String>(MAX_LINES)

    fun addLine(line: String) {
        if (lines.size >= MAX_LINES) lines.removeFirst()
        lines.addLast(line)
        notifyItemInserted(lines.size - 1)
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tv: TextView = view.findViewById(R.id.tvLogLine)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log_line, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val line = lines[position]
        holder.tv.text = line
        // Color-code by prefix
        val color = when {
            line.contains("✅") -> 0xFF00F2FE.toInt()
            line.contains("❌") -> 0xFFFF0055.toInt()
            line.contains("⚠")  -> 0xFFFFCC00.toInt()
            line.contains("⚙")  -> 0xFF8888AA.toInt()
            else                 -> 0xFFCCCCCC.toInt()
        }
        holder.tv.setTextColor(color)
    }

    override fun getItemCount() = lines.size
}

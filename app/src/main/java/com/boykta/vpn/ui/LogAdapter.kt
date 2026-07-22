package com.boykta.vpn.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.boykta.vpn.R

/**
 * Terminal-style log adapter for the real-time VPN log viewer.
 *
 * Color coding by structured log level prefix:
 *   [OK]   — Electric Cyan  #00F2FE  (successful milestones)
 *   [ERR]  — Neon Red       #FF0055  (errors)
 *   [WARN] — Amber          #FFCC00  (warnings)
 *   [SYS]  — Grey-Blue      #8888AA  (system/lifecycle)
 *   [DEV]  — Steel Blue     #6B9AC4  (device/environment)
 *   [INFO] — White          #CCCCCC  (informational)
 *
 * Keeps at most MAX_LINES entries to avoid unbounded memory growth.
 * UI updates batched: notifyItemInserted only (no full rebinds).
 */
class LogAdapter : RecyclerView.Adapter<LogAdapter.VH>() {

    companion object {
        private const val MAX_LINES = 250   // cap log lines for smooth 60fps

        // ARGB colors (fully opaque)
        private const val COLOR_OK   = 0xFF00F2FE.toInt()   // cyan
        private const val COLOR_ERR  = 0xFFFF0055.toInt()   // neon red
        private const val COLOR_WARN = 0xFFFFCC00.toInt()   // amber
        private const val COLOR_SYS  = 0xFF8888AA.toInt()   // grey-blue
        private const val COLOR_DEV  = 0xFF6B9AC4.toInt()   // steel-blue
        private const val COLOR_INFO = 0xFFCCCCCC.toInt()   // white-ish
    }

    private val lines = ArrayDeque<String>(MAX_LINES)

    fun addLine(line: String) {
        if (lines.size >= MAX_LINES) {
            lines.removeFirst()
            notifyItemRemoved(0)
        }
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
        holder.tv.setTextColor(lineColor(line))
    }

    private fun lineColor(line: String): Int = when {
        line.contains("[OK]")   -> COLOR_OK
        line.contains("[ERR]")  -> COLOR_ERR
        line.contains("[WARN]") -> COLOR_WARN
        line.contains("[SYS]")  -> COLOR_SYS
        line.contains("[DEV]")  -> COLOR_DEV
        else                    -> COLOR_INFO
    }

    override fun getItemCount() = lines.size
}

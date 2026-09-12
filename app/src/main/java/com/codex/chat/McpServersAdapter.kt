package com.codex.chat

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView
import com.codex.chat.core.mcp.model.McpServerInfo
import com.codex.chat.core.mcp.model.McpServerType

class McpServersAdapter(
    private var servers: List<McpServerInfo>,
    private val onToggle: (McpServerInfo, Boolean) -> Unit,
    private val onViewTools: (McpServerInfo) -> Unit
) : RecyclerView.Adapter<McpServersAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvIcon: TextView = view.findViewById(R.id.tvMcpServerIcon)
        val tvName: TextView = view.findViewById(R.id.tvMcpServerName)
        val tvBadge: TextView = view.findViewById(R.id.tvMcpServerBadge)
        val tvDesc: TextView = view.findViewById(R.id.tvMcpServerDesc)
        val tvToolsCount: TextView = view.findViewById(R.id.tvMcpToolsCount)
        val switchToggle: SwitchCompat = view.findViewById(R.id.switchMcpServer)
        val btnViewTools: TextView = view.findViewById(R.id.btnViewMcpTools)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_mcp_server, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val server = servers[position]

        holder.tvIcon.text = server.iconEmoji
        holder.tvName.text = server.name
        holder.tvDesc.text = server.description
        holder.tvToolsCount.text = "⚡ ${server.toolsCount} herramientas disponibles"

        if (server.type == McpServerType.NATIVE) {
            holder.tvBadge.text = "NATIVO"
            holder.tvBadge.setTextColor(Color.parseColor("#10A37F"))
            holder.tvBadge.setBackgroundColor(Color.parseColor("#152B20"))
        } else {
            holder.tvBadge.text = "REMOTO HTTP"
            holder.tvBadge.setTextColor(Color.parseColor("#79C0FF"))
            holder.tvBadge.setBackgroundColor(Color.parseColor("#172433"))
        }

        // Avoid triggering listener during bind
        holder.switchToggle.setOnCheckedChangeListener(null)
        holder.switchToggle.isChecked = server.isEnabled
        holder.switchToggle.setOnCheckedChangeListener { _, isChecked ->
            server.isEnabled = isChecked
            onToggle(server, isChecked)
        }

        holder.btnViewTools.setOnClickListener {
            onViewTools(server)
        }
    }

    override fun getItemCount(): Int = servers.size

    fun updateData(newServers: List<McpServerInfo>) {
        servers = newServers
        notifyDataSetChanged()
    }
}

package com.codex.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.codex.chat.core.model.SlashCommandInfo

class SlashCommandsAdapter(
    private var commands: List<SlashCommandInfo>,
    private val onSelected: (SlashCommandInfo) -> Unit
) : RecyclerView.Adapter<SlashCommandsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvIcon: TextView = view.findViewById(R.id.tvSlashIcon)
        val tvCommand: TextView = view.findViewById(R.id.tvSlashCommand)
        val tvDesc: TextView = view.findViewById(R.id.tvSlashDescription)
        val tvBadge: TextView = view.findViewById(R.id.tvSlashBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_slash_command, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = commands[position]
        holder.tvIcon.text = item.iconEmoji
        holder.tvCommand.text = item.command
        holder.tvDesc.text = item.description
        holder.tvBadge.text = item.badge

        holder.itemView.setOnClickListener {
            onSelected(item)
        }
    }

    override fun getItemCount(): Int = commands.size

    fun updateData(newCommands: List<SlashCommandInfo>) {
        commands = newCommands
        notifyDataSetChanged()
    }
}

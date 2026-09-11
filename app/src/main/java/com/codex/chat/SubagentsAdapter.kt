package com.codex.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.codex.chat.core.model.SubagentInfo

class SubagentsAdapter(
    private val subagents: List<SubagentInfo>,
    private val onSelected: (SubagentInfo) -> Unit
) : RecyclerView.Adapter<SubagentsAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvIcon: TextView = itemView.findViewById(R.id.tvSubagentIcon)
        val tvName: TextView = itemView.findViewById(R.id.tvSubagentName)
        val tvEffort: TextView = itemView.findViewById(R.id.tvSubagentEffort)
        val tvDescription: TextView = itemView.findViewById(R.id.tvSubagentDescription)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_subagent, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = subagents[position]
        holder.tvIcon.text = item.iconEmoji
        holder.tvName.text = item.name
        holder.tvEffort.text = item.reasoningEffort.value.uppercase()
        holder.tvDescription.text = item.description

        holder.itemView.setOnClickListener {
            onSelected(item)
        }
    }

    override fun getItemCount(): Int = subagents.size
}

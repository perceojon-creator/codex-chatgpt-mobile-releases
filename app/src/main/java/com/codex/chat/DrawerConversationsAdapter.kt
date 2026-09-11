package com.codex.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

data class RemoteConversation(
    val threadId: String,
    val title: String,
    val dateFormatted: String,
    val cwd: String,
    val provider: String
)

class DrawerConversationsAdapter(
    private val items: MutableList<RemoteConversation>,
    private val onItemClick: (RemoteConversation) -> Unit
) : RecyclerView.Adapter<DrawerConversationsAdapter.ViewHolder>() {

    private var selectedThreadId: String? = null

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvConvTitle)
        val tvDate: TextView = view.findViewById(R.id.tvConvDate)
        val tvCwd: TextView = view.findViewById(R.id.tvConvCwd)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_drawer_conversation, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvTitle.text = item.title
        holder.tvDate.text = item.dateFormatted
        
        if (item.cwd.isNotEmpty()) {
            val folderName = item.cwd.split("\\", "/").lastOrNull() ?: item.cwd
            holder.tvCwd.visibility = View.VISIBLE
            holder.tvCwd.text = "📁 " + folderName
        } else {
            holder.tvCwd.visibility = View.GONE
        }

        val isSelected = item.threadId == selectedThreadId
        holder.itemView.setBackgroundColor(
            if (isSelected) 0xFF2A2A2A.toInt() else 0x00000000
        )

        holder.itemView.setOnClickListener {
            selectedThreadId = item.threadId
            notifyDataSetChanged()
            onItemClick(item)
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateData(newItems: List<RemoteConversation>, activeId: String? = null) {
        items.clear()
        items.addAll(newItems)
        selectedThreadId = activeId
        notifyDataSetChanged()
    }
}

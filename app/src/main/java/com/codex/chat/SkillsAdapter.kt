package com.codex.chat

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.codex.chat.core.model.SkillInfo

class SkillsAdapter(
    private var skills: List<SkillInfo>,
    private var activeSkillId: String?,
    private val onToggle: (SkillInfo) -> Unit,
    private val onDetails: (SkillInfo) -> Unit,
    private val onDelete: (SkillInfo) -> Unit
) : RecyclerView.Adapter<SkillsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvIcon: TextView = view.findViewById(R.id.tvSkillIcon)
        val tvName: TextView = view.findViewById(R.id.tvSkillName)
        val tvCategory: TextView = view.findViewById(R.id.tvSkillCategory)
        val tvAuthor: TextView = view.findViewById(R.id.tvSkillAuthor)
        val tvDescription: TextView = view.findViewById(R.id.tvSkillDescription)
        val tvActiveBadge: TextView = view.findViewById(R.id.tvSkillActiveBadge)
        val btnToggle: TextView = view.findViewById(R.id.btnToggleSkill)
        val btnDetails: TextView = view.findViewById(R.id.btnViewSkillDetails)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDeleteSkill)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_skill_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val skill = skills[position]
        val isActive = skill.id == activeSkillId

        holder.tvIcon.text = skill.iconEmoji
        holder.tvName.text = skill.name
        holder.tvCategory.text = skill.category
        holder.tvAuthor.text = skill.author
        holder.tvDescription.text = skill.description

        if (isActive) {
            holder.tvActiveBadge.visibility = View.VISIBLE
            holder.btnToggle.text = "✓ Activa"
            holder.btnToggle.setBackgroundColor(Color.parseColor("#10A37F"))
            holder.btnToggle.setTextColor(Color.WHITE)
        } else {
            holder.tvActiveBadge.visibility = View.GONE
            holder.btnToggle.text = "⚡ Activar"
            holder.btnToggle.setBackgroundColor(Color.parseColor("#2E3832"))
            holder.btnToggle.setTextColor(Color.parseColor("#ECECEC"))
        }

        if (skill.isCustom) {
            holder.btnDelete.visibility = View.VISIBLE
            holder.btnDelete.setOnClickListener { onDelete(skill) }
        } else {
            holder.btnDelete.visibility = View.GONE
        }

        holder.btnToggle.setOnClickListener { onToggle(skill) }
        holder.btnDetails.setOnClickListener { onDetails(skill) }
        holder.itemView.setOnClickListener { onDetails(skill) }
    }

    override fun getItemCount(): Int = skills.size

    fun updateData(newSkills: List<SkillInfo>, newActiveId: String?) {
        skills = newSkills
        activeSkillId = newActiveId
        notifyDataSetChanged()
    }
}

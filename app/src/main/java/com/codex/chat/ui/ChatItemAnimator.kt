package com.codex.chat.ui

import android.animation.AnimatorListenerAdapter
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView

/**
 * Animador de items profesional para el feed de chat.
 * Mensajes nuevos: sube con fade; cambios (streaming): cross-fade sin escala.
 */
class ChatItemAnimator : DefaultItemAnimator() {

    init {
        // Streaming: sin animación de cambio con escala (elimina el parpadeo)
        supportsChangeAnimations = false
        addDuration = 260
        changeDuration = 160
        removeDuration = 180
    }

    override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
        val view = holder.itemView
        view.translationY = 36f
        view.alpha = 0f
        return super.animateAdd(holder)
    }

    override fun onAnimationFinished(holder: RecyclerView.ViewHolder) {
        super.onAnimationFinished(holder)
        holder.itemView.translationY = 0f
        holder.itemView.alpha = 1f
    }
}
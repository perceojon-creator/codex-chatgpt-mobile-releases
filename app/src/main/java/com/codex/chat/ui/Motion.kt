package com.codex.chat.ui

import android.animation.AnimatorSet
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.Resources
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.interpolator.view.animation.FastOutSlowInInterpolator

/**
 * Sistema de movimiento profesional centralizado.
 * Todas las duraciones e interpoladores siguen la escala Material 3
 * para que la app entera se sienta coherente, suave y sin saltos bruscos.
 */
object Motion {

    // Escala de duraciones Material 3 (ms)
    const val DURATION_XS: Long = 80   // feedback táctil, estados
    const val DURATION_S: Long = 150   // chips, fades
    const val DURATION_M: Long = 220   // barras, burbujas
    const val DURATION_L: Long = 300   // tarjetas, contenedores
    const val DURATION_XL: Long = 450  // transiciones grandes

    val decelerate = DecelerateInterpolator(1.2f)
    val accelerate = AccelerateInterpolator(1.0f)
    val emphasized = FastOutSlowInInterpolator()
    val overshoot = OvershootInterpolator(1.1f)

    /** Aparición suave con deslizamiento vertical corto (entradas estándar M3). */
    fun slideUpFadeIn(view: View, duration: Long = DURATION_M, startOffsetPx: Float = 28f) {
        view.clearAnimation()
        view.translationY = startOffsetPx
        view.alpha = 0f
        view.visibility = View.VISIBLE
        view.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(duration)
            .setInterpolator(decelerate)
            .withEndAction { view.translationY = 0f; view.alpha = 1f }
            .start()
    }

    /** Desaparición suave con deslizamiento hacia abajo (salida estándar M3). */
    fun slideDownFadeOut(view: View, duration: Long = DURATION_S, endAction: (() -> Unit)? = null) {
        view.clearAnimation()
        view.animate()
            .translationY(view.height.toFloat() * 0.4f)
            .alpha(0f)
            .setDuration(duration)
            .setInterpolator(accelerate)
            .withEndAction {
                view.visibility = View.GONE
                view.translationY = 0f
                view.alpha = 1f
                endAction?.invoke()
            }
            .start()
    }

    /** Cross-fade de visibilidad: nunca salta de GONE a VISIBLE en seco. */
    fun setGoneSmoothly(view: View, gone: Boolean, duration: Long = DURATION_S) {
        if (gone) {
            if (view.visibility == View.VISIBLE) {
                view.animate()
                    .alpha(0f)
                    .setDuration(duration)
                    .setInterpolator(accelerate)
                    .withEndAction { view.visibility = View.GONE; view.alpha = 1f }
                    .start()
            }
        } else {
            if (view.visibility != View.VISIBLE) {
                view.alpha = 0f
                view.visibility = View.VISIBLE
                view.animate()
                    .alpha(1f)
                    .setDuration(duration)
                    .setInterpolator(decelerate)
                    .start()
            }
        }
    }

    /** Pop-in elástico para burbujas nuevas y chips (personalidad del producto). */
    fun popIn(view: View, duration: Long = DURATION_M) {
        view.clearAnimation()
        view.alpha = 0f
        view.scaleX = 0.92f
        view.scaleY = 0.92f
        view.visibility = View.VISIBLE
        view.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(duration)
            .setInterpolator(overshoot)
            .withEndAction { view.scaleX = 1f; view.scaleY = 1f; view.alpha = 1f }
            .start()
    }

    /** Pulso de atención para el badge de ejecutando (indeterminado). */
    fun pulse(view: View) {
        view.animate()
            .alpha(0.4f)
            .setDuration(600)
            .setInterpolator(emphasized)
            .withEndAction {
                view.animate()
                    .alpha(1f)
                    .setDuration(600)
                    .setInterpolator(emphasized)
                    .withEndAction { if (view.tag == "pulse") pulse(view) }
                    .start()
            }
            .start()
        view.tag = "pulse"
    }

    fun stopPulse(view: View) {
        view.tag = null
        view.animate().cancel()
        view.alpha = 1f
    }

    /** Anima el color de texto con ArgbEvaluator (tabs, badges). */
    fun animateTextColor(view: android.widget.TextView, targetColor: Int, duration: Long = DURATION_M) {
        val anim = ObjectAnimator.ofObject(
            view,
            "textColor",
            ArgbEvaluator(),
            view.currentTextColor,
            targetColor
        )
        anim.duration = duration
        anim.interpolator = emphasized
        anim.start()
    }

    /** Cross-fade del fondo de una vista hacia un nuevo drawable (píldoras de tabs). */
    fun fadeBackgroundResource(view: View, resId: Int, duration: Long = DURATION_M) {
        try {
            val newBg = androidx.core.content.ContextCompat.getDrawable(view.context, resId) ?: return
            val old = view.background
            view.background = newBg
            if (old != null) {
                val fadeIn = ObjectAnimator.ofInt(newBg, "alpha", 0, 255)
                fadeIn.duration = duration
                fadeIn.interpolator = decelerate
                fadeIn.start()
                old.alpha = 255
            }
        } catch (e: Exception) {
            view.setBackgroundResource(resId)
        }
    }

    /** Micro-rebote al pulsar (feedback táctil de botones). */
    fun tapFeedback(view: View) {
        view.animate()
            .scaleX(0.9f)
            .scaleY(0.9f)
            .setDuration(70)
            .setInterpolator(accelerate)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(140)
                    .setInterpolator(overshoot)
                    .start()
            }
            .start()
    }
}
package com.codex.chat.ui.voice

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.AttributeSet

/**
 * Vista interactiva GLSurfaceView para el orbe de voz Horizon de ChatGPT.
 */
class HorizonOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val renderer: HorizonOrbRenderer

    init {
        setEGLContextClientVersion(2)
        setZOrderOnTop(true)
        holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)

        renderer = HorizonOrbRenderer(context)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    /**
     * Actualiza la amplitud de audio recibida desde el micrófono para modular el orbe.
     */
    fun onAudioAmplitude(amplitude: Float) {
        renderer.setAudioLevel(amplitude)
    }
}

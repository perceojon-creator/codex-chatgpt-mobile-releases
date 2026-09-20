package com.codex.chat.agent.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.codex.chat.R
import com.codex.chat.agent.core.AgentStatus
import com.codex.chat.agent.core.AutonomousAgentLoop
import com.codex.chat.agent.device.CodexAccessibilityService
import com.codex.chat.core.security.EstopSentinel
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * High-priority floating system overlay (ChatHead) anchored over all active Android apps.
 * Displays live thought stream, execution step count, and an instantaneous red Emergency Stop (ESTOP)
 * kill-switch button that halts device actuation immediately upon touch.
 *
 * Supports dual invocation:
 * 1. Native Conversational MCP tool-calling (MainActivity tool-chain)
 * 2. AutonomousAgentLoop engine
 */
class FloatingAgentOverlay {

    companion object {
        val instance: FloatingAgentOverlay by lazy { FloatingAgentOverlay() }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var collectorJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    val isShowing: Boolean
        get() = overlayView != null

    /**
     * Shows the floating overlay anchored over the screen for conversational MCP tool execution.
     */
    fun show(context: Context, onEstopClicked: (() -> Unit)? = null) {
        if (isShowing) return

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        this.windowManager = wm

        val themedContext = androidx.appcompat.view.ContextThemeWrapper(context, R.style.Theme_ChatGPTCustom)
        val inflater = LayoutInflater.from(themedContext)
        val view = inflater.inflate(R.layout.overlay_agent_bubble, null)
        this.overlayView = view

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 160
        }

        // Setup touch drag handler on header
        val header = view.findViewById<View>(R.id.layout_drag_header)
        header.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                if (event == null) return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager?.updateViewLayout(overlayView, params)
                        return true
                    }
                }
                return false
            }
        })

        // Hook up Emergency Stop (ESTOP) button
        val estopBtn = view.findViewById<MaterialButton>(R.id.btn_estop_kill)
        estopBtn.setOnClickListener {
            // CRITICAL DEFENSE: Ignore synthetic taps dispatched by the agent itself.
            // Only genuine human finger touches are allowed to trigger ESTOP.
            if (CodexAccessibilityService.instance?.isDispatchingGesture == true) {
                return@setOnClickListener
            }
            onEstopClicked?.invoke() ?: run {
                EstopSentinel.engage("Parada de Emergencia pulsada desde el overlay flotante")
            }
            updateAborted("Parada de Emergencia activada por el usuario")
        }

        // Hook up close button
        val closeBtn = view.findViewById<View>(R.id.btn_overlay_close)
        closeBtn.setOnClickListener {
            detach()
        }

        wm.addView(view, params)
    }

    fun updateProgress(stepIndex: Int, statusText: String, thoughtText: String = "") {
        mainHandler.post {
            val view = overlayView ?: return@post
            val tvThought = view.findViewById<TextView>(R.id.tv_live_thought)
            val tvBadge   = view.findViewById<TextView>(R.id.tv_step_badge)
            val tvTitle   = view.findViewById<TextView>(R.id.tv_agent_title)
            val estopBtn  = view.findViewById<MaterialButton>(R.id.btn_estop_kill)

            val displayText = if (thoughtText.isNotBlank()) "💭 " + thoughtText + "\n" + statusText else statusText
            tvThought.text = displayText
            tvBadge.text = "Paso " + stepIndex
            tvThought.setTextColor(0xFFECECEC.toInt())
            tvTitle?.text = "Modo Agente Activo"
            tvTitle?.setTextColor(0xFF10A37F.toInt())
            estopBtn?.text = "STOP"
            estopBtn?.backgroundTintList = ColorStateList.valueOf(0xFFD32F2F.toInt())
            estopBtn?.setIconResource(android.R.drawable.ic_delete)
        }
    }

    fun updateComplete(resultText: String) {
        mainHandler.post {
            val view = overlayView ?: return@post
            val tvThought = view.findViewById<TextView>(R.id.tv_live_thought)
            val tvTitle   = view.findViewById<TextView>(R.id.tv_agent_title)
            val estopBtn  = view.findViewById<MaterialButton>(R.id.btn_estop_kill)

            tvThought.text = resultText
            tvThought.setTextColor(0xFF10A37F.toInt())
            tvTitle?.text = "✓ Objetivo Completado"
            tvTitle?.setTextColor(0xFF10A37F.toInt())
            estopBtn?.text = "FINALIZAR"
            estopBtn?.backgroundTintList = ColorStateList.valueOf(0xFF10A37F.toInt())
            estopBtn?.setIconResource(android.R.drawable.checkbox_on_background)
            estopBtn?.setOnClickListener { detach() }

            // Auto-detach after 5 seconds so it doesn't block the screen while user enjoys their media
            mainHandler.postDelayed({
                if (isShowing) detach()
            }, 5000L)
        }
    }

    fun updateAborted(reason: String) {
        mainHandler.post {
            val view = overlayView ?: return@post
            val tvThought = view.findViewById<TextView>(R.id.tv_live_thought)
            val tvTitle   = view.findViewById<TextView>(R.id.tv_agent_title)
            val estopBtn  = view.findViewById<MaterialButton>(R.id.btn_estop_kill)

            tvThought.text = reason
            tvThought.setTextColor(0xFFD32F2F.toInt())
            tvTitle?.text = "Parada de Emergencia"
            tvTitle?.setTextColor(0xFFD32F2F.toInt())
            estopBtn?.text = "CERRAR"
            estopBtn?.backgroundTintList = ColorStateList.valueOf(0xFF424242.toInt())
            estopBtn?.setIconResource(android.R.drawable.ic_menu_close_clear_cancel)
            estopBtn?.setOnClickListener { detach() }
        }
    }

    fun attach(context: Context, loop: AutonomousAgentLoop, scope: CoroutineScope) {
        show(context) {
            loop.abort("Parada de Emergencia pulsada desde el overlay flotante")
        }

        collectorJob = scope.launch(Dispatchers.Default) {
            loop.status.collectLatest { status ->
                withContext(Dispatchers.Main) {
                    if (status.isAborted) {
                        updateAborted(status.statusText)
                    } else if (status.isComplete) {
                        updateComplete(status.statusText)
                    } else {
                        updateProgress(status.stepIndex, status.statusText)
                    }
                }
            }
        }
    }

    fun detach() {
        mainHandler.removeCallbacksAndMessages(null)
        collectorJob?.cancel()
        collectorJob = null

        val view = overlayView
        val wm = windowManager
        if (view != null && wm != null) {
            try {
                wm.removeView(view)
            } catch (_: Throwable) {}
        }
        overlayView = null
        windowManager = null
    }
}

package com.codex.chat.agent.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.codex.chat.R
import com.codex.chat.agent.core.AgentStatus
import com.codex.chat.agent.core.AutonomousAgentLoop
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
 */
class FloatingAgentOverlay {

    companion object {
        val instance: FloatingAgentOverlay by lazy { FloatingAgentOverlay() }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var collectorJob: Job? = null

    val isShowing: Boolean
        get() = overlayView != null

    fun attach(context: Context, loop: AutonomousAgentLoop, scope: CoroutineScope) {
        if (isShowing) {
            detach()
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        this.windowManager = wm

        // CRITICAL FIX: Application context does not have a Material theme.
        // MaterialCardView & MaterialButton require Theme.MaterialComponents or Theme.Material3.
        // Wrap context in ContextThemeWrapper to prevent InflateException / IllegalArgumentException.
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
        val estopBtn = view.findViewById<View>(R.id.btn_estop_kill)
        estopBtn.setOnClickListener {
            loop.abort("Parada de Emergencia pulsada desde el overlay flotante")
        }

        // Hook up close button
        val closeBtn = view.findViewById<View>(R.id.btn_overlay_close)
        closeBtn.setOnClickListener {
            detach()
        }

        // Add view to WindowManager
        wm.addView(view, params)

        // Collect live agent status flow
        collectorJob = scope.launch(Dispatchers.Default) {
            loop.status.collectLatest { status ->
                withContext(Dispatchers.Main) {
                    updateUi(view, status)
                }
            }
        }
    }

    private fun updateUi(view: View, status: AgentStatus) {
        val tvThought = view.findViewById<TextView>(R.id.tv_live_thought)
        val tvBadge = view.findViewById<TextView>(R.id.tv_step_badge)

        tvThought.text = status.statusText
        tvBadge.text = "Paso " + status.stepIndex + "/" + status.maxSteps

        if (status.isAborted) {
            tvThought.setTextColor(0xFFD32F2F.toInt())
        } else if (status.isComplete) {
            tvThought.setTextColor(0xFF10A37F.toInt())
        } else {
            tvThought.setTextColor(0xFFECECEC.toInt())
        }
    }

    fun detach() {
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

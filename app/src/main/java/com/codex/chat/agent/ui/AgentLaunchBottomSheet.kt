package com.codex.chat.agent.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.codex.chat.R
import com.codex.chat.SettingsManager
import com.codex.chat.agent.device.CodexAccessibilityService
import com.codex.chat.agent.device.ScreenCaptureService
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Bottom Sheet that guards 4-step permissions and launches the autonomous agent.
 *
 * Permission chain:
 *   1. AccessibilityService enabled
 *   2. SYSTEM_ALERT_WINDOW (overlay) granted
 *   3. Battery optimization exemption (critical for background survival on MIUI/OxygenOS/ColorOS)
 *   4. MediaProjection screen capture token
 *
 * All config is forwarded to ScreenCaptureService via Intent extras.
 * The Service owns AutonomousAgentLoop on ProcessLifecycleOwner.lifecycleScope.
 * Overlay attaches via poll-retry (up to 3s) instead of fragile fixed-delay race condition.
 */
class AgentLaunchBottomSheet : BottomSheetDialogFragment() {

    private var pendingGoalText: String = ""
    private var pendingModel: String = "gemini-3.8-flash"
    private var pendingReasoningEffort: String? = null

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val ctx = context ?: return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            launchServiceAndOverlay(ctx, result.resultCode, result.data!!)
        } else {
            Toast.makeText(ctx, "Permiso de captura de pantalla denegado", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.bottom_sheet_agent_launcher, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val etGoal        = view.findViewById<EditText>(R.id.et_agent_goal)
        val btnLaunch     = view.findViewById<Button>(R.id.btn_launch_agent)
        val rgModel       = view.findViewById<RadioGroup>(R.id.rg_model_selector)
        val layoutEffort  = view.findViewById<View>(R.id.layout_effort_section)
        val rgEffort      = view.findViewById<RadioGroup>(R.id.rg_effort_selector)
        val tvEffortBadge = view.findViewById<TextView>(R.id.tv_effort_badge)

        rgModel.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rb_claude_37 -> {
                    layoutEffort.visibility = View.VISIBLE
                    if (rgEffort.checkedRadioButtonId == R.id.rb_effort_none) rgEffort.check(R.id.rb_effort_medium)
                }
                R.id.rb_gemini_38 -> layoutEffort.visibility = View.VISIBLE
                R.id.rb_glm_53   -> layoutEffort.visibility = View.GONE
            }
        }

        rgEffort.setOnCheckedChangeListener { _, checkedId ->
            tvEffortBadge.text = when (checkedId) {
                R.id.rb_effort_none   -> "NONE"
                R.id.rb_effort_low    -> "LOW"
                R.id.rb_effort_medium -> "MEDIUM"
                R.id.rb_effort_high   -> "HIGH"
                else                  -> "MEDIUM"
            }
        }

        btnLaunch.setOnClickListener {
            val goal = etGoal.text?.toString()?.trim() ?: ""
            if (goal.isBlank()) {
                Toast.makeText(requireContext(), "Por favor describe el objetivo del agente", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pendingGoalText = goal
            pendingModel = when (rgModel.checkedRadioButtonId) {
                R.id.rb_claude_37 -> "claude-3-7-sonnet-20250219"
                R.id.rb_glm_53    -> "glm-5.3-flash"
                else              -> "gemini-3.8-flash-high"
            }
            pendingReasoningEffort = if (layoutEffort.visibility == View.VISIBLE) {
                when (rgEffort.checkedRadioButtonId) {
                    R.id.rb_effort_none -> null
                    R.id.rb_effort_low  -> "low"
                    R.id.rb_effort_high -> "high"
                    else                -> "medium"
                }
            } else null

            verifyPermissionsAndLaunch()
        }
    }

    // ─── Permission chain ─────────────────────────────────────────────────

    private fun verifyPermissionsAndLaunch() {
        val ctx = requireContext()

        // Step 1: AccessibilityService must be active
        if (CodexAccessibilityService.instance == null) {
            Toast.makeText(ctx, "Activa 'Autonomous Agent Mode' en Accesibilidad", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        // Step 2: Overlay (SYSTEM_ALERT_WINDOW) for FloatingAgentOverlay ESTOP button
        if (!Settings.canDrawOverlays(ctx)) {
            Toast.makeText(ctx, "Concede permiso de superposicion para el boton ESTOP", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)))
            return
        }

        // Step 3: Battery optimization exemption — CRITICAL on MIUI/OxygenOS/ColorOS/OneUI.
        // Without this, the OS kills foreground service network I/O after 3-10 min in background.
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && !pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
            Toast.makeText(
                ctx,
                "Excluye la app de optimizacion de bateria para que el agente no sea interrumpido",
                Toast.LENGTH_LONG
            ).show()
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:" + ctx.packageName)
                })
            } catch (_: Throwable) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            return
        }

        // Step 4: MediaProjection screen capture consent
        Toast.makeText(
            ctx,
            "⚠️ Selecciona 'Toda la pantalla' (no 'Una sola app') para que el agente opere YouTube",
            Toast.LENGTH_LONG
        ).show()
        val mpManager = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        mpManager?.let { mediaProjectionLauncher.launch(it.createScreenCaptureIntent()) }
    }

    // ─── Service launch + overlay attach ──────────────────────────────────

    /**
     * Starts ScreenCaptureService with all agent config as Intent extras.
     * The service creates AutonomousAgentLoop on ProcessLifecycleOwner scope (survives background).
     * Overlay attaches via poll-retry every 200ms up to 3s to avoid fixed-delay race condition.
     */
    private fun launchServiceAndOverlay(context: Context, resultCode: Int, data: Intent) {
        val activityRef = activity ?: return
        val settings    = SettingsManager(context)
        val resolvedKey = if (settings.apiKey.isNotBlank()) settings.apiKey else "proxy-pool"

        // Disengage any previous ESTOP latch so new user-requested task executes cleanly
        com.codex.chat.core.security.EstopSentinel.disengage()

        ContextCompat.startForegroundService(
            context,
            Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_DATA, data)
                putExtra(ScreenCaptureService.EXTRA_GOAL, pendingGoalText)
                putExtra(ScreenCaptureService.EXTRA_MODEL, pendingModel)
                putExtra(ScreenCaptureService.EXTRA_REASONING_EFFORT, pendingReasoningEffort)
                putExtra(ScreenCaptureService.EXTRA_BASE_URL, settings.baseUrl)
                putExtra(ScreenCaptureService.EXTRA_API_KEY, resolvedKey)
                putExtra(ScreenCaptureService.EXTRA_MAX_STEPS, 1_000_000)
            }
        )

        // Poll-retry attach: check every 200ms up to 15 attempts (3 000ms max).
        // Much safer than fixed 800ms postDelayed which is a race condition.
        val appCtx   = context.applicationContext
        val handler  = Handler(Looper.getMainLooper())
        var attempts = 0

        fun tryAttach() {
            val loop = ScreenCaptureService.activeLoop
            when {
                loop != null -> {
                    val processScope = ProcessLifecycleOwner.get().lifecycleScope
                    FloatingAgentOverlay.instance.attach(appCtx, loop, processScope)
                }
                attempts++ < 15 -> handler.postDelayed(::tryAttach, 200L)
                // else: loop failed to start — service logs the error, agent runs headless
            }
        }
        handler.postDelayed(::tryAttach, 200L)

        dismiss()
        activityRef.moveTaskToBack(true)
    }
}
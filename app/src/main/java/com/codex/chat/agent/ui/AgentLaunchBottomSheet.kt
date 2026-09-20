package com.codex.chat.agent.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.codex.chat.R
import com.codex.chat.SettingsManager
import com.codex.chat.agent.core.AutonomousAgentLoop
import com.codex.chat.agent.core.GroundingPromptBuilder
import com.codex.chat.agent.device.CodexAccessibilityService
import com.codex.chat.agent.device.DeviceMetricsProvider
import com.codex.chat.agent.device.ScreenCaptureService
import com.codex.chat.agent.network.AgentVisionClient
import com.codex.chat.agent.network.VisionResponseParser
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Bottom Sheet modal to configure goal, select multimodal vision model,
 * audit device permissions, and initiate autonomous device operation.
 */
class AgentLaunchBottomSheet : BottomSheetDialogFragment() {

    private var pendingGoalText: String = ""

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val ctx = context ?: return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startAgentPipeline(ctx, result.resultCode, result.data!!)
        } else {
            Toast.makeText(ctx, "Permiso de captura de pantalla denegado", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_agent_launcher, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val etGoal = view.findViewById<EditText>(R.id.et_agent_goal)
        val btnLaunch = view.findViewById<Button>(R.id.btn_launch_agent)

        btnLaunch.setOnClickListener {
            val goal = etGoal.text?.toString()?.trim() ?: ""
            if (goal.isBlank()) {
                Toast.makeText(requireContext(), "Por favor describe el objetivo del agente", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pendingGoalText = goal
            verifyPermissionsAndLaunch()
        }
    }

    private fun verifyPermissionsAndLaunch() {
        val ctx = requireContext()

        // 1. Accessibility Service Guard
        if (CodexAccessibilityService.instance == null) {
            Toast.makeText(ctx, "Activa 'Autonomous Agent Mode' en Accesibilidad", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }

        // 2. Window Overlay Permission Guard
        if (!Settings.canDrawOverlays(ctx)) {
            Toast.makeText(ctx, "Concede permiso de superposición para el botón ESTOP", Toast.LENGTH_LONG).show()
            val overlayIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + ctx.packageName)
            )
            startActivity(overlayIntent)
            return
        }

        // 3. MediaProjection Screen Capture Permission Guard
        val mpManager = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        if (mpManager != null) {
            mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
        }
    }

    private fun startAgentPipeline(context: Context, resultCode: Int, data: Intent) {
        val activity = activity ?: return
        val view = view ?: return

        // 1. Start ScreenCaptureService with MediaProjection token extras
        val captureIntent = Intent(context, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_DATA, data)
        }
        ContextCompat.startForegroundService(context, captureIntent)

        // 2. Determine selected model
        val rgModel = view.findViewById<RadioGroup>(R.id.rg_model_selector)
        val selectedModel = when (rgModel?.checkedRadioButtonId) {
            R.id.rb_claude -> "claude-3-7-sonnet"
            R.id.rb_glm -> "z-ai/glm-5.3-flash"
            else -> "gemini-3.5-flash"
        }

        val settings = SettingsManager(context)
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        val visionClient = AgentVisionClient(
            baseUrl = settings.baseUrl,
            apiKey = settings.apiKey,
            okHttpClient = okHttpClient,
            promptBuilder = GroundingPromptBuilder(),
            parser = VisionResponseParser(),
            screenWidth = DeviceMetricsProvider.getScreenWidth(context),
            screenHeight = DeviceMetricsProvider.getScreenHeight(context),
            model = selectedModel
        )

        val a11y = CodexAccessibilityService.instance
        if (a11y == null) {
            Toast.makeText(context, "El servicio de accesibilidad se desconectó", Toast.LENGTH_SHORT).show()
            return
        }

        val hostActivity = activity as? AppCompatActivity
        val scope = hostActivity?.lifecycleScope ?: lifecycleScope

        // 3. Initialize Autonomous Loop and Floating Overlay
        val loop = AutonomousAgentLoop(
            device = a11y,
            vision = visionClient,
            maxSteps = 20,
            externalScope = scope
        )

        FloatingAgentOverlay.instance.attach(context.applicationContext, loop, scope)
        loop.start(pendingGoalText)

        dismiss()
        // Minimize app to allow autonomous agent to operate device
        activity.moveTaskToBack(true)
    }
}
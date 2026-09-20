package com.codex.chat.agent.device

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.codex.chat.R
import com.codex.chat.agent.core.AutonomousAgentLoop
import com.codex.chat.agent.core.GroundingPromptBuilder
import com.codex.chat.agent.network.AgentVisionClient
import com.codex.chat.agent.network.VisionResponseParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Foreground Service hosting the Android MediaProjection pipeline AND the AutonomousAgentLoop.
 * By running both inside the foreground service, the loop survives when the app is backgrounded.
 * Manages VirtualDisplay and ImageReader with zero PC tethering,
 * low-latency JPEG compression, strict buffer reclamation, and CPU WakeLock.
 */
class ScreenCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "agent_screen_capture_channel"
        const val NOTIF_ID = 9002

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"
        const val EXTRA_GOAL = "extra_goal"
        const val EXTRA_MODEL = "extra_model"
        const val EXTRA_REASONING_EFFORT = "extra_reasoning_effort"
        const val EXTRA_BASE_URL = "extra_base_url"
        const val EXTRA_API_KEY = "extra_api_key"
        const val EXTRA_MAX_STEPS = "extra_max_steps"
        const val ACTION_STOP = "action_stop_capture"

        @Volatile
        var instance: ScreenCaptureService? = null
            private set

        /** Live agent loop reference for FloatingAgentOverlay to observe */
        @Volatile
        var activeLoop: AutonomousAgentLoop? = null
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        instance = this

        // Acquire partial WakeLock to prevent CPU suspension during autonomous operation
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = pm?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "codex:AgentAutonomousLoop"
        )?.also { it.acquire(30 * 60 * 1000L) } // Max 30 minutes
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            activeLoop?.abort("Service stop requested")
            stopSelf()
            return START_NOT_STICKY
        }

        // 1. MUST promote to foreground immediately (within 5 seconds)
        val notification = buildForegroundNotification()
        startForeground(NOTIF_ID, notification)

        // 2. Extract MediaProjection token extras
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }

        if (resultCode != 0 && resultData != null && mediaProjection == null) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            mediaProjection = mpManager?.getMediaProjection(resultCode, resultData)
            setupVirtualDisplay()
        }

        // 3. Extract agent configuration and start the loop inside the service
        val goal = intent?.getStringExtra(EXTRA_GOAL)
        val model = intent?.getStringExtra(EXTRA_MODEL) ?: "gemini-3.8-flash"
        val reasoningEffort = intent?.getStringExtra(EXTRA_REASONING_EFFORT)
        val baseUrl = intent?.getStringExtra(EXTRA_BASE_URL) ?: ""
        val apiKey = intent?.getStringExtra(EXTRA_API_KEY) ?: ""
        val maxSteps = intent?.getIntExtra(EXTRA_MAX_STEPS, 20) ?: 20

        if (!goal.isNullOrBlank() && activeLoop == null) {
            startAgentLoop(goal, model, reasoningEffort, baseUrl, apiKey, maxSteps)
        }

        // START_REDELIVER_INTENT: if OS kills the service under memory pressure,
        // Android will restart it with the original intent so the agent loop resumes.
        return START_REDELIVER_INTENT
    }

    private fun startAgentLoop(
        goal: String,
        model: String,
        reasoningEffort: String?,
        baseUrl: String,
        apiKey: String,
        maxSteps: Int
    ) {
        val a11y = CodexAccessibilityService.instance ?: return

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()

        val visionClient = AgentVisionClient(
            baseUrl = baseUrl,
            apiKey = apiKey,
            okHttpClient = okHttpClient,
            promptBuilder = GroundingPromptBuilder(),
            parser = VisionResponseParser(),
            screenWidth = DeviceMetricsProvider.getScreenWidth(this),
            screenHeight = DeviceMetricsProvider.getScreenHeight(this),
            model = model,
            reasoningEffort = reasoningEffort
        )

        // Use ProcessLifecycleOwner scope — survives Activity going to background
        val processScope = ProcessLifecycleOwner.get().lifecycleScope

        val loop = AutonomousAgentLoop(
            device = a11y,
            vision = visionClient,
            maxSteps = maxSteps,
            externalScope = processScope
        )
        activeLoop = loop
        loop.start(goal)
    }

    private fun setupVirtualDisplay() {
        val proj = mediaProjection ?: return

        val screenW = DeviceMetricsProvider.getScreenWidth(this)
        val screenH = DeviceMetricsProvider.getScreenHeight(this)
        val densityDpi = DeviceMetricsProvider.getDensityDpi(this)

        imageReader?.close()
        virtualDisplay?.release()

        val reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        this.imageReader = reader

        this.virtualDisplay = proj.createVirtualDisplay(
            "AgentVirtualDisplay",
            screenW,
            screenH,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        )
    }

    /**
     * Captures the active virtual display frame, scales proportionally to maxDimension,
     * compresses to JPEG with specified quality (70-75% optimal), and encodes to Base64.
     * Guaranteed safe: always closes the Image in a finally block to prevent buffer starvation.
     */
    suspend fun captureScreenshotBase64(maxDimension: Int = 1080, quality: Int = 75): String =
        withContext(Dispatchers.IO) {
            val reader = imageReader ?: return@withContext ""
            val image = reader.acquireLatestImage() ?: return@withContext ""

            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * image.width

                var bitmap = Bitmap.createBitmap(
                    image.width + rowPadding / pixelStride,
                    image.height,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)

                // Crop any row-stride padding
                if (rowPadding > 0) {
                    val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
                    if (cropped != bitmap) {
                        bitmap.recycle()
                        bitmap = cropped
                    }
                }

                // Scale down proportionally if larger than maxDimension
                val maxSide = maxOf(bitmap.width, bitmap.height)
                if (maxSide > maxDimension) {
                    val scale = maxDimension.toFloat() / maxSide
                    val targetW = (bitmap.width * scale).toInt().coerceAtLeast(1)
                    val targetH = (bitmap.height * scale).toInt().coerceAtLeast(1)
                    val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
                    if (scaled != bitmap) {
                        bitmap.recycle()
                        bitmap = scaled
                    }
                }

                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                bitmap.recycle()

                val bytes = stream.toByteArray()
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            } catch (e: Throwable) {
                ""
            } finally {
                // MANDATORY: always return buffer to ImageReader queue
                image.close()
            }
        }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.agent_notification_channel),
                NotificationManager.IMPORTANCE_HIGH  // HIGH: ensures OS doesn't suppress service
            ).apply {
                description = "Agente Autónomo operando el dispositivo en segundo plano"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
                setSound(null, null)  // Silent but high-importance
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.agent_notification_title))
            .setContentText("Agente autónomo activo — toca para ver estado")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            // HIGH priority: signals to OS that this foreground service is user-facing
            // and should survive background trimming and Doze transitions.
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Android 12+: show notification immediately instead of deferring 10s
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        activeLoop?.abort("Service destroyed")
        activeLoop = null
        instance = null
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection?.stop()
            mediaProjection = null
        } catch (_: Throwable) {}
    }
}
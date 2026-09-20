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
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.codex.chat.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Foreground Service hosting the Android MediaProjection pipeline.
 * Manages VirtualDisplay and ImageReader with zero PC tethering,
 * low-latency JPEG compression, and strict buffer reclamation.
 */
class ScreenCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "agent_screen_capture_channel"
        const val NOTIF_ID = 9002

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"
        const val ACTION_STOP = "action_stop_capture"

        @Volatile
        var instance: ScreenCaptureService? = null
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
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

        return START_NOT_STICKY
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
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Captura de pantalla para el Agente Autónomo"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.agent_notification_title))
            .setContentText("Operando dispositivo autónomamente vía CLIProxyAPI")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection?.stop()
            mediaProjection = null
        } catch (_: Throwable) {}
    }
}

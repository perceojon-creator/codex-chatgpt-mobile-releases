package com.codex.chat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val releaseNotes: String,
    val apkUrl: String,
    val sizeBytes: Long
)

class AppUpdateManager(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun checkForUpdates(serverBaseUrl: String, onUpdateAvailable: (UpdateInfo) -> Unit, onNoUpdate: (() -> Unit)? = null) {
        thread {
            try {
                val url = "$serverBaseUrl/api/update/check"
                val request = Request.Builder().url(url).get().build()
                val response = httpClient.newCall(request).execute()
                val bodyStr = response.body?.string() ?: "{}"
                val json = JSONObject(bodyStr)

                val serverCode = json.optInt("version_code", 0)
                val serverName = json.optString("version_name", "")
                val notes = json.optString("release_notes", "Nueva versión disponible.")
                val apkUrl = json.optString("apk_url", "")
                val size = json.optLong("size_bytes", 0)

                val currentCode = BuildConfig.VERSION_CODE

                if (serverCode > currentCode && apkUrl.isNotEmpty()) {
                    val info = UpdateInfo(
                        versionCode = serverCode,
                        versionName = serverName,
                        releaseNotes = notes,
                        apkUrl = apkUrl,
                        sizeBytes = size
                    )
                    (context as? Activity)?.runOnUiThread {
                        onUpdateAvailable(info)
                    }
                } else {
                    (context as? Activity)?.runOnUiThread {
                        onNoUpdate?.invoke()
                    }
                }
            } catch (e: Exception) {
                // Ignore network errors on background update check
            }
        }
    }

    fun showUpdateDialog(activity: Activity, info: UpdateInfo) {
        val mb = if (info.sizeBytes > 0) " (%.1f MB)".format(info.sizeBytes / 1048576.0) else ""
        MaterialAlertDialogBuilder(activity)
            .setTitle("🚀 Actualización Disponible: v" + info.versionName)
            .setMessage(
                "Hay una nueva versión de la aplicación lista para instalar" + mb + ".\n\n" +
                "Cambios:\n" + info.releaseNotes + "\n\n" +
                "¿Deseas descargar e instalar la actualización ahora?"
            )
            .setPositiveButton("Actualizar Ahora") { _, _ ->
                checkPermissionAndDownload(activity, info)
            }
            .setNegativeButton("Más tarde", null)
            .setCancelable(true)
            .show()
    }

    private fun checkPermissionAndDownload(activity: Activity, info: UpdateInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle("Permiso de Instalación")
                    .setMessage("Para actualizar la app directamente desde tu PC, permite instalar aplicaciones desconocidas para esta app.")
                    .setPositiveButton("Permitir") { _, _ ->
                        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                            data = Uri.parse("package:" + activity.packageName)
                        }
                        activity.startActivity(intent)
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
                return
            }
        }
        downloadAndInstallApk(activity, info)
    }

    private fun downloadAndInstallApk(activity: Activity, info: UpdateInfo) {
        // Show progress dialog
        val progressView = LayoutInflater.from(activity).inflate(R.layout.dialog_settings, null)
        // Create custom simple progress alert
        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            setPadding(40, 30, 40, 20)
        }
        val tvStatus = TextView(activity).apply {
            text = "Descargando actualización v" + info.versionName + "…"
            setTextColor(0xFFECECEC.toInt())
            setPadding(40, 10, 40, 30)
        }

        val container = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xFF212121.toInt())
            addView(tvStatus)
            addView(progressBar)
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Descargando Actualización")
            .setView(container)
            .setCancelable(false)
            .create()

        dialog.show()

        thread {
            try {
                val req = Request.Builder().url(info.apkUrl).get().build()
                val resp = httpClient.newCall(req).execute()
                val body = resp.body ?: throw Exception("Cuerpo de descarga vacío")

                val totalBytes = body.contentLength()
                val apkFile = File(activity.cacheDir, "Codex-ChatGPT-Update.apk")
                if (apkFile.exists()) apkFile.delete()

                val inputStream: InputStream = body.byteStream()
                val outputStream = FileOutputStream(apkFile)

                val buffer = ByteArray(8192)
                var bytesRead: Int
                var downloaded: Long = 0

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    downloaded += bytesRead

                    if (totalBytes > 0) {
                        val progress = ((downloaded * 100) / totalBytes).toInt()
                        activity.runOnUiThread {
                            progressBar.progress = progress
                            tvStatus.text = "Descargando: %d%% (%.1f / %.1f MB)".format(
                                progress,
                                downloaded / 1048576.0,
                                totalBytes / 1048576.0
                            )
                        }
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()

                activity.runOnUiThread {
                    dialog.dismiss()
                    promptInstall(activity, apkFile)
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    dialog.dismiss()
                    Toast.makeText(activity, "Error en descarga: " + e.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun promptInstall(activity: Activity, apkFile: File) {
        try {
            val contentUri = FileProvider.getUriForFile(
                activity,
                activity.packageName + ".fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            activity.startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(activity, "Error iniciando instalador: " + e.message, Toast.LENGTH_LONG).show()
        }
    }
}

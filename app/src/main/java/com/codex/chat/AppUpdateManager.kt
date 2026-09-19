package com.codex.chat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.codex.chat.core.update.ApkVerifier
import com.codex.chat.core.update.ReleaseMetadataParser
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
    val sizeBytes: Long,
    val sha256: String = ""
)

/**
 * Gestor oficial y exclusivo de actualizaciones OTA mediante GitHub Releases público.
 * Todas las actualizaciones se obtienen directamente de api.github.com bajo canal seguro HTTPS.
 * Se prohíbe cualquier fallback o endpoint local/túnel no verificado por política de seguridad.
 */
class AppUpdateManager(private val context: Context) {

    companion object {
        const val GITHUB_RELEASES_API = "https://api.github.com/repos/perceojon-creator/codex-chatgpt-mobile-releases/releases/latest"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun checkForUpdates(
        onUpdateAvailable: (UpdateInfo) -> Unit,
        onNoUpdate: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        thread {
            try {
                // Consulta directa y estricta a GitHub Releases oficial
                val req = Request.Builder()
                    .url(GITHUB_RELEASES_API)
                    .header("User-Agent", "Codex-Mobile-OTA")
                    .header("Accept", "application/vnd.github.v3+json")
                    .get()
                    .build()

                val resp = httpClient.newCall(req).execute()
                var tagName = ""
                var releaseNotes = "Nueva versión disponible."
                var downloadUrl = ""
                var apkSize = 0L

                if (resp.isSuccessful) {
                    val bodyStr = resp.body?.string() ?: "{}"
                    val json = JSONObject(bodyStr)
                    tagName = json.optString("tag_name", "").removePrefix("v").trim()
                    releaseNotes = json.optString("body", "Nueva versión disponible.")

                    val assets = json.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.optJSONObject(i) ?: continue
                            val name = asset.optString("name", "")
                            if (name.endsWith(".apk", ignoreCase = true)) {
                                downloadUrl = asset.optString("browser_download_url", "")
                                apkSize = asset.optLong("size", 0L)
                                break
                            }
                        }
                    }
                } else {
                    // Fallback resiliente: Redirección web sin cuota de GitHub API (por si se excede el rate limit anónimo)
                    try {
                        val webClient = httpClient.newBuilder().followRedirects(false).build()
                        val webReq = Request.Builder()
                            .url("https://github.com/perceojon-creator/codex-chatgpt-mobile-releases/releases/latest")
                            .header("User-Agent", "Codex-Mobile-OTA")
                            .head()
                            .build()
                        val webResp = webClient.newCall(webReq).execute()
                        val location = webResp.header("Location") ?: ""
                        if (location.contains("/tag/")) {
                            tagName = location.substringAfterLast("/tag/").removePrefix("v").trim()
                            downloadUrl = "https://github.com/perceojon-creator/codex-chatgpt-mobile-releases/releases/download/v$tagName/Codex-ChatGPT-Mobile.apk"
                            releaseNotes = "Versión $tagName publicada en GitHub Releases."
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("AppUpdateManager", "Fallback redirect failed: " + e.message)
                    }
                }

                if (downloadUrl.isBlank()) {
                    // Fallback directo a la URL fija de descarga de latest
                    downloadUrl = "https://github.com/perceojon-creator/codex-chatgpt-mobile-releases/releases/latest/download/Codex-ChatGPT-Mobile.apk"
                }

                if (downloadUrl.isNotEmpty() && isNewerVersion(tagName, BuildConfig.VERSION_NAME)) {
                    // El SHA-256 y el versionCode se leen del release publicado, nunca se fabrican.
                    // Si el release no los publica quedan vacios y la instalacion se bloquea (fail-closed).
                    val publishedSha = ReleaseMetadataParser.extractSha256(releaseNotes)
                    val publishedCode = ReleaseMetadataParser.extractVersionCode(releaseNotes)
                    val info = UpdateInfo(
                        versionCode = publishedCode ?: 0,
                        versionName = tagName,
                        releaseNotes = releaseNotes,
                        apkUrl = downloadUrl,
                        sizeBytes = apkSize,
                        sha256 = publishedSha.orEmpty()
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
                (context as? Activity)?.runOnUiThread {
                    onError?.invoke(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    private fun isNewerVersion(remote: String, current: String): Boolean {
        if (remote.isBlank() || current.isBlank()) return false
        val rParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val cParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(rParts.size, cParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val c = cParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    fun showUpdateDialog(activity: Activity, info: UpdateInfo) {
        val mb = if (info.sizeBytes > 0) " (%.1f MB)".format(info.sizeBytes / 1048576.0) else ""
        val msg = "Nueva versión: v" + info.versionName + mb + "\n\n" +
                  "Notas del cambio:\n" + info.releaseNotes + "\n\n" +
                  "¿Deseas descargar e instalar la actualización oficial directamente desde GitHub?"
        MaterialAlertDialogBuilder(activity)
            .setTitle("🚀 Actualización disponible v" + info.versionName)
            .setMessage(msg)
            .setPositiveButton("Actualizar ahora") { _, _ ->
                downloadAndInstallApk(activity, info)
            }
            .setNegativeButton("Más tarde", null)
            .show()
    }

    private fun downloadAndInstallApk(activity: Activity, info: UpdateInfo) {
        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
        }
        val tvStatus = TextView(activity).apply {
            text = "Conectando con GitHub Releases..."
            setPadding(0, 24, 0, 0)
        }
        val container = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(64, 32, 64, 32)
            addView(progressBar)
            addView(tvStatus)
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Descargando actualización...")
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
                            tvStatus.text = "Descargando de GitHub: %d%% (%.1f / %.1f MB)".format(
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
                val apkFile = File(activity.cacheDir, "Codex-ChatGPT-Update.apk")
                if (apkFile.exists()) {
                    try { apkFile.delete() } catch (_: Exception) {}
                }
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

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
import com.codex.chat.core.update.ApkVerifier
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

class AppUpdateManager(private val context: Context) {

    companion object {
        const val GITHUB_RELEASES_API = "https://api.github.com/repos/perceojon-creator/codex-chatgpt-mobile-releases/releases/latest"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun checkForUpdates(
        serverBaseUrl: String? = null,
        onUpdateAvailable: (UpdateInfo) -> Unit,
        onNoUpdate: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        thread {
            try {
                // 1. Prioridad: Repositorio Público de GitHub Releases
                val req = Request.Builder()
                    .url(GITHUB_RELEASES_API)
                    .header("User-Agent", "Codex-Mobile-OTA")
                    .header("Accept", "application/vnd.github.v3+json")
                    .get()
                    .build()

                val resp = httpClient.newCall(req).execute()
                if (resp.isSuccessful) {
                    val bodyStr = resp.body?.string() ?: "{}"
                    val json = JSONObject(bodyStr)
                    val tagName = json.optString("tag_name", "").removePrefix("v").trim()
                    val releaseNotes = json.optString("body", "Nueva versión disponible.")

                    // Parsear assets para encontrar Codex-ChatGPT-Mobile.apk
                    val assets = json.optJSONArray("assets")
                    var downloadUrl = ""
                    var apkSize = 0L

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

                    if (downloadUrl.isNotEmpty() && isNewerVersion(tagName, BuildConfig.VERSION_NAME)) {
                        val info = UpdateInfo(
                            versionCode = BuildConfig.VERSION_CODE + 1, // GitHub tags representan nueva versión
                            versionName = tagName,
                            releaseNotes = releaseNotes,
                            apkUrl = downloadUrl,
                            sizeBytes = apkSize,
                            sha256 = ""
                        )
                        (context as? Activity)?.runOnUiThread {
                            onUpdateAvailable(info)
                        }
                        return@thread
                    } else if (downloadUrl.isNotEmpty()) {
                        (context as? Activity)?.runOnUiThread {
                            onNoUpdate?.invoke()
                        }
                        return@thread
                    }
                }

                // 2. Fallback de contingencia: Servidor local si se provee y GitHub falla
                if (!serverBaseUrl.isNullOrBlank()) {
                    val cleanBase = serverBaseUrl.trimEnd('/')
                    val fallbackUrl = "$cleanBase/api/update/check"
                    val localReq = Request.Builder().url(fallbackUrl).get().build()
                    val localResp = httpClient.newCall(localReq).execute()
                    if (localResp.isSuccessful) {
                        val localJson = JSONObject(localResp.body?.string() ?: "{}")
                        val serverCode = localJson.optInt("version_code", localJson.optInt("versionCode", 0))
                        val serverName = localJson.optString("version_name", localJson.optString("versionName", ""))
                        val notes = localJson.optString("release_notes", localJson.optString("releaseNotes", "Nueva versión."))
                        val apkUrl = localJson.optString("apk_url", localJson.optString("apkUrl", ""))
                        val size = localJson.optLong("size_bytes", localJson.optLong("sizeBytes", 0))
                        val sha256 = localJson.optString("sha256", localJson.optString("sha_256", ""))

                        if (serverCode > BuildConfig.VERSION_CODE && apkUrl.isNotEmpty()) {
                            val info = UpdateInfo(serverCode, serverName, notes, apkUrl, size, sha256)
                            (context as? Activity)?.runOnUiThread { onUpdateAvailable(info) }
                            return@thread
                        } else {
                            (context as? Activity)?.runOnUiThread { onNoUpdate?.invoke() }
                            return@thread
                        }
                    }
                }

                (context as? Activity)?.runOnUiThread {
                    onNoUpdate?.invoke()
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
                  "¿Deseas descargar e instalar la actualización directamente desde GitHub?"
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
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_settings, null, false)
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

                // Si se proporcionó hash SHA-256 en el metadata, verificar
                val expectedSha = info.sha256.trim()
                if (expectedSha.isNotBlank()) {
                    val actualSha = ApkVerifier.sha256(apkFile)
                    if (!ApkVerifier.coincide(expectedSha, actualSha)) {
                        if (apkFile.exists()) apkFile.delete()
                        throw SecurityException("La actualización descargada no coincide con la firma esperada.")
                    }
                }

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

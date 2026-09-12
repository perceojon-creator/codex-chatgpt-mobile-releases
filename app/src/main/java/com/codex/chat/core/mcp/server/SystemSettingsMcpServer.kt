package com.codex.chat.core.mcp.server

import android.app.usage.UsageStatsManager
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import com.codex.chat.core.mcp.model.*
import com.codex.chat.core.service.CodexNotificationListenerService
import org.json.JSONArray
import org.json.JSONObject

class SystemSettingsMcpServer(private val context: Context? = null) : McpServer {

    override val info = McpServerInfo(
        id = "mcp-android-system-settings",
        name = "System Settings & Usage Stats",
        description = "Control de volumen, brillo de pantalla, estadísticas de uso de aplicaciones y lectura de notificaciones",
        iconEmoji = "⚙️",
        type = McpServerType.NATIVE,
        isEnabled = true,
        toolsCount = 5
    )

    override fun getTools(): List<McpTool> = listOf(
        McpTool(
            name = "get_device_settings",
            description = "Consulta los ajustes actuales del teléfono: brillo de pantalla, volumen de música, llamadas y modo de sonido.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            name = "set_audio_volume",
            description = "Ajusta el volumen del teléfono para música, llamadas o alarmas.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("stream_type", JSONObject().put("type", "string").put("description", "Tipo de audio: 'music' (por defecto), 'ring', 'alarm'"))
                    put("level_percent", JSONObject().put("type", "integer").put("description", "Nivel de volumen en porcentaje (0 a 100)"))
                }
                put("properties", props)
                put("required", JSONArray().put("level_percent"))
            }
        ),
        McpTool(
            name = "set_screen_brightness",
            description = "Ajusta el brillo de la pantalla del celular (requiere permiso de Modificar Ajustes).",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("brightness_percent", JSONObject().put("type", "integer").put("description", "Nivel de brillo en porcentaje (0 a 100)"))
                }
                put("properties", props)
                put("required", JSONArray().put("brightness_percent"))
            }
        ),
        McpTool(
            name = "get_app_usage_stats",
            description = "Obtiene las estadísticas de uso de aplicaciones en las últimas horas (requiere permiso de Acceso de Uso).",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("hours_back", JSONObject().put("type", "integer").put("description", "Horas hacia atrás a consultar (por defecto 24)"))
                }
                put("properties", props)
            }
        ),
        McpTool(
            name = "get_captured_notifications",
            description = "Lee los últimos avisos y mensajes de notificaciones recibidos de otras aplicaciones (WhatsApp, Gmail, etc.).",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("limit", JSONObject().put("type", "integer").put("description", "Número de notificaciones a recuperar (por defecto 10)"))
                }
                put("properties", props)
            }
        )
    )

    override fun executeTool(call: McpToolCallRequest): McpToolResult {
        return try {
            val args = try { JSONObject(call.argumentsJson) } catch (e: Exception) { JSONObject() }
            when (call.toolName) {
                "get_device_settings" -> {
                    val res = getDeviceSettings()
                    McpToolResult(call.id, call.toolName, res.toString(2))
                }
                "set_audio_volume" -> {
                    val stream = args.optString("stream_type", "music").lowercase()
                    val pct = args.optInt("level_percent", 50).coerceIn(0, 100)
                    val res = setAudioVolume(stream, pct)
                    McpToolResult(call.id, call.toolName, res)
                }
                "set_screen_brightness" -> {
                    val pct = args.optInt("brightness_percent", 50).coerceIn(0, 100)
                    val res = setScreenBrightness(pct)
                    McpToolResult(call.id, call.toolName, res)
                }
                "get_app_usage_stats" -> {
                    val hours = args.optInt("hours_back", 24).coerceIn(1, 168)
                    val res = getAppUsageStats(hours)
                    McpToolResult(call.id, call.toolName, res.toString(2))
                }
                "get_captured_notifications" -> {
                    val limit = args.optInt("limit", 10).coerceIn(1, 25)
                    val res = getCapturedNotifications(limit)
                    McpToolResult(call.id, call.toolName, res.toString(2))
                }
                else -> McpToolResult(call.id, call.toolName, "Herramienta desconocida: " + call.toolName, isError = true)
            }
        } catch (e: Exception) {
            McpToolResult(call.id, call.toolName, "Error ejecutando " + call.toolName + ": " + e.message, isError = true)
        }
    }

    private fun getDeviceSettings(): JSONObject {
        val root = JSONObject()
        if (context == null) {
            root.put("screen_brightness_percent", 65)
            root.put("music_volume_percent", 70)
            root.put("ringer_mode", "NORMAL")
            root.put("can_write_settings", true)
            root.put("note", "Modo emulado / Test")
            return root
        }

        try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val maxMusic = audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
            val curMusic = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 10
            val musicPct = if (maxMusic > 0) (curMusic * 100 / maxMusic) else 50

            val curBrightness = try {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            } catch (e: Throwable) { 128 }
            val brightPct = (curBrightness * 100 / 255)

            val ringerMode = when (audio?.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "SILENT"
                AudioManager.RINGER_MODE_VIBRATE -> "VIBRATE"
                else -> "NORMAL"
            }

            root.put("screen_brightness_percent", brightPct)
            root.put("music_volume_percent", musicPct)
            root.put("ringer_mode", ringerMode)
            root.put("can_write_settings", Settings.System.canWrite(context))
        } catch (e: Throwable) {
            root.put("error", e.message)
        }
        return root
    }

    private fun setAudioVolume(streamType: String, percent: Int): String {
        if (context == null) {
            return "✅ [Modo Test] Volumen de $streamType ajustado al $percent%."
        }
        return try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val stream = when (streamType) {
                "ring", "ringer" -> AudioManager.STREAM_RING
                "alarm" -> AudioManager.STREAM_ALARM
                else -> AudioManager.STREAM_MUSIC
            }
            val max = audio?.getStreamMaxVolume(stream) ?: 15
            val targetLevel = (percent * max / 100).coerceIn(0, max)
            audio?.setStreamVolume(stream, targetLevel, AudioManager.FLAG_SHOW_UI)
            "✅ Volumen de $streamType ajustado a $targetLevel/$max ($percent%)."
        } catch (e: Throwable) {
            "Error ajustando volumen: " + e.message
        }
    }

    private fun setScreenBrightness(percent: Int): String {
        if (context == null) {
            return "✅ [Modo Test] Brillo de pantalla ajustado al $percent%."
        }
        return try {
            if (!Settings.System.canWrite(context)) {
                return "⚠️ No se tiene permiso de 'Modificar Ajustes del Sistema'. Usa /permissions para activarlo."
            }
            val target = (percent * 255 / 100).coerceIn(0, 255)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, target)
            "✅ Brillo de pantalla ajustado al $percent% ($target/255)."
        } catch (e: Throwable) {
            "Error cambiando brillo: " + e.message
        }
    }

    private fun getAppUsageStats(hoursBack: Int): JSONObject {
        val root = JSONObject()
        if (context == null) {
            val arr = JSONArray().apply {
                put(JSONObject().put("app", "com.whatsapp").put("minutes_foreground", 42))
                put(JSONObject().put("app", "com.google.android.youtube").put("minutes_foreground", 35))
            }
            root.put("count", 2)
            root.put("usage", arr)
            root.put("note", "Modo emulado / Test")
            return root
        }

        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            val now = System.currentTimeMillis()
            val start = now - (hoursBack * 3600L * 1000L)
            val stats = usm?.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now)

            if (stats.isNullOrEmpty()) {
                root.put("available", false)
                root.put("message", "Acceso a Estadísticas de Uso no activo o sin datos. Habilítalo en Ajustes -> Acceso a datos de uso.")
                return root
            }

            val topList = stats.filter { it.totalTimeInForeground > 60000L }
                .sortedByDescending { it.totalTimeInForeground }
                .take(10)

            val arr = JSONArray()
            for (item in topList) {
                val mins = item.totalTimeInForeground / (60 * 1000)
                arr.put(JSONObject().apply {
                    put("package_name", item.packageName)
                    put("minutes_foreground", mins)
                })
            }
            root.put("count", arr.length())
            root.put("usage", arr)
        } catch (e: Throwable) {
            root.put("error", e.message)
        }
        return root
    }

    private fun getCapturedNotifications(limit: Int): JSONObject {
        val root = JSONObject()
        val notifs = CodexNotificationListenerService.getRecentNotifications(limit)
        val arr = JSONArray()
        for (n in notifs) {
            arr.put(JSONObject().apply {
                put("app", n.packageName)
                put("title", n.title)
                put("text", n.text)
                put("time_epoch_ms", n.postTime)
            })
        }
        root.put("count", arr.length())
        root.put("notifications", arr)
        if (arr.length() == 0) {
            root.put("hint", "Si está vacío, activa 'Acceso a notificaciones' para Codex ChatGPT en los Ajustes de Android.")
        }
        return root
    }
}

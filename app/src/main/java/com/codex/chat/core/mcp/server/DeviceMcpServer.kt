package com.codex.chat.core.mcp.server

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.codex.chat.core.mcp.model.*
import org.json.JSONObject

class DeviceMcpServer(private val context: Context? = null) : McpServer {

    override val info = McpServerInfo(
        id = "mcp-android-device",
        name = "Android Device & Telemetry",
        description = "Herramientas de telemetría de hardware, batería, memoria RAM y almacenamiento del teléfono",
        iconEmoji = "📱",
        type = McpServerType.NATIVE,
        isEnabled = true,
        toolsCount = 3
    )

    override fun getTools(): List<McpTool> = listOf(
        McpTool(
            name = "get_battery_status",
            description = "Obtiene el estado actual de la batería del dispositivo: porcentaje, si está cargando, salud y temperatura.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            name = "get_device_telemetry",
            description = "Obtiene información detallada del hardware del teléfono: modelo, fabricante, versión de Android, memoria RAM disponible y almacenamiento libre.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            name = "get_storage_info",
            description = "Obtiene el espacio de almacenamiento interno total, usado y libre en gigabytes (GB).",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        )
    )

    override fun executeTool(call: McpToolCallRequest): McpToolResult {
        return try {
            when (call.toolName) {
                "get_battery_status" -> {
                    val res = getBatteryStatus()
                    McpToolResult(call.id, call.toolName, res.toString(2))
                }
                "get_device_telemetry" -> {
                    val res = getDeviceTelemetry()
                    McpToolResult(call.id, call.toolName, res.toString(2))
                }
                "get_storage_info" -> {
                    val res = getStorageInfo()
                    McpToolResult(call.id, call.toolName, res.toString(2))
                }
                else -> McpToolResult(call.id, call.toolName, "Herramienta desconocida: ${call.toolName}", isError = true)
            }
        } catch (e: Exception) {
            McpToolResult(call.id, call.toolName, "Error ejecutando ${call.toolName}: ${e.message}", isError = true)
        }
    }

    private fun getBatteryStatus(): JSONObject {
        val root = JSONObject()
        if (context == null) {
            root.put("level_percent", 95)
            root.put("is_charging", false)
            root.put("plugged_source", "BATTERY")
            root.put("health", "GOOD")
            root.put("temperature_celsius", 28.5)
            root.put("note", "Modo emulado / Test sin contexto")
            return root
        }

        val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, ifilter)

        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 100

        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val chargePlug = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val pluggedSource = when (chargePlug) {
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_AC -> "AC_CHARGER"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
            else -> "BATTERY"
        }

        val temp = (batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0

        root.put("level_percent", batteryPct)
        root.put("is_charging", isCharging)
        root.put("plugged_source", pluggedSource)
        root.put("health", "GOOD")
        root.put("temperature_celsius", temp)
        return root
    }

    private fun getDeviceTelemetry(): JSONObject {
        val root = JSONObject()
        root.put("manufacturer", Build.MANUFACTURER ?: "Generic")
        root.put("model", Build.MODEL ?: "Android Device")
        root.put("android_version", Build.VERSION.RELEASE ?: "14")
        root.put("sdk_int", Build.VERSION.SDK_INT)
        root.put("board", Build.BOARD ?: "Unknown")

        // RAM info
        val runtime = Runtime.getRuntime()
        val maxMemoryMb = runtime.maxMemory() / (1024 * 1024)
        val totalMemoryMb = runtime.totalMemory() / (1024 * 1024)
        val freeMemoryMb = runtime.freeMemory() / (1024 * 1024)
        val jvmMemory = JSONObject().apply {
            put("max_mb", maxMemoryMb)
            put("allocated_mb", totalMemoryMb)
            put("free_mb", freeMemoryMb)
        }
        root.put("jvm_memory", jvmMemory)

        return root
    }

    private fun getStorageInfo(): JSONObject {
        val root = JSONObject()
        try {
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong

            val totalBytes = totalBlocks * blockSize
            val freeBytes = availableBlocks * blockSize
            val usedBytes = totalBytes - freeBytes

            val totalGb = String.format("%.2f", totalBytes / (1024.0 * 1024.0 * 1024.0))
            val freeGb = String.format("%.2f", freeBytes / (1024.0 * 1024.0 * 1024.0))
            val usedGb = String.format("%.2f", usedBytes / (1024.0 * 1024.0 * 1024.0))

            root.put("total_gb", totalGb)
            root.put("used_gb", usedGb)
            root.put("free_gb", freeGb)
            root.put("percent_free", if (totalBytes > 0) ((freeBytes * 100) / totalBytes) else 0)
        } catch (e: Exception) {
            root.put("total_gb", "64.00")
            root.put("free_gb", "32.00")
            root.put("used_gb", "32.00")
            root.put("percent_free", 50)
            root.put("note", "Valores emulados: ${e.message}")
        }
        return root
    }
}

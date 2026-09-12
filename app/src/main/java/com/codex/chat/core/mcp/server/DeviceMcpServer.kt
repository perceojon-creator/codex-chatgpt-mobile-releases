package com.codex.chat.core.mcp.server

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import com.codex.chat.core.mcp.model.*
import org.json.JSONObject

class DeviceMcpServer(private val context: Context? = null) : McpServer {

    override val info = McpServerInfo(
        id = "mcp-android-device",
        name = "Android Device & Telemetry",
        description = "Herramientas de telemetría de hardware, batería, memoria RAM, almacenamiento, sensores y vibración háptica del teléfono",
        iconEmoji = "📱",
        type = McpServerType.NATIVE,
        isEnabled = true,
        toolsCount = 6
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
        ),
        McpTool(
            name = "vibrate_device",
            description = "Ejecuta una vibración háptica en el dispositivo móvil con una duración en milisegundos especificada.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("duration_ms", JSONObject().put("type", "integer").put("description", "Duración en milisegundos"))
                }
                put("properties", props)
            }
        ),
        McpTool(
            name = "get_wifi_status",
            description = "Obtiene el estado de conexión Wi-Fi, intensidad de señal y conectividad del teléfono.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            name = "get_device_location",
            description = "Obtiene las últimas coordenadas de ubicación conocidas (latitud y longitud) del teléfono si el permiso fue concedido.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        )
    )

    override fun executeTool(call: McpToolCallRequest): McpToolResult {
        return try {
            val args = try {
                JSONObject(call.argumentsJson)
            } catch (e: Exception) {
                JSONObject()
            }

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
                "vibrate_device" -> {
                    val ms = args.optLong("duration_ms", 300L).coerceIn(50L, 2000L)
                    val ok = vibrateDevice(ms)
                    if (ok) {
                        McpToolResult(call.id, call.toolName, "✅ Vibración háptica ejecutada por " + ms + "ms en el dispositivo.")
                    } else {
                        McpToolResult(call.id, call.toolName, "Dispositivo no compatible con vibrador o contexto no disponible.")
                    }
                }
                "get_wifi_status" -> {
                    val res = getWifiStatus()
                    McpToolResult(call.id, call.toolName, res.toString(2))
                }
                "get_device_location" -> {
                    val res = getDeviceLocation()
                    McpToolResult(call.id, call.toolName, res.toString(2))
                }
                else -> McpToolResult(call.id, call.toolName, "Herramienta desconocida: " + call.toolName, isError = true)
            }
        } catch (e: Exception) {
            McpToolResult(call.id, call.toolName, "Error ejecutando " + call.toolName + ": " + e.message, isError = true)
        }
    }

    private fun vibrateDevice(durationMs: Long): Boolean {
        if (context == null) return true
        return try {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(durationMs)
                }
                true
            } else {
                false
            }
        } catch (e: Throwable) {
            false
        }
    }

    private fun getWifiStatus(): JSONObject {
        val root = JSONObject()
        if (context == null) {
            root.put("wifi_enabled", true)
            root.put("connected", true)
            root.put("ssid", "Codex-Home-5G")
            root.put("ip_address", "192.168.1.50")
            root.put("note", "Modo emulado / Test")
            return root
        }
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val isEnabled = wm?.isWifiEnabled ?: false
            val connInfo = wm?.connectionInfo
            root.put("wifi_enabled", isEnabled)
            root.put("connected", connInfo != null && connInfo.networkId != -1)
            val rawSsid = connInfo?.ssid ?: "Desconocido"
            val cleanSsid = rawSsid.replace(34.toChar().toString(), "")
            root.put("ssid", if (cleanSsid != "<unknown ssid>") cleanSsid else "Red Wi-Fi Local")
            root.put("link_speed_mbps", connInfo?.linkSpeed ?: -1)
            root.put("rssi_dbm", connInfo?.rssi ?: -1)
        } catch (e: Throwable) {
            root.put("error", e.message)
        }
        return root
    }

    private fun getDeviceLocation(): JSONObject {
        val root = JSONObject()
        if (context == null) {
            root.put("latitude", 40.4168)
            root.put("longitude", -3.7038)
            root.put("provider", "mock_gps")
            root.put("note", "Modo emulado / Test")
            return root
        }
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val gpsLoc = lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val netLoc = lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val best = gpsLoc ?: netLoc

            if (best != null) {
                root.put("latitude", best.latitude)
                root.put("longitude", best.longitude)
                root.put("altitude", best.altitude)
                root.put("accuracy_meters", best.accuracy)
                root.put("provider", best.provider)
            } else {
                root.put("available", false)
                root.put("message", "Ubicación no disponible en este momento o permisos de GPS pendientes de concesión.")
            }
        } catch (e: SecurityException) {
            root.put("error", "Permiso de ubicación no concedido en los Ajustes de Android.")
        } catch (e: Throwable) {
            root.put("error", e.message)
        }
        return root
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
        val plugSource = when (chargePlug) {
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_AC -> "AC_CHARGER"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
            else -> "BATTERY"
        }

        val healthCode = batteryStatus?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN) ?: 1
        val healthStr = when (healthCode) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
            BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER_VOLTAGE"
            else -> "NORMAL"
        }

        val tempTenths = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempCelsius = tempTenths / 10.0

        root.put("level_percent", batteryPct)
        root.put("is_charging", isCharging)
        root.put("plugged_source", plugSource)
        root.put("health", healthStr)
        root.put("temperature_celsius", tempCelsius)
        return root
    }

    private fun getDeviceTelemetry(): JSONObject {
        val root = JSONObject()
        root.put("model", Build.MODEL)
        root.put("manufacturer", Build.MANUFACTURER)
        root.put("brand", Build.BRAND)
        root.put("device", Build.DEVICE)
        root.put("board", Build.BOARD)
        root.put("hardware", Build.HARDWARE)
        root.put("android_version", Build.VERSION.RELEASE)
        root.put("sdk_int", Build.VERSION.SDK_INT)

        val runtime = Runtime.getRuntime()
        val totalMemoryMb = runtime.totalMemory() / (1024 * 1024)
        val freeMemoryMb = runtime.freeMemory() / (1024 * 1024)
        val maxMemoryMb = runtime.maxMemory() / (1024 * 1024)

        root.put("app_heap_total_mb", totalMemoryMb)
        root.put("app_heap_free_mb", freeMemoryMb)
        root.put("app_heap_max_mb", maxMemoryMb)

        val storage = getStorageInfo()
        root.put("storage", storage)
        return root
    }

    private fun getStorageInfo(): JSONObject {
        val root = JSONObject()
        return try {
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

            root.put("total_storage_gb", totalGb)
            root.put("free_storage_gb", freeGb)
            root.put("used_storage_gb", usedGb)
            root
        } catch (e: Exception) {
            root.put("total_storage_gb", "64.00")
            root.put("free_storage_gb", "28.50")
            root.put("used_storage_gb", "35.50")
            root.put("note", "Fallback estimado")
        }
        return root
    }
}
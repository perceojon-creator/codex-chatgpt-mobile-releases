package com.codex.chat.core.mcp.server

import android.content.Context
import com.codex.chat.agent.core.AgentAction
import com.codex.chat.agent.device.CodexAccessibilityService
import com.codex.chat.agent.device.DeviceMetricsProvider
import com.codex.chat.agent.device.IDeviceController
import com.codex.chat.core.mcp.model.*
import com.codex.chat.core.security.EstopSentinel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native Model Context Protocol (MCP) Server for Android Mobile Use.
 * Exposes device perception (screenshots, accessibility hierarchy) and
 * physical gesture actuation (tap, swipe, text injection, global keys)
 * directly to conversational LLM tool-calling.
 */
class MobileUseMcpServer(
    private val context: Context? = null,
    private val deviceControllerProvider: () -> IDeviceController? = { CodexAccessibilityService.instance }
) : McpServer {

    override val info = McpServerInfo(
        id = "mcp-mobile-use",
        name = "Mobile Use (Control de Pantalla y Gestos)",
        description = "Herramientas de visión de pantalla y gestos táctiles para operar el teléfono de forma autónoma",
        iconEmoji = "📱",
        type = McpServerType.NATIVE,
        isEnabled = true,
        toolsCount = 6
    )

    override fun getTools(): List<McpTool> = listOf(
        McpTool(
            name = "mobile_get_screen",
            description = "Captura la pantalla actual del dispositivo y devuelve el árbol de elementos interactivos visibles junto con las dimensiones de la pantalla.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("max_dimension", JSONObject().put("type", "integer").put("description", "Dimensión máxima de la imagen en píxeles (defecto 1080)"))
                    put("quality", JSONObject().put("type", "integer").put("description", "Calidad de compresión JPEG de 1 a 100 (defecto 75)"))
                }
                put("properties", props)
            }
        ),
        McpTool(
            name = "mobile_click",
            description = "Ejecuta un toque táctil (tap) en las coordenadas físicas exactas (x, y) de la pantalla del dispositivo.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("required", JSONArray().put("x").put("y"))
                val props = JSONObject().apply {
                    put("x", JSONObject().put("type", "integer").put("description", "Coordenada X en píxeles de la pantalla"))
                    put("y", JSONObject().put("type", "integer").put("description", "Coordenada Y en píxeles de la pantalla"))
                    put("reason", JSONObject().put("type", "string").put("description", "Explicación concisa del motivo del toque"))
                }
                put("properties", props)
            }
        ),
        McpTool(
            name = "mobile_swipe",
            description = "Desplaza la pantalla ejecutando un deslizamiento (swipe / scroll) suave desde (startX, startY) hasta (endX, endY).",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("required", JSONArray().put("startX").put("startY").put("endX").put("endY"))
                val props = JSONObject().apply {
                    put("startX", JSONObject().put("type", "integer").put("description", "Coordenada X inicial"))
                    put("startY", JSONObject().put("type", "integer").put("description", "Coordenada Y inicial"))
                    put("endX", JSONObject().put("type", "integer").put("description", "Coordenada X final"))
                    put("endY", JSONObject().put("type", "integer").put("description", "Coordenada Y final"))
                    put("duration_ms", JSONObject().put("type", "integer").put("description", "Duración del gesto en milisegundos (defecto 300)"))
                    put("reason", JSONObject().put("type", "string").put("description", "Motivo del deslizamiento"))
                }
                put("properties", props)
            }
        ),
        McpTool(
            name = "mobile_type",
            description = "Escribe texto en el campo de entrada editable o enfocado actualmente, con opción de pulsar Enter o la tecla de búsqueda.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("required", JSONArray().put("text"))
                val props = JSONObject().apply {
                    put("text", JSONObject().put("type", "string").put("description", "Texto que se va a escribir"))
                    put("press_enter", JSONObject().put("type", "boolean").put("description", "Si es true, presiona Enter o Buscar tras escribir"))
                    put("reason", JSONObject().put("type", "string").put("description", "Motivo de la escritura"))
                }
                put("properties", props)
            }
        ),
        McpTool(
            name = "mobile_press_key",
            description = "Presiona una tecla global de navegación del sistema Android: 'HOME' (inicio), 'BACK' (volver atrás) o 'RECENTS' (multitarea).",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("required", JSONArray().put("key"))
                val props = JSONObject().apply {
                    put("key", JSONObject().put("type", "string").put("description", "Tecla global: HOME, BACK o RECENTS"))
                    put("reason", JSONObject().put("type", "string").put("description", "Motivo de presionar la tecla"))
                }
                put("properties", props)
            }
        ),
        McpTool(
            name = "mobile_wait",
            description = "Espera una cantidad especificada de segundos para permitir que la pantalla, video o contenido termine de cargar.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("required", JSONArray().put("seconds"))
                val props = JSONObject().apply {
                    put("seconds", JSONObject().put("type", "integer").put("description", "Número de segundos de espera (mínimo 1)"))
                    put("reason", JSONObject().put("type", "string").put("description", "Motivo de la espera"))
                }
                put("properties", props)
            }
        )
    )

    override fun executeTool(call: McpToolCallRequest): McpToolResult {
        try {
            EstopSentinel.checkOrThrow()
        } catch (e: Exception) {
            return McpToolResult(call.id, call.toolName, "Error: [ESTOP] " + e.message, isError = true)
        }

        val device = deviceControllerProvider()
        if (device == null || !device.isAvailable) {
            return McpToolResult(
                call.id,
                call.toolName,
                "Error: El servicio de accesibilidad 'Autonomous Agent Mode' no está activo o vinculado en el dispositivo.",
                isError = true
            )
        }

        val args = try {
            JSONObject(if (call.argumentsJson.isBlank()) "{}" else call.argumentsJson)
        } catch (e: Exception) {
            return McpToolResult(call.id, call.toolName, "Error: Argumentos JSON inválidos: " + e.message, isError = true)
        }

        return try {
            when (call.toolName) {
                "mobile_get_screen" -> executeGetScreen(call.id, device, args)
                "mobile_click" -> executeClick(call.id, device, args)
                "mobile_swipe" -> executeSwipe(call.id, device, args)
                "mobile_type" -> executeType(call.id, device, args)
                "mobile_press_key" -> executePressKey(call.id, device, args)
                "mobile_wait" -> executeWait(call.id, device, args)
                else -> McpToolResult(call.id, call.toolName, "Error: Herramienta móvil desconocida '" + call.toolName + "'", isError = true)
            }
        } catch (e: Exception) {
            McpToolResult(call.id, call.toolName, "Error durante ejecución de " + call.toolName + ": " + e.message, isError = true)
        }
    }

    private fun executeGetScreen(callId: String, device: IDeviceController, args: JSONObject): McpToolResult {
        val maxDim = args.optInt("max_dimension", 1080)
        val quality = args.optInt("quality", 75)

        // BUG-6 FIX: Retry screenshot capture up to 3 times with 400ms delay.
        // ScreenCaptureService.imageReader may still be null if VirtualDisplay is initializing.
        val (screenshot, hierarchy) = runBlocking {
            var s = ""
            var attempts = 0
            while (s.isEmpty() && attempts < 3) {
                if (attempts > 0) kotlinx.coroutines.delay(400L)
                s = device.captureScreenshotBase64(maxDim, quality)
                attempts++
            }
            if (s.isEmpty()) {
                android.util.Log.w("MobileUseMcpServer", "captureScreenshotBase64 returned empty after 3 attempts — VirtualDisplay may not be ready")
            }
            val h = device.dumpUiHierarchy()
            Pair(s, h)
        }

        val screenW = context?.let { DeviceMetricsProvider.getScreenWidth(it) } ?: 1080
        val screenH = context?.let { DeviceMetricsProvider.getScreenHeight(it) } ?: 1920

        val hierarchyJson = try {
            JSONObject(hierarchy)
        } catch (_: Throwable) {
            JSONObject().put("count", 0)
        }
        val elementsCount = hierarchyJson.optInt("count", 0)

        val responseJson = JSONObject().apply {
            put("status", "success")
            put("screen_width", screenW)
            put("screen_height", screenH)
            put("interactive_elements_count", elementsCount)
            put("ui_hierarchy", hierarchy)
            put("screenshot_base64", screenshot)
            put("summary", "📸 Pantalla capturada (" + screenW + "x" + screenH + ", " + elementsCount + " elementos interactivos)")
        }

        return McpToolResult(callId, "mobile_get_screen", responseJson.toString())
    }

    private fun executeClick(callId: String, device: IDeviceController, args: JSONObject): McpToolResult {
        val x = args.getInt("x")
        val y = args.getInt("y")
        val reason = args.optString("reason", "Click at (" + x + ", " + y + ")")

        val success = runBlocking {
            device.dispatch(AgentAction.Tap(x, y, reason))
        }

        val resp = JSONObject().apply {
            put("status", if (success) "success" else "failed")
            put("action", "tap")
            put("x", x)
            put("y", y)
            put("reason", reason)
        }
        return McpToolResult(callId, "mobile_click", resp.toString(), isError = !success)
    }

    private fun executeSwipe(callId: String, device: IDeviceController, args: JSONObject): McpToolResult {
        val sx = args.getInt("startX")
        val sy = args.getInt("startY")
        val ex = args.getInt("endX")
        val ey = args.getInt("endY")
        val dur = args.optLong("duration_ms", 300L)
        val reason = args.optString("reason", "Swipe from (" + sx + ", " + sy + ") to (" + ex + ", " + ey + ")")

        val success = runBlocking {
            device.dispatch(AgentAction.Swipe(sx, sy, ex, ey, dur, reason))
        }

        val resp = JSONObject().apply {
            put("status", if (success) "success" else "failed")
            put("action", "swipe")
            put("startX", sx)
            put("startY", sy)
            put("endX", ex)
            put("endY", ey)
            put("duration_ms", dur)
            put("reason", reason)
        }
        return McpToolResult(callId, "mobile_swipe", resp.toString(), isError = !success)
    }

    private fun executeType(callId: String, device: IDeviceController, args: JSONObject): McpToolResult {
        val text = args.getString("text")
        val pressEnter = args.optBoolean("press_enter", false)
        val reason = args.optString("reason", "Type: " + text)

        val success = runBlocking {
            device.dispatch(AgentAction.InputText(text, pressEnter, reason))
        }

        val resp = JSONObject().apply {
            put("status", if (success) "success" else "failed")
            put("action", "input_text")
            put("text", text)
            put("press_enter", pressEnter)
            put("reason", reason)
        }
        return McpToolResult(callId, "mobile_type", resp.toString(), isError = !success)
    }

    private fun executePressKey(callId: String, device: IDeviceController, args: JSONObject): McpToolResult {
        val keyStr = args.getString("key").uppercase()
        val reason = args.optString("reason", "Press " + keyStr)
        val key = try {
            AgentAction.GlobalKey.valueOf(keyStr)
        } catch (_: Throwable) {
            return McpToolResult(callId, "mobile_press_key", "Error: Tecla inválida '" + keyStr + "'. Válidas: HOME, BACK, RECENTS", isError = true)
        }

        val success = runBlocking {
            device.dispatch(AgentAction.PressKey(key, reason))
        }

        val resp = JSONObject().apply {
            put("status", if (success) "success" else "failed")
            put("action", "press_key")
            put("key", keyStr)
            put("reason", reason)
        }
        return McpToolResult(callId, "mobile_press_key", resp.toString(), isError = !success)
    }

    private fun executeWait(callId: String, device: IDeviceController, args: JSONObject): McpToolResult {
        val seconds = args.optInt("seconds", 1).coerceAtLeast(1)
        val reason = args.optString("reason", "Wait " + seconds + "s")

        val success = runBlocking {
            device.dispatch(AgentAction.Wait(seconds, reason))
        }

        val resp = JSONObject().apply {
            put("status", if (success) "success" else "failed")
            put("action", "wait")
            put("seconds", seconds)
            put("reason", reason)
        }
        return McpToolResult(callId, "mobile_wait", resp.toString(), isError = !success)
    }
}
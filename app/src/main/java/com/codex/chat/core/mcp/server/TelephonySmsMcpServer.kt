package com.codex.chat.core.mcp.server

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.telephony.SmsManager
import com.codex.chat.core.mcp.model.*
import org.json.JSONArray
import org.json.JSONObject

class TelephonySmsMcpServer(private val context: Context? = null) : McpServer {

    override val info = McpServerInfo(
        id = "mcp-android-telephony-sms",
        name = "Telephony & SMS Engine",
        description = "Consulta de historial de llamadas telefónicas, lectura de mensajes SMS y envío de SMS",
        iconEmoji = "📞",
        type = McpServerType.NATIVE,
        isEnabled = true,
        toolsCount = 3
    )

    override fun getTools(): List<McpTool> = listOf(
        McpTool(
            name = "get_call_log",
            description = "Consulta las últimas llamadas telefónicas entrantes, salientes o perdidas.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("limit", JSONObject().put("type", "integer").put("description", "Número de llamadas a recuperar (por defecto 10)"))
                }
                put("properties", props)
            }
        ),
        McpTool(
            name = "read_sms_messages",
            description = "Lee los últimos mensajes de texto SMS recibidos o enviados.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("limit", JSONObject().put("type", "integer").put("description", "Número de mensajes a recuperar (por defecto 10)"))
                }
                put("properties", props)
            }
        ),
        McpTool(
            name = "send_sms",
            description = "Envía un mensaje de texto SMS a un número de teléfono especificado.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("phone_number", JSONObject().put("type", "string").put("description", "Número de teléfono destino (ej. '+34612345678')"))
                    put("message", JSONObject().put("type", "string").put("description", "Texto del mensaje a enviar"))
                }
                put("properties", props)
                put("required", JSONArray().put("phone_number").put("message"))
            }
        )
    )

    override fun executeTool(call: McpToolCallRequest): McpToolResult {
        return try {
            val args = try { JSONObject(call.argumentsJson) } catch (e: Exception) { JSONObject() }
            when (call.toolName) {
                "get_call_log" -> {
                    val limit = args.optInt("limit", 10).coerceIn(1, 50)
                    val res = getCallLog(limit)
                    McpToolResult(call.id, call.toolName, res.toString(2))
                }
                "read_sms_messages" -> {
                    val limit = args.optInt("limit", 10).coerceIn(1, 50)
                    val res = getSmsMessages(limit)
                    McpToolResult(call.id, call.toolName, res.toString(2))
                }
                "send_sms" -> {
                    val phone = args.optString("phone_number", "").trim()
                    val text = args.optString("message", "").trim()
                    if (phone.isEmpty() || text.isEmpty()) {
                        return McpToolResult(call.id, call.toolName, "Faltan 'phone_number' o 'message'", isError = true)
                    }
                    val res = sendSmsMessage(phone, text)
                    McpToolResult(call.id, call.toolName, res)
                }
                else -> McpToolResult(call.id, call.toolName, "Herramienta desconocida: " + call.toolName, isError = true)
            }
        } catch (e: Exception) {
            McpToolResult(call.id, call.toolName, "Error ejecutando " + call.toolName + ": " + e.message, isError = true)
        }
    }

    private fun getCallLog(limit: Int): JSONObject {
        val root = JSONObject()
        if (context == null) {
            val arr = JSONArray().apply {
                put(JSONObject().put("number", "+34612000111").put("type", "INCOMING").put("duration_sec", 45))
                put(JSONObject().put("number", "+34699333444").put("type", "MISSED").put("duration_sec", 0))
            }
            root.put("count", 2)
            root.put("calls", arr)
            root.put("note", "Modo emulado / Test")
            return root
        }

        val arr = JSONArray()
        var cursor: Cursor? = null
        try {
            val uri = CallLog.Calls.CONTENT_URI
            val projection = arrayOf(
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION
            )
            val sortOrder = CallLog.Calls.DATE + " DESC"

            cursor = context.contentResolver.query(uri, projection, null, null, sortOrder)
            var count = 0
            if (cursor != null) {
                while (cursor.moveToNext() && count < limit) {
                    val number = cursor.getString(0) ?: "Desconocido"
                    val typeCode = cursor.getInt(1)
                    val duration = cursor.getInt(3)
                    val typeStr = when (typeCode) {
                        CallLog.Calls.INCOMING_TYPE -> "INCOMING"
                        CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                        CallLog.Calls.MISSED_TYPE -> "MISSED"
                        CallLog.Calls.REJECTED_TYPE -> "REJECTED"
                        else -> "CALL"
                    }
                    arr.put(JSONObject().put("number", number).put("type", typeStr).put("duration_sec", duration))
                    count++
                }
            }
            root.put("count", arr.length())
            root.put("calls", arr)
        } catch (e: SecurityException) {
            root.put("error", "Permiso de Registro de Llamadas no concedido. Usa /permissions para habilitarlo.")
        } catch (e: Throwable) {
            root.put("error", e.message)
        } finally {
            cursor?.close()
        }
        return root
    }

    private fun getSmsMessages(limit: Int): JSONObject {
        val root = JSONObject()
        if (context == null) {
            val arr = JSONArray().apply {
                put(JSONObject().put("sender", "Banco").put("body", "Tu código de verificación es 492012"))
            }
            root.put("count", 1)
            root.put("messages", arr)
            root.put("note", "Modo emulado / Test")
            return root
        }

        val arr = JSONArray()
        var cursor: Cursor? = null
        try {
            val uri = Uri.parse("content://sms/inbox")
            val projection = arrayOf("address", "body", "date")
            val sortOrder = "date DESC"

            cursor = context.contentResolver.query(uri, projection, null, null, sortOrder)
            var count = 0
            if (cursor != null) {
                while (cursor.moveToNext() && count < limit) {
                    val sender = cursor.getString(0) ?: "Desconocido"
                    val body = cursor.getString(1) ?: ""
                    arr.put(JSONObject().put("sender", sender).put("body", body))
                    count++
                }
            }
            root.put("count", arr.length())
            root.put("messages", arr)
        } catch (e: SecurityException) {
            root.put("error", "Permiso de Lectura de SMS no concedido. Usa /permissions para habilitarlo.")
        } catch (e: Throwable) {
            root.put("error", e.message)
        } finally {
            cursor?.close()
        }
        return root
    }

    private fun sendSmsMessage(phoneNumber: String, message: String): String {
        if (context == null) {
            return "✅ [Modo Test] SMS enviado con éxito a $phoneNumber: '$message'"
        }
        return try {
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(phoneNumber, null, message, null, null)
            "✅ Mensaje SMS enviado exitosamente a $phoneNumber."
        } catch (e: SecurityException) {
            "Permiso para enviar SMS no concedido por el sistema."
        } catch (e: Throwable) {
            "Error enviando SMS: " + e.message
        }
    }
}

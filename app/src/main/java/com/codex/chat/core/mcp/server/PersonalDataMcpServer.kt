package com.codex.chat.core.mcp.server

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.provider.CalendarContract
import android.provider.ContactsContract
import com.codex.chat.core.mcp.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

class PersonalDataMcpServer(private val context: Context? = null) : McpServer {

    override val info = McpServerInfo(
        id = "mcp-android-personal-data",
        name = "Contacts & Calendar Store",
        description = "Acceso a la agenda telefónica, búsqueda de contactos y gestión de eventos en el calendario del teléfono",
        iconEmoji = "📅",
        type = McpServerType.NATIVE,
        isEnabled = true,
        toolsCount = 3
    )

    override fun getTools(): List<McpTool> = listOf(
        McpTool(
            name = "list_contacts",
            description = "Busca o lista contactos en la agenda del teléfono por nombre o número.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("query", JSONObject().put("type", "string").put("description", "Filtro opcional por nombre o número (dejar vacío para listar los primeros)"))
                    put("limit", JSONObject().put("type", "integer").put("description", "Máximo de contactos a recuperar (por defecto 15)"))
                }
                put("properties", props)
            }
        ),
        McpTool(
            name = "list_calendar_events",
            description = "Consulta los próximos eventos o citas en el calendario del dispositivo.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("days_ahead", JSONObject().put("type", "integer").put("description", "Días hacia adelante a consultar (por defecto 7)"))
                    put("limit", JSONObject().put("type", "integer").put("description", "Máximo de eventos a listar (por defecto 10)"))
                }
                put("properties", props)
            }
        ),
        McpTool(
            name = "create_calendar_event",
            description = "Crea un nuevo evento o recordatorio en el calendario del teléfono.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("title", JSONObject().put("type", "string").put("description", "Título del evento"))
                    put("description", JSONObject().put("type", "string").put("description", "Descripción opcional"))
                    put("duration_minutes", JSONObject().put("type", "integer").put("description", "Duración en minutos (ej. 60)"))
                }
                put("properties", props)
                put("required", JSONArray().put("title"))
            }
        )
    )

    override fun executeTool(call: McpToolCallRequest): McpToolResult {
        return try {
            val args = try { JSONObject(call.argumentsJson) } catch (e: Exception) { JSONObject() }
            when (call.toolName) {
                "list_contacts" -> {
                    val q = args.optString("query", "").trim()
                    val limit = args.optInt("limit", 15).coerceIn(1, 100)
                    val res = getContacts(q, limit)
                    McpToolResult(call.id, call.toolName, res.toString(2))
                }
                "list_calendar_events" -> {
                    val days = args.optInt("days_ahead", 7).coerceIn(1, 365)
                    val limit = args.optInt("limit", 10).coerceIn(1, 50)
                    val res = getCalendarEvents(days, limit)
                    McpToolResult(call.id, call.toolName, res.toString(2))
                }
                "create_calendar_event" -> {
                    val title = args.optString("title", "").trim()
                    if (title.isEmpty()) {
                        return McpToolResult(call.id, call.toolName, "Falta el título del evento", isError = true)
                    }
                    val desc = args.optString("description", "")
                    val durationMin = args.optInt("duration_minutes", 60)
                    val res = createCalendarEvent(title, desc, durationMin)
                    McpToolResult(call.id, call.toolName, res)
                }
                else -> McpToolResult(call.id, call.toolName, "Herramienta desconocida: " + call.toolName, isError = true)
            }
        } catch (e: Exception) {
            McpToolResult(call.id, call.toolName, "Error ejecutando " + call.toolName + ": " + e.message, isError = true)
        }
    }

    private fun getContacts(query: String, limit: Int): JSONObject {
        val root = JSONObject()
        if (context == null) {
            val arr = JSONArray().apply {
                put(JSONObject().put("name", "Carlos Martínez").put("number", "+34 612 345 678"))
                put(JSONObject().put("name", "Laura Gomez").put("number", "+34 699 876 543"))
            }
            root.put("count", 2)
            root.put("contacts", arr)
            root.put("note", "Modo emulado / Test sin contexto")
            return root
        }

        val arr = JSONArray()
        var cursor: Cursor? = null
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = if (query.isNotEmpty()) {
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ? OR " +
                ContactsContract.CommonDataKinds.Phone.NUMBER + " LIKE ?"
            } else null
            val selectionArgs = if (query.isNotEmpty()) arrayOf("%" + query + "%", "%" + query + "%") else null
            val sortOrder = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"

            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
            var count = 0
            if (cursor != null) {
                while (cursor.moveToNext() && count < limit) {
                    val name = cursor.getString(0) ?: "Sin nombre"
                    val number = cursor.getString(1) ?: ""
                    arr.put(JSONObject().put("name", name).put("number", number))
                    count++
                }
            }
            root.put("count", arr.length())
            root.put("contacts", arr)
        } catch (e: SecurityException) {
            root.put("error", "Permiso de Contactos no concedido. Usa /permissions para habilitarlo.")
        } catch (e: Throwable) {
            root.put("error", e.message)
        } finally {
            cursor?.close()
        }
        return root
    }

    private fun getCalendarEvents(daysAhead: Int, limit: Int): JSONObject {
        val root = JSONObject()
        if (context == null) {
            val arr = JSONArray().apply {
                put(JSONObject().put("title", "Reunión de Proyecto").put("start_time", "Mañana 10:00").put("description", "Revisión técnica"))
            }
            root.put("count", 1)
            root.put("events", arr)
            root.put("note", "Modo emulado / Test")
            return root
        }

        val arr = JSONArray()
        var cursor: Cursor? = null
        try {
            val now = System.currentTimeMillis()
            val end = now + (daysAhead * 24L * 3600L * 1000L)
            val uri = CalendarContract.Events.CONTENT_URI
            val projection = arrayOf(
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DESCRIPTION
            )
            val selection = CalendarContract.Events.DTSTART + " >= ? AND " + CalendarContract.Events.DTSTART + " <= ?"
            val selectionArgs = arrayOf(now.toString(), end.toString())
            val sortOrder = CalendarContract.Events.DTSTART + " ASC"

            cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
            var count = 0
            if (cursor != null) {
                while (cursor.moveToNext() && count < limit) {
                    val title = cursor.getString(0) ?: "Sin título"
                    val dtStart = cursor.getLong(1)
                    val desc = cursor.getString(2) ?: ""
                    arr.put(JSONObject().put("title", title).put("start_timestamp", dtStart).put("description", desc))
                    count++
                }
            }
            root.put("count", arr.length())
            root.put("events", arr)
        } catch (e: SecurityException) {
            root.put("error", "Permiso de Calendario no concedido. Usa /permissions para habilitarlo.")
        } catch (e: Throwable) {
            root.put("error", e.message)
        } finally {
            cursor?.close()
        }
        return root
    }

    private fun createCalendarEvent(title: String, description: String, durationMinutes: Int): String {
        if (context == null) {
            return "✅ [Modo Test] Evento '$title' creado con éxito en el calendario."
        }
        return try {
            val startMillis = System.currentTimeMillis() + (3600L * 1000L) // En 1 hora por defecto
            val endMillis = startMillis + (durationMinutes * 60L * 1000L)

            val values = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, startMillis)
                put(CalendarContract.Events.DTEND, endMillis)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, description)
                put(CalendarContract.Events.CALENDAR_ID, 1)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) {
                "✅ Evento '$title' programado con éxito en tu Google Calendar."
            } else {
                "No se pudo insertar el evento en el calendario principal."
            }
        } catch (e: SecurityException) {
            "Permiso de escritura de Calendario no concedido."
        } catch (e: Throwable) {
            "Error creando evento: " + e.message
        }
    }
}

package com.codex.chat.media

import com.codex.chat.core.media.VisualMediaParser
import com.codex.chat.core.media.VisualMediaType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*

class JsonRoundTripImageTest {
    @Test
    fun test_roundtrip_and_parse() {
        val b64 = "/9j/4AAQSkZJRgABAQ".repeat(55000) // ~880KB
        val marker = "\n\n![imagen-generada](data:image/jpeg;base64,$b64)\n\n"
        
        val t0 = System.currentTimeMillis()
        val root = JSONArray()
        val session = JSONObject()
        session.put("id", "sess-1")
        session.put("title", "Test Sess")
        session.put("timestamp", System.currentTimeMillis())
        val msgs = JSONArray()
        val m = JSONObject()
        m.put("role", "assistant")
        m.put("content", marker)
        msgs.put(m)
        session.put("messages", msgs)
        root.put(session)
        val t1 = System.currentTimeMillis()
        
        val jsonStr = root.toString(2)
        val t2 = System.currentTimeMillis()
        
        val parsedRoot = JSONArray(jsonStr)
        val t3 = System.currentTimeMillis()
        val parsedContent = parsedRoot.getJSONObject(0).getJSONArray("messages").getJSONObject(0).getString("content")
        
        val parsedVisual = VisualMediaParser.parse(parsedContent)
        val t4 = System.currentTimeMillis()
        
        println("Build JSON: ${t1 - t0}ms")
        println("toString(2) (${jsonStr.length} chars): ${t2 - t1}ms")
        println("JSONArray parse: ${t3 - t2}ms")
        println("VisualMediaParser.parse: ${t4 - t3}ms")
        assertTrue(parsedVisual.hasMedia)
        assertEquals(VisualMediaType.IMAGE, parsedVisual.type)
    }
}
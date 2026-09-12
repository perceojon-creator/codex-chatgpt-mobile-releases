package com.codex.chat.core.mcp.server

import com.codex.chat.core.mcp.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.*

class CalculatorMcpServer : McpServer {

    override val info = McpServerInfo(
        id = "mcp-android-calculator",
        name = "Math Engine & Utilities",
        description = "Evaluación de expresiones matemáticas de alta precisión, hashes criptográficos y codificación Base64",
        iconEmoji = "🧮",
        type = McpServerType.NATIVE,
        isEnabled = true,
        toolsCount = 3
    )

    override fun getTools(): List<McpTool> = listOf(
        McpTool(
            name = "evaluate_math",
            description = "Calcula el resultado exacto de expresiones matemáticas (ej. '2^16 - 1', 'sqrt(144) + 15 * 3', 'sin(3.14159/2)').",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("expression", JSONObject().put("type", "string").put("description", "Expresión aritmética a evaluar"))
                }
                put("properties", props)
                put("required", JSONArray().put("expression"))
            }
        ),
        McpTool(
            name = "compute_hash",
            description = "Calcula el hash criptográfico (SHA-256, SHA-512, MD5 o SHA-1) de una cadena de texto.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("text", JSONObject().put("type", "string").put("description", "Texto de entrada"))
                    put("algorithm", JSONObject().put("type", "string").put("description", "Algoritmo: 'SHA-256', 'SHA-512', 'MD5', 'SHA-1'"))
                }
                put("properties", props)
                put("required", JSONArray().put("text"))
            }
        ),
        McpTool(
            name = "base64_codec",
            description = "Codifica o decodifica una cadena en formato Base64.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("text", JSONObject().put("type", "string").put("description", "Cadena a transformar"))
                    put("operation", JSONObject().put("type", "string").put("description", "'encode' o 'decode'"))
                }
                put("properties", props)
                put("required", JSONArray().put("text").put("operation"))
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
                "evaluate_math" -> {
                    val expr = args.optString("expression", "").trim()
                    if (expr.isEmpty()) return McpToolResult(call.id, call.toolName, "Expresión vacía", isError = true)
                    val result = eval(expr)
                    McpToolResult(call.id, call.toolName, "Resultado de '$expr' = $result")
                }
                "compute_hash" -> {
                    val text = args.optString("text", "")
                    val algo = args.optString("algorithm", "SHA-256").uppercase()
                    val md = MessageDigest.getInstance(algo)
                    val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
                    val hex = bytes.joinToString("") { "%02x".format(it) }
                    McpToolResult(call.id, call.toolName, "$algo Hash = $hex")
                }
                "base64_codec" -> {
                    val text = args.optString("text", "")
                    val op = args.optString("operation", "encode").lowercase()
                    if (op == "decode") {
                        val decoded = String(Base64.getDecoder().decode(text), Charsets.UTF_8)
                        McpToolResult(call.id, call.toolName, "Base64 Decoded: $decoded")
                    } else {
                        val encoded = Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))
                        McpToolResult(call.id, call.toolName, "Base64 Encoded: $encoded")
                    }
                }
                else -> McpToolResult(call.id, call.toolName, "Herramienta desconocida: ${call.toolName}", isError = true)
            }
        } catch (e: Exception) {
            McpToolResult(call.id, call.toolName, "Error matemático/utilidad: ${e.message}", isError = true)
        }
    }

    private fun eval(str: String): Double {
        return object : Any() {
            var pos = -1
            var ch = 0

            fun nextChar() {
                ch = if (++pos < str.length) str[pos].code else -1
            }

            fun eat(charToEat: Int): Boolean {
                while (ch == ' '.code) nextChar()
                if (ch == charToEat) {
                    nextChar()
                    return true
                }
                return false
            }

            fun parse(): Double {
                nextChar()
                val x = parseExpression()
                if (pos < str.length) throw RuntimeException("Carácter inesperado: " + ch.toChar())
                return x
            }

            fun parseExpression(): Double {
                var x = parseTerm()
                while (true) {
                    when {
                        eat('+'.code) -> x += parseTerm()
                        eat('-'.code) -> x -= parseTerm()
                        else -> return x
                    }
                }
            }

            fun parseTerm(): Double {
                var x = parseFactor()
                while (true) {
                    when {
                        eat('*'.code) -> x *= parseFactor()
                        eat('/'.code) -> {
                            val div = parseFactor()
                            if (div == 0.0) throw ArithmeticException("División por cero")
                            x /= div
                        }
                        eat('%'.code) -> x %= parseFactor()
                        else -> return x
                    }
                }
            }

            fun parseFactor(): Double {
                if (eat('+'.code)) return +parseFactor()
                if (eat('-'.code)) return -parseFactor()

                var x: Double
                val startPos = pos
                if (eat('('.code)) {
                    x = parseExpression()
                    eat(')'.code)
                } else if (ch in '0'.code..'9'.code || ch == '.'.code) {
                    while (ch in '0'.code..'9'.code || ch == '.'.code) nextChar()
                    x = str.substring(startPos, pos).toDouble()
                } else if (ch in 'a'.code..'z'.code || ch in 'A'.code..'Z'.code) {
                    while (ch in 'a'.code..'z'.code || ch in 'A'.code..'Z'.code) nextChar()
                    val func = str.substring(startPos, pos)
                    if (eat('('.code)) {
                        x = parseExpression()
                        eat(')'.code)
                    } else {
                        x = parseFactor()
                    }
                    x = when (func.lowercase()) {
                        "sqrt" -> sqrt(x)
                        "sin" -> sin(x)
                        "cos" -> cos(x)
                        "tan" -> tan(x)
                        "abs" -> abs(x)
                        "log" -> ln(x)
                        "round" -> round(x)
                        else -> throw RuntimeException("Función desconocida: $func")
                    }
                } else {
                    throw RuntimeException("Token inesperado: " + ch.toChar())
                }

                if (eat('^'.code)) x = x.pow(parseFactor())
                return x
            }
        }.parse()
    }
}

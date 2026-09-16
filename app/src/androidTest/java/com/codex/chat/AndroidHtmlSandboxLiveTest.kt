package com.codex.chat

import android.app.Activity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.codex.chat.core.mcp.McpRegistry
import com.codex.chat.core.mcp.model.McpToolCallRequest
import com.codex.chat.core.mcp.server.HtmlSandboxMcpServer
import com.codex.chat.core.media.VisualMediaDialog
import com.codex.chat.core.media.VisualMediaType
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidHtmlSandboxLiveTest {

    @Test
    fun testRealDevice_McpRegistry_RegistersHtmlSandboxServer() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val registry = McpRegistry(appContext)
        val activeTools = registry.getAllActiveTools()
        val toolNames = activeTools.map { it.name }

        assertTrue("Debe registrar la herramienta test_html_code", toolNames.contains("test_html_code"))
        assertTrue("Debe registrar la herramienta inspect_html_dom", toolNames.contains("inspect_html_dom"))
    }

    @Test
    fun testRealDevice_HtmlSandbox_ExecutesValidCanvasAndDetectsDomElements() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val server = HtmlSandboxMcpServer(appContext)

        val gameHtml = """
            <!DOCTYPE html>
            <html>
            <head><meta charset="utf-8"><title>Mini Game</title></head>
            <body style="background:#000; color:#fff;">
                <canvas id="c" width="300" height="300"></canvas>
                <button id="btnStart">Iniciar Juego</button>
                <script>
                    console.log("Inicializando motor de juego...");
                    var canvas = document.getElementById("c");
                    var ctx = canvas.getContext("2d");
                    ctx.fillStyle = "#38bdf8";
                    ctx.fillRect(10, 10, 50, 50);
                    console.log("Canvas 2D renderizado con exito");
                </script>
            </body>
            </html>
        """.trimIndent()

        val call = McpToolCallRequest(
            id = "call-real-game-1",
            toolName = "test_html_code",
            argumentsJson = JSONObject().put("html", gameHtml).put("timeout_ms", 3500).toString()
        )

        val result = server.executeTool(call)
        assertFalse("No debe fallar la ejecución del juego válido: ${result.content}", result.isError)
        assertTrue("Debe reportar verificación pasada", result.content.contains("HTML Sandbox Verification Passed"))
        assertTrue("Debe detectar el canvas en el DOM", result.content.contains("Canvases: 1"))
        assertTrue("Debe detectar el botón en el DOM", result.content.contains("Botones: 1"))
        assertTrue("Debe contener los logs de consola", result.content.contains("Canvas 2D renderizado"))
    }

    @Test
    fun testRealDevice_HtmlSandbox_CatchesJavaScriptRuntimeErrors() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val server = HtmlSandboxMcpServer(appContext)

        val brokenHtml = """
            <!DOCTYPE html>
            <html>
            <body>
                <h1>Prueba Error</h1>
                <script>
                    console.log("Paso 1 correcto");
                    // Llamada a función inexistente que causa ReferenceError
                    funcionInexistenteQueRompeElCodigo();
                </script>
            </body>
            </html>
        """.trimIndent()

        val call = McpToolCallRequest(
            id = "call-broken-js",
            toolName = "test_html_code",
            argumentsJson = JSONObject().put("html", brokenHtml).put("timeout_ms", 3000).toString()
        )

        val result = server.executeTool(call)
        assertTrue("Debe marcar isError = true ante excepción de JS", result.isError)
        assertTrue("Debe reportar que la verificación falló", result.content.contains("HTML Sandbox Verification Failed"))
        assertTrue("Debe señalar el error específico de JS o la función faltante", 
            result.content.contains("funcionInexistenteQueRompeElCodigo") || result.content.contains("ReferenceError") || result.content.contains("Exception")
        )
        assertTrue("Debe dar instrucción de auto-corrección al agente", result.content.contains("auto-corrección"))
    }

    @Test
    fun testRealDevice_VisualMediaDialog_InstantiatesAndRendersInLiveStudio() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity: Activity ->
            val sampleArtifact = """
                <canvas id="game" width="200" height="200"></canvas>
                <button>Tocar</button>
                <script>
                    console.log("Live Studio abierto en pantalla");
                </script>
            """.trimIndent()

            // Debe instanciar el Live Sandbox Studio sin crashear en hilo UI
            VisualMediaDialog.show(
                context = activity,
                type = VisualMediaType.HTML_CHART,
                title = "Juego Demo en Vivo",
                source = sampleArtifact
            )
        }
        scenario.close()
    }
}

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

    @Test
    fun testRealDevice_ToolRiskClassifier_ClassifiesHtmlSandboxToolsAsSafe() {
        assertEquals("test_html_code debe ser SAFE para permitir verificación autónoma", 
            com.codex.chat.core.mcp.approval.ToolRiskLevel.SAFE, 
            com.codex.chat.core.mcp.approval.ToolRiskClassifier.classify("test_html_code")
        )
        assertEquals("inspect_html_dom debe ser SAFE para permitir verificación autónoma", 
            com.codex.chat.core.mcp.approval.ToolRiskLevel.SAFE, 
            com.codex.chat.core.mcp.approval.ToolRiskClassifier.classify("inspect_html_dom")
        )

        val prompt = com.codex.chat.core.network.CodexPayloadBuilder.buildSystemPrompt()
        assertTrue("El prompt debe contener el mandato Sandbox First", prompt.contains("Mandato Apex Sandbox First"))
        assertTrue("El prompt debe obligar a llamar test_html_code en el primer turno", prompt.contains("DEBES invocar obligatoriamente la herramienta 'test_html_code'"))
    }

    @Test
    fun testRealDevice_LiveStream_AgentCallsHtmlSandboxToolAutonomously() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val registry = McpRegistry(appContext)
        val apiClient = com.codex.chat.core.network.CodexApiClient()
        val settings = SettingsManager(appContext)
        val model = com.codex.chat.core.model.ModelInfo(
            id = "gemini-3.8-flash-high",
            displayName = "Gemini 3.8 Flash High",
            provider = "Antigravity",
            supportsReasoning = true
        )

        val userMsg = com.codex.chat.core.model.ChatMessage(
            role = com.codex.chat.core.model.MessageRole.USER,
            content = "Crea un mini juego en HTML Canvas donde un cuadrado salta al hacer click. Todo autocontenido."
        )

        val latch = java.util.concurrent.CountDownLatch(1)
        val detectedTools = mutableListOf<String>()
        var completeContent = ""
        var completeReasoning = ""
        var streamError: Throwable? = null

        apiClient.executeStream(
            baseUrl = settings.baseUrl,
            apiKey = settings.apiKey,
            model = model,
            effort = com.codex.chat.core.model.ReasoningEffort.LOW,
            messages = listOf(userMsg),
            mcpRegistry = registry,
            callback = object : com.codex.chat.core.network.CodexApiClient.StreamCallback {
                override fun onReasoningDelta(delta: String) {}
                override fun onContentDelta(delta: String) {
                    completeContent += delta
                }
                override fun onToolCallsDetected(toolCalls: List<com.codex.chat.core.parser.SseStreamParser.CompletedToolCall>) {
                    detectedTools.addAll(toolCalls.map { it.name })
                    latch.countDown()
                }

                override fun onComplete(fullContent: String, fullReasoning: String) {
                    completeContent = fullContent
                    completeReasoning = fullReasoning
                    latch.countDown()
                }

                override fun onError(error: Throwable) {
                    streamError = error
                    latch.countDown()
                }
            }
        )

        val arrived = latch.await(30, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue("Debe recibir respuesta en menos de 30s", arrived)
        assertTrue("El agente DEBE invocar de forma autónoma la herramienta test_html_code en lugar de devolver código sin probar: $detectedTools (err=${streamError?.message}, content=${completeContent.take(200)}, reason=${completeReasoning.take(200)})", 
            detectedTools.contains("test_html_code")
        )
    }

    @Test
    fun testRealDevice_VisualMediaParser_FullHtmlDocDetection() {
        val sampleResponse = """
            Aquí tienes el juego completo en HTML5 Canvas probado y verificado.
            
            <!DOCTYPE html>
            <html lang="es">
            <head><title>Juego</title></head>
            <body>
            <canvas id="c"></canvas>
            <script>console.log("Canvas listo");</script>
            </body>
            </html>
            
            Disfrútalo!
        """.trimIndent()

        val parsed = com.codex.chat.core.media.VisualMediaParser.parse(sampleResponse)
        assertTrue("En el runtime de Android debe detectar hasMedia=true", parsed.hasMedia)
        assertEquals(com.codex.chat.core.media.VisualMediaType.HTML_CHART, parsed.type)
        assertEquals("📈 Vista Gráfica HTML Interactiva", parsed.title)
        assertTrue("El cleanContent no debe tener DOCTYPE", !parsed.cleanContent.contains("<!DOCTYPE"))
        assertTrue("mediaSource debe contener canvas", parsed.mediaSource.contains("<canvas"))
    }

    @Test
    fun testRealDevice_ViewHolderBindsHtmlChart() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val rv = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvMessages)
            val adapter = rv.adapter as? ChatAdapter
            assertNotNull("Adapter must not be null", adapter)

            val text = """
                ✅ **[Resultado MCP: `test_html_code`]**
                ```json
                {"passed":true,"errors":[],"consoleLogs":["Canvas ready"]}
                ```

                Aquí tienes el juego completo verificado:

                <!DOCTYPE html>
                <html lang="es">
                <head><title>Test Game</title></head>
                <body>
                <canvas id="c"></canvas>
                <script>console.log("Running");</script>
                </body>
                </html>
            """.trimIndent()

            val testMsg = com.codex.chat.core.model.ChatMessage(
                role = com.codex.chat.core.model.MessageRole.ASSISTANT,
                content = text,
                isStreaming = false
            )

            adapter!!.addMessage(testMsg)
            rv.measure(1080, 1920)
            rv.layout(0, 0, 1080, 1920)

            val holder = rv.findViewHolderForAdapterPosition(adapter.itemCount - 1) as? ChatAdapter.AssistantViewHolder
            assertNotNull("Holder must be bound", holder)

            val layoutVisualMedia = holder!!.itemView.findViewById<android.view.View>(R.id.layoutVisualMedia)
            val tvMediaTitle = holder.itemView.findViewById<android.widget.TextView>(R.id.tvMediaTitle)
            val btnFullscreenMedia = holder.itemView.findViewById<android.widget.TextView>(R.id.btnFullscreenMedia)

            assertEquals("layoutVisualMedia debe ser VISIBLE", android.view.View.VISIBLE, layoutVisualMedia.visibility)
            assertEquals("📈 Vista Gráfica HTML Interactiva", tvMediaTitle.text.toString())
            assertEquals("🧪 Live Studio", btnFullscreenMedia.text.toString())

            // Simular clic en el botón Live Studio
            btnFullscreenMedia.performClick()
        }
        scenario.close()
    }
}

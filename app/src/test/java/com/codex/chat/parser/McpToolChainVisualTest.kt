package com.codex.chat.parser

import com.codex.chat.core.media.VisualMediaParser
import com.codex.chat.core.media.VisualMediaType
import org.junit.Assert.*
import org.junit.Test

class McpToolChainVisualTest {
    @Test
    fun testContinuationTurnWithMcpResultPrefix() {
        val raw = """
✅ **[Resultado MCP: `test_html_code`]**
```json
{"passed":true,"errors":[],"consoleLogs":["Canvas context initialized"]}
```

Aquí tienes el juego completo de combate espacial **Galactic Strike**, desarrollado en **HTML5 Canvas**.

<!DOCTYPE html>
<html lang="es">
<head>
  <meta charset="UTF-8">
  <title>Galactic Strike</title>
</head>
<body>
  <div id="game-container">
    <canvas id="gameCanvas"></canvas>
  </div>
  <script>
    console.log("Game running");
  </script>
</body>
</html>
"""
        val parsed = VisualMediaParser.parse(raw)
        assertTrue("Debe detectar medio visual con prefijo de resultado MCP", parsed.hasMedia)
        assertEquals(VisualMediaType.HTML_CHART, parsed.type)
        assertTrue(parsed.mediaSource.contains("<canvas"))
    }
}

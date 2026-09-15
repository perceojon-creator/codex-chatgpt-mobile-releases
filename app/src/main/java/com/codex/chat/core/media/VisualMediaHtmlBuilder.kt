package com.codex.chat.core.media

object VisualMediaHtmlBuilder {

    fun buildHtml(type: VisualMediaType, source: String): String {
        if (type == VisualMediaType.HTML_CHART && source.contains("<html", ignoreCase = true)) {
            val darkStyle = "<meta name='viewport' content='width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes'><style>body{background-color:#12161F;color:#ECECEC;margin:0;padding:8px;font-family:sans-serif;}svg,canvas{max-width:100%;height:auto;margin:0 auto;display:block;}</style>"
            return if (source.contains("<head>", ignoreCase = true)) {
                source.replace("<head>", "<head>$darkStyle", ignoreCase = true)
            } else {
                "$darkStyle$source"
            }
        }

        val innerBody = when (type) {
            VisualMediaType.SVG -> buildSvgBody(source)
            VisualMediaType.MERMAID -> buildMermaidBody(source)
            VisualMediaType.HTML_CHART -> buildHtmlChartBody(source)
            VisualMediaType.IMAGE -> buildImageBody(source)
            VisualMediaType.VIDEO -> buildVideoBody(source)
        }

        return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes">
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    background-color: #12161F;
    color: #ECECEC;
    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
    display: flex;
    justify-content: center;
    align-items: center;
    min-height: 100vh;
    padding: 8px;
    overflow: auto;
  }
  .media-card {
    width: 100%;
    display: flex;
    justify-content: center;
    align-items: center;
    flex-direction: column;
  }
  svg {
    max-width: 100%;
    height: auto;
    display: block;
    margin: 0 auto;
  }
  img {
    max-width: 100%;
    max-height: 360px;
    object-fit: contain;
    border-radius: 8px;
    display: block;
  }
  video {
    width: 100%;
    max-height: 340px;
    border-radius: 8px;
    outline: none;
    background: #000;
  }
  .mermaid {
    width: 100%;
    display: flex;
    justify-content: center;
    text-align: center;
  }
  canvas {
    max-width: 100%;
    height: auto;
  }
</style>
</head>
<body>
<div class="media-card">
  $innerBody
</div>
</body>
</html>
""".trimIndent()
    }

    private fun buildSvgBody(svg: String): String {
        return "<div style='width:100%; text-align:center;'>$svg</div>"
    }

    private fun buildMermaidBody(mermaidCode: String): String {
        val escaped = mermaidCode.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return """
<script src="file:///android_asset/mermaid.min.js"></script>
<script>
  if (typeof mermaid === 'undefined') {
    document.write('<script src="https://cdn.jsdelivr.net/npm/mermaid@10/dist/mermaid.min.js"><\\/script>');
  }
</script>
<script>
  window.addEventListener('DOMContentLoaded', function() {
    try {
      if (typeof mermaid !== 'undefined') {
        mermaid.initialize({
          startOnLoad: true,
          theme: 'dark',
          themeVariables: {
            darkMode: true,
            background: '#12161F',
            primaryColor: '#1E293B',
            primaryBorderColor: '#38BDF8',
            primaryTextColor: '#F8FAFC',
            lineColor: '#38BDF8',
            secondaryColor: '#0F172A',
            tertiaryColor: '#1E293B'
          }
        });
      }
    } catch(e) {}
  });
</script>
<pre class="mermaid">
$escaped
</pre>
""".trimIndent()
    }

    private fun buildHtmlChartBody(html: String): String {
        return html
    }

    private fun buildImageBody(urlOrBase64: String): String {
        return "<img src='$urlOrBase64' alt='Imagen' loading='lazy' />"
    }

    private fun buildVideoBody(urlOrTag: String): String {
        return if (urlOrTag.trim().startsWith("<video")) {
            urlOrTag
        } else {
            "<video controls playsinline controlsList='nodownload'><source src='$urlOrTag'>Tu dispositivo no soporta este video.</video>"
        }
    }
}
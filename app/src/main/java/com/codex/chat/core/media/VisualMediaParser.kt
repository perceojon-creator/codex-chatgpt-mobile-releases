package com.codex.chat.core.media

enum class VisualMediaType(val displayName: String, val icon: String) {
    SVG("Gráfico Vectorial SVG", "🎨"),
    MERMAID("Diagrama Mermaid Interactivo", "📊"),
    HTML_CHART("Gráfico Interactivo Canvas/HTML", "📈"),
    IMAGE("Imagen", "🖼️"),
    VIDEO("Video Interactivo", "🎬")
}

data class ParsedVisualMedia(
    val hasMedia: Boolean,
    val type: VisualMediaType = VisualMediaType.IMAGE,
    val title: String = "",
    val mediaSource: String = "",
    val cleanContent: String = "",
    /** Todas las imágenes detectadas en el mensaje (N≥1). El resto del pipeline es retrocompatible. */
    val imageSources: List<String> = emptyList()
)

object VisualMediaParser {

    private val SVG_TAG_REGEX = Regex(
        """<svg[\s\S]*?<\/svg>""",
        RegexOption.IGNORE_CASE
    )

    private val SVG_CODE_BLOCK_REGEX = Regex(
        """```(?:xml|svg|html)?\n([\s\S]*?<svg[\s\S]*?<\/svg>[\s\S]*?)\n```""",
        RegexOption.IGNORE_CASE
    )

    private val MERMAID_REGEX = Regex(
        """```mermaid\n([\s\S]*?)\n```""",
        RegexOption.IGNORE_CASE
    )

    private val HTML_BLOCK_REGEX = Regex(
        """```(?:html|htm)?\s*\r?\n([\s\S]*?)\r?\n\s*```""",
        RegexOption.IGNORE_CASE
    )

    private val FULL_HTML_DOC_REGEX = Regex(
        """(?:<!DOCTYPE\s+html[^>]*>[\s\S]*?<html[^>]*>[\s\S]*?<\/html>|<html[^>]*>[\s\S]*?<\/html>)""",
        RegexOption.IGNORE_CASE
    )

    private val MARKDOWN_IMAGE_REGEX = Regex(
        """!\[([^\]]*)\]\((https?:\/\/[^\s\)]+|file:\/\/[^\s\)]+|\/[^\s\)]+|data:image\/[a-zA-Z+]+;base64,[^\s\)]+)\)"""
    )

    private val DIRECT_IMAGE_URL_REGEX = Regex(
        """(https?:\/\/[^\s"'<>]+\.(?:png|jpg|jpeg|webp|gif|svg)(?:\?[^\s"'<>]*)?)""",
        RegexOption.IGNORE_CASE
    )

    private val DIRECT_VIDEO_URL_REGEX = Regex(
        """(https?:\/\/[^\s"'<>]+\.(?:mp4|webm)(?:\?[^\s"'<>]*)?)""",
        RegexOption.IGNORE_CASE
    )

    private val HTML_VIDEO_TAG_REGEX = Regex(
        """<video[\s\S]*?<\/video>""",
        RegexOption.IGNORE_CASE
    )

    fun parse(content: String): ParsedVisualMedia {
        if (content.isBlank()) return ParsedVisualMedia(hasMedia = false, cleanContent = content)

        // FAST-PATH (anti-congelamiento): cualquier medio posible exige uno de estos disparadores.
        // Sin ellos, saltamos TODAS las regex pesadas. Clave cuando el contenido incluye imágenes
        // base64 de 850 KB+ que llegan token a token durante el streaming: sin este gate, cada
        // delta ejecutaba ~8 regex sobre megabytes acumulados y la UI se congelaba.
        // Formas ESCAPADAS (llegan así desde JSON del proxy): \u003csvg, &lt;svg, \u003chtml...
        // Deben disparar el camino completo porque solo existen tras desescapar.
        val hasEscapedMedia = content.contains("\\u003c") ||
            content.contains("&lt;svg", ignoreCase = true) ||
            content.contains("&lt;html", ignoreCase = true)

        if (!hasEscapedMedia && !content.contains("```mermaid") &&
            !content.contains("<svg", ignoreCase = true) &&
            !content.contains("</svg>", ignoreCase = true) &&
            !content.contains("<html", ignoreCase = true) &&
            !content.contains("```html") &&
            !content.contains("<video") &&
            !content.contains("![") &&
            !content.contains("data:image/") &&
            !content.contains("file://") &&
            !content.contains(".png", ignoreCase = true) &&
            !content.contains(".jpg", ignoreCase = true) &&
            !content.contains(".jpeg", ignoreCase = true) &&
            !content.contains(".gif", ignoreCase = true) &&
            !content.contains(".webp", ignoreCase = true) &&
            !content.contains("<canvas", ignoreCase = true) &&
            !content.contains(".mp4", ignoreCase = true) &&
            !content.contains(".webm", ignoreCase = true) &&
            !content.contains(".mov", ignoreCase = true) &&
            !content.contains("<video", ignoreCase = true)) {
            return ParsedVisualMedia(hasMedia = false, cleanContent = content)
        }

        // FAST-PATH MULTI-IMAGEN MARKDOWN: extrae TODAS las imágenes ![alt](url) del mensaje
        // en una sola pasada con indexOf (sin regex) y las remueve todas del cleanContent.
        // Con URIs locales (~80 chars) la latencia es de microsegundos.
        if (!content.contains("```mermaid") &&
            !content.contains("<svg", ignoreCase = true) &&
            !content.contains("<html", ignoreCase = true) &&
            !content.contains("<video") &&
            !content.contains("<canvas", ignoreCase = true)) {
            val images = mutableListOf<String>()
            val cleanBuilder = StringBuilder()
            var cursor = 0
            while (cursor < content.length) {
                val altStart = content.indexOf("![", cursor)
                if (altStart == -1) {
                    cleanBuilder.append(content.substring(cursor))
                    break
                }
                val closeBracket = content.indexOf(']', altStart)
                if (closeBracket == -1 || closeBracket + 1 >= content.length || content[closeBracket + 1] != '(') {
                    cleanBuilder.append(content.substring(cursor, altStart + 2))
                    cursor = altStart + 2
                    continue
                }
                val urlEnd = content.indexOf(')', closeBracket + 2)
                if (urlEnd == -1) {
                    cleanBuilder.append(content.substring(cursor, closeBracket + 2))
                    cursor = closeBracket + 2
                    continue
                }
                val url = content.substring(closeBracket + 2, urlEnd).trim()
                val isImageSource = url.startsWith("file://") ||
                    url.startsWith("/") ||
                    url.startsWith("data:image/") ||
                    url.startsWith("http://") ||
                    url.startsWith("https://")
                if (isImageSource && !url.contains(' ')) {
                    // Deduplicar: ignorar imágenes repetidas (misma URL o mismo tamaño de archivo)
                    if (!isDuplicateImage(images, url)) {
                        images.add(url)
                    }
                    cleanBuilder.append(content.substring(cursor, altStart))
                    cursor = urlEnd + 1
                } else {
                    cleanBuilder.append(content.substring(cursor, urlEnd + 1))
                    cursor = urlEnd + 1
                }
            }
            if (images.isNotEmpty()) {
                var clean = cleanBuilder.toString().replace("\n\n\n\n", "\n\n").trim()
                // Preservar el alt-text de la primera imagen para el título (retrocompatible)
                val firstAltStart = content.indexOf("![")
                val firstAltEnd = content.indexOf(']', firstAltStart)
                val firstAlt = if (firstAltStart != -1 && firstAltEnd > firstAltStart) {
                    content.substring(firstAltStart + 2, firstAltEnd).ifBlank { "Imagen" }
                } else "Imagen"
                val title = if (images.size == 1) "🖼️ $firstAlt" else "🖼️ ${images.size} imágenes · $firstAlt"
                return ParsedVisualMedia(
                    hasMedia = true,
                    type = VisualMediaType.IMAGE,
                    title = title,
                    mediaSource = images[0],
                    imageSources = images,
                    cleanContent = clean
                )
            }
        }

        // FASE 1: Sanitizar y desescapar secuencias Unicode (\u003c, \u003e), entidades HTML y caracteres JSON
        val normalized = unescapeAndNormalize(content)

        // 1. Diagramas Mermaid
        val mermaidMatch = MERMAID_REGEX.find(normalized)
        if (mermaidMatch != null) {
            val code = mermaidMatch.groupValues[1].trim()
            val clean = normalized.replace(mermaidMatch.value, "").trim()
            return ParsedVisualMedia(
                hasMedia = true,
                type = VisualMediaType.MERMAID,
                title = "📊 Diagrama Mermaid Interactivo",
                mediaSource = code,
                cleanContent = if (clean.isNotBlank()) clean else "Diagrama interactivo generado:"
            )
        }

        // 2. Gráficos SVG completos (bloque fenced o etiqueta directa)
        val svgCodeMatch = SVG_CODE_BLOCK_REGEX.find(normalized)
        if (svgCodeMatch != null) {
            val svgXml = svgCodeMatch.groupValues[1].trim()
            val clean = normalized.replace(svgCodeMatch.value, "").trim()
            return ParsedVisualMedia(
                hasMedia = true,
                type = VisualMediaType.SVG,
                title = "🎨 Gráfico Vectorial SVG",
                mediaSource = extractSvgTag(svgXml),
                cleanContent = if (clean.isNotBlank()) clean else "Gráfico vectorial SVG generado:"
            )
        }

        val rawSvgMatch = SVG_TAG_REGEX.find(normalized)
        if (rawSvgMatch != null) {
            val svgXml = rawSvgMatch.value.trim()
            val clean = normalized.replace(rawSvgMatch.value, "").trim()
            return ParsedVisualMedia(
                hasMedia = true,
                type = VisualMediaType.SVG,
                title = "🎨 Gráfico Vectorial SVG",
                mediaSource = svgXml,
                cleanContent = if (clean.isNotBlank()) clean else "Gráfico vectorial SVG generado:"
            )
        }

        // 3. SVG con cabecera omitida o truncada (pero con elementos vectoriales y cierre </svg>)
        val reconstructedSvg = tryReconstructSvg(normalized)
        if (reconstructedSvg != null) {
            return ParsedVisualMedia(
                hasMedia = true,
                type = VisualMediaType.SVG,
                title = "🎨 Gráfico Vectorial SVG (Reconstruido)",
                mediaSource = reconstructedSvg.first,
                cleanContent = reconstructedSvg.second.ifBlank { "Gráfico vectorial SVG visualizado:" }
            )
        }
        // 4. Bloque ```html con documento completo, canvas, svg o interactividad
        val htmlBlockMatch = HTML_BLOCK_REGEX.find(normalized)
        if (htmlBlockMatch != null) {
            val html = htmlBlockMatch.groupValues[1].trim()
            val clean = normalized.replace(htmlBlockMatch.value, "").replace(Regex("""```(?:html|htm)?\s*\r?\n\s*```"""), "").trim()
            return ParsedVisualMedia(
                hasMedia = true,
                type = VisualMediaType.HTML_CHART,
                title = "📈 Gráfico Interactivo HTML",
                mediaSource = html,
                cleanContent = if (clean.isNotBlank()) clean else "Gráfico interactivo generado:"
            )
        }

        // 5. Documento HTML interactivo completo (con Canvas, SVG o interactividad)
        val htmlDocMatch = FULL_HTML_DOC_REGEX.find(normalized)
        if (htmlDocMatch != null) {
            val htmlContent = htmlDocMatch.value.trim()
            val clean = normalized.replace(htmlDocMatch.value, "").replace(Regex("""```(?:html|htm)?\s*\r?\n\s*```"""), "").trim()
            return ParsedVisualMedia(
                hasMedia = true,
                type = VisualMediaType.HTML_CHART,
                title = "📈 Vista Gráfica HTML Interactiva",
                mediaSource = htmlContent,
                cleanContent = if (clean.isNotBlank()) clean else "Visualización interactiva generada:"
            )
        }
        val videoTagMatch = HTML_VIDEO_TAG_REGEX.find(normalized)
        if (videoTagMatch != null) {
            val tag = videoTagMatch.value.trim()
            val clean = normalized.replace(videoTagMatch.value, "").trim()
            return ParsedVisualMedia(
                hasMedia = true,
                type = VisualMediaType.VIDEO,
                title = "🎬 Video Interactivo",
                mediaSource = tag,
                cleanContent = if (clean.isNotBlank()) clean else "Video para reproducir:"
            )
        }

        val directVideoMatch = DIRECT_VIDEO_URL_REGEX.find(normalized)
        if (directVideoMatch != null) {
            val url = directVideoMatch.groupValues[1].trim()
            val clean = normalized.replace(directVideoMatch.value, "").trim()
            return ParsedVisualMedia(
                hasMedia = true,
                type = VisualMediaType.VIDEO,
                title = "🎬 Video Interactivo",
                mediaSource = url,
                cleanContent = if (clean.isNotBlank()) clean else "Video para reproducir:"
            )
        }

        // 7. Markdown Image
        val mdImgMatch = MARKDOWN_IMAGE_REGEX.find(normalized)
        if (mdImgMatch != null) {
            val alt = mdImgMatch.groupValues[1].ifBlank { "Imagen" }
            val url = mdImgMatch.groupValues[2].trim()
            val clean = normalized.replace(mdImgMatch.value, "").trim()
            return ParsedVisualMedia(
                hasMedia = true,
                type = VisualMediaType.IMAGE,
                title = "🖼️ $alt",
                mediaSource = url,
                cleanContent = clean
            )
        }

        // 8. Direct Image URL
        val directImgMatch = DIRECT_IMAGE_URL_REGEX.find(normalized)
        if (directImgMatch != null) {
            val url = directImgMatch.groupValues[1].trim()
            val clean = normalized.replace(directImgMatch.value, "").trim()
            return ParsedVisualMedia(
                hasMedia = true,
                type = VisualMediaType.IMAGE,
                title = "🖼️ Imagen",
                mediaSource = url,
                cleanContent = clean
            )
        }

        return ParsedVisualMedia(hasMedia = false, cleanContent = content)
    }

    private fun isDuplicateImage(existing: List<String>, candidate: String): Boolean {
        if (existing.contains(candidate)) return true
        if (candidate.startsWith("file://")) {
            val candPath = candidate.removePrefix("file://")
            val candFile = java.io.File(candPath)
            if (candFile.exists()) {
                val candLen = candFile.length()
                if (candLen > 512) {
                    val candHead = readSample(candFile, 0, 4096)
                    val candMid = readSample(candFile, candLen / 2, 4096)
                    for (prev in existing) {
                        if (prev.startsWith("file://")) {
                            val prevPath = prev.removePrefix("file://")
                            val prevFile = java.io.File(prevPath)
                            if (prevFile.exists()) {
                                val prevLen = prevFile.length()
                                val sizeDiff = kotlin.math.abs(candLen - prevLen).toDouble() / prevLen.toDouble()
                                if (sizeDiff <= 0.15) {
                                    // Misma URL-base o mismo muestreo de bytes → duplicada
                                    val prevHead = readSample(prevFile, 0, 4096)
                                    val prevMid = readSample(prevFile, prevLen / 2, 4096)
                                    if (byteRatio(prevHead, candHead) >= 0.85 ||
                                        byteRatio(prevMid, candMid) >= 0.85) {
                                        return true
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return false
    }

    // Cache en memoria para muestras de archivos de imagen y evitar relecturas de disco síncronas en el hilo de UI (fixes C2)
    private val sampleCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private val JSON_ARG_SUFFIX_REGEX = Regex("""\?"s*}s*$""")

    private fun readSample(f: java.io.File, offset: Long, len: Int): ByteArray {
        val cacheKey = "${f.absolutePath}:${f.lastModified()}:$offset:$len"
        sampleCache[cacheKey]?.let { return it }
        return try {
            java.io.RandomAccessFile(f, "r").use { raf ->
                raf.seek(offset)
                val buf = ByteArray(len)
                val read = raf.read(buf)
                val result = if (read <= 0) ByteArray(0) else buf.copyOf(read)
                if (sampleCache.size < 200) {
                    sampleCache[cacheKey] = result
                }
                result
            }
        } catch (e: Throwable) {
            ByteArray(0)
        }
    }

    private fun byteRatio(a: ByteArray, b: ByteArray): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val n = minOf(a.size, b.size)
        var matches = 0
        for (i in 0 until n) {
            if (a[i] == b[i]) matches++
        }
        return matches.toDouble() / n
    }

    fun unescapeAndNormalize(raw: String): String {
        var s = raw

        // Desescapar secuencias de escape JSON/Unicode \u003c, \u003e, etc.
        if (s.contains("\\u003c", ignoreCase = true) || s.contains("\\u003e", ignoreCase = true)) {
            s = s.replace("\\u003c", "<", ignoreCase = true)
                 .replace("\\u003e", ">", ignoreCase = true)
                 .replace("\\u0026", "&", ignoreCase = true)
                 .replace("\\u0022", "\"", ignoreCase = true)
                 .replace("\\u0027", "'", ignoreCase = true)
        }

        // Desescapar saltos de línea y tabuladores literales si vienen como texto plano en JSON
        if (s.contains("\\n") || s.contains("\\r")) {
            s = s.replace("\\n", "\n")
                 .replace("\\r", "\r")
                 .replace("\\t", "\t")
                 .replace("\\\"", "\"")
        }

        // Remover artefactos de cierre de argumentos JSON como `\n"}` o `"}` al final
        s = s.replace(JSON_ARG_SUFFIX_REGEX, "")

        // Desescapar entidades HTML habituales
        if (s.contains("&lt;svg", ignoreCase = true) || s.contains("&lt;/svg", ignoreCase = true) || s.contains("&lt;html", ignoreCase = true) || s.contains("&lt;/html", ignoreCase = true) || s.contains("&lt;!DOCTYPE", ignoreCase = true) || s.contains("&lt;canvas", ignoreCase = true)) {
            s = s.replace("&lt;", "<", ignoreCase = true)
                 .replace("&gt;", ">", ignoreCase = true)
                 .replace("&quot;", "\"", ignoreCase = true)
                 .replace("&amp;", "&", ignoreCase = true)
        }

        return s
    }

    private fun extractSvgTag(raw: String): String {
        val match = SVG_TAG_REGEX.find(raw)
        return match?.value?.trim() ?: raw.trim()
    }

    private fun tryReconstructSvg(text: String): Pair<String, String>? {
        val closeIdx = text.lastIndexOf("</svg>", ignoreCase = true)
        if (closeIdx == -1) return null

        // Comprobar si hay elementos gráficos de SVG presentes
        val hasSvgElements = text.contains("<rect", ignoreCase = true) ||
                             text.contains("<circle", ignoreCase = true) ||
                             text.contains("<path", ignoreCase = true) ||
                             text.contains("<polygon", ignoreCase = true) ||
                             text.contains("<line", ignoreCase = true) ||
                             text.contains("<g", ignoreCase = true)

        if (!hasSvgElements) return null

        // Si ya tenía apertura <svg, el regex normal lo habría tomado; si falta, la reconstruimos
        val openIdx = text.indexOf("<svg", ignoreCase = true)
        if (openIdx != -1 && openIdx < closeIdx) {
            val full = text.substring(openIdx, closeIdx + 6)
            val clean = (text.substring(0, openIdx) + text.substring(closeIdx + 6)).trim()
            return Pair(full, clean)
        }

        // Reconstrucción con viewBox estándar responsive
        val firstTagIdx = text.indexOf("<")
        if (firstTagIdx == -1 || firstTagIdx >= closeIdx) return null

        val body = text.substring(firstTagIdx, closeIdx + 6)
        val clean = text.substring(0, firstTagIdx).trim()
        val reconstructed = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 800 600\" width=\"100%\" height=\"auto\">\n$body"
        return Pair(reconstructed, clean)
    }
}
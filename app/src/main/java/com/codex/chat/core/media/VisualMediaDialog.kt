package com.codex.chat.core.media

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VisualMediaDialog {

    fun show(
        context: Context,
        type: VisualMediaType,
        title: String,
        source: String,
        allSources: List<String> = emptyList(),
        initialIndex: Int = 0
    ) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.parseColor("#090D16")))

        val imageList = if (allSources.isNotEmpty()) allSources else listOf(source)
        var currentIndex = initialIndex.coerceIn(0, (imageList.size - 1).coerceAtLeast(0))

        val isDirectImage = type == VisualMediaType.IMAGE && (
            source.startsWith("file://") ||
            source.startsWith("/") ||
            source.startsWith("data:image/")
        )

        if (isDirectImage) {
            showDirectImageViewer(context, dialog, type, title, source, imageList, currentIndex)
            return
        }

        // Live Sandbox Studio para Creaciones Web, Canvas 2D, Juegos y Artefactos HTML
        showLiveSandboxStudio(context, dialog, type, title, source)
    }

    private fun showDirectImageViewer(
        context: Context,
        dialog: Dialog,
        type: VisualMediaType,
        title: String,
        source: String,
        imageList: List<String>,
        initialIndex: Int
    ) {
        var currentIndex = initialIndex
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#090D16"))
            setPadding(16, 16, 16, 16)
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 0, 0, 12)
        }

        val tvTitle = TextView(context).apply {
            text = if (imageList.size > 1) {
                "${type.icon} Imagen ${currentIndex + 1}/${imageList.size}"
            } else {
                "${type.icon} $title"
            }
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnResetZoom = Button(context).apply {
            text = "↺ 100%"
            setTextColor(Color.parseColor("#38BDF8"))
            setBackgroundColor(Color.parseColor("#1E293B"))
            textSize = 11f
        }

        val btnCopy = Button(context).apply {
            text = "📋 Copiar"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E293B"))
            textSize = 11f
            setOnClickListener {
                val currentSrc = imageList.getOrNull(currentIndex) ?: source
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Media Source", currentSrc))
                Toast.makeText(context, "Fuente copiada al portapapeles", Toast.LENGTH_SHORT).show()
            }
        }

        val btnClose = Button(context).apply {
            text = "✕ Cerrar"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#334155"))
            textSize = 11f
            setOnClickListener { dialog.dismiss() }
        }

        header.addView(tvTitle)
        header.addView(btnResetZoom)
        header.addView(btnCopy)
        header.addView(btnClose)

        val zoomView = ZoomableImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setBackgroundColor(Color.parseColor("#12161F"))
        }

        var tvPageIndicator: TextView? = null

        fun loadImageForIndex(idx: Int) {
            val currentSrc = imageList[idx]
            val cacheKey = MediaBitmapCache.keyFor(currentSrc)
            val bmp = MediaBitmapCache.get(cacheKey) ?: MediaBitmapCache.decodeBlocking(currentSrc)
            if (bmp != null) {
                zoomView.setImageBitmap(bmp)
                zoomView.resetZoom()
            }
            if (imageList.size > 1) {
                tvTitle.text = "${type.icon} Imagen ${idx + 1}/${imageList.size}"
                tvPageIndicator?.text = "${idx + 1} de ${imageList.size}"
            }
        }

        btnResetZoom.setOnClickListener { zoomView.resetZoom() }
        loadImageForIndex(currentIndex)

        root.addView(header)
        root.addView(zoomView)

        if (imageList.size > 1) {
            val footer = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, 8, 0, 0)
            }

            val btnPrev = Button(context).apply {
                text = "◀ Anterior"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#1E293B"))
                textSize = 12f
                setOnClickListener {
                    if (currentIndex > 0) {
                        currentIndex--
                        loadImageForIndex(currentIndex)
                    }
                }
            }

            tvPageIndicator = TextView(context).apply {
                text = "${currentIndex + 1} de ${imageList.size}"
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 13f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btnNext = Button(context).apply {
                text = "Siguiente ▶"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#1E293B"))
                textSize = 12f
                setOnClickListener {
                    if (currentIndex < imageList.size - 1) {
                        currentIndex++
                        loadImageForIndex(currentIndex)
                    }
                }
            }

            footer.addView(btnPrev)
            footer.addView(tvPageIndicator)
            footer.addView(btnNext)
            root.addView(footer)
        }

        dialog.setContentView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showLiveSandboxStudio(
        context: Context,
        dialog: Dialog,
        type: VisualMediaType,
        title: String,
        source: String
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#090D16"))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        // 1. TOP HEADER & STUDIO CONTROLS
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 0, 0, dp(8))
        }

        val tvStudioTitle = TextView(context).apply {
            text = "${type.icon} Live Studio"
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        // Chip de estado en vivo
        val statusChip = TextView(context).apply {
            text = "🟡 Cargando..."
            textSize = 11f
            setTextColor(Color.parseColor("#FBBF24"))
            typeface = Typeface.MONOSPACE
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) }
        }

        var isMobileViewport = false
        val btnToggleViewport = Button(context).apply {
            text = "📱 390px"
            setTextColor(Color.parseColor("#94A3B8"))
            setBackgroundColor(Color.parseColor("#1E293B"))
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(36)
            ).apply { marginEnd = dp(4) }
        }

        val btnReload = Button(context).apply {
            text = "🔄"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E293B"))
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(36)
            ).apply { marginEnd = dp(4) }
        }

        var isConsoleVisible = type == VisualMediaType.HTML_CHART
        val btnToggleConsole = Button(context).apply {
            text = "📟 Consola"
            setTextColor(if (isConsoleVisible) Color.parseColor("#38BDF8") else Color.parseColor("#94A3B8"))
            setBackgroundColor(Color.parseColor("#1E293B"))
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(36)
            ).apply { marginEnd = dp(4) }
        }

        val btnCopy = Button(context).apply {
            text = "📋"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E293B"))
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(36)
            ).apply { marginEnd = dp(4) }
            setOnClickListener {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Código HTML", source))
                Toast.makeText(context, "Código copiado al portapapeles", Toast.LENGTH_SHORT).show()
            }
        }

        val btnClose = Button(context).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#334155"))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(36)
            )
            setOnClickListener { dialog.dismiss() }
        }

        header.addView(tvStudioTitle)
        header.addView(statusChip)
        header.addView(btnToggleViewport)
        header.addView(btnReload)
        header.addView(btnToggleConsole)
        header.addView(btnCopy)
        header.addView(btnClose)

        // 2. VIEWPORT CONTAINER (Soporta ancho fluido o ancho móvil 390px)
        val viewportContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setBackgroundColor(Color.parseColor("#0F172A"))
        }

        val webView = WebView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
            setBackgroundColor(Color.parseColor("#12161F"))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true)
                builtInZoomControls = true
                displayZoomControls = false
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
        }
        viewportContainer.addView(webView)

        btnToggleViewport.setOnClickListener {
            isMobileViewport = !isMobileViewport
            if (isMobileViewport) {
                btnToggleViewport.text = "🖥️ 100%"
                btnToggleViewport.setTextColor(Color.parseColor("#38BDF8"))
                val targetWidth = dp(390)
                webView.layoutParams = FrameLayout.LayoutParams(
                    targetWidth,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            } else {
                btnToggleViewport.text = "📱 390px"
                btnToggleViewport.setTextColor(Color.parseColor("#94A3B8"))
                webView.layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            }
        }

        // 3. LIVE DEVTOOLS CONSOLE & METRICS DRAWER
        val consoleDrawer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(190)
            )
            setBackgroundColor(Color.parseColor("#0B0F17"))
            visibility = if (isConsoleVisible) View.VISIBLE else View.GONE
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val consoleHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 0, 0, dp(4))
        }

        val tvConsoleTitle = TextView(context).apply {
            text = "📟 DevTools Console"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvDomMetrics = TextView(context).apply {
            text = "Métricas: Analizando..."
            setTextColor(Color.parseColor("#38BDF8"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(8) }
        }

        val btnClearLogs = Button(context).apply {
            text = "🧹"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E293B"))
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(28)
            )
        }

        consoleHeader.addView(tvConsoleTitle)
        consoleHeader.addView(tvDomMetrics)
        consoleHeader.addView(btnClearLogs)

        val logScrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            isVerticalScrollBarEnabled = true
        }

        val tvLogs = TextView(context).apply {
            text = ""
            setTextColor(Color.parseColor("#E2E8F0"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        logScrollView.addView(tvLogs)
        consoleDrawer.addView(consoleHeader)
        consoleDrawer.addView(logScrollView)

        btnToggleConsole.setOnClickListener {
            isConsoleVisible = !isConsoleVisible
            consoleDrawer.visibility = if (isConsoleVisible) View.VISIBLE else View.GONE
            btnToggleConsole.setTextColor(if (isConsoleVisible) Color.parseColor("#38BDF8") else Color.parseColor("#94A3B8"))
        }

        val mainHandler = Handler(Looper.getMainLooper())
        val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
        var errorCount = 0

        fun appendLog(level: String, msg: String) {
            mainHandler.post {
                val time = timeFormat.format(Date())
                val prefix = when (level.uppercase()) {
                    "ERROR" -> "❌"
                    "WARN" -> "⚠️"
                    else -> "ℹ️"
                }
                tvLogs.append("[$time] $prefix [$level] $msg\n")
                logScrollView.post { logScrollView.fullScroll(View.FOCUS_DOWN) }

                if (level.equals("ERROR", ignoreCase = true)) {
                    errorCount++
                    statusChip.text = "🔴 Error JS ($errorCount)"
                    statusChip.setTextColor(Color.parseColor("#EF4444"))
                }
            }
        }

        btnClearLogs.setOnClickListener {
            tvLogs.text = ""
            errorCount = 0
            statusChip.text = "🟢 Listo"
            statusChip.setTextColor(Color.parseColor("#10B981"))
        }

        // JS Bridge para interceptación instantánea en vivo
        class LiveStudioBridge {
            @JavascriptInterface
            fun reportLog(level: String, msg: String) {
                appendLog(level, msg)
            }

            @JavascriptInterface
            fun reportError(msg: String, line: Int) {
                appendLog("ERROR", "Línea $line: $msg")
            }

            @JavascriptInterface
            fun reportDomReady(metrics: String, loadMs: Long) {
                mainHandler.post {
                    tvDomMetrics.text = "$metrics (${loadMs}ms)"
                    if (errorCount == 0) {
                        statusChip.text = "🟢 Listo (${loadMs}ms)"
                        statusChip.setTextColor(Color.parseColor("#10B981"))
                    }
                    appendLog("INFO", "DOMContentLoaded en ${loadMs}ms: $metrics")
                }
            }
        }

        webView.addJavascriptInterface(LiveStudioBridge(), "AndroidStudioBridge")

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val level = when (consoleMessage.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> "ERROR"
                    ConsoleMessage.MessageLevel.WARNING -> "WARN"
                    else -> "LOG"
                }
                appendLog(level, "L${consoleMessage.lineNumber()}: ${consoleMessage.message()}")
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (errorCount == 0) {
                    statusChip.text = "🟢 Render Activo"
                    statusChip.setTextColor(Color.parseColor("#10B981"))
                }
            }
        }

        fun loadStudioContent() {
            errorCount = 0
            tvLogs.text = ""
            statusChip.text = "🟡 Inicializando..."
            statusChip.setTextColor(Color.parseColor("#FBBF24"))

            val baseHtml = VisualMediaHtmlBuilder.buildHtml(type, source)

            val telemetryScript = """
                <script>
                (function() {
                    var startT = performance.now();
                    window.onerror = function(m, u, l, c, e) {
                        if (window.AndroidStudioBridge) {
                            window.AndroidStudioBridge.reportError(m + '', l || 0);
                        }
                        return false;
                    };
                    var oldLog = console.log;
                    console.log = function() {
                        oldLog.apply(console, arguments);
                        if (window.AndroidStudioBridge) {
                            window.AndroidStudioBridge.reportLog('LOG', Array.from(arguments).join(' '));
                        }
                    };
                    var oldWarn = console.warn;
                    console.warn = function() {
                        oldWarn.apply(console, arguments);
                        if (window.AndroidStudioBridge) {
                            window.AndroidStudioBridge.reportLog('WARN', Array.from(arguments).join(' '));
                        }
                    };
                    var oldErr = console.error;
                    console.error = function() {
                        oldErr.apply(console, arguments);
                        if (window.AndroidStudioBridge) {
                            window.AndroidStudioBridge.reportLog('ERROR', Array.from(arguments).join(' '));
                        }
                    };
                    window.addEventListener('DOMContentLoaded', function() {
                        var dur = Math.round(performance.now() - startT);
                        var canvases = document.querySelectorAll('canvas').length;
                        var buttons = document.querySelectorAll('button').length;
                        var imgs = document.querySelectorAll('img').length;
                        var svgs = document.querySelectorAll('svg').length;
                        var summ = canvases + ' canvas, ' + buttons + ' btn, ' + imgs + ' img, ' + svgs + ' svg';
                        if (window.AndroidStudioBridge) {
                            window.AndroidStudioBridge.reportDomReady(summ, dur);
                        }
                    });
                })();
                </script>
            """.trimIndent()

            val instrumentedHtml = if (baseHtml.contains("<head>", ignoreCase = true)) {
                baseHtml.replace(Regex("<head>", RegexOption.IGNORE_CASE), "<head>$telemetryScript")
            } else {
                "$telemetryScript$baseHtml"
            }

            webView.loadDataWithBaseURL("https://local.codex.sandbox", instrumentedHtml, "text/html", "UTF-8", null)
        }

        btnReload.setOnClickListener {
            loadStudioContent()
        }

        loadStudioContent()

        root.addView(header)
        root.addView(viewportContainer)
        root.addView(consoleDrawer)

        dialog.setContentView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.show()
    }
}

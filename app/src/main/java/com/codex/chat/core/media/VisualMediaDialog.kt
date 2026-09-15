package com.codex.chat.core.media

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

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
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.parseColor("#0F172A")))

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(16, 16, 16, 16)
        }

        val imageList = if (allSources.isNotEmpty()) allSources else listOf(source)
        var currentIndex = initialIndex.coerceIn(0, (imageList.size - 1).coerceAtLeast(0))

        val isDirectImage = type == VisualMediaType.IMAGE && (
            source.startsWith("file://") ||
            source.startsWith("/") ||
            source.startsWith("data:image/")
        )

        // Header bar
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
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnResetZoom = Button(context).apply {
            text = "↺ 100%"
            setTextColor(Color.parseColor("#38BDF8"))
            setBackgroundColor(Color.parseColor("#1E293B"))
            textSize = 11f
            visibility = if (isDirectImage) View.VISIBLE else View.GONE
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
        if (isDirectImage) header.addView(btnResetZoom)
        header.addView(btnCopy)
        header.addView(btnClose)

        if (isDirectImage) {
            // Visualizador táctil interactivo con ZOOM, PELLIZCO, DOBLE TOQUE Y PANEO
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

            btnResetZoom.setOnClickListener {
                zoomView.resetZoom()
            }

            loadImageForIndex(currentIndex)

            root.addView(header)
            root.addView(zoomView)

            // Si hay múltiples imágenes, barra de navegación inferior
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
            dialog.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            dialog.show()
            return
        }

        // Fullscreen interactive WebView with pinch-zoom support
        val webView = WebView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
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
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            val html = VisualMediaHtmlBuilder.buildHtml(type, source)
            loadDataWithBaseURL("https://local.codex", html, "text/html", "UTF-8", null)
        }

        root.addView(header)
        root.addView(webView)

        dialog.setContentView(root)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        dialog.show()
    }
}

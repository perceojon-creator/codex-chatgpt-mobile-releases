package com.codex.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.codex.chat.core.media.ImageCarouselAdapter
import com.codex.chat.core.media.VisualMediaDialog
import com.codex.chat.core.media.VisualMediaHtmlBuilder
import com.codex.chat.core.media.VisualMediaParser
import com.codex.chat.core.media.VisualMediaType
import com.codex.chat.core.model.ChatMessage
import com.codex.chat.core.model.MessageRole
import com.codex.chat.core.parser.ParsedToolCode
import com.codex.chat.core.parser.ToolCodeBlockParser

class ChatAdapter(
    private val messages: MutableList<ChatMessage>,
    private val onContinueTaskRequested: ((ChatMessage) -> Unit)? = null
) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_ASSISTANT = 2
        const val PAYLOAD_STREAMING = "PAYLOAD_STREAMING"
    }

    override fun getItemViewType(position: Int): Int {
        return when (messages[position].role) {
            MessageRole.USER -> VIEW_TYPE_USER
            MessageRole.ASSISTANT, MessageRole.SYSTEM -> VIEW_TYPE_ASSISTANT
            MessageRole.TOOL -> VIEW_TYPE_ASSISTANT // datos protocolares: se muestran como asistente (tarjeta colapsable)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_USER) {
            val view = inflater.inflate(R.layout.item_message_user, parent, false)
            UserViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_message_assistant, parent, false)
            AssistantViewHolder(view, onContinueTaskRequested)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        if (holder is UserViewHolder) {
            holder.bind(msg)
        } else if (holder is AssistantViewHolder) {
            holder.bind(msg)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.contains(PAYLOAD_STREAMING) && holder is AssistantViewHolder) {
            holder.updateStreaming(messages[position])
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun getItemCount(): Int = messages.size

    fun addMessage(msg: ChatMessage) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }

    fun updateLastMessage(newContent: String, newReasoning: String = "") {
        if (messages.isNotEmpty()) {
            val lastIdx = messages.size - 1
            val last = messages[lastIdx]
            last.content = newContent
            if (newReasoning.isNotEmpty()) {
                last.reasoningContent = newReasoning
            }
            notifyItemChanged(lastIdx, PAYLOAD_STREAMING)
        }
    }

    /**
     * Finaliza el streaming del último mensaje: apaga el flag y fuerza el bind COMPLETO.
     * Sin esto, isStreaming quedaba true para siempre tras completar y el placeholder de
     * imagen (o cualquier fast-path de streaming) seguía aplicando tras terminar el stream.
     */
        fun completeLastMessage(
        newContent: String,
        newReasoning: String = "",
        metrics: com.codex.chat.core.metrics.StreamMetrics? = null,
        canContinueTask: Boolean = false
    ) {
        if (messages.isNotEmpty()) {
            val lastIdx = messages.size - 1
            val last = messages[lastIdx]
            last.content = newContent
            last.reasoningContent = newReasoning
            last.isStreaming = false
            last.canContinueTask = canContinueTask
            if (metrics != null) {
                last.applyStreamMetrics(metrics)
            }
            notifyItemChanged(lastIdx) // bind completo: aquí SÍ se parsea la imagen (una sola vez)
        }
    }

    fun setMessages(newMessages: List<ChatMessage>) {
        val oldSnapshot = ArrayList(messages)
        val newSnapshot = ArrayList(newMessages)
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldSnapshot.size
            override fun getNewListSize(): Int = newSnapshot.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldSnapshot[oldItemPosition].id == newSnapshot[newItemPosition].id
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val o = oldSnapshot[oldItemPosition]
                val n = newSnapshot[newItemPosition]
                return o.content == n.content &&
                       o.reasoningContent == n.reasoningContent &&
                       o.role == n.role &&
                       o.isStreaming == n.isStreaming &&
                       o.isThinkingExpanded == n.isThinkingExpanded &&
                       o.isToolExpanded == n.isToolExpanded &&
                       o.durationMs == n.durationMs &&
                       o.thinkingDurationMs == n.thinkingDurationMs &&
                       o.generationDurationMs == n.generationDurationMs &&
                       o.completionTokens == n.completionTokens &&
                       o.tokensPerSecond == n.tokensPerSecond &&
                       o.canContinueTask == n.canContinueTask
            }
        })
        messages.clear()
        messages.addAll(newSnapshot)
        diff.dispatchUpdatesTo(this)
    }

    class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvContent: TextView = itemView.findViewById(R.id.tvUserContent)
        fun bind(msg: ChatMessage) {
            tvContent.text = msg.content
        }
    }

    class AssistantViewHolder(
        itemView: View,
        private val onContinueTaskRequested: ((ChatMessage) -> Unit)? = null
    ) : RecyclerView.ViewHolder(itemView) {
        private val tvContent: TextView = itemView.findViewById(R.id.tvAssistantContent)
        private val layoutThinking: LinearLayout = itemView.findViewById(R.id.layoutThinking)
        private val tvThinkingHeader: TextView = itemView.findViewById(R.id.tvThinkingHeader)
        private val tvThinkingBody: TextView = itemView.findViewById(R.id.tvThinkingBody)
        private val btnCopy: TextView = itemView.findViewById(R.id.btnCopy)

        // Collapsible Tool / Code block views
        private val layoutToolExecution: LinearLayout = itemView.findViewById(R.id.layoutToolExecution)
        private val layoutToolHeader: LinearLayout = itemView.findViewById(R.id.layoutToolHeader)
        private val tvToolTitle: TextView = itemView.findViewById(R.id.tvToolTitle)
        private val tvToolStatusBadge: TextView = itemView.findViewById(R.id.tvToolStatusBadge)
        private val tvToolChevron: TextView = itemView.findViewById(R.id.tvToolChevron)
        private val layoutToolBody: LinearLayout = itemView.findViewById(R.id.layoutToolBody)
        private val tvToolCode: TextView = itemView.findViewById(R.id.tvToolCode)
        private val btnCopyToolCode: TextView = itemView.findViewById(R.id.btnCopyToolCode)

        // Visual Media Views (Diagrams, SVGs, Images, Videos)
        private val layoutVisualMedia: LinearLayout = itemView.findViewById(R.id.layoutVisualMedia)
        private val tvMediaTitle: TextView = itemView.findViewById(R.id.tvMediaTitle)
        private val btnFullscreenMedia: TextView = itemView.findViewById(R.id.btnFullscreenMedia)
        private val webViewMedia: WebView = itemView.findViewById(R.id.webViewMedia)
        // ImageView nativo para fotos base64 o archivo local único
        private val imgViewMedia: ImageView = itemView.findViewById(R.id.imgViewMedia)

        // Carrusel ViewPager2 para respuestas con N imágenes generadas (deslizamiento táctil horizontal)
        private val layoutCarouselControls: LinearLayout = itemView.findViewById(R.id.layoutCarouselControls)
        private val tvCarouselIndicator: TextView = itemView.findViewById(R.id.tvCarouselIndicator)
        private val btnCarouselPrev: TextView = itemView.findViewById(R.id.btnCarouselPrev)
        private val btnCarouselNext: TextView = itemView.findViewById(R.id.btnCarouselNext)
        private val viewPagerMedia: ViewPager2 = itemView.findViewById(R.id.viewPagerMedia)
        private val layoutActions: LinearLayout = itemView.findViewById(R.id.layoutActions)
        private val layoutMetrics: LinearLayout = itemView.findViewById(R.id.layoutMetrics)
        private val tvMetricsText: TextView = itemView.findViewById(R.id.tvMetricsText)
        private val btnContinueTask: TextView = itemView.findViewById(R.id.btnContinueTask)

        init {
            webViewMedia.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            webViewMedia.setBackgroundColor(Color.TRANSPARENT)
        }

        fun bind(msg: ChatMessage) {
            val isNewMessageForHolder = lastBoundMessageId != msg.id
            lastBoundMessageId = msg.id
            if (isNewMessageForHolder) {
                lastLoadedMediaSource = null
            }
            bindThinking(msg)
            bindVisualMediaAndContent(msg)
            bindMetrics(msg)
            bindContinueTask(msg)
            bindCopy(msg)
        }

        fun updateStreaming(msg: ChatMessage) {
            bindThinking(msg)
            bindVisualMediaAndContent(msg)
            bindMetrics(msg)
            bindContinueTask(msg)
        }

        private fun bindContinueTask(msg: ChatMessage) {
            if (!msg.isStreaming && msg.canContinueTask) {
                btnContinueTask.visibility = View.VISIBLE
                layoutActions.visibility = View.VISIBLE
                btnContinueTask.setOnClickListener {
                    onContinueTaskRequested?.invoke(msg)
                }
            } else {
                btnContinueTask.visibility = View.GONE
            }
        }

        private fun bindMetrics(msg: ChatMessage) {
            if (!msg.isStreaming && msg.hasPerformanceMetrics) {
                val metrics = msg.toStreamMetrics()
                val summary = metrics.formatSummary()
                if (summary.isNotBlank()) {
                    tvMetricsText.text = summary
                    layoutMetrics.visibility = View.VISIBLE
                    layoutActions.visibility = View.VISIBLE
                    layoutMetrics.setOnClickListener {
                        val detail = metrics.formatDetailedTooltip()
                        Toast.makeText(itemView.context, detail, Toast.LENGTH_LONG).show()
                    }
                    return
                }
            }
            layoutMetrics.visibility = View.GONE
        }

        private fun bindThinking(msg: ChatMessage) {
            if (msg.hasReasoning) {
                if (layoutThinking.visibility != View.VISIBLE) {
                    com.codex.chat.ui.Motion.slideUpFadeIn(layoutThinking)
                }
                tvThinkingBody.text = msg.reasoningContent
                updateThinkingState(msg.isThinkingExpanded)

                tvThinkingHeader.setOnClickListener {
                    msg.isThinkingExpanded = !msg.isThinkingExpanded
                    animateSmoothTransition()
                    updateThinkingState(msg.isThinkingExpanded)
                }
            } else {
                layoutThinking.visibility = View.GONE
            }
        }

        private fun updateThinkingState(expanded: Boolean) {
            if (expanded) {
                tvThinkingBody.visibility = View.VISIBLE
                tvThinkingHeader.text = "💭 Ocultar razonamiento ▴"
            } else {
                tvThinkingBody.visibility = View.GONE
                tvThinkingHeader.text = "💭 Proceso de razonamiento ▾"
            }
        }

        private var lastLoadedMediaSource: String? = null
        // FIX blanco-tras-reinicio: el ViewHolder reciclado NO conserva el WebView cargado aunque
        // el contenido sea idéntico (DiffUtil no re-bindea items "iguales"). Forzamos recarga
        // cuando el holder muestra un mensaje distinto al que tenía renderizado.
        private var lastBoundMessageId: String? = null
        private var carouselAdapter: ImageCarouselAdapter? = null
        private var onPageChangeCallback: ViewPager2.OnPageChangeCallback? = null

        private fun bindVisualMediaAndContent(msg: ChatMessage) {
            // FIX anti-congelamiento: placeholder ligero durante el stream; el parseo real ocurre
            // UNA sola vez cuando el stream termina (bind completo con isStreaming=false).
            if (msg.isStreaming && (msg.content.contains("data:image/") || msg.content.contains("![imagen-generada]("))) {
                layoutVisualMedia.visibility = View.VISIBLE
                imgViewMedia.visibility = View.GONE
                layoutCarouselControls.visibility = View.GONE
                viewPagerMedia.visibility = View.GONE
                webViewMedia.visibility = View.GONE
                tvMediaTitle.text = "🖼️ Generando imagen…"
                bindToolAndContent("Procesando la imagen generada…", msg)
                return
            }
            val visual = VisualMediaParser.parse(msg.content)
            if (visual.hasMedia) {
                if (layoutVisualMedia.visibility != View.VISIBLE) {
                    com.codex.chat.ui.Motion.slideUpFadeIn(layoutVisualMedia, duration = com.codex.chat.ui.Motion.DURATION_L)
                }
                tvMediaTitle.text = visual.title

                val allImages = if (visual.imageSources.isNotEmpty()) visual.imageSources else listOf(visual.mediaSource)

                btnFullscreenMedia.setOnClickListener {
                    val curPos = if (allImages.size > 1) viewPagerMedia.currentItem.coerceIn(0, allImages.size - 1) else 0
                    val curSrc = if (allImages.isNotEmpty()) allImages[curPos] else visual.mediaSource
                    VisualMediaDialog.show(
                        itemView.context,
                        visual.type,
                        visual.title,
                        curSrc,
                        allImages,
                        curPos
                    )
                }

                val isDirectImage = visual.type == VisualMediaType.IMAGE && (
                    visual.mediaSource.startsWith("file://") ||
                    visual.mediaSource.startsWith("/") ||
                    visual.mediaSource.startsWith("data:image/")
                )
                if (isDirectImage) {
                    webViewMedia.visibility = View.GONE

                    if (allImages.size <= 1) {
                        // Caso 1 imagen: ImageView nativo
                        layoutCarouselControls.visibility = View.GONE
                        viewPagerMedia.visibility = View.GONE
                        imgViewMedia.visibility = View.VISIBLE

                        val src = allImages.firstOrNull() ?: visual.mediaSource
                        imgViewMedia.setOnClickListener {
                            VisualMediaDialog.show(itemView.context, visual.type, visual.title, src)
                        }
                        val cacheKey = com.codex.chat.core.media.MediaBitmapCache.keyFor(src)
                        val cached = com.codex.chat.core.media.MediaBitmapCache.get(cacheKey)
                        if (cached != null) {
                            imgViewMedia.setImageBitmap(cached)
                        } else {
                            imgViewMedia.setImageDrawable(null)
                            com.codex.chat.core.media.MediaBitmapCache.decodeAsync(cacheKey, src) { bmp ->
                                if (lastBoundMessageId == msg.id && bmp != null) {
                                    imgViewMedia.setImageBitmap(bmp)
                                }
                            }
                        }
                    } else {
                        // Caso N imágenes: Carrusel ViewPager2 deslizable con controles laterales
                        imgViewMedia.visibility = View.GONE
                        layoutCarouselControls.visibility = View.VISIBLE
                        viewPagerMedia.visibility = View.VISIBLE

                        if (carouselAdapter == null) {
                            carouselAdapter = ImageCarouselAdapter(allImages) { pos, src ->
                                VisualMediaDialog.show(
                                    itemView.context,
                                    visual.type,
                                    visual.title,
                                    src,
                                    allImages,
                                    pos
                                )
                            }
                            viewPagerMedia.adapter = carouselAdapter
                        } else {
                            carouselAdapter?.submitImages(allImages)
                        }

                        onPageChangeCallback?.let { viewPagerMedia.unregisterOnPageChangeCallback(it) }
                        val callback = object : ViewPager2.OnPageChangeCallback() {
                            override fun onPageSelected(position: Int) {
                                tvCarouselIndicator.text = "🖼️ ${position + 1} de ${allImages.size} · Desliza para ver más"
                                btnCarouselPrev.visibility = if (position > 0) View.VISIBLE else View.INVISIBLE
                                btnCarouselNext.visibility = if (position < allImages.size - 1) View.VISIBLE else View.INVISIBLE
                            }
                        }
                        onPageChangeCallback = callback
                        viewPagerMedia.registerOnPageChangeCallback(callback)

                        btnCarouselPrev.setOnClickListener {
                            val cur = viewPagerMedia.currentItem
                            if (cur > 0) viewPagerMedia.setCurrentItem(cur - 1, true)
                        }
                        btnCarouselNext.setOnClickListener {
                            val cur = viewPagerMedia.currentItem
                            if (cur < allImages.size - 1) viewPagerMedia.setCurrentItem(cur + 1, true)
                        }

                        val curPos = viewPagerMedia.currentItem.coerceIn(0, allImages.size - 1)
                        tvCarouselIndicator.text = "🖼️ ${curPos + 1} de ${allImages.size} · Desliza para ver más"
                        btnCarouselPrev.visibility = if (curPos > 0) View.VISIBLE else View.INVISIBLE
                        btnCarouselNext.visibility = if (curPos < allImages.size - 1) View.VISIBLE else View.INVISIBLE
                    }
                } else {
                    // SVG / Mermaid / HTML / Video / imágenes http(s) → WebView como antes.
                    imgViewMedia.visibility = View.GONE
                    layoutCarouselControls.visibility = View.GONE
                    viewPagerMedia.visibility = View.GONE
                    webViewMedia.visibility = View.VISIBLE
                    if (lastLoadedMediaSource != visual.mediaSource) {
                        lastLoadedMediaSource = visual.mediaSource
                        val html = VisualMediaHtmlBuilder.buildHtml(visual.type, visual.mediaSource)
                        webViewMedia.loadDataWithBaseURL("https://local.codex", html, "text/html", "UTF-8", null)
                    }
                }

                bindToolAndContent(visual.cleanContent, msg)
            } else {
                lastLoadedMediaSource = null
                layoutVisualMedia.visibility = View.GONE
                imgViewMedia.visibility = View.GONE
                layoutCarouselControls.visibility = View.GONE
                viewPagerMedia.visibility = View.GONE
                webViewMedia.visibility = View.GONE
                bindToolAndContent(msg.content, msg)
            }
        }

        private fun bindToolAndContent(rawText: String, msg: ChatMessage) {
            val parsed: ParsedToolCode = ToolCodeBlockParser.parse(rawText)

            if (parsed.hasToolOrCode) {
                if (layoutToolExecution.visibility != View.VISIBLE) {
                    com.codex.chat.ui.Motion.slideUpFadeIn(layoutToolExecution, duration = com.codex.chat.ui.Motion.DURATION_L)
                }
                tvToolTitle.text = parsed.tagTitle
                tvToolStatusBadge.text = parsed.statusBadge
                tvToolCode.text = parsed.codeContent

                // Pulso vivo mientras la herramienta está en ejecución
                if (parsed.statusBadge.contains("Ejecutando")) {
                    com.codex.chat.ui.Motion.pulse(tvToolStatusBadge)
                } else {
                    com.codex.chat.ui.Motion.stopPulse(tvToolStatusBadge)
                }

                updateToolState(msg.isToolExpanded)

                layoutToolHeader.setOnClickListener {
                    msg.isToolExpanded = !msg.isToolExpanded
                    animateSmoothTransition()
                    updateToolState(msg.isToolExpanded)
                }

                btnCopyToolCode.setOnClickListener {
                    copyToClipboard(itemView.context, "Salida de Herramienta", parsed.codeContent)
                    Toast.makeText(itemView.context, "Código/salida copiada", Toast.LENGTH_SHORT).show()
                }

                if (parsed.cleanContent.isNotBlank()) {
                    tvContent.visibility = View.VISIBLE
                    tvContent.text = parsed.cleanContent
                    layoutActions.visibility = View.VISIBLE
                } else {
                    tvContent.visibility = View.GONE
                    layoutActions.visibility = View.GONE
                }
            } else {
                layoutToolExecution.visibility = View.GONE
                if (rawText.isNotBlank()) {
                    tvContent.visibility = View.VISIBLE
                    tvContent.text = rawText
                    layoutActions.visibility = View.VISIBLE
                } else {
                    tvContent.visibility = View.GONE
                    layoutActions.visibility = View.GONE
                }
            }
        }

        private fun updateToolState(expanded: Boolean) {
            if (expanded) {
                layoutToolBody.visibility = View.VISIBLE
                tvToolChevron.animate().rotation(90f).setDuration(180).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            } else {
                layoutToolBody.visibility = View.GONE
                tvToolChevron.animate().rotation(0f).setDuration(180).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            }
        }

        private fun bindCopy(msg: ChatMessage) {
            btnCopy.setOnClickListener {
                copyToClipboard(itemView.context, "ChatGPT response", msg.content)
                Toast.makeText(itemView.context, "Mensaje copiado al portapapeles", Toast.LENGTH_SHORT).show()
            }
        }

        private fun animateSmoothTransition() {
            val container = (itemView.parent as? ViewGroup) ?: (itemView as? ViewGroup) ?: return
            val transition = AutoTransition().apply {
                duration = 220
                interpolator = android.view.animation.DecelerateInterpolator(1.5f)
            }
            TransitionManager.beginDelayedTransition(container, transition)
        }

        private fun copyToClipboard(context: Context, label: String, text: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard.setPrimaryClip(clip)
        }
    }
}

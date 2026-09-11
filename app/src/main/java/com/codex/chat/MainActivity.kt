package com.codex.chat

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.codex.chat.core.model.*
import com.codex.chat.core.network.CodexApiClient
import com.codex.chat.core.repository.DynamicModelsRepository
import com.codex.chat.core.repository.DynamicSubagentsRepository
import com.codex.chat.databinding.ActivityMainBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import java.io.InputStream
import java.util.*
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsManager
    private lateinit var apiClient: CodexApiClient
    private lateinit var modelsRepo: DynamicModelsRepository
    private lateinit var subagentsRepo: DynamicSubagentsRepository
    private lateinit var adapter: ChatAdapter
    private val messages = mutableListOf<ChatMessage>()

    private var activeSubagent: SubagentInfo? = null
    private var pendingAttachment: Attachment? = null

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    // File Picker launchers
    private val documentPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data?.data != null) {
            handleFileUri(result.data!!.data!!, isImage = false)
        }
    }

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data?.data != null) {
            handleFileUri(result.data!!.data!!, isImage = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = SettingsManager(this)
        apiClient = CodexApiClient()
        modelsRepo = DynamicModelsRepository()
        subagentsRepo = DynamicSubagentsRepository()

        // Restore active subagent if persisted
        settings.activeSubagentId?.let { id ->
            activeSubagent = subagentsRepo.getSubagentById(id)
        }

        setupRecyclerView()
        setupHeader()
        setupSubagentChips()
        setupInputListeners()
        setupSpeechRecognizer()

        // Welcome message
        val welcomeText = "¡Hola! Estoy conectado a tu servidor proxy local en ${settings.baseUrl}.\n\n" +
                "• Modelo: ${settings.selectedModelId} (${settings.reasoningEffort.value.uppercase()})\n" +
                "• Pulsa (+) para adjuntar archivos, fotos o elegir subagentes de ingeniería.\n" +
                "• Pulsa el micrófono para dictar con tu voz."

        adapter.addMessage(ChatMessage(role = MessageRole.ASSISTANT, content = welcomeText))

        // Background refresh of live models from CLIProxyAPI
        thread {
            modelsRepo.fetchLiveModels(settings.baseUrl, settings.apiKey)
        }
    }

    private fun setupRecyclerView() {
        adapter = ChatAdapter(messages)
        val layoutManager = LinearLayoutManager(this)
        layoutManager.stackFromEnd = true
        binding.rvMessages.layoutManager = layoutManager
        binding.rvMessages.adapter = adapter
    }

    private fun setupHeader() {
        updateHeaderBadges()

        binding.modelSelectorContainer.setOnClickListener {
            showModelAndEffortPicker()
        }

        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        binding.btnNewChat.setOnClickListener {
            messages.clear()
            pendingAttachment = null
            binding.attachmentPreviewBar.visibility = View.GONE
            adapter.notifyDataSetChanged()
            adapter.addMessage(
                ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = "Nueva sesión iniciada con modelo ${settings.selectedModelId}."
                )
            )
            Toast.makeText(this, "Sesión reiniciada", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateHeaderBadges() {
        binding.tvModelTitle.text = "${settings.selectedModelId} ▾"
        val model = modelsRepo.getModelById(settings.selectedModelId)
        if (model.supportsReasoning) {
            binding.tvEffortBadge.visibility = View.VISIBLE
            binding.tvEffortBadge.text = settings.reasoningEffort.value.uppercase()
        } else {
            binding.tvEffortBadge.visibility = View.GONE
        }
    }

    private fun setupSubagentChips() {
        binding.layoutChips.removeAllViews()
        for (agent in subagentsRepo.getAllSubagents()) {
            val chip = Chip(this)
            chip.text = "${agent.iconEmoji} ${agent.name}"
            chip.setChipBackgroundColorResource(R.color.bg_surface_light)
            chip.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            chip.setOnClickListener {
                selectSubagent(agent)
            }
            binding.layoutChips.addView(chip)
        }
    }

    private fun selectSubagent(agent: SubagentInfo) {
        activeSubagent = agent
        settings.activeSubagentId = agent.id
        settings.selectedModelId = agent.defaultModel
        settings.reasoningEffort = agent.reasoningEffort
        updateHeaderBadges()

        Toast.makeText(this, "Subagente activo: ${agent.name} (${agent.defaultModel})", Toast.LENGTH_SHORT).show()
        val notice = "🤖 Subagente activado: **${agent.name}**\n_${agent.description}_\nModelo: ${agent.defaultModel} | Esfuerzo: ${agent.reasoningEffort.value.uppercase()}"
        adapter.addMessage(ChatMessage(role = MessageRole.ASSISTANT, content = notice))
        binding.rvMessages.scrollToPosition(messages.size - 1)
    }

    private fun setupInputListeners() {
        binding.btnSend.setOnClickListener {
            sendMessage()
        }

        binding.btnMic.setOnClickListener {
            toggleSpeech()
        }

        binding.btnPlus.setOnClickListener {
            showActionBottomSheet()
        }

        binding.btnRemoveAttachment.setOnClickListener {
            pendingAttachment = null
            binding.attachmentPreviewBar.visibility = View.GONE
        }
    }

    private fun showActionBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val sheetView = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_actions, null)
        dialog.setContentView(sheetView)

        // 1. Attach document
        sheetView.findViewById<View>(R.id.actionAttachDoc).setOnClickListener {
            dialog.dismiss()
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/*", "application/json", "application/javascript", "application/python", "application/pdf"))
            }
            documentPickerLauncher.launch(intent)
        }

        // 2. Attach image
        sheetView.findViewById<View>(R.id.actionAttachImage).setOnClickListener {
            dialog.dismiss()
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
            }
            imagePickerLauncher.launch(intent)
        }

        // 3. Subagents full dialog
        sheetView.findViewById<View>(R.id.actionSubagents).setOnClickListener {
            dialog.dismiss()
            showSubagentsBottomSheet()
        }

        // 4. Web Search
        sheetView.findViewById<View>(R.id.actionWebSearch).setOnClickListener {
            dialog.dismiss()
            val current = binding.etMessage.text.toString()
            if (!current.startsWith("🌐 [Búsqueda Web]:")) {
                binding.etMessage.setText("🌐 [Búsqueda Web]: $current")
                binding.etMessage.setSelection(binding.etMessage.text.length)
            }
        }

        dialog.show()
    }

    private fun showSubagentsBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val sheetView = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_subagents, null)
        dialog.setContentView(sheetView)

        val recycler = sheetView.findViewById<RecyclerView>(R.id.rvSubagentsList)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = SubagentsAdapter(subagentsRepo.getAllSubagents()) { selectedAgent ->
            dialog.dismiss()
            selectSubagent(selectedAgent)
        }

        dialog.show()
    }

    private fun handleFileUri(uri: Uri, isImage: Boolean) {
        try {
            var fileName = "adjunto"
            var fileSize: Long = 0
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex != -1) fileName = it.getString(nameIndex)
                    if (sizeIndex != -1) fileSize = it.getLong(sizeIndex)
                }
            }

            val mimeType = contentResolver.getType(uri) ?: if (isImage) "image/png" else "text/plain"

            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            val bytes = inputStream?.readBytes() ?: ByteArray(0)
            val base64 = java.util.Base64.getEncoder().encodeToString(bytes)

            pendingAttachment = Attachment(
                id = UUID.randomUUID().toString(),
                fileName = fileName,
                mimeType = mimeType,
                sizeBytes = fileSize,
                base64Data = base64,
                fileUri = uri.toString()
            )

            binding.tvAttachmentIcon.text = if (isImage) "🖼️" else "📄"
            binding.tvAttachmentName.text = "$fileName (${fileSize / 1024} KB)"
            binding.attachmentPreviewBar.visibility = View.VISIBLE

            Toast.makeText(this, "Adjuntado: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error leyendo archivo: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun sendMessage() {
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty() && pendingAttachment == null) return

        val attachmentsList = mutableListOf<Attachment>()
        pendingAttachment?.let { attachmentsList.add(it) }

        // 1. Add user message
        val userMsg = ChatMessage(
            role = MessageRole.USER,
            content = text,
            attachments = attachmentsList
        )
        adapter.addMessage(userMsg)

        // Clear input and attachment bar
        binding.etMessage.setText("")
        pendingAttachment = null
        binding.attachmentPreviewBar.visibility = View.GONE
        binding.rvMessages.scrollToPosition(messages.size - 1)

        // 2. Add placeholder assistant streaming message
        val assistantMsg = ChatMessage(role = MessageRole.ASSISTANT, content = "Pensando…", isStreaming = true)
        adapter.addMessage(assistantMsg)
        binding.rvMessages.scrollToPosition(messages.size - 1)

        binding.btnSend.isEnabled = false

        val streamContentBuffer = StringBuilder()
        val streamReasoningBuffer = StringBuilder()

        val activeModel = modelsRepo.getModelById(settings.selectedModelId)

        apiClient.executeStream(
            baseUrl = settings.baseUrl,
            apiKey = settings.apiKey,
            model = activeModel,
            effort = settings.reasoningEffort,
            messages = messages.dropLast(1),
            activeSubagent = activeSubagent,
            callback = object : CodexApiClient.StreamCallback {
                override fun onReasoningDelta(delta: String) {
                    runOnUiThread {
                        streamReasoningBuffer.append(delta)
                        adapter.updateLastMessage(
                            if (streamContentBuffer.isEmpty()) "Pensando…" else streamContentBuffer.toString(),
                            streamReasoningBuffer.toString()
                        )
                        binding.rvMessages.scrollToPosition(messages.size - 1)
                    }
                }

                override fun onContentDelta(delta: String) {
                    runOnUiThread {
                        streamContentBuffer.append(delta)
                        adapter.updateLastMessage(
                            streamContentBuffer.toString(),
                            streamReasoningBuffer.toString()
                        )
                        binding.rvMessages.scrollToPosition(messages.size - 1)
                    }
                }

                override fun onComplete(fullContent: String, fullReasoning: String) {
                    runOnUiThread {
                        binding.btnSend.isEnabled = true
                        adapter.updateLastMessage(fullContent, fullReasoning)
                    }
                }

                override fun onError(error: Throwable) {
                    runOnUiThread {
                        binding.btnSend.isEnabled = true
                        adapter.updateLastMessage("⚠️ Error: " + error.message)
                    }
                }
            }
        )
    }

    private fun showModelAndEffortPicker() {
        val models = modelsRepo.getCachedModels()
        val modelNames = models.map { "${it.displayName} (${it.provider})" }.toTypedArray()
        var selectedIdx = models.indexOfFirst { it.id == settings.selectedModelId }.coerceAtLeast(0)

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null) // Reuse container or build custom dialog
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Selecciona el Modelo")

        builder.setSingleChoiceItems(modelNames, selectedIdx) { dialog, which ->
            val chosen = models[which]
            settings.selectedModelId = chosen.id
            dialog.dismiss()

            if (chosen.supportsReasoning) {
                showEffortPicker(chosen)
            } else {
                updateHeaderBadges()
                Toast.makeText(this@MainActivity, "Modelo cambiado a ${chosen.id}", Toast.LENGTH_SHORT).show()
            }
        }

        builder.show()
    }

    private fun showEffortPicker(model: ModelInfo) {
        val efforts = arrayOf("Low (Rápido)", "Medium (Equilibrado)", "High (Profundo)", "xHigh (Máximo Arquitecto)")
        val effortValues = arrayOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH, ReasoningEffort.XHIGH)
        val currentIdx = effortValues.indexOf(settings.reasoningEffort).coerceAtLeast(2)

        AlertDialog.Builder(this)
            .setTitle("Nivel de Razonamiento para ${model.id}")
            .setSingleChoiceItems(efforts, currentIdx) { dialog, which ->
                settings.reasoningEffort = effortValues[which]
                updateHeaderBadges()
                Toast.makeText(this@MainActivity, "Razonamiento: ${settings.reasoningEffort.value.uppercase()}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .show()
    }

    private fun showSettingsDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null)
        val etBaseUrl = view.findViewById<EditText>(R.id.etBaseUrl)
        val etApiKey = view.findViewById<EditText>(R.id.etApiKey)
        val btnPresetPC = view.findViewById<Button>(R.id.btnPresetPC)
        val btnPresetEmulator = view.findViewById<Button>(R.id.btnPresetEmulator)
        val btnSave = view.findViewById<Button>(R.id.btnSaveSettings)

        etBaseUrl.setText(settings.baseUrl)
        etApiKey.setText(settings.apiKey)

        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()

        btnPresetPC.setOnClickListener {
            etBaseUrl.setText("http://192.168.1.6:8317/v1")
        }

        btnPresetEmulator.setOnClickListener {
            etBaseUrl.setText("http://10.0.2.2:8317/v1")
        }

        btnSave.setOnClickListener {
            settings.baseUrl = etBaseUrl.text.toString().trim()
            settings.apiKey = etApiKey.text.toString().trim()
            Toast.makeText(this, "Ajustes guardados", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            thread {
                modelsRepo.fetchLiveModels(settings.baseUrl, settings.apiKey)
            }
        }

        dialog.show()
    }

    private fun setupSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    isListening = false
                    binding.btnMic.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                }
                override fun onError(error: Int) {
                    isListening = false
                    binding.btnMic.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                }
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        val heard = matches[0]
                        val cur = binding.etMessage.text.toString()
                        binding.etMessage.setText(if (cur.isEmpty()) heard else "$cur $heard")
                        binding.etMessage.setSelection(binding.etMessage.text.length)
                    }
                    isListening = false
                    binding.btnMic.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    private fun toggleSpeech() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
            return
        }

        if (isListening) {
            speechRecognizer?.stopListening()
            isListening = false
            binding.btnMic.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary))
        } else {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            }
            speechRecognizer?.startListening(intent)
            isListening = true
            binding.btnMic.setColorFilter(ContextCompat.getColor(this, R.color.brand_green))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
    }
}

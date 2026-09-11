package com.codex.chat

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.codex.chat.databinding.ActivityMainBinding
import com.google.android.material.chip.Chip
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsManager
    private lateinit var apiClient: ApiClient
    private lateinit var adapter: ChatAdapter
    private val messages = mutableListOf<ChatMessage>()

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    private val availableModels = arrayOf(
        "gpt-5.6-sol (Gemini 3.8 Flash High)",
        "astra (Claude Sonnet 4.6 Antigravity)",
        "gpt-6-astra (Claude Sonnet 4.6)",
        "gpt-5.6-terra (DeepSeek V4 Flash)",
        "gpt-5.6-luna (GLM 5.3 Flash)"
    )

    private val subagents = listOf(
        "system-architect" to "🏛️ System Architect",
        "tdd-implementer" to "🧪 TDD Implementer",
        "systematic-debugger" to "🔍 Debugger",
        "adversarial-reviewer" to "🛡️ Reviewer",
        "performance-profiler" to "📊 Profiler",
        "refactoring-code-craftsman" to "✨ Refactorer",
        "astra" to "🌟 Astra (Sonnet 4.6)"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = SettingsManager(this)
        apiClient = ApiClient(settings)

        setupRecyclerView()
        setupHeader()
        setupSubagentChips()
        setupInputListeners()
        setupSpeechRecognizer()

        // Welcome message
        adapter.addMessage(
            ChatMessage(
                MessageRole.ASSISTANT,
                "¡Hola! Soy tu asistente de ingeniería conectado directamente a tu proxy local CLIProxyAPI en tu PC.\n\nModelo activo: ${settings.selectedModel}\nPuedes escribir, dictar con el micrófono o pulsar los subagentes arriba."
            )
        )
    }

    private fun setupRecyclerView() {
        adapter = ChatAdapter(messages)
        val layoutManager = LinearLayoutManager(this)
        layoutManager.stackFromEnd = true
        binding.rvMessages.layoutManager = layoutManager
        binding.rvMessages.adapter = adapter
    }

    private fun setupHeader() {
        binding.tvModelTitle.text = "${settings.selectedModel} ▼"
        binding.tvModelTitle.setOnClickListener {
            showModelPicker()
        }

        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        binding.btnNewChat.setOnClickListener {
            messages.clear()
            adapter.notifyDataSetChanged()
            adapter.addMessage(
                ChatMessage(
                    MessageRole.ASSISTANT,
                    "Nueva conversación iniciada con el modelo: ${settings.selectedModel}"
                )
            )
            Toast.makeText(this, "Conversación reiniciada", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSubagentChips() {
        for ((slug, label) in subagents) {
            val chip = Chip(this)
            chip.text = label
            chip.setChipBackgroundColorResource(R.color.bg_surface_light)
            chip.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            chip.setOnClickListener {
                val prefix = "Lanza el subagente @$slug para:\n"
                val curText = binding.etMessage.text.toString()
                if (!curText.contains("@$slug")) {
                    binding.etMessage.setText(prefix + curText)
                    binding.etMessage.setSelection(binding.etMessage.text.length)
                }
            }
            binding.layoutChips.addView(chip)
        }
    }

    private fun setupInputListeners() {
        binding.btnSend.setOnClickListener {
            sendMessage()
        }

        binding.btnMic.setOnClickListener {
            toggleSpeech()
        }
    }

    private fun sendMessage() {
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty()) return

        // 1. Add user message
        val userMsg = ChatMessage(MessageRole.USER, text)
        adapter.addMessage(userMsg)
        binding.etMessage.setText("")
        binding.rvMessages.scrollToPosition(messages.size - 1)

        // 2. Add placeholder assistant streaming message
        val assistantMsg = ChatMessage(MessageRole.ASSISTANT, "Pensando…", isStreaming = true)
        adapter.addMessage(assistantMsg)
        binding.rvMessages.scrollToPosition(messages.size - 1)

        // Disable send button while streaming
        binding.btnSend.isEnabled = false

        val streamBuffer = StringBuilder()

        apiClient.sendMessageStream(messages.dropLast(1), object : ApiClient.StreamCallback {
            override fun onDelta(chunk: String) {
                runOnUiThread {
                    streamBuffer.append(chunk)
                    adapter.updateLastMessage(streamBuffer.toString())
                    binding.rvMessages.scrollToPosition(messages.size - 1)
                }
            }

            override fun onComplete(fullText: String) {
                runOnUiThread {
                    binding.btnSend.isEnabled = true
                    if (streamBuffer.isNotEmpty()) {
                        adapter.updateLastMessage(streamBuffer.toString())
                    }
                }
            }

            override fun onError(errorMessage: String) {
                runOnUiThread {
                    binding.btnSend.isEnabled = true
                    adapter.updateLastMessage("⚠️ Error: $errorMessage")
                }
            }
        })
    }

    private fun showModelPicker() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Selecciona el Modelo de IA")

        val modelIds = arrayOf("gpt-5.6-sol", "astra", "gpt-6-astra", "gpt-5.6-terra", "gpt-5.6-luna")
        var checkedItem = modelIds.indexOf(settings.selectedModel)
        if (checkedItem < 0) checkedItem = 0

        builder.setSingleChoiceItems(availableModels, checkedItem) { dialog, which ->
            val selected = modelIds[which]
            settings.selectedModel = selected
            binding.tvModelTitle.text = "$selected ▼"
            Toast.makeText(this, "Modelo cambiado a: $selected", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }
        builder.show()
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
            Toast.makeText(this, "Ajustes guardados correctamente", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
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

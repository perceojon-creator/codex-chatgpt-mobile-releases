package com.codex.chat.ui.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * Gestor autónomo y desacoplado de entrada por voz y reconocimiento de voz (SpeechRecognizer).
 *
 * Extraído de MainActivity para erradicar el antipatrón God-Class y centralizar
 * la normalización de decibelios de audio para [HorizonOrbView], los callbacks
 * de reconocimiento y el ciclo de vida del reconocedor de voz nativo de Android.
 */
class VoiceInputManager(
    private val context: Context,
    private val listener: Listener
) {

    interface Listener {
        fun onReadyForSpeech()
        fun onBeginningOfSpeech()
        fun onAudioAmplitude(amplitude: Float)
        fun onPartialTranscription(partial: String)
        fun onFinalTranscription(text: String)
        fun onError(errorCode: Int)
        fun onEndOfSpeech()
    }

    companion object {
        private const val TAG = "VoiceInputManager"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    var isListening: Boolean = false
        private set

    init {
        initRecognizer()
    }

    fun isRecognitionAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    private fun initRecognizer() {
        if (!isRecognitionAvailable()) {
            Log.w(TAG, "Reconocimiento de voz no disponible en este dispositivo.")
            return
        }

        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        mainHandler.post { listener.onReadyForSpeech() }
                    }

                    override fun onBeginningOfSpeech() {
                        mainHandler.post { listener.onBeginningOfSpeech() }
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        // Normaliza rmsdB (-2 a +10 aprox) a rango visual [0.05f .. 1.0f]
                        val normalized = ((rmsdB + 2.0f) / 12.0f).coerceIn(0.05f, 1.0f)
                        mainHandler.post { listener.onAudioAmplitude(normalized) }
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        isListening = false
                        mainHandler.post { listener.onEndOfSpeech() }
                    }

                    override fun onError(error: Int) {
                        isListening = false
                        Log.w(TAG, "SpeechRecognizer error code: $error")
                        mainHandler.post { listener.onError(error) }
                    }

                    override fun onResults(results: Bundle?) {
                        isListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val text = matches?.firstOrNull().orEmpty()
                        mainHandler.post { listener.onFinalTranscription(text) }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        if (!partial.isNullOrBlank()) {
                            mainHandler.post { listener.onPartialTranscription(partial) }
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error inicializando SpeechRecognizer: ${e.message}", e)
        }
    }

    fun startListening() {
        if (speechRecognizer == null) {
            initRecognizer()
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }

        try {
            speechRecognizer?.startListening(intent)
            isListening = true
        } catch (e: Exception) {
            Log.e(TAG, "Fallo al iniciar escucha en SpeechRecognizer: ${e.message}", e)
            isListening = false
            listener.onError(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    fun stopListening() {
        if (isListening) {
            try {
                speechRecognizer?.stopListening()
            } catch (e: Exception) {
                Log.w(TAG, "Aviso al detener SpeechRecognizer: ${e.message}")
            }
            isListening = false
        }
    }

    fun destroy() {
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Aviso al destruir SpeechRecognizer: ${e.message}")
        }
        speechRecognizer = null
        isListening = false
    }
}

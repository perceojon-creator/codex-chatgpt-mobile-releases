package com.codex.chat.core.connector

/**
 * Proveedor o tipo de conector soportado. Diseñado para ampliarse con nuevos conectores
 * con el tiempo (Google Flow, OpenAI DALL-E/Sora, Stability, Midjourney, etc.).
 */
enum class ConnectorProvider(
    val id: String,
    val displayName: String,
    val description: String
) {
    GOOGLE_FLOW(
        id = "google_flow",
        displayName = "Google Flow",
        description = "Generación de imágenes (Imagen 3.1 / ImageFX) y videos (Veo 3.1 / VideoFX) vía Google Flow y CLIProxyAPI local"
    );

    companion object {
        fun fromId(id: String?): ConnectorProvider {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: GOOGLE_FLOW
        }
    }
}

/**
 * Modalidad de medio soportada por el conector (Imagen o Video).
 */
enum class MediaConnectorType(val displayName: String, val icon: String) {
    IMAGE("Imagen", "🎨"),
    VIDEO("Video", "🎬")
}

/**
 * Configuración para el conector Google Flow u otros conectores futuros.
 */
data class MediaConnectorConfig(
    val provider: ConnectorProvider = ConnectorProvider.GOOGLE_FLOW,
    val baseUrl: String = "http://127.0.0.1:8317/v1",
    val apiKey: String = "proxy-pool",
    val imageModel: String = "imagen-3.1",
    val videoModel: String = "veo-3.1",
    val imageSize: String = "1024x1024",
    val videoAspectRatio: String = "16:9",
    val videoDuration: Int = 5,
    val responseFormat: String = "b64_json"
) {
    val connectorName: String
        get() = provider.displayName
}

/**
 * Resultado de la generación devuelto por el conector.
 */
data class MediaGenerationResult(
    val isSuccess: Boolean,
    val type: MediaConnectorType,
    val prompt: String,
    val model: String,
    val provider: String = ConnectorProvider.GOOGLE_FLOW.displayName,
    val mediaUrl: String? = null,
    val b64Data: String? = null,
    val markdownContent: String = "",
    val errorMessage: String? = null,
    val rawResponse: String? = null
) {
    val success: Boolean get() = isSuccess
    val errorDetails: String? get() = errorMessage
}

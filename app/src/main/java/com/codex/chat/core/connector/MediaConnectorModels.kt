package com.codex.chat.core.connector

/**
 * Proveedor o tipo de conector soportado. Diseñado para ampliarse con nuevos conectores
 * con el tiempo (Google Flow, OpenAI DALL-E/Sora, Stability, Midjourney, etc.).
 */
enum class ConnectorProvider(
    val id: String,
    val displayName: String,
    val description: String,
    val category: String = "Imagen y Video",
    val iconEmoji: String = "⚡",
    val isAvailable: Boolean = true
) {
    GOOGLE_FLOW(
        id = "google_flow",
        displayName = "Google Flow",
        description = "Generación de imágenes (Imagen 3.1 / ImageFX) y videos (Veo 3.1 / VideoFX) vía Google Flow y CLIProxyAPI local",
        category = "Imagen y Video",
        iconEmoji = "⚡",
        isAvailable = true
    ),
    GOOGLE_DRIVE(
        id = "google_drive",
        displayName = "Google Drive",
        description = "Acceso, lectura y sincronización de archivos y documentos en la nube",
        category = "Productividad y Nube",
        iconEmoji = "📁",
        isAvailable = false
    ),
    GMAIL(
        id = "gmail",
        displayName = "Gmail",
        description = "Lectura, redacción y gestión asistida de correos electrónicos",
        category = "Productividad y Nube",
        iconEmoji = "✉️",
        isAvailable = false
    ),
    GITHUB(
        id = "github",
        displayName = "GitHub",
        description = "Acceso nativo a repositorios, código fuente, issues y pull requests",
        category = "Desarrollo y Código",
        iconEmoji = "🐙",
        isAvailable = true
    ),
    VIBES(
        id = "vibes",
        displayName = "Vibes (v0)",
        description = "Generación de componentes UI y prototipado visual full-stack",
        category = "Diseño y Prototipado",
        iconEmoji = "✨",
        isAvailable = false
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
    val rawResponse: String? = null,
    val creditsCost: Double = 0.0,
    val creditsRemaining: Double = 0.0,
    val creditsTotal: Double = 0.0,
    val accountEmail: String? = null
) {
    val success: Boolean get() = isSuccess
    val errorDetails: String? get() = errorMessage
}

/**
 * Información de balance de créditos devuelta por GET /v1/flow/credits
 */
data class FlowCreditsResponse(
    val status: String = "ok",
    val account: String = "",
    val creditsRemaining: Double = 1050.0,
    val creditsTotal: Double = 1050.0,
    val dailyCredits: Double = 50.0,
    val planCredits: Double = 1000.0,
    val isConnected: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val pricingJson: String? = null
)

/**
 * Elemento del desglose de precios por modelo y cualidad
 */
data class FlowPricingItem(
    val category: String,
    val modelName: String,
    val configuration: String,
    val costCredits: Double,
    val notes: String = ""
)

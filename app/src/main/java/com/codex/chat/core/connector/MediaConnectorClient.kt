package com.codex.chat.core.connector

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cliente de red para conectores de medios (Google Flow y compatibles OpenAI/REST).
 */
class MediaConnectorClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) {

    /**
     * Resuelve la URL completa para el endpoint de generación.
     * Ejemplo: baseUrl = "http://127.0.0.1:8317/v1", endpoint = "/images/generations"
     * -> "http://127.0.0.1:8317/v1/images/generations"
     */
    fun resolveEndpointUrl(baseUrl: String, endpoint: String): String {
        val trimmedBase = baseUrl.trim().trimEnd('/')
        val trimmedEndpoint = "/" + endpoint.trim().trimStart('/')

        if (trimmedBase.endsWith(trimmedEndpoint, ignoreCase = true)) {
            return trimmedBase
        }

        return if (!trimmedBase.endsWith("/v1", ignoreCase = true) && !trimmedEndpoint.startsWith("/v1", ignoreCase = true)) {
            "$trimmedBase/v1$trimmedEndpoint"
        } else {
            "$trimmedBase$trimmedEndpoint"
        }
    }

    /**
     * Genera una imagen llamando al conector (Google Flow vía /v1/images/generations o /v1/images/edits si hay imagen de entrada).
     */
    fun generateImage(
        prompt: String,
        config: MediaConnectorConfig,
        inputImage: String? = null
    ): MediaGenerationResult {
        if (!inputImage.isNullOrBlank()) {
            return editImage(prompt, inputImage, null, config)
        }

        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty()) {
            return MediaGenerationResult(
                isSuccess = false,
                type = MediaConnectorType.IMAGE,
                prompt = cleanPrompt,
                model = config.imageModel,
                provider = config.connectorName,
                errorMessage = "El prompt para generar la imagen con ${config.connectorName} no puede estar vacío."
            )
        }

        val url = resolveEndpointUrl(config.baseUrl, config.typeOrDefaultEndpoint(MediaConnectorType.IMAGE))

        val payload = JSONObject().apply {
            put("prompt", cleanPrompt)
            put("model", config.imageModel)
            put("n", 1)
            put("size", config.imageSize)
            put("response_format", config.responseFormat)
        }

        val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")

        if (config.apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer ${config.apiKey.trim()}")
        }

        return try {
            val response = client.newCall(requestBuilder.build()).execute()
            val code = response.code
            val responseBody = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                val errorMsg = parseErrorMessage(responseBody, code, config.connectorName)
                return MediaGenerationResult(
                    isSuccess = false,
                    type = MediaConnectorType.IMAGE,
                    prompt = cleanPrompt,
                    model = config.imageModel,
                    provider = config.connectorName,
                    errorMessage = errorMsg,
                    rawResponse = responseBody
                )
            }

            val extracted = extractImageData(responseBody)
            if (extracted.first == null && extracted.second == null) {
                return MediaGenerationResult(
                    isSuccess = false,
                    type = MediaConnectorType.IMAGE,
                    prompt = cleanPrompt,
                    model = config.imageModel,
                    provider = config.connectorName,
                    errorMessage = "La respuesta del conector ${config.connectorName} no contiene datos de imagen válidos: $responseBody",
                    rawResponse = responseBody
                )
            }

            val mediaUrl = extracted.first
            val b64 = extracted.second
            val markdown = formatImageMarkdown(cleanPrompt, config.connectorName, config.imageModel, mediaUrl, b64)

            MediaGenerationResult(
                isSuccess = true,
                type = MediaConnectorType.IMAGE,
                prompt = cleanPrompt,
                model = config.imageModel,
                provider = config.connectorName,
                mediaUrl = mediaUrl,
                b64Data = b64,
                markdownContent = markdown,
                rawResponse = responseBody
            )
        } catch (e: Exception) {
            MediaGenerationResult(
                isSuccess = false,
                type = MediaConnectorType.IMAGE,
                prompt = cleanPrompt,
                model = config.imageModel,
                provider = config.connectorName,
                errorMessage = "Error de conexión con el conector ${config.connectorName} en $url: ${e.message}",
                rawResponse = null
            )
        }
    }

    /**
     * Edita o aplica inpainting sobre una imagen llamando a /v1/images/edits.
     */
    fun editImage(
        prompt: String,
        imageBase64: String,
        maskBase64: String? = null,
        config: MediaConnectorConfig
    ): MediaGenerationResult {
        val cleanPrompt = prompt.trim()
        val url = resolveEndpointUrl(config.baseUrl, "/images/edits")

        val payload = JSONObject().apply {
            put("prompt", if (cleanPrompt.isNotEmpty()) cleanPrompt else "Enhance and edit this image")
            put("model", config.imageModel)
            put("image", imageBase64)
            if (!maskBase64.isNullOrBlank()) {
                put("mask", maskBase64)
            }
            put("response_format", config.responseFormat)
        }

        val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")

        if (config.apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer ${config.apiKey.trim()}")
        }

        return try {
            val response = client.newCall(requestBuilder.build()).execute()
            val code = response.code
            val responseBody = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                val errorMsg = parseErrorMessage(responseBody, code, config.connectorName)
                return MediaGenerationResult(
                    isSuccess = false,
                    type = MediaConnectorType.IMAGE,
                    prompt = cleanPrompt,
                    model = config.imageModel,
                    provider = config.connectorName,
                    errorMessage = errorMsg,
                    rawResponse = responseBody
                )
            }

            val extracted = extractImageData(responseBody)
            if (extracted.first == null && extracted.second == null) {
                return MediaGenerationResult(
                    isSuccess = false,
                    type = MediaConnectorType.IMAGE,
                    prompt = cleanPrompt,
                    model = config.imageModel,
                    provider = config.connectorName,
                    errorMessage = "La respuesta del conector ${config.connectorName} no contiene datos de imagen editada válidos: $responseBody",
                    rawResponse = responseBody
                )
            }

            val mediaUrl = extracted.first
            val b64 = extracted.second
            val markdown = formatImageMarkdown(cleanPrompt, config.connectorName, config.imageModel, mediaUrl, b64)

            MediaGenerationResult(
                isSuccess = true,
                type = MediaConnectorType.IMAGE,
                prompt = cleanPrompt,
                model = config.imageModel,
                provider = config.connectorName,
                mediaUrl = mediaUrl,
                b64Data = b64,
                markdownContent = markdown,
                rawResponse = responseBody
            )
        } catch (e: Exception) {
            MediaGenerationResult(
                isSuccess = false,
                type = MediaConnectorType.IMAGE,
                prompt = cleanPrompt,
                model = config.imageModel,
                provider = config.connectorName,
                errorMessage = "Error de conexión con el conector ${config.connectorName} en $url: ${e.message}",
                rawResponse = null
            )
        }
    }

    /**
     * Genera un video llamando al conector (Google Flow vía /v1/videos/generations).
     * Soporta Image-to-Video cuando inputImage no es nulo.
     */
    fun generateVideo(
        prompt: String,
        config: MediaConnectorConfig,
        inputImage: String? = null
    ): MediaGenerationResult {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty()) {
            return MediaGenerationResult(
                isSuccess = false,
                type = MediaConnectorType.VIDEO,
                prompt = cleanPrompt,
                model = config.videoModel,
                provider = config.connectorName,
                errorMessage = "El prompt para generar el video con ${config.connectorName} no puede estar vacío."
            )
        }

        val url = resolveEndpointUrl(config.baseUrl, config.typeOrDefaultEndpoint(MediaConnectorType.VIDEO))

        val payload = JSONObject().apply {
            put("prompt", cleanPrompt)
            put("model", config.videoModel)
            put("aspect_ratio", config.videoAspectRatio)
            put("duration", config.videoDuration)
            put("seconds", config.videoDuration)
            if (!inputImage.isNullOrBlank()) {
                put("image", inputImage)
                put("first_frame", inputImage)
            }
        }

        val requestBody = payload.toString().toRequestBody("application/json".toMediaType())
        val requestBuilder = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")

        if (config.apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer ${config.apiKey.trim()}")
        }

        return try {
            val response = client.newCall(requestBuilder.build()).execute()
            val code = response.code
            val responseBody = response.body?.string().orEmpty()

            if (!response.isSuccessful) {
                val errorMsg = parseErrorMessage(responseBody, code, config.connectorName)
                return MediaGenerationResult(
                    isSuccess = false,
                    type = MediaConnectorType.VIDEO,
                    prompt = cleanPrompt,
                    model = config.videoModel,
                    provider = config.connectorName,
                    errorMessage = errorMsg,
                    rawResponse = responseBody
                )
            }

            val videoUrl = extractVideoData(responseBody)
            if (videoUrl == null) {
                return MediaGenerationResult(
                    isSuccess = false,
                    type = MediaConnectorType.VIDEO,
                    prompt = cleanPrompt,
                    model = config.videoModel,
                    provider = config.connectorName,
                    errorMessage = "La respuesta del conector ${config.connectorName} no contiene una URL o archivo de video reproducible: $responseBody",
                    rawResponse = responseBody
                )
            }

            val isImageToVideo = !inputImage.isNullOrBlank()
            val markdown = formatVideoMarkdown(cleanPrompt, config.connectorName, config.videoModel, videoUrl, isImageToVideo)

            MediaGenerationResult(
                isSuccess = true,
                type = MediaConnectorType.VIDEO,
                prompt = cleanPrompt,
                model = config.videoModel,
                provider = config.connectorName,
                mediaUrl = videoUrl,
                markdownContent = markdown,
                rawResponse = responseBody
            )
        } catch (e: Exception) {
            MediaGenerationResult(
                isSuccess = false,
                type = MediaConnectorType.VIDEO,
                prompt = cleanPrompt,
                model = config.videoModel,
                provider = config.connectorName,
                errorMessage = "Error de conexión con el conector ${config.connectorName} en $url: ${e.message}",
                rawResponse = null
            )
        }
    }

    /**
     * Extrae URL o base64 de la respuesta JSON del motor de imágenes.
     * Retorna Pair<Url?, Base64?>
     */
    fun extractImageData(jsonStr: String): Pair<String?, String?> {
        return try {
            val json = JSONObject(jsonStr)

            // 1. OpenAI format: { "data": [ { "url": "...", "b64_json": "..." } ] }
            val dataArray = json.optJSONArray("data")
            if (dataArray != null && dataArray.length() > 0) {
                val first = dataArray.getJSONObject(0)
                val url = first.optString("url").takeIf { it.isNotBlank() }
                val b64 = first.optString("b64_json").takeIf { it.isNotBlank() }
                    ?: first.optString("base64").takeIf { it.isNotBlank() }
                if (url != null || b64 != null) {
                    return Pair(url, b64)
                }
            }

            // 2. Format: { "images": [ "url_or_b64" ] }
            val imagesArray = json.optJSONArray("images")
            if (imagesArray != null && imagesArray.length() > 0) {
                val firstItem = imagesArray.get(0)
                if (firstItem is JSONObject) {
                    val url = firstItem.optString("url").takeIf { it.isNotBlank() }
                    val b64 = firstItem.optString("b64_json").takeIf { it.isNotBlank() }
                        ?: firstItem.optString("base64").takeIf { it.isNotBlank() }
                    return Pair(url, b64)
                } else if (firstItem is String && firstItem.isNotBlank()) {
                    if (firstItem.startsWith("http://") || firstItem.startsWith("https://")) {
                        return Pair(firstItem, null)
                    } else {
                        return Pair(null, firstItem)
                    }
                }
            }

            // 3. Format: { "candidates": [ ... ] } (Google Imagen / ImageFX direct)
            val candidates = json.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val firstCandidate = candidates.getJSONObject(0)
                val url = firstCandidate.optString("url").takeIf { it.isNotBlank() }
                val b64 = firstCandidate.optString("image").takeIf { it.isNotBlank() }
                    ?: firstCandidate.optString("bytesBase64Encoded").takeIf { it.isNotBlank() }
                if (url != null || b64 != null) {
                    return Pair(url, b64)
                }
            }

            // 4. Top-level url or b64
            val topUrl = json.optString("url").takeIf { it.isNotBlank() }
            val topB64 = json.optString("b64_json").takeIf { it.isNotBlank() }
                ?: json.optString("base64").takeIf { it.isNotBlank() }

            Pair(topUrl, topB64)
        } catch (e: Exception) {
            Pair(null, null)
        }
    }

    /**
     * Extrae la URL de video de la respuesta JSON.
     */
    fun extractVideoData(jsonStr: String): String? {
        return try {
            val json = JSONObject(jsonStr)

            // Direct URL
            val directUrl = json.optString("url").takeIf { it.isNotBlank() }
                ?: json.optString("video_url").takeIf { it.isNotBlank() }
            if (directUrl != null) return directUrl

            // Data array
            val dataArray = json.optJSONArray("data")
            if (dataArray != null && dataArray.length() > 0) {
                val first = dataArray.getJSONObject(0)
                val url = first.optString("url").takeIf { it.isNotBlank() }
                    ?: first.optString("video_url").takeIf { it.isNotBlank() }
                if (url != null) return url
            }

            // Videos array
            val videosArray = json.optJSONArray("videos")
            if (videosArray != null && videosArray.length() > 0) {
                val first = videosArray.get(0)
                if (first is JSONObject) {
                    val url = first.optString("url").takeIf { it.isNotBlank() }
                    if (url != null) return url
                } else if (first is String && first.isNotBlank()) {
                    return first
                }
            }

            null
        } catch (e: Exception) {
            null
        }
    }

    fun formatImageMarkdown(
        prompt: String,
        provider: String = ConnectorProvider.GOOGLE_FLOW.displayName,
        model: String,
        url: String?,
        b64: String?
    ): String {
        val src = when {
            !url.isNullOrBlank() -> url
            !b64.isNullOrBlank() -> {
                if (b64.startsWith("data:image")) b64 else "data:image/png;base64,$b64"
            }
            else -> ""
        }

        return """
            ![$prompt]($src)

            🎨 **$provider • Imagen generada con éxito**
            - **Conector:** $provider
            - **Modelo:** `$model`
            - **Prompt:** "$prompt"
        """.trimIndent()
    }

    fun formatVideoMarkdown(
        prompt: String,
        provider: String = ConnectorProvider.GOOGLE_FLOW.displayName,
        model: String,
        videoUrl: String,
        isImageToVideo: Boolean = false
    ): String {
        val modeBadge = if (isImageToVideo) "\n- **Modo:** 🎞️ Image-to-Video (Animación desde referencia)" else ""
        return """
            <video src="$videoUrl" controls></video>

            🎬 **$provider • Video generado con éxito**
            - **Conector:** $provider
            - **Modelo:** `$model`$modeBadge
            - **Prompt:** "$prompt"
        """.trimIndent()
    }

    private fun parseErrorMessage(responseBody: String, httpCode: Int, connectorName: String): String {
        return try {
            val json = JSONObject(responseBody)
            val errObj = json.optJSONObject("error")
            if (errObj != null) {
                val msg = errObj.optString("message")
                if (msg.isNotBlank()) {
                    return "Conector $connectorName (Error $httpCode): $msg"
                }
            }
            val msg = json.optString("message")
            if (msg.isNotBlank()) "Conector $connectorName (Error $httpCode): $msg" else "Error HTTP $httpCode del conector $connectorName"
        } catch (e: Exception) {
            if (responseBody.isNotBlank()) {
                "Conector $connectorName (Error $httpCode): $responseBody"
            } else {
                "Error HTTP $httpCode recibido del conector $connectorName"
            }
        }
    }

    fun formatResultMarkdown(result: MediaGenerationResult, fallbackPrompt: String = ""): String {
        val prompt = if (result.prompt.isNotBlank()) result.prompt else fallbackPrompt
        return when (result.type) {
            MediaConnectorType.IMAGE -> formatImageMarkdown(
                prompt = prompt,
                provider = result.provider,
                model = result.model,
                url = result.mediaUrl,
                b64 = result.b64Data
            )
            MediaConnectorType.VIDEO -> formatVideoMarkdown(
                prompt = prompt,
                provider = result.provider,
                model = result.model,
                videoUrl = result.mediaUrl ?: ""
            )
        }
    }

    private fun MediaConnectorConfig.typeOrDefaultEndpoint(type: MediaConnectorType): String {
        return when (type) {
            MediaConnectorType.IMAGE -> "/images/generations"
            MediaConnectorType.VIDEO -> "/videos/generations"
        }
    }

    companion object {
        private val defaultClient = MediaConnectorClient()

        fun generateImage(prompt: String, config: MediaConnectorConfig, inputImage: String? = null): MediaGenerationResult {
            return defaultClient.generateImage(prompt, config, inputImage)
        }

        fun generateVideo(prompt: String, config: MediaConnectorConfig, inputImage: String? = null): MediaGenerationResult {
            return defaultClient.generateVideo(prompt, config, inputImage)
        }

        fun editImage(prompt: String, imageBase64: String, maskBase64: String? = null, config: MediaConnectorConfig): MediaGenerationResult {
            return defaultClient.editImage(prompt, imageBase64, maskBase64, config)
        }

        fun formatResultMarkdown(result: MediaGenerationResult, fallbackPrompt: String = ""): String {
            return defaultClient.formatResultMarkdown(result, fallbackPrompt)
        }
    }
}

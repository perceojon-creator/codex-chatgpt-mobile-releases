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
     * Consulta el estado de la cuenta y créditos en tiempo real a /v1/flow/credits.
     */
    fun fetchCredits(config: MediaConnectorConfig): FlowCreditsResponse {
        val url = resolveEndpointUrl(config.baseUrl, "/flow/credits")
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
            .addHeader("Accept", "application/json")

        if (config.apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer ${config.apiKey.trim()}")
        }

        return try {
            val response = client.newCall(requestBuilder.build()).execute()
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful || body.isBlank()) {
                FlowCreditsResponse(status = "error", isConnected = false)
            } else {
                val json = JSONObject(body)
                val pricingObj = json.optJSONObject("pricing")?.toString()
                val rem = json.optDouble("credits_remaining", 1050.0)
                val tot = json.optDouble("credits_total", 1050.0)
                val daily = json.optDouble("daily_credits", 50.0)
                val plan = json.optDouble("plan_credits", 1000.0)
                val acc = json.optString("account", "")
                FlowCreditsResponse(
                    status = json.optString("status", "ok"),
                    account = acc,
                    creditsRemaining = if (rem >= 0.0) rem else 1050.0,
                    creditsTotal = if (tot >= 0.0) tot else 1050.0,
                    dailyCredits = if (daily >= 0.0) daily else 50.0,
                    planCredits = if (plan >= 0.0) plan else 1000.0,
                    isConnected = json.optBoolean("connected", true),
                    timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                    pricingJson = pricingObj
                )
            }
        } catch (e: Exception) {
            FlowCreditsResponse(status = "error", isConnected = false)
        }
    }

    /**
     * Consulta el catálogo completo de precios de Google Flow a /v1/flow/pricing.
     */
    fun fetchPricing(config: MediaConnectorConfig): String {
        val url = resolveEndpointUrl(config.baseUrl, "/flow/pricing")
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
            .addHeader("Accept", "application/json")

        if (config.apiKey.isNotBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer ${config.apiKey.trim()}")
        }

        return try {
            val response = client.newCall(requestBuilder.build()).execute()
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful && body.isNotBlank()) {
                body
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
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
            val credits = parseCreditsFromResponse(responseBody, defaultCost = 0.0)
            val markdown = formatImageMarkdown(
                prompt = cleanPrompt,
                provider = config.connectorName,
                model = config.imageModel,
                url = mediaUrl,
                b64 = b64,
                creditsCost = credits.cost,
                creditsRemaining = credits.remaining,
                creditsTotal = credits.total,
                accountEmail = credits.account
            )

            MediaGenerationResult(
                isSuccess = true,
                type = MediaConnectorType.IMAGE,
                prompt = cleanPrompt,
                model = config.imageModel,
                provider = config.connectorName,
                mediaUrl = mediaUrl,
                b64Data = b64,
                markdownContent = markdown,
                rawResponse = responseBody,
                creditsCost = credits.cost,
                creditsRemaining = credits.remaining,
                creditsTotal = credits.total,
                accountEmail = credits.account
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
            val credits = parseCreditsFromResponse(responseBody, defaultCost = 0.0)
            val markdown = formatImageMarkdown(
                prompt = cleanPrompt,
                provider = config.connectorName,
                model = config.imageModel,
                url = mediaUrl,
                b64 = b64,
                creditsCost = credits.cost,
                creditsRemaining = credits.remaining,
                creditsTotal = credits.total,
                accountEmail = credits.account
            )

            MediaGenerationResult(
                isSuccess = true,
                type = MediaConnectorType.IMAGE,
                prompt = cleanPrompt,
                model = config.imageModel,
                provider = config.connectorName,
                mediaUrl = mediaUrl,
                b64Data = b64,
                markdownContent = markdown,
                rawResponse = responseBody,
                creditsCost = credits.cost,
                creditsRemaining = credits.remaining,
                creditsTotal = credits.total,
                accountEmail = credits.account
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
            val defaultVidCost = when {
                config.videoModel.contains("quality", ignoreCase = true) -> 100.0
                config.videoModel.contains("flash", ignoreCase = true) || config.videoModel.contains("fast", ignoreCase = true) -> 20.0
                config.videoModel.contains("lite", ignoreCase = true) -> 10.0
                else -> 10.0
            }
            val credits = parseCreditsFromResponse(responseBody, defaultCost = defaultVidCost)
            val markdown = formatVideoMarkdown(
                prompt = cleanPrompt,
                provider = config.connectorName,
                model = config.videoModel,
                videoUrl = videoUrl,
                isImageToVideo = isImageToVideo,
                creditsCost = credits.cost,
                creditsRemaining = credits.remaining,
                creditsTotal = credits.total,
                accountEmail = credits.account
            )

            MediaGenerationResult(
                isSuccess = true,
                type = MediaConnectorType.VIDEO,
                prompt = cleanPrompt,
                model = config.videoModel,
                provider = config.connectorName,
                mediaUrl = videoUrl,
                markdownContent = markdown,
                rawResponse = responseBody,
                creditsCost = credits.cost,
                creditsRemaining = credits.remaining,
                creditsTotal = credits.total,
                accountEmail = credits.account
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

    data class ParsedCreditsInfo(
        val cost: Double = 0.0,
        val remaining: Double = 0.0,
        val total: Double = 0.0,
        val account: String? = null
    )

    fun parseCreditsFromResponse(jsonStr: String, defaultCost: Double = 0.0): ParsedCreditsInfo {
        return try {
            val json = JSONObject(jsonStr)
            val cost = json.optDouble("credits_cost", json.optDouble("cost", defaultCost))
            val remaining = json.optDouble("credits_remaining", json.optDouble("remaining_credits", 0.0))
            val total = json.optDouble("credits_total", json.optDouble("total_credits", 0.0))
            val account = json.optString("account_email", json.optString("account", "")).takeIf { it.isNotBlank() }
            ParsedCreditsInfo(cost, remaining, total, account)
        } catch (e: Exception) {
            ParsedCreditsInfo(cost = defaultCost)
        }
    }

    fun formatImageMarkdown(
        prompt: String,
        provider: String = ConnectorProvider.GOOGLE_FLOW.displayName,
        model: String,
        url: String?,
        b64: String?,
        creditsCost: Double = 0.0,
        creditsRemaining: Double = 0.0,
        creditsTotal: Double = 0.0,
        accountEmail: String? = null
    ): String {
        val src = when {
            !url.isNullOrBlank() -> url
            !b64.isNullOrBlank() -> {
                if (b64.startsWith("data:image")) b64 else "data:image/png;base64,$b64"
            }
            else -> ""
        }

        val costBadge = if (creditsRemaining > 0.0 || creditsCost > 0.0 || !accountEmail.isNullOrBlank()) {
            val costPart = if (creditsCost % 1.0 == 0.0) "${creditsCost.toInt()}" else "$creditsCost"
            val costText = if (creditsCost == 0.0) "0 cr (Gratis)" else "-$costPart cr"
            val remainingPart = if (creditsTotal > 0.0) "${creditsRemaining.toInt()}/${creditsTotal.toInt()} cr" else "${creditsRemaining.toInt()} cr"
            val accountPart = if (!accountEmail.isNullOrBlank()) " • `$accountEmail`" else ""
            "\n> 💎 **Coste:** $costText | **Saldo:** $remainingPart$accountPart\n"
        } else ""

        return """
            ![$prompt]($src)

            🎨 **$provider • Imagen generada con éxito**$costBadge
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
        isImageToVideo: Boolean = false,
        creditsCost: Double = 0.0,
        creditsRemaining: Double = 0.0,
        creditsTotal: Double = 0.0,
        accountEmail: String? = null
    ): String {
        val modeBadge = if (isImageToVideo) "\n- **Modo:** 🎞️ Image-to-Video (Animación desde referencia)" else ""
        val costBadge = if (creditsRemaining > 0.0 || creditsCost > 0.0 || !accountEmail.isNullOrBlank()) {
            val costPart = if (creditsCost % 1.0 == 0.0) "${creditsCost.toInt()}" else "$creditsCost"
            val costText = if (creditsCost == 0.0) "0 cr (Gratis)" else "-$costPart cr"
            val remainingPart = if (creditsTotal > 0.0) "${creditsRemaining.toInt()}/${creditsTotal.toInt()} cr" else "${creditsRemaining.toInt()} cr"
            val accountPart = if (!accountEmail.isNullOrBlank()) " • `$accountEmail`" else ""
            "\n> 💎 **Coste:** $costText | **Saldo:** $remainingPart$accountPart\n"
        } else ""

        return """
            <video src="$videoUrl" controls></video>

            🎬 **$provider • Video generado con éxito**$costBadge
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
                b64 = result.b64Data,
                creditsCost = result.creditsCost,
                creditsRemaining = result.creditsRemaining,
                creditsTotal = result.creditsTotal,
                accountEmail = result.accountEmail
            )
            MediaConnectorType.VIDEO -> formatVideoMarkdown(
                prompt = prompt,
                provider = result.provider,
                model = result.model,
                videoUrl = result.mediaUrl ?: "",
                creditsCost = result.creditsCost,
                creditsRemaining = result.creditsRemaining,
                creditsTotal = result.creditsTotal,
                accountEmail = result.accountEmail
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

        fun fetchCredits(config: MediaConnectorConfig): FlowCreditsResponse {
            return defaultClient.fetchCredits(config)
        }

        fun fetchPricing(config: MediaConnectorConfig): String {
            return defaultClient.fetchPricing(config)
        }
    }
}

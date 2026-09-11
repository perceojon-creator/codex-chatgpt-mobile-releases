package com.codex.chat.core.model

data class Attachment(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val base64Data: String? = null,
    val fileUri: String? = null
) {
    val isImage: Boolean
        get() = mimeType.startsWith("image/")

    val isTextDocument: Boolean
        get() = mimeType.startsWith("text/") || 
                mimeType.contains("json") || 
                mimeType.contains("javascript") || 
                mimeType.contains("python") ||
                mimeType.contains("markdown")
}

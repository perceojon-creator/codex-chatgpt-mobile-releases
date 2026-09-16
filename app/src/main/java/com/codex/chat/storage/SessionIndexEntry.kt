package com.codex.chat.storage

data class SessionIndexEntry(
    val id: String,
    val title: String,
    val timestamp: Long,
    val messageCount: Int = 0,
    val lastModified: Long = System.currentTimeMillis()
)

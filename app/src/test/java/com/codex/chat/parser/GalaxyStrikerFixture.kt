package com.codex.chat.parser

object GalaxyStrikerFixture {
    val RAW_CONTENT: String by lazy {
        GalaxyStrikerFixture::class.java.getResourceAsStream("/galaxy_striker.txt")?.bufferedReader()?.readText() ?: ""
    }
}

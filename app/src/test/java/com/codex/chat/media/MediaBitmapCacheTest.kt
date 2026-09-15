package com.codex.chat.media

import com.codex.chat.core.media.MediaBitmapCache
import org.junit.Test

class MediaBitmapCacheTest {
    @Test
    fun test_key_for() {
        val url = "data:image/jpeg;base64," + "/9j/4AAQSkZJRgABAQ".repeat(1000)
        val k1 = MediaBitmapCache.keyFor(url)
        val k2 = MediaBitmapCache.keyFor(url)
        org.junit.Assert.assertEquals(k1, k2)
        println("Key: $k1")
    }
}
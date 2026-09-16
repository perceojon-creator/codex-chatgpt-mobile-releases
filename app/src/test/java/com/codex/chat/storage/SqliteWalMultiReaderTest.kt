package com.codex.chat.storage

import android.content.Context
import com.codex.chat.core.mcp.server.MemorySqliteStore
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Modifier

class SqliteWalMultiReaderTest {

    @Test
    fun test_read_methods_do_not_synchronize_on_global_lock() {
        val clazz = Class.forName("com.codex.chat.core.mcp.server.MemorySqliteStore")
        // Verificar que los métodos de lectura principales (get, searchFts5, list) existen
        val getMethod = clazz.getMethod("get", String::class.java)
        val searchMethod = clazz.getMethod("searchFts5", String::class.java, Int::class.javaPrimitiveType)
        val listMethod = clazz.getMethod("list", String::class.java, Int::class.javaPrimitiveType)

        assertNotNull(getMethod)
        assertNotNull(searchMethod)
        assertNotNull(listMethod)

        // Verificar que ningún método de lectura tiene el modificador synchronized a nivel de función
        assertFalse(Modifier.isSynchronized(getMethod.modifiers))
        assertFalse(Modifier.isSynchronized(searchMethod.modifiers))
        assertFalse(Modifier.isSynchronized(listMethod.modifiers))
    }
}

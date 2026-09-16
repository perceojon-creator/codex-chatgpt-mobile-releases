package com.codex.chat.storage

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Modifier

class MemorySqliteSingletonTest {

    @Test
    fun test_memory_sqlite_store_constructors_are_private() {
        val clazz = Class.forName("com.codex.chat.core.mcp.server.MemorySqliteStore")
        val constructors = clazz.declaredConstructors
        assertTrue("Debe existir al menos un constructor", constructors.isNotEmpty())
        for (constructor in constructors) {
            if (!constructor.isSynthetic) {
                assertTrue(
                    "El constructor real de MemorySqliteStore debe ser privado para forzar getInstance() y evitar SQLiteDatabaseLockedException",
                    Modifier.isPrivate(constructor.modifiers)
                )
            }
        }
        
        // Verificar que existe el método singleton getInstance(Context)
        val getInstanceMethod = clazz.getMethod("getInstance", Context::class.java)
        assertNotNull("MemorySqliteStore debe exponer el método estático getInstance(Context)", getInstanceMethod)
        assertTrue(Modifier.isStatic(getInstanceMethod.modifiers) || clazz.declaredClasses.any { it.simpleName == "Companion" })
    }
}

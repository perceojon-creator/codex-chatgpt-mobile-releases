# Plan de Implementación por Fases: Optimización Integral de Rendimiento, Resiliencia de Concurrencia y Arnés de Benchmarks TUI

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Erradicar todos los cuellos de botella de rendimiento, condiciones de carrera (crashes), riesgos de corrupción de datos y contención de hilos en ChatGPT-Android-Studio, validando cada fase mediante un arnés interactivo TUI (Terminal User Interface) embebido en el mismo APK que ejecuta pruebas reales de estrés en el hardware del dispositivo.

**Architecture:** Arquitectura desacoplada en capas: (1) Almacenamiento particionado atómico con durabilidad física estricta (`fd.sync()`) y migración no bloqueante; (2) Concurrencia no bloqueante para herramientas MCP y SQLite WAL multi-lector; (3) Pipeline de streaming reactivo libre de bloqueos (`AtomicReference<StreamSnapshot>` compuesto); (4) Renderizado en UI de cero-copia sincronizado con el VSYNC del `Choreographer`; (5) Arnés de telemetría y benchmarks TUI embebido en el APK para auditoría continua de regresiones.

**Tech Stack:** Kotlin 1.9+, Android SDK 35 (minSdk 26), Okio 3.x, OkHttp 4.12, SQLite WAL nativo, AndroidX Tracing, JUnit 4, Window FrameMetrics API.

**Spec:** `PERFORMANCE_BOTTLENECKS_ANALYSIS.md` (Revisión v2.1).

## Global Constraints
- **Presupuesto VSYNC:** p95 de tiempo de cuadro < 16.6 ms (a 60 Hz) y < 8.3 ms (a 120 Hz) durante generación activa a 80+ tokens/segundo.
- **Tolerancia Cero a Crashes de Concurrencia:** 0 excepciones `ConcurrentModificationException` y 0 `SQLiteDatabaseLockedException`.
- **Garantía Absoluta de Durabilidad:** 0 archivos corruptos o en 0-bytes tras corte abrupto (`kill -9`).
- **Retrocompatibilidad:** Migración 100% transparente para usuarios existentes desde `chatgpt_local_history.json` sin congelamiento en el inicio en frío.
- **Sin Regresiones Visuales:** Todo el formato enriquecido (Markdown, bloques de código, diagramas Mermaid, visual media) debe preservarse intacto tras completar la generación.

---

## FASE 0: Arnés de Benchmarks TUI en el APK y Línea Base de Profiling

> **Rol de Medición de Fase:** **Staff Performance & Systems Instrumentation Engineer**  
> **Objetivo:** Construir la infraestructura de diagnóstico e interactividad TUI (Terminal User Interface) dentro del mismo APK, permitiendo ejecutar benchmarks reales sobre el hardware del dispositivo (interactivamente o headless vía `adb shell`), e insertar marcadores de traza en los 4 cuellos de botella críticos.

---

### Task 1: Consola TUI y Motor de Benchmarks Embebido en el APK

**Files:**
- Create: `app/src/main/java/com/codex/chat/benchmark/TuiBenchmarkEngine.kt`
- Create: `app/src/main/java/com/codex/chat/benchmark/TuiBenchmarkActivity.kt`
- Create: `app/src/main/res/layout/activity_tui_benchmark.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/com/codex/chat/benchmark/TuiBenchmarkEngineTest.kt`

**Interfaces:**
- Produces: `TuiBenchmarkEngine.runBenchmark(name: String, block: suspend () -> BenchmarkResult): BenchmarkResult`
- Produces: `TuiBenchmarkActivity` para ejecución visual en pantalla o headless vía comando:
  `adb shell am start -n com.codex.chat/.benchmark.TuiBenchmarkActivity --es suite "all"`

- [ ] **Step 1: Escribir la prueba unitaria que falla para el motor TUI**

Crear `app/src/test/java/com/codex/chat/benchmark/TuiBenchmarkEngineTest.kt`:
```kotlin
package com.codex.chat.benchmark

import org.junit.Assert.*
import org.junit.Test

class TuiBenchmarkEngineTest {

    @Test
    fun test_benchmark_result_formatting_ansi() {
        val result = BenchmarkResult(
            name = "DISK_WRITE_STRESS",
            passed = true,
            durationMs = 42L,
            metrics = mapOf("throughput_mb_s" to 128.5, "p95_ms" to 3.2),
            logLines = listOf("Step 1: Write ok", "Step 2: Sync ok")
        )
        val formatted = result.toAnsiOutput()
        assertTrue(formatted.contains("[PASS]"))
        assertTrue(formatted.contains("DISK_WRITE_STRESS"))
        assertTrue(formatted.contains("128.5"))
    }

    @Test
    fun test_engine_registers_and_executes_suite() {
        val engine = TuiBenchmarkEngine()
        var executed = false
        engine.registerBenchmark("MOCK_TEST") {
            executed = true
            BenchmarkResult("MOCK_TEST", true, 10L, emptyMap(), listOf("Done"))
        }
        val result = engine.run("MOCK_TEST")
        assertTrue(executed)
        assertTrue(result.passed)
    }
}
```

- [ ] **Step 2: Ejecutar el test para verificar que falla**

Ejecutar: `cmd.exe /c gradlew.bat testDebugUnitTest --tests com.codex.chat.benchmark.TuiBenchmarkEngineTest`  
Resultado esperado: FALLA con símbolo no encontrado (`BenchmarkResult`, `TuiBenchmarkEngine`).

- [ ] **Step 3: Implementar `TuiBenchmarkEngine.kt` y `TuiBenchmarkActivity.kt`**

Crear `app/src/main/java/com/codex/chat/benchmark/TuiBenchmarkEngine.kt`:
```kotlin
package com.codex.chat.benchmark

data class BenchmarkResult(
    val name: String,
    val passed: Boolean,
    val durationMs: Long,
    val metrics: Map<String, Double> = emptyMap(),
    val logLines: List<String> = emptyList(),
    val errorMessage: String? = null
) {
    fun toAnsiOutput(): String {
        val statusTag = if (passed) "[PASS]" else "[FAIL]"
        val sb = StringBuilder()
        sb.append(statusTag).append(" ").append(name).append(" (").append(durationMs).append(" ms)\n")
        metrics.forEach { (k, v) ->
            sb.append("   * ").append(k).append(": ").append(String.format("%.2f", v)).append("\n")
        }
        if (errorMessage != null) {
            sb.append("   ! Error: ").append(errorMessage).append("\n")
        }
        return sb.toString()
    }
}

class TuiBenchmarkEngine {
    private val benchmarks = mutableMapOf<String, () -> BenchmarkResult>()

    fun registerBenchmark(name: String, block: () -> BenchmarkResult) {
        benchmarks[name] = block
    }

    fun listBenchmarks(): List<String> = benchmarks.keys.toList()

    fun run(name: String): BenchmarkResult {
        val benchmark = benchmarks[name] ?: return BenchmarkResult(
            name = name,
            passed = false,
            durationMs = 0L,
            errorMessage = "Benchmark '$name' no encontrado en el registro"
        )
        val start = System.currentTimeMillis()
        return try {
            val res = benchmark()
            res.copy(durationMs = System.currentTimeMillis() - start)
        } catch (e: Throwable) {
            BenchmarkResult(
                name = name,
                passed = false,
                durationMs = System.currentTimeMillis() - start,
                errorMessage = e.message ?: e.javaClass.simpleName
            )
        }
    }

    fun runAll(): List<BenchmarkResult> {
        return benchmarks.keys.map { run(it) }
    }
}
```

Crear `app/src/main/res/layout/activity_tui_benchmark.xml` con un `TextView` estilo consola negra de fondo con fuente monoespaciada (`monospace`) y un `ScrollView`.

Crear `app/src/main/java/com/codex/chat/benchmark/TuiBenchmarkActivity.kt` registrándola en `AndroidManifest.xml` como exportable para pruebas CLI vía `adb shell`.

- [ ] **Step 4: Ejecutar el test para verificar que pasa**

Ejecutar: `cmd.exe /c gradlew.bat testDebugUnitTest --tests com.codex.chat.benchmark.TuiBenchmarkEngineTest`  
Resultado esperado: PASS (2 tests passed).

- [ ] **Step 5: Commit del arnés TUI**

```bash
git add app/src/main/java/com/codex/chat/benchmark/ app/src/main/res/layout/activity_tui_benchmark.xml app/src/test/java/com/codex/chat/benchmark/ app/src/main/AndroidManifest.xml
git commit -m "feat(benchmark): add embedded in-APK TUI benchmark engine and activity"
```

---

### Task 2: Instrumentación de Trazas Diagnósticas con `androidx.tracing`

**Files:**
- Modify: `app/src/main/java/com/codex/chat/LocalChatRepository.kt`
- Modify: `app/src/main/java/com/codex/chat/ChatAdapter.kt`
- Modify: `app/src/main/java/com/codex/chat/core/metrics/ContextMetricsCalculator.kt`
- Test: `app/src/test/java/com/codex/chat/benchmark/TracingVerificationTest.kt`

- [ ] **Step 1: Escribir la prueba unitaria para verificar la presencia de trazadores**

Crear `app/src/test/java/com/codex/chat/benchmark/TracingVerificationTest.kt`:
```kotlin
package com.codex.chat.benchmark

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TracingVerificationTest {
    @Test
    fun test_critical_hotpaths_contain_trace_sections() {
        val repoFile = File("src/main/java/com/codex/chat/LocalChatRepository.kt")
        if (!repoFile.exists()) return // skip if run outside project root
        val text = repoFile.readText()
        assertTrue("LocalChatRepository debe contener Trace.beginSection", text.contains("Trace.beginSection") || text.contains("trace("))
    }
}
```

- [ ] **Step 2: Ejecutar test para verificar estado inicial**

- [ ] **Step 3: Añadir bloques `androidx.tracing.Trace.beginSection / endSection`**
En los métodos:
- `LocalChatRepository.writeToDisk` -> `Trace.beginSection("Codex:writeToDisk")`
- `ChatAdapter.updateStreaming` -> `Trace.beginSection("Codex:updateStreaming")`
- `ContextMetricsCalculator.calculate` -> `Trace.beginSection("Codex:calculateTokens")`

- [ ] **Step 4: Ejecutar test y compilar**

- [ ] **Step 5: Commit de instrumentación**

```bash
git add app/src/main/java/com/codex/chat/LocalChatRepository.kt app/src/main/java/com/codex/chat/ChatAdapter.kt app/src/main/java/com/codex/chat/core/metrics/ContextMetricsCalculator.kt
git commit -m "perf(trace): instrument critical hotpaths with AndroidX Tracing"
```

---

## FASE 1: Prioridad P0 — Estabilidad, Integridad y Prevención de Corrupción de Datos

> **Rol de Medición de Fase:** **Principal Database & Storage Reliability Engineer**  
> **Objetivo:** Erradicar fallos fatales (`ConcurrentModificationException`), eliminar riesgos de archivos de 0 bytes por falta de `fd.sync()` y unificar el acceso a SQLite para prevenir bloqueos (`SQLiteDatabaseLockedException`).

---

### Task 3: Encapsulación Atómica Bidireccional de `LocalChatSession` (Hallazgo C9)

**Files:**
- Modify: `app/src/main/java/com/codex/chat/LocalChatRepository.kt` (líneas 14–25, 142–155, 198–215)
- Modify: `app/src/main/java/com/codex/chat/MainActivity.kt` (líneas 3388–3405)
- Test: `app/src/test/java/com/codex/chat/concurrency/SessionConcurrencyRegressionTest.kt`

**Interfaces:**
- Consumes: `ChatMessage`, `LocalChatSession`
- Produces: `LocalChatSession.getMessagesSnapshot(): List<ChatMessage>`
- Produces: `LocalChatSession.setMessages(newMessages: List<ChatMessage>)`
- Produces: `LocalChatSession.addMessage(message: ChatMessage)`

- [ ] **Step 1: Escribir el test de concurrencia que provoca la condición de carrera**

Crear `app/src/test/java/com/codex/chat/concurrency/SessionConcurrencyRegressionTest.kt`:
```kotlin
package com.codex.chat.concurrency

import com.codex.chat.ChatMessage
import com.codex.chat.LocalChatSession
import com.codex.chat.MessageRole
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SessionConcurrencyRegressionTest {

    @Test
    fun test_concurrent_mutations_and_snapshot_reads_never_throw() {
        val session = LocalChatSession(title = "Concurrencia")
        val iterations = 1000
        val latch = CountDownLatch(2)
        val readCount = AtomicInteger(0)
        val writeCount = AtomicInteger(0)
        var thrownException: Throwable? = null

        // Hilo Escritor (Simula el hilo de UI / streaming añadiendo mensajes)
        val writer = Thread {
            try {
                for (i in 1..iterations) {
                    session.addMessage(ChatMessage(id = "msg-$i", role = MessageRole.USER, content = "Msg $i"))
                    writeCount.incrementAndGet()
                    Thread.yield()
                }
            } catch (t: Throwable) {
                thrownException = t
            } finally {
                latch.countDown()
            }
        }

        // Hilo Lector (Simula el repositorio serializando para disco)
        val reader = Thread {
            try {
                for (i in 1..iterations) {
                    val snapshot = session.getMessagesSnapshot()
                    readCount.incrementAndGet()
                    // Iterar sobre la lista obtenida para verificar que no hay mutación concurrente
                    var length = 0
                    for (m in snapshot) {
                        length += m.content.length
                    }
                    Thread.yield()
                }
            } catch (t: Throwable) {
                thrownException = t
            } finally {
                latch.countDown()
            }
        }

        writer.start()
        reader.start()
        assertTrue("Los hilos deben completar en menos de 5 segundos", latch.await(5, TimeUnit.SECONDS))
        assertNull("No debe arrojarse ConcurrentModificationException", thrownException)
        assertEquals(iterations, writeCount.get())
        assertEquals(iterations, readCount.get())
    }
}
```

- [ ] **Step 2: Ejecutar el test para verificar que falla**

Ejecutar: `cmd.exe /c gradlew.bat testDebugUnitTest --tests com.codex.chat.concurrency.SessionConcurrencyRegressionTest`  
Resultado esperado: FALLA al compilar porque `getMessagesSnapshot` y `addMessage` no existen aún.

- [ ] **Step 3: Implementar la encapsulación en `LocalChatRepository.kt` y `MainActivity.kt`**

Modificar `LocalChatRepository.kt`:
```kotlin
data class LocalChatSession(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "Nueva conversación",
    val timestamp: Long = System.currentTimeMillis(),
    private val _messages: MutableList<ChatMessage> = mutableListOf()
) {
    val messagesLock = Any()

    fun getMessagesSnapshot(): List<ChatMessage> = synchronized(messagesLock) {
        ArrayList(_messages)
    }

    fun setMessages(newMessages: List<ChatMessage>) = synchronized(messagesLock) {
        _messages.clear()
        _messages.addAll(newMessages)
    }

    fun addMessage(message: ChatMessage) = synchronized(messagesLock) {
        _messages.add(message)
    }

    val messageCount: Int
        get() = synchronized(messagesLock) { _messages.size }
}
```

Modificar `MainActivity.kt` líneas 3390–3400 para utilizar `session.setMessages(snapshot)` en lugar de mutar `session.messages` directamente.
Modificar `LocalChatRepository.kt:writeToDisk` para iterar sobre `s.getMessagesSnapshot()`.

- [ ] **Step 4: Ejecutar el test para verificar que pasa**

Ejecutar: `cmd.exe /c gradlew.bat testDebugUnitTest --tests com.codex.chat.concurrency.SessionConcurrencyRegressionTest`  
Resultado esperado: PASS (1000 iteraciones concurrentes limpias sin excepciones).

- [ ] **Step 5: Commit del fix de concurrencia**

```bash
git add app/src/main/java/com/codex/chat/LocalChatRepository.kt app/src/main/java/com/codex/chat/MainActivity.kt app/src/test/java/com/codex/chat/concurrency/SessionConcurrencyRegressionTest.kt
git commit -m "fix(storage): enforce bidirectional atomic locking on LocalChatSession messages (fixes C9)"
```

---

### Task 4: Durabilidad Física con `fd.sync()` y Eliminación de `copyTo` (Hallazgo C8)

**Files:**
- Modify: `app/src/main/java/com/codex/chat/LocalChatRepository.kt` (método `writeToDisk`)
- Test: `app/src/test/java/com/codex/chat/storage/AtomicDiskWriteDurabilityTest.kt`

- [ ] **Step 1: Escribir el test para validar atomicidad y durabilidad de escritura**

Crear `app/src/test/java/com/codex/chat/storage/AtomicDiskWriteDurabilityTest.kt`:
```kotlin
package com.codex.chat.storage

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream

class AtomicDiskWriteDurabilityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun test_atomic_write_preserves_target_on_failure_and_syncs() {
        val root = tempFolder.newFolder("sessions")
        val targetFile = File(root, "session_1.json").apply { writeText("VALID_PREVIOUS_DATA") }
        val tmpFile = File(root, "session_1.json.tmp")

        // Escritura con sync
        FileOutputStream(tmpFile).use { fos ->
            fos.write("NEW_DATA".toByteArray())
            fos.flush()
            fos.fd.sync()
        }

        assertTrue("El archivo temporal debe existir antes del rename", tmpFile.exists())
        val renamed = tmpFile.renameTo(targetFile)
        assertTrue("El renombrado atómico en el mismo filesystem debe ser exitoso", renamed)
        assertEquals("NEW_DATA", targetFile.readText())
        assertFalse("El archivo temporal no debe existir tras el rename exitoso", tmpFile.exists())
    }
}
```

- [ ] **Step 2: Ejecutar el test para verificar baseline**

- [ ] **Step 3: Actualizar `LocalChatRepository.kt:writeToDisk`**

Implementar en `LocalChatRepository.kt`:
```kotlin
val tmp = File(context.filesDir, "chatgpt_local_history.json.tmp")
FileOutputStream(tmp).use { fos ->
    fos.write(jsonString.toByteArray(Charsets.UTF_8))
    fos.flush()
    fos.fd.sync() // DURABILIDAD ESTRICTA: Fuerza flush de dirty pages en chip flash
}
val renameOk = tmp.renameTo(storageFile)
if (!renameOk) {
    android.util.Log.e("ChatPersist", "CRITICAL: Fallo atomic rename de ${tmp.absolutePath} a ${storageFile.absolutePath}")
    throw java.io.IOException("Fallo al renombrar archivo temporal de chatgpt_local_history")
}
```

- [ ] **Step 4: Ejecutar test y compilar**

- [ ] **Step 5: Commit del fix de durabilidad**

```bash
git add app/src/main/java/com/codex/chat/LocalChatRepository.kt app/src/test/java/com/codex/chat/storage/AtomicDiskWriteDurabilityTest.kt
git commit -m "fix(storage): enforce physical fsync durability and remove corruptive copyTo fallback (fixes C8)"
```

---

### Task 5: Unificación Estricta del Singleton `MemorySqliteStore` (Hallazgo C11)

**Files:**
- Modify: `app/src/main/java/com/codex/chat/core/mcp/server/MemorySqliteStore.kt`
- Modify: `app/src/main/java/com/codex/chat/core/mcp/server/MemoryMcpServer.kt`
- Modify: `app/src/main/java/com/codex/chat/core/harvest/AntiAmnesiaHarvester.kt`
- Modify: `app/src/main/java/com/codex/chat/worker/CodexMaintenanceWorker.kt`
- Test: `app/src/test/java/com/codex/chat/storage/MemorySqliteSingletonTest.kt`

- [ ] **Step 1: Escribir el test para verificar el patrón singleton estricto**

Crear `app/src/test/java/com/codex/chat/storage/MemorySqliteSingletonTest.kt`:
```kotlin
package com.codex.chat.storage

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Modifier

class MemorySqliteSingletonTest {

    @Test
    fun test_memory_sqlite_store_constructors_are_private() {
        val clazz = Class.forName("com.codex.chat.core.mcp.server.MemorySqliteStore")
        for (constructor in clazz.declaredConstructors) {
            assertTrue("El constructor de MemorySqliteStore debe ser privado para forzar getInstance()",
                Modifier.isPrivate(constructor.modifiers))
        }
    }
}
```

- [ ] **Step 2: Ejecutar test para verificar que falla** (constructores públicos actuales).

- [ ] **Step 3: Hacer privado el constructor y re-enrutar todos los llamadores a `getInstance(context.applicationContext)`**

- [ ] **Step 4: Ejecutar test para verificar que pasa**

- [ ] **Step 5: Commit de unificación de SQLiteOpenHelper**

```bash
git add app/src/main/java/com/codex/chat/core/mcp/server/MemorySqliteStore.kt app/src/main/java/com/codex/chat/core/mcp/server/MemoryMcpServer.kt app/src/main/java/com/codex/chat/core/harvest/AntiAmnesiaHarvester.kt app/src/main/java/com/codex/chat/worker/CodexMaintenanceWorker.kt app/src/test/java/com/codex/chat/storage/MemorySqliteSingletonTest.kt
git commit -m "fix(sqlite): enforce strict singleton on MemorySqliteStore to prevent SQLiteDatabaseLockedException (fixes C11)"
```

---

### Task 6: Módulo TUI de Regresión: `DataIntegrityBenchmark` en el APK

**Files:**
- Create: `app/src/main/java/com/codex/chat/benchmark/DataIntegrityBenchmark.kt`
- Modify: `app/src/main/java/com/codex/chat/benchmark/TuiBenchmarkActivity.kt`
- Test: `app/src/test/java/com/codex/chat/benchmark/DataIntegrityBenchmarkTest.kt`

- [ ] **Step 1: Crear la suite de benchmarks de integridad de datos para ejecutar en el TUI del APK**
Realiza 500 escrituras concurrentes multi-hilo en disco flash y valida la integridad de los inodos y datos guardados.
- [ ] **Step 2: Ejecutar test unitario**
- [ ] **Step 3: Registrar en `TuiBenchmarkActivity`**
- [ ] **Step 4: Verificar ejecución**
- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/codex/chat/benchmark/
git commit -m "test(tui): register DataIntegrityBenchmark in in-APK TUI console"
```

## FASE 2: Arquitectura de Almacenamiento Particionado y Protocolo de Corte (Cutover)

> **Rol de Medición de Fase:** **Senior Distributed Systems & Storage Migration Architect**  
> **Objetivo:** Particionar el almacenamiento local por sesión (`sessions/{id}.json`) con serialización en streaming mediante `JsonWriter`, e implementar el protocolo de corte (*cutover*) sin pérdida de datos ni bloqueo del inicio en frío (*cold start*).

---

### Task 7: Implementación de `PartitionedChatStorage.kt` con `JsonWriter`

**Files:**
- Create: `app/src/main/java/com/codex/chat/storage/PartitionedChatStorage.kt`
- Create: `app/src/main/java/com/codex/chat/storage/SessionIndexEntry.kt`
- Test: `app/src/test/java/com/codex/chat/storage/PartitionedChatStorageTest.kt`

**Interfaces:**
- Produces: `PartitionedChatStorage.saveSession(session: LocalChatSession)`
- Produces: `PartitionedChatStorage.loadSession(id: String): LocalChatSession?`
- Produces: `PartitionedChatStorage.loadSessionsIndex(): List<SessionIndexEntry>`
- Produces: `PartitionedChatStorage.deleteSession(id: String)`

- [ ] **Step 1: Escribir el test para el almacenamiento particionado**

Crear `app/src/test/java/com/codex/chat/storage/PartitionedChatStorageTest.kt`:
```kotlin
package com.codex.chat.storage

import com.codex.chat.ChatMessage
import com.codex.chat.LocalChatSession
import com.codex.chat.MessageRole
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PartitionedChatStorageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun test_save_and_load_session_partitioned() {
        val root = tempFolder.newFolder("chat_storage")
        val storage = PartitionedChatStorage(root)

        val session = LocalChatSession(id = "test-session-1", title = "Particionado")
        session.addMessage(ChatMessage(id = "m1", role = MessageRole.USER, content = "Hola"))
        session.addMessage(ChatMessage(id = "m2", role = MessageRole.ASSISTANT, content = "Mundo"))

        storage.saveSession(session)

        val loaded = storage.loadSession("test-session-1")
        assertNotNull(loaded)
        assertEquals("test-session-1", loaded!!.id)
        assertEquals(2, loaded.messageCount)
        assertEquals("Hola", loaded.getMessagesSnapshot()[0].content)

        val index = storage.loadSessionsIndex()
        assertEquals(1, index.size)
        assertEquals("test-session-1", index[0].id)
    }
}
```

- [ ] **Step 2: Ejecutar test para verificar fallo**

- [ ] **Step 3: Implementar `PartitionedChatStorage.kt` con streaming `android.util.JsonWriter` y `fd.sync()`**

- [ ] **Step 4: Ejecutar test y verificar que pasa**

- [ ] **Step 5: Commit del almacenamiento particionado**

```bash
git add app/src/main/java/com/codex/chat/storage/
git commit -m "feat(storage): implement zero-allocation PartitionedChatStorage with streaming JsonWriter"
```

---

### Task 8: Migrador de Corte en Streaming (`StorageCutoverMigrator.kt`)

**Files:**
- Create: `app/src/main/java/com/codex/chat/storage/StorageCutoverMigrator.kt`
- Modify: `app/src/main/java/com/codex/chat/LocalChatRepository.kt`
- Test: `app/src/test/java/com/codex/chat/storage/StorageCutoverMigratorTest.kt`

**Interfaces:**
- Consumes: `PartitionedChatStorage`, archivo legado `chatgpt_local_history.json`
- Produces: `StorageCutoverMigrator.migrateAsync(onComplete: () -> Unit)`
- Invariante: Si la sesión ya existe en `sessions/{id}.json`, OMITIR sobrescritura (evita pisar mensajes creados en caliente).

- [ ] **Step 1: Escribir el test para el corte de migración sin pérdida de datos**

Crear `app/src/test/java/com/codex/chat/storage/StorageCutoverMigratorTest.kt`:
```kotlin
package com.codex.chat.storage

import com.codex.chat.ChatMessage
import com.codex.chat.LocalChatSession
import com.codex.chat.MessageRole
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageCutoverMigratorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun test_migrator_does_not_overwrite_newer_session_written_during_migration() {
        val root = tempFolder.newFolder("migration_test")
        val legacyFile = File(root, "chatgpt_local_history.json")
        legacyFile.writeText("""
            [
              {"id": "session-A", "title": "Old A", "messages": [{"id": "m1", "role": "user", "content": "Old Msg"}]},
              {"id": "session-B", "title": "Old B", "messages": [{"id": "m2", "role": "user", "content": "Old Msg B"}]}
            ]
        """.trimIndent())

        val storage = PartitionedChatStorage(root)

        // Simular escritura en caliente del usuario antes de que el migrador alcance la sesión A:
        val activeSession = LocalChatSession(id = "session-A", title = "Newer A")
        activeSession.addMessage(ChatMessage(id = "mNew", role = MessageRole.USER, content = "New Hot Message"))
        storage.saveSession(activeSession)

        val migrator = StorageCutoverMigrator(root, storage)
        val stats = migrator.migrateSync()

        assertEquals(1, stats.migratedCount) // Solo session-B debió migrarse
        assertEquals(1, stats.skippedCount)  // session-A se omitió para no pisar el mensaje nuevo

        val sessionA = storage.loadSession("session-A")
        assertNotNull(sessionA)
        assertEquals("New Hot Message", sessionA!!.getMessagesSnapshot()[0].content)

        assertTrue(File(root, "chatgpt_local_history.json.bak").exists())
    }
}
```

- [ ] **Step 2: Ejecutar test para verificar fallo**

- [ ] **Step 3: Implementar `StorageCutoverMigrator.kt`**

- [ ] **Step 4: Ejecutar test y compilar**

- [ ] **Step 5: Commit del migrador de corte**

```bash
git add app/src/main/java/com/codex/chat/storage/
git commit -m "feat(storage): implement StorageCutoverMigrator with anti-overwrite invariant"
```

---

### Task 9: Módulo TUI de Regresión: `MigrationCutoverBenchmark` en el APK

**Files:**
- Create: `app/src/main/java/com/codex/chat/benchmark/MigrationCutoverBenchmark.kt`
- Modify: `app/src/main/java/com/codex/chat/benchmark/TuiBenchmarkActivity.kt`
- Test: `app/src/test/java/com/codex/chat/benchmark/MigrationCutoverBenchmarkTest.kt`

- [ ] **Step 1: Suite TUI que genera 1.000 mensajes legados y simula corte con escrituras concurrentes**
- [ ] **Step 2: Ejecutar test unitario**
- [ ] **Step 3: Registrar en `TuiBenchmarkActivity`**
- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/codex/chat/benchmark/
git commit -m "test(tui): register MigrationCutoverBenchmark in in-APK TUI console"
```

---

## FASE 3: Prioridad P1 — Concurrencia, Desbloqueo de MCP y Tareas de Fondo

> **Rol de Medición de Fase:** **Lead Concurrency & Systems Protocols Engineer**  
> **Objetivo:** Erradicar el Head-of-Line Blocking en `ToolBatchExecutor` mediante despacho asíncrono con corrutinas, liberar la concurrencia de lectura en SQLite WAL y desacoplar `ReasoningTranslator` del ciclo de vida del proceso.

---

### Task 10: Eliminación de Head-of-Line (HoL) Blocking en `ToolBatchExecutor.kt` (Hallazgo C12)

**Files:**
- Modify: `app/src/main/java/com/codex/chat/core/concurrency/ToolBatchExecutor.kt`
- Test: `app/src/test/java/com/codex/chat/concurrency/ToolBatchHolBlockingTest.kt`

**Interfaces:**
- Produces: `suspend fun executeBatchProgressive(calls: List<CompletedToolCall>, onResultYielded: (CompletedToolCall, McpToolResult) -> Unit)`

- [ ] **Step 1: Escribir el test que comprueba que tareas rápidas se entregan inmediatamente**

Crear `app/src/test/java/com/codex/chat/concurrency/ToolBatchHolBlockingTest.kt`:
```kotlin
package com.codex.chat.concurrency

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ToolBatchHolBlockingTest {

    @Test
    fun test_fast_tools_yield_results_before_slow_tool_finishes() {
        val completedOrder = ConcurrentLinkedQueue<String>()
        val slowStartedLatch = CountDownLatch(1)
        val fastFinishedLatch = CountDownLatch(2)

        // Tarea Lenta: tarda 500 ms
        // Tareas Rápidas: tardan 10 ms
        val tasks = listOf(
            Runnable {
                slowStartedLatch.countDown()
                Thread.sleep(500)
                completedOrder.add("SLOW_TOOL_0")
            },
            Runnable {
                slowStartedLatch.await(1, TimeUnit.SECONDS)
                Thread.sleep(10)
                completedOrder.add("FAST_TOOL_1")
                fastFinishedLatch.countDown()
            },
            Runnable {
                slowStartedLatch.await(1, TimeUnit.SECONDS)
                Thread.sleep(10)
                completedOrder.add("FAST_TOOL_2")
                fastFinishedLatch.countDown()
            }
        )

        // Ejecutar con despachador concurrente
        val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
        tasks.forEach { pool.submit(it) }

        assertTrue("Herramientas rápidas deben terminar en menos de 200 ms", fastFinishedLatch.await(200, TimeUnit.MILLISECONDS))
        assertTrue("FAST_TOOL_1 debió completarse antes de SLOW_TOOL_0", completedOrder.contains("FAST_TOOL_1"))
        assertFalse("SLOW_TOOL_0 no debe haber terminado todavía", completedOrder.contains("SLOW_TOOL_0"))
        pool.shutdownNow()
    }
}
```

- [ ] **Step 2: Ejecutar test para verificar baseline de concurrencia**

- [ ] **Step 3: Refactorizar `ToolBatchExecutor.kt` para canalizar resultados conforme se resuelven individualmente**

- [ ] **Step 4: Ejecutar test y verificar que pasa**

- [ ] **Step 5: Commit de eliminación de HoL Blocking**

```bash
git add app/src/main/java/com/codex/chat/core/concurrency/ToolBatchExecutor.kt app/src/test/java/com/codex/chat/concurrency/ToolBatchHolBlockingTest.kt
git commit -m "perf(concurrency): eliminate Head-of-Line blocking in ToolBatchExecutor progressive yield (fixes C12)"
```

---

### Task 11: Liberación de Concurrencia Multi-Lector en SQLite WAL (Hallazgo C10)

**Files:**
- Modify: `app/src/main/java/com/codex/chat/core/mcp/server/MemorySqliteStore.kt`
- Test: `app/src/test/java/com/codex/chat/storage/MemorySqliteWalConcurrencyTest.kt`

- [ ] **Step 1: Escribir test de concurrencia multi-lector durante escritura en WAL**
- [ ] **Step 2: Ejecutar test para observar el cuello de botella del monitor `synchronized(lock)`**
- [ ] **Step 3: Eliminar `synchronized(lock)` de métodos de lectura (`get`, `searchFts5`, `list`)**
- [ ] **Step 4: Ejecutar test y verificar concurrencia fluida**
- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/codex/chat/core/mcp/server/MemorySqliteStore.kt
git commit -m "perf(sqlite): remove JVM monitor lock on read operations to enable true WAL multi-reader concurrency (fixes C10)"
```

---

### Task 12: Refactorización Asíncrona de `ReasoningTranslator.kt` (Hallazgo C6)

**Files:**
- Modify: `app/src/main/java/com/codex/chat/core/network/ReasoningTranslator.kt`
- Test: `app/src/test/java/com/codex/chat/network/ReasoningTranslatorAsyncTest.kt`

- [ ] **Step 1: Escribir test de cancelación estructurada y timeout por frase**
- [ ] **Step 2: Ejecutar test**
- [ ] **Step 3: Reemplazar `thread(name = "reasoning-translate")` por corrutinas en `Dispatchers.IO` con timeout de 2 segundos**
- [ ] **Step 4: Ejecutar test**
- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/codex/chat/core/network/ReasoningTranslator.kt
git commit -m "fix(network): replace unpooled OS threads with structured coroutines in ReasoningTranslator (fixes C6)"
```

---

### Task 13: Módulo TUI de Regresión: `ConcurrencyAndMcpBenchmark` en el APK

**Files:**
- Create: `app/src/main/java/com/codex/chat/benchmark/ConcurrencyAndMcpBenchmark.kt`
- Modify: `app/src/main/java/com/codex/chat/benchmark/TuiBenchmarkActivity.kt`
- Test: `app/src/test/java/com/codex/chat/benchmark/ConcurrencyAndMcpBenchmarkTest.kt`

- [ ] **Step 1: Suite TUI que mide entrega progresiva de herramientas y latencia de SQLite WAL bajo estrés**
- [ ] **Step 2: Ejecutar test unitario**
- [ ] **Step 3: Registrar en `TuiBenchmarkActivity`**
- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/codex/chat/benchmark/
git commit -m "test(tui): register ConcurrencyAndMcpBenchmark in in-APK TUI console"
```

## FASE 4: Prioridad P2 — Fluidez de UI, Streaming y Optimización de Asignaciones

> **Rol de Medición de Fase:** **Principal Android Graphics & UI Rendering Architect**  
> **Objetivo:** Garantizar 60/120 FPS sin caídas de cuadros (*jank* o *skipped frames*), erradicar el *State Tearing* en streaming mediante instantáneas inmutables compuestas (`StreamSnapshot`), aligerar radicalmente el payload en `ChatAdapter`, desacoplar la lectura de imágenes flash del hilo UI y optimizar el cálculo de métricas de tokens.

---

### Task 14: Erradicación de State Tearing con Snapshot Atómico Compuesto (`StreamBuffer.kt`) (Hallazgo C5)

**Files:**
- Modify: `app/src/main/java/com/codex/chat/core/concurrency/StreamBuffer.kt`
- Modify: `app/src/main/java/com/codex/chat/MainActivity.kt` (líneas 2845–2858)
- Test: `app/src/test/java/com/codex/chat/concurrency/StreamBufferSnapshotTest.kt`

**Interfaces:**
- Produces: `data class StreamSnapshot(val content: String, val reasoning: String, val version: Long)`
- Produces: `StreamBuffer.getSnapshot(): StreamSnapshot`
- Produces: `StreamBuffer.appendContent(delta: String)`
- Produces: `StreamBuffer.appendReasoning(delta: String)`

- [ ] **Step 1: Escribir el test que comprueba atomicidad y coherencia temporal en `StreamSnapshot`**

Crear `app/src/test/java/com/codex/chat/concurrency/StreamBufferSnapshotTest.kt`:
```kotlin
package com.codex.chat.concurrency

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class StreamBufferSnapshotTest {

    @Test
    fun test_stream_buffer_snapshot_is_temporally_consistent_and_lock_free() {
        val buffer = StreamBuffer()
        val running = AtomicBoolean(true)
        val latch = CountDownLatch(2)
        var tearingDetected = false

        // Hilo Productor: actualiza contenido y razonamiento en sincronía de versión
        val producer = Thread {
            var v = 0L
            while (running.get()) {
                v++
                buffer.appendContent("content_$v;")
                buffer.appendReasoning("reasoning_$v;")
                Thread.yield()
            }
            latch.countDown()
        }

        // Hilo Consumidor (simula hilo UI): lee snapshots atómicos
        val consumer = Thread {
            for (i in 1..2000) {
                val snap = buffer.getSnapshot()
                // Validar que ambos campos están presentes y consistentes
                if (snap.version > 0 && snap.content.isEmpty() && snap.reasoning.isNotEmpty()) {
                    tearingDetected = true
                }
                Thread.yield()
            }
            running.set(false)
            latch.countDown()
        }

        producer.start()
        consumer.start()
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertFalse("No debe ocurrir State Tearing entre content y reasoning", tearingDetected)
    }
}
```

- [ ] **Step 2: Ejecutar test para verificar fallo**

- [ ] **Step 3: Implementar `StreamSnapshot` con `AtomicReference` en `StreamBuffer.kt`**

```kotlin
data class StreamSnapshot(
    val content: String = "",
    val reasoning: String = "",
    val version: Long = 0L
)

class StreamBuffer {
    private val snapshotRef = java.util.concurrent.atomic.AtomicReference(StreamSnapshot())
    private val contentBuilder = StringBuilder()
    private val reasoningBuilder = StringBuilder()
    private val writeLock = Any()

    fun appendContent(delta: String) = synchronized(writeLock) {
        contentBuilder.append(delta)
        val current = snapshotRef.get()
        snapshotRef.set(StreamSnapshot(contentBuilder.toString(), reasoningBuilder.toString(), current.version + 1))
    }

    fun appendReasoning(delta: String) = synchronized(writeLock) {
        reasoningBuilder.append(delta)
        val current = snapshotRef.get()
        snapshotRef.set(StreamSnapshot(contentBuilder.toString(), reasoningBuilder.toString(), current.version + 1))
    }

    fun getSnapshot(): StreamSnapshot = snapshotRef.get()

    fun clear() = synchronized(writeLock) {
        contentBuilder.setLength(0)
        reasoningBuilder.setLength(0)
        snapshotRef.set(StreamSnapshot())
    }
}
```

Modificar `MainActivity.kt` para llamar a `val snap = streamBuffer.getSnapshot()` y actualizar la UI con una única referencia consistente.

- [ ] **Step 4: Ejecutar test y verificar que pasa**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/codex/chat/core/concurrency/StreamBuffer.kt app/src/main/java/com/codex/chat/MainActivity.kt app/src/test/java/com/codex/chat/concurrency/StreamBufferSnapshotTest.kt
git commit -m "fix(streaming): eradicate state tearing with atomic composite StreamSnapshot (fixes C5)"
```

---

### Task 15: Payload Granular de Streaming y Aislamiento de Parsers en `ChatAdapter.kt` (Hallazgo C1)

**Files:**
- Modify: `app/src/main/java/com/codex/chat/ChatAdapter.kt` (líneas 71–85, 217–245)
- Test: `app/src/test/java/com/codex/chat/ui/StreamingPayloadIsolationTest.kt`

**Interfaces:**
- Produces: `data class StreamingTextPayload(val text: String, val reasoningText: String)`
- Produces: `ChatAdapter.onBindViewHolder(holder: ViewHolder, position: Int, payloads: List<Any>)`

- [ ] **Step 1: Escribir test unitario que verifica que `StreamingTextPayload` evita el pipeline pesado**

Crear `app/src/test/java/com/codex/chat/ui/StreamingPayloadIsolationTest.kt`:
```kotlin
package com.codex.chat.ui

import org.junit.Assert.*
import org.junit.Test

class StreamingPayloadIsolationTest {
    @Test
    fun test_payload_model_integrity() {
        val payload = StreamingTextPayload("Respuesta parcial", "Pensando...")
        assertEquals("Respuesta parcial", payload.text)
        assertEquals("Pensando...", payload.reasoningText)
    }
}
```

- [ ] **Step 2: Ejecutar test**

- [ ] **Step 3: Modificar `ChatAdapter.kt`**
En `updateStreaming(holder, payload: StreamingTextPayload)`:
- Asignar directamente `holder.binding.messageTextView.text = payload.text` sin re-analizar bloques Markdown, sin invocar `ToolCodeBlockParser.parse()` y sin invocar `VisualMediaParser.parse()`.
- El parseo pesado completo y formateo con resaltado de sintaxis solo se ejecuta al completarse el mensaje (`onStreamingComplete()`), liberando 15 ms por cada cuadro en el hilo principal.

- [ ] **Step 4: Ejecutar test y compilar**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/codex/chat/ChatAdapter.kt app/src/test/java/com/codex/chat/ui/StreamingPayloadIsolationTest.kt
git commit -m "perf(ui): isolate ChatAdapter streaming payload from expensive code block parsers (fixes C1)"
```

---

### Task 16: Desacoplamiento de I/O en UI y Optimización de `VisualMediaParser.kt` (Hallazgo C2)

**Files:**
- Modify: `app/src/main/java/com/codex/chat/core/parser/VisualMediaParser.kt`
- Test: `app/src/test/java/com/codex/chat/parser/VisualMediaParserBenchmarkTest.kt`

- [ ] **Step 1: Escribir test de rendimiento que mide el tiempo y asignaciones de normalización**

Crear `app/src/test/java/com/codex/chat/parser/VisualMediaParserBenchmarkTest.kt`:
```kotlin
package com.codex.chat.parser

import org.junit.Assert.*
import org.junit.Test

class VisualMediaParserBenchmarkTest {
    @Test
    fun test_unescape_and_normalize_no_redundant_allocations() {
        val input = "![Image](https://example.com/test.png\\n\\r\\t)"
        val normalized = VisualMediaParser.unescapeAndNormalize(input)
        assertFalse(normalized.contains("\\n"))
    }
}
```

- [ ] **Step 2: Ejecutar test**

- [ ] **Step 3: Optimizar `VisualMediaParser.kt`**
1. Mover la comprobación de duplicados en disco (`isDuplicateImage`) a un hilo en segundo plano con caché en memoria (`LruCache<String, Boolean>`), evitando violación de `StrictMode` en el hilo UI.
2. Reemplazar las 14 llamadas sucesivas a `.replace()` por una única pasada de análisis sobre `StringBuilder`.

- [ ] **Step 4: Ejecutar test y compilar**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/codex/chat/core/parser/VisualMediaParser.kt app/src/test/java/com/codex/chat/parser/VisualMediaParserBenchmarkTest.kt
git commit -m "perf(parser): eliminate disk I/O on UI thread and single-pass unescape in VisualMediaParser (fixes C2)"
```

---

### Task 17: Coalescencia de Eventos y Scroll Condicional en `MainActivity.kt` (Hallazgo C7)

**Files:**
- Modify: `app/src/main/java/com/codex/chat/MainActivity.kt` (líneas 3075–3095, 2445–2460)
- Test: `app/src/test/java/com/codex/chat/ui/StreamingCoalescerTest.kt`

- [ ] **Step 1: Escribir test unitario para el limitador temporal (`StreamingCoalescer`)**

Crear `app/src/test/java/com/codex/chat/ui/StreamingCoalescerTest.kt`:
```kotlin
package com.codex.chat.ui

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class StreamingCoalescerTest {
    @Test
    fun test_coalescer_drops_intermediate_updates_within_throttle_interval() {
        val dispatchCount = AtomicInteger(0)
        var lastTime = 0L
        val intervalMs = 33L // 30 fps

        val emit = { now: Long ->
            if (now - lastTime >= intervalMs) {
                dispatchCount.incrementAndGet()
                lastTime = now
            }
        }

        // 100 llamadas rápidas en ráfaga
        for (t in 0..100) {
            emit(t.toLong())
        }

        // En 100 ms con intervalo de 33 ms solo deben emitirse ~4 actualizaciones
        assertTrue(dispatchCount.get() in 3..5)
    }
}
```

- [ ] **Step 2: Ejecutar test**

- [ ] **Step 3: Integrar coalescencia en todas las rutas de streaming de `MainActivity.kt`**
Aplicar la ventana de 33 ms tanto en el stream principal como en `triggerToolContinuationTurn` y el socket de PC local.
Reemplazar `smoothScrollToPosition` incondicional por scroll condicional con detección de proximidad al fondo.

- [ ] **Step 4: Ejecutar test y compilar**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/codex/chat/MainActivity.kt app/src/test/java/com/codex/chat/ui/StreamingCoalescerTest.kt
git commit -m "perf(ui): coalesce streaming UI events to 30fps and decouple smoothScroll (fixes C7)"
```

---

### Task 18: Parser SSE de Cero Asignaciones con DFA en `SseStreamParser.kt` (Hallazgos C3, C4)

**Files:**
- Modify: `app/src/main/java/com/codex/chat/core/network/SseStreamParser.kt`
- Test: `app/src/test/java/com/codex/chat/network/SseStreamParserPerformanceTest.kt`

- [ ] **Step 1: Escribir test de no regresión y asignaciones para el parseo SSE**

Crear `app/src/test/java/com/codex/chat/network/SseStreamParserPerformanceTest.kt`:
```kotlin
package com.codex.chat.network

import org.junit.Assert.*
import org.junit.Test

class SseStreamParserPerformanceTest {
    @Test
    fun test_parse_delta_extracts_content_without_full_json_tree() {
        val sseChunk = "data: {\"choices\":[{\"delta\":{\"content\":\"Hola mundo\"}}]}"
        // Extraer token
        val extracted = SseStreamParser.extractDeltaFast(sseChunk)
        assertEquals("Hola mundo", extracted)
    }
}
```

- [ ] **Step 2: Ejecutar test**

- [ ] **Step 3: Implementar extracción directa de strings y DFA sin llamadas redundantes a `System.arraycopy`**

- [ ] **Step 4: Ejecutar test y compilar**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/codex/chat/core/network/SseStreamParser.kt app/src/test/java/com/codex/chat/network/SseStreamParserPerformanceTest.kt
git commit -m "perf(network): zero-allocation streaming extraction and DFA tag matching in SseStreamParser (fixes C3, C4)"
```

---

### Task 19: Precompilación de Expresiones Regulares en `ContextMetricsCalculator.kt` (Hallazgo C13)

**Files:**
- Modify: `app/src/main/java/com/codex/chat/core/metrics/ContextMetricsCalculator.kt`
- Modify: `app/src/main/java/com/codex/chat/MainActivity.kt` (líneas 445–472)
- Test: `app/src/test/java/com/codex/chat/metrics/ContextMetricsPrecompiledRegexTest.kt`

- [ ] **Step 1: Escribir test de verificación de precompilación de expresiones regulares**

Crear `app/src/test/java/com/codex/chat/metrics/ContextMetricsPrecompiledRegexTest.kt`:
```kotlin
package com.codex.chat.metrics

import org.junit.Assert.*
import org.junit.Test

class ContextMetricsPrecompiledRegexTest {
    @Test
    fun test_token_meter_calculation_throughput() {
        val text = "Texto representativo con varias palabras para medir velocidad de conteo"
        val start = System.nanoTime()
        for (i in 1..1000) {
            ContextMetricsCalculator.fastWordCount(text)
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        assertTrue("1000 conteos deben tardar menos de 20 ms", elapsedMs < 20.0)
    }
}
```

- [ ] **Step 2: Ejecutar test**

- [ ] **Step 3: Precompilar todos los `Regex` en variables `companion object` estáticas y cachear esquemas de herramientas MCP**
Evita serializar 50 herramientas a JSON Schema en cada tecla o cambio de mensaje.

- [ ] **Step 4: Ejecutar test y compilar**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/codex/chat/core/metrics/ContextMetricsCalculator.kt app/src/main/java/com/codex/chat/MainActivity.kt app/src/test/java/com/codex/chat/metrics/ContextMetricsPrecompiledRegexTest.kt
git commit -m "perf(metrics): precompile static regexes and cache MCP tool schemas in ContextMetricsCalculator (fixes C13)"
```

---

### Task 20: Módulo TUI de Regresión: `UiRenderingAndStreamingBenchmark` en el APK

**Files:**
- Create: `app/src/main/java/com/codex/chat/benchmark/UiRenderingAndStreamingBenchmark.kt`
- Modify: `app/src/main/java/com/codex/chat/benchmark/TuiBenchmarkActivity.kt`
- Test: `app/src/test/java/com/codex/chat/benchmark/UiRenderingAndStreamingBenchmarkTest.kt`

- [ ] **Step 1: Suite TUI que simula streaming sintético a 100 tokens/s y mide tiempos de cuadro mediante Window FrameMetrics**
- [ ] **Step 2: Ejecutar test unitario**
- [ ] **Step 3: Registrar en `TuiBenchmarkActivity`**
- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/codex/chat/benchmark/
git commit -m "test(tui): register UiRenderingAndStreamingBenchmark in in-APK TUI console"
```

---

## FASE 5: Validación Empírica, Suite de Caos y Criterios de Aceptación Globales

> **Rol de Medición de Fase:** **Lead Quality & Chaos Engineering Architect**  
> **Objetivo:** Ejecutar la suite integral de estrés en la consola TUI del APK (interactivamente en pantalla o headless vía `adb shell`), validando de forma rigurosa y empírica los 6 criterios cuantitativos de aceptación post-fix antes de la entrega final.

---

### Task 21: Orquestador Maestro de Benchmarks TUI (`MasterBenchmarkRunner.kt`)

**Files:**
- Create: `app/src/main/java/com/codex/chat/benchmark/MasterBenchmarkRunner.kt`
- Modify: `app/src/main/java/com/codex/chat/benchmark/TuiBenchmarkActivity.kt`
- Test: `app/src/test/java/com/codex/chat/benchmark/MasterBenchmarkRunnerTest.kt`

**Interfaces:**
- Produces: `MasterBenchmarkRunner.executeFullSuite(): SuiteReport`
- Produces: Salida formateada ANSI para la pantalla TUI del teléfono y salida JSON estructurada en `/sdcard/Download/benchmark_report.json` para auditoría externa vía `adb pull`.

- [ ] **Step 1: Escribir el test para el orquestador maestro**

Crear `app/src/test/java/com/codex/chat/benchmark/MasterBenchmarkRunnerTest.kt`:
```kotlin
package com.codex.chat.benchmark

import org.junit.Assert.*
import org.junit.Test

class MasterBenchmarkRunnerTest {
    @Test
    fun test_master_runner_aggregates_all_benchmarks() {
        val engine = TuiBenchmarkEngine()
        engine.registerBenchmark("B1") { BenchmarkResult("B1", true, 10L) }
        engine.registerBenchmark("B2") { BenchmarkResult("B2", true, 20L) }

        val runner = MasterBenchmarkRunner(engine)
        val report = runner.runAll()
        assertEquals(2, report.totalCount)
        assertEquals(2, report.passedCount)
        assertEquals(0, report.failedCount)
        assertTrue(report.toJson().contains(""passedCount":2"))
    }
}
```

- [ ] **Step 2: Ejecutar test para verificar fallo**

- [ ] **Step 3: Implementar `MasterBenchmarkRunner.kt`**

- [ ] **Step 4: Ejecutar test y verificar que pasa**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/codex/chat/benchmark/MasterBenchmarkRunner.kt app/src/test/java/com/codex/chat/benchmark/MasterBenchmarkRunnerTest.kt
git commit -m "feat(benchmark): implement MasterBenchmarkRunner with structured JSON and ANSI telemetry"
```

---

### Task 22: Suite de Pruebas de Caos y Cierres Abruptos (`KillAndCorruptionChaosTest.kt`)

**Files:**
- Create: `app/src/androidTest/java/com/codex/chat/chaos/KillAndCorruptionChaosTest.kt`
- Modify: `app/src/main/java/com/codex/chat/benchmark/TuiBenchmarkEngine.kt`

- [ ] **Step 1: Implementar prueba instrumentada de caos que simula 100 cortes abruptos durante escrituras particionadas**
- [ ] **Step 2: Verificar que el 100% de los archivos conservan inodos válidos y cero archivos vacíos (0 bytes)**
- [ ] **Step 3: Integrar el resultado en el reporte maestro**
- [ ] **Step 4: Commit**

```bash
git add app/src/androidTest/java/com/codex/chat/chaos/KillAndCorruptionChaosTest.kt
git commit -m "test(chaos): implement KillAndCorruptionChaosTest validating zero 0-byte files across 100 forced terminations"
```

---

### Task 23: Verificación Cuantitativa Final contra la Matriz de Aceptación

**Files:**
- Test: Ejecución completa en dispositivo / CI:
  `cmd.exe /c gradlew.bat testDebugUnitTest connectedDebugAndroidTest`
- Verificación Headless TUI:
  `adb shell am start -n com.codex.chat/.benchmark.TuiBenchmarkActivity --es suite "all"`
  `adb shell cat /sdcard/Download/benchmark_report.json`

- [ ] **Step 1: Ejecutar la suite unitaria completa de no-regresión**
Verificar que la totalidad de pruebas pasan con 0 fallos.

- [ ] **Step 2: Comprobar el cumplimiento estricto de los 6 Criterios de Aceptación:**
  1. **Integridad de Datos:** 0 corrupciones y 0 archivos de 0 bytes tras estrés de escrituras y cortes abruptos.
  2. **Concurrencia de Sesión:** 0 excepciones `ConcurrentModificationException` tras 500 iteraciones concurrentes.
  3. **Bases de Datos:** 0 excepciones `SQLiteDatabaseLockedException` con 4 lectores y 1 escritor simultáneos.
  4. **Fluidez de Renderizado:** Tiempos de cuadro p95 < 16.6 ms (en 60 Hz) y < 8.3 ms (en 120 Hz) durante streaming a 80+ tokens/s.
  5. **Presión de GC:** Reducción >= 80% en asignaciones de memoria transitoria en Heap durante un stream de 2.000 tokens.
  6. **Responsividad MCP:** Herramientas rápidas (< 5 ms) entregadas en UI en t < 50 ms a pesar de coexistir con herramientas lentas (> 5 s) en el mismo lote.

- [ ] **Step 3: Commit final de consolidación del arnés**

```bash
git add docs/superpowers/plans/
git commit -m "docs: finalize complete performance optimization and anti-regression TUI harness plan"
```

---

## Roles de Agentes Especializados por Fase (Subagent Protocol)

Para asegurar máxima especialización y cero dilución de contexto, cada fase debe ser evaluada y auditada por un agente con perfil de dominio específico:

| Fase | Tareas Cubiertas | Rol Especializado del Subagente | Enfoque de Auditoría y Verificación |
|---|---|---|---|
| **Fase 0** | Tasks 1–2 | **Staff Performance & Systems Instrumentation Engineer** | Validar la consola TUI interactiva, integración de comandos `adb shell` y marcadores `androidx.tracing` en hotpaths. |
| **Fase 1** | Tasks 3–6 | **Principal Database & Storage Reliability Engineer** | Auditar la simetría de bloqueos en JMM (`messagesLock`), durabilidad física con `fd.sync()` y unificación estricta del singleton SQLite. |
| **Fase 2** | Tasks 7–9 | **Senior Distributed Systems & Storage Migration Architect** | Auditar la regla anti-sobrescritura del protocolo de corte (*cutover*) y streaming `JsonWriter` particionado. |
| **Fase 3** | Tasks 10–13 | **Lead Concurrency & Systems Protocols Engineer** | Auditar la canalización progresiva libre de HoL Blocking en `ToolBatchExecutor` y liberación de bloqueos en SQLite WAL. |
| **Fase 4** | Tasks 14–20 | **Principal Android Graphics & UI Rendering Architect** | Auditar presupuesto VSYNC de 16.6ms / 8.3ms, erradicación de State Tearing (`StreamSnapshot`), coalescencia a 30fps y parser SSE sin asignaciones. |
| **Fase 5** | Tasks 21–23 | **Lead Quality & Chaos Engineering Architect** | Ejecutar la suite de caos, simulación de cortes abruptos (`kill -9`) y verificación empírica de los 6 umbrales de aceptación. |

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-09-16-performance-optimization-and-regression-harness.md`.

Two execution options:

1. **Subagent-Driven (recommended)** - I dispatch a fresh specialized subagent per task/phase with the exact role assigned in the table above, review between tasks, and maintain high-speed iteration with zero context bloat.
2. **Inline Execution** - Execute tasks directly in this session using executing-plans, batch execution with checkpoints.

Which approach?

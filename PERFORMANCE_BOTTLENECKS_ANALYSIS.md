# Auditoría Técnica de Arquitectura, Concurrencia y Rendimiento
**Proyecto:** `ChatGPT-Android-Studio` (`com.codex.chat`)  
**Fecha:** Septiembre 2026  
**Tipo de Documento:** Auditoría de Sistemas, Diagnóstico de Cuellos de Botella y Plan de Remediación Priorizado  
**Estado:** Revisión Técnica v2.0 (Corregida: Datos empíricos vs. hipótesis analíticas, priorización por riesgo de corrupción/crash, plan de migración y criterios de aceptación)

---

## 1. Declaración Metodológica y Alcance del Diagnóstico

Esta auditoría técnica evalúa el código fuente de la aplicación móvil con foco en cuatro áreas de arquitectura de software:
1. **Integridad de Datos y Persistencia:** Ciclo de vida de almacenamiento local, atomicidad en disco y contención de bases de datos SQLite.
2. **Concurrencia y Sincronización:** Hilos de fondo, contención de monitores JVM, planificación de tareas en lote y modelos de paso de mensajes.
3. **Pipeline de Red y Streaming:** Ingesta de Server-Sent Events (SSE), deserialización en bucles de alta frecuencia y presión de recolección de basura en el runtime Android (ART).
4. **Renderizado de Interfaz de Usuario:** Cumplimiento del presupuesto de cuadros del `Choreographer` (16.6 ms a 60 Hz / 8.3 ms a 120 Hz) y eficiencia de reciclaje en `RecyclerView`.

### Distinción Crítica: Evidencia Estática vs. Hipótesis de Impacto
- **Hechos Verificados:** Las citas de archivos, números de línea, patrones de diseño deficientes, condiciones de carrera y llamadas bloqueantes descritas en este informe han sido confirmadas directamente mediante inspección exhaustiva del código fuente.
- **Hipótesis de Latencia y Asignación:** Los rangos de tiempo por cuadro (ej. 28–110 ms) y volúmenes de objetos representan **hipótesis basadas en modelos teóricos de costo computacional** (complejidad algorítmica, asignaciones por llamada al runtime y comportamiento documentado de ART/Linux I/O). **No deben tratarse como mediciones empíricas finales.** El plan de trabajo establece como primer hito obligatorio la instrumentación con `androidx.tracing` y perfiles en Android Studio Profiler / Perfetto para determinar qué componente domina cuantitativamente el tiempo de cuadro antes de refactorizar.

---

## 2. Mapa de Diagnóstico de Fallas y Cuellos de Botella

A continuación se detalla la anatomía técnica de las fallas detectadas, agrupadas por dominio arquitectónico:

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                        MAPA DE PROBLEMAS ARQUITECTÓNICOS                               │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ P0: INTEGRIDAD DE DATOS Y ESTABILIDAD (CRASHES / CORRUPCIÓN)                           │
│  ├── LocalChatRepository.kt: Data Race & ConcurrentModificationException en s.messages│
│  ├── LocalChatRepository.kt: Falta de fsync() en renameTo() (Riesgo de archivo 0-bytes) │
│  └── MemorySqliteStore.kt: Múltiples SQLiteOpenHelper -> SQLiteDatabaseLockedException │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ P1: CONCURRENCIA, LATENCIA DE FONDO Y MCP                                              │
│  ├── ToolBatchExecutor.kt: Head-of-Line Blocking en future.get() secuencial            │
│  ├── ReasoningTranslator.kt: Spawning de hilos OS nativos y HTTP síncrono bloqueante   │
│  └── MemorySqliteStore.kt: synchronized(lock) anula concurrencia multi-lector de WAL   │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ P2: TIEMPO DE CUADRO UI, BASURA EN HEAP Y STREAMING                                    │
│  ├── ChatAdapter.kt: Payload bind ilusorio (re-ejecuta el 98% del bind completo)       │
│  ├── Parsers: Regex dinámicas no compiladas y escaneo O(N) acumulativo en hilo UI       │
│  ├── VisualMediaParser.kt: RandomAccessFile síncrono en hilo principal (StrictMode)    │
│  ├── MainActivity.kt: Inundación del Looper en continuación agéntica (sin coalescencia)│
│  ├── MainActivity.kt: Layout thrashing por smoothScrollToPosition() en cada delta      │
│  ├── item_message_assistant.xml: textIsSelectable fuerza StaticLayout costoso          │
│  ├── SseStreamParser.kt: Deserialización DOM con JSONObject (alto churn de Heap)       │
│  ├── SseStreamParser.kt: System.arraycopy en lineBuffer.delete() y 9 substrings/token  │
│  └── ContextMetricsCalculator.kt: Recálculo de tokens en UI (reserializa 50 schemas)   │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Matriz Exhaustiva de Hallazgos Técnicos (C1 a C13)

| ID | Componente / Líneas Exactas | Clasificación | Causa Raíz Comprobada en Código | Hipótesis de Impacto y Riesgo Real |
|---|---|---|---|---|
| **C1** | `ChatAdapter.kt:71–81`, `217–239` | UI / Renderizado | `updateStreaming()` ejecuta exactamente la misma cadena pesada que `bind()` (`bindThinking`, `bindToolExecution`, `bindVisualMediaAndContent`, `bindMetrics`, `bindContinueTask`), omitiendo únicamente `bindCopy()`. No es un payload ligero. | El payload no aísla el texto; re-ejecuta analizadores y layouts en cada delta de streaming. |
| **C2** | `VisualMediaParser.kt:309–355`, `367–399` | I/O en UI / Heap Churn | 1. `isDuplicateImage()` abre un `RandomAccessFile` en modo `"r"` y lee bloques de 8 KB en el hilo principal.<br>2. `unescapeAndNormalize()` encadena 14 llamadas sucesivas a `.replace()`. | 1. Violación directa de `StrictMode` por I/O de disco flash síncrono.<br>2. Creación de 14 copias intermedias de strings por llamada, incrementando la presión de memoria transitoria. |
| **C3** | `SseStreamParser.kt:188` | Deserialización / GC | `JSONObject(dataContent)` crea un árbol DOM completo (`JSONTokener`, mapas hash `LinkedHashMap`, arrays) por cada línea SSE (cada token). | Para 2.000 tokens se generan decenas de miles de objetos descartados de inmediato, elevando la frecuencia de pausas de recolección de basura del ART. |
| **C4** | `SseStreamParser.kt:63–64`, `447–455` | Algorítmico / Memoria | 1. `lineBuffer.delete(0, newlineIdx + 1)` invoca `System.arraycopy` para mover el resto del búfer a offset 0 en cada línea.<br>2. `findPartialTagStart` extrae hasta 9 substrings por token mediante bucle `1..str.length.coerceAtMost(9)`. | Desplazamientos repetidos de bloques de memoria y generación de hasta 18.000 strings efímeros en un stream mediano. |
| **C5** | `StreamBuffer.kt:18, 62, 88` | Concurrencia / Tearing | 1. Usa `ReentrantReadWriteLock` con bloqueo de lectura prolongado por copia de strings (`toString()`).<br>2. En `MainActivity.kt:2849–2852`, `getContent()` y `getReasoning()` se leen en dos llamadas independientes no atómicas entre sí. | 1. Riesgo de bloqueo mutuo entre el hilo de red OkHttp y el hilo UI.<br>2. **State Tearing:** La UI puede renderizar un contenido actualizado con un reasoning desincronizado. |
| **C6** | `ReasoningTranslator.kt:109–131`, `155` | Hilos / Red Bloqueante | 1. `thread(name = "reasoning-translate")` crea un hilo nativo OS de 1 MB de stack cada vez que drena frases.<br>2. `translateBlocking()` realiza un HTTP POST síncrono (`client.newCall().execute()`) por frase.<br>3. `flush()` no sincroniza con los hilos de fondo antes de persistir. | 1. Sobrecarga por creación de hilos del kernel.<br>2. Latencia de red de cientos de milisegundos por frase.<br>3. Pérdida o guardado incompleto de traducciones al cerrar la sesión. |
| **C7** | `MainActivity.kt:3080–3090`, `2449–2458` | Flujo de Eventos | En `triggerToolContinuationTurn` y el socket de PC local no existe coalescencia temporal; cada fragmento recibido invoca `runOnUiThread { ... }` sin filtro. | A 80 tokens/segundo se satura el `MessageQueue` del `Looper` principal con decenas de Runnables redundantes, retrasando la atención de eventos táctiles. |
| **C8** | `LocalChatRepository.kt:198–245` | Almacenamiento / I/O | Almacenamiento monolítico: `writeToDisk()` recorre todas las sesiones y todos los mensajes, generando un mega-JSON en memoria con `array.toString()` antes de escribir el archivo temporal. | Amplificación de escritura O(S x M). Guardar un mensaje de 20 caracteres obliga a reserializar miles de mensajes históricos. |
| **C9** | `LocalChatRepository.kt:144–154`, `207` | Concurrencia / Crash | `persistAsync` clona la lista de sesiones, pero `session.messages` es una referencia a una `MutableList` compartida. En la línea 207 se itera con `for (m in s.messages)` mientras el hilo principal añade mensajes con `messages.add()`. | **Fallo Fatal (Crash):** Provoca de manera intermitente `java.util.ConcurrentModificationException` durante el streaming activo. |
| **C10**| `MemorySqliteStore.kt:96, 116, 166, 186, 277` | Base de Datos / Bloqueo | Todos los métodos públicos (incluyendo lecturas: `get`, `searchFts5`, `list`) están envueltos en `synchronized(lock)` a nivel de objeto JVM. | Neutraliza por completo la capacidad multi-lector de SQLite WAL (Write-Ahead Logging); las consultas concurrentes se encolan detrás de escrituras largas. |
| **C11**| `MemoryMcpServer.kt:26`, `AntiAmnesiaHarvester.kt:74`, `CodexMaintenanceWorker.kt:35` | Base de Datos / Integridad | Instanciación directa e inconexa de `MemorySqliteStore(...)` en lugar de usar consistentemente el singleton `getInstance()`. | Cada instancia abre su propio pool nativo `SQLiteConnectionPool` sobre el mismo archivo. Provoca colisiones `android.database.sqlite.SQLiteDatabaseLockedException`. |
| **C12**| `ToolBatchExecutor.kt:154, 183–188, 216` | Planificación Concurrente | 1. Se crea y destruye un `newFixedThreadPool` en cada llamada.<br>2. Se itera sobre `futures` en orden secuencial llamando a `future.get(120, TimeUnit.SECONDS)`. | **Head-of-Line (HoL) Blocking:** Si la herramienta 0 tarda 8 segundos (red o confirmación) y las herramientas 1 a 4 tardan 2 milisegundos, los resultados de 1 a 4 quedan congelados hasta que 0 desbloquea el bucle. |
| **C13**| `ContextMetricsCalculator.kt:42–126`, `MainActivity.kt:448–471` | CPU en Hilo UI | `updateRealtimeTokenMeter()` corre en el hilo UI y en cada invocación:<br>1. Reconstruye el System Prompt completo concatenando strings.<br>2. Serializa 50 herramientas MCP a JSON Schema llamando a `toOpenAiToolSchema()`.<br>3. Recorre todo el historial de mensajes recompilando `Regex("""\s+""")`. | El costo computacional en el hilo principal no proviene solo del recorrido de palabras, sino de la recompilación del autómata Regex y la reserialización de 50 esquemas JSON pesados en cada evento de UI. |

---

## 4. Plan de Remediación Priorizado por Riesgo Real

La intervención debe ejecutarse en fases estrictamente ordenadas por criticidad, protegiendo primero la persistencia del usuario y la estabilidad de la aplicación antes de intervenir el pipeline visual.

```
  FASE 0: Instrumentación Diagnóstica (Línea base con Perfetto / Profiler)
                            │
                            ▼
  FASE 1: P0 - Estabilidad y Prevención de Pérdida de Datos (C9, C8-Durabilidad, C11)
                            │
                            ▼
  FASE 2: Migración Arquitectónica de Almacenamiento (De monolito a sesiones particionadas)
                            │
                            ▼
  FASE 3: P1 - Desbloqueo de Concurrencia y Pipelines de Fondo (C12, C6, C10)
                            │
                            ▼
  FASE 4: P2 - Fluidez de UI, Streaming y Optimización de Asignaciones (C1, C2, C5, C7, C3, C4, C13)
                            │
                            ▼
  FASE 5: Validación Empírica y Criterios de Aceptación Post-Fix
```

---

### FASE 0: Instrumentación Diagnóstica y Medición de Línea Base
**Objetivo:** Obtener trazas deterministas antes de modificar la lógica de negocio.
1. Insertar trazas con `androidx.tracing.Trace.beginSection(name)` / `endSection()` en:
   - `ChatAdapter.updateStreaming`
   - `ToolCodeBlockParser.parse`
   - `VisualMediaParser.parse`
   - `ContextMetricsCalculator.calculate`
   - `LocalChatRepository.writeToDisk`
2. Grabar una sesión de 30 segundos de streaming intenso con **Android Studio CPU Profiler** y **Perfetto**.
3. Medir cuantitativamente:
   - Porcentaje de tiempo de cuadro consumido por `Regex` vs. `StaticLayout` vs. serialización JSON.
   - Conteo de asignaciones en Heap durante el streaming (usando Allocation Tracker).
   - Frecuencia de `Choreographer: Skipped frames`.

---

### FASE 1: Prioridad P0 — Estabilidad, Integridad y Prevención de Corrupción de Datos

#### 1.1. Erradicación de `ConcurrentModificationException` (Hallazgo C9)
* **Archivo:** `app/src/main/java/com/codex/chat/LocalChatRepository.kt`
* **Acción:** En `persistAsync`, obtener un snapshot defensivo inmutable y profundo de la sesión bajo sincronización atómica:
  ```kotlin
  fun saveSessionAsync(session: LocalChatSession) {
      val snapshot = synchronized(session.messages) {
          // Copia inmutable de la lista para aislamiento total frente a mutaciones concurrentes
          ArrayList(session.messages)
      }
      ioExecutor.execute {
          writeSessionSnapshot(session.id, session.title, session.timestamp, snapshot)
      }
  }
  ```

#### 1.2. Durabilidad Física en Disco con `fsync()` (Hallazgo C8 - Durabilidad)
* **Archivo:** `app/src/main/java/com/codex/chat/LocalChatRepository.kt`
* **Acción:** El renombrado atómico POSIX (`renameTo`) solo actualiza metadatos del sistema de archivos; los datos en caché de páginas pueden perderse ante una descarga de batería o kernel panic. Garantizar la sincronización de bloques físicos antes del renombrado:
  ```kotlin
  FileOutputStream(tempFile).use { fos ->
      fos.write(bytes)
      fos.flush()
      // Sincronización obligatoria con el controlador de almacenamiento físico
      fos.fd.sync()
  }
  if (!tempFile.renameTo(targetFile)) {
      tempFile.copyTo(targetFile, overwrite = true)
      tempFile.delete()
  }
  ```

#### 1.3. Unificación Estricta de `SQLiteOpenHelper` (Hallazgo C11)
* **Archivos:** `MemoryMcpServer.kt`, `AntiAmnesiaHarvester.kt`, `CodexMaintenanceWorker.kt`
* **Acción:**
  - Hacer privado el constructor de `MemorySqliteStore`.
  - Reemplazar toda invocación `MemorySqliteStore(context)` por el singleton `MemorySqliteStore.getInstance(context.applicationContext)`.
  - Configurar `setWriteAheadLoggingEnabled(true)` en un único punto dentro de `onConfigure`.

---

### FASE 2: Estrategia de Migración de Almacenamiento (Monolito a Particionado)

#### 2.1. Arquitectura de Almacenamiento Destino
Sustituir el archivo monolítico `chatgpt_local_history.json` por un esquema de archivos particionados por sesión:
```
context.filesDir/
  ├── sessions_index.json          # Metadatos ligeros: id, title, timestamp, lastModified (ordenados)
  └── sessions/
        ├── session_abc123.json     # Mensajes exclusivos de la sesión abc123
        └── session_def456.json     # Mensajes exclusivos de la sesión def456
```

#### 2.2. Protocolo de Migración No Bloqueante en Producción
Para no penalizar el tiempo de inicio en frío (*Cold Start*):
1. **Detección Rápida:** En el arranque, comprobar si existe `chatgpt_local_history.json` y no existe `sessions_index.json`.
2. **Carga Inmediata de Sesión Activa:** Si el usuario abre la app, leer del archivo legado únicamente la última sesión activa para permitir interacción instantánea.
3. **Migración en Segundo Plano:**
   - Despachar un trabajo de fondo o corrutina en `Dispatchers.IO` con baja prioridad de I/O.
   - Leer `chatgpt_local_history.json` mediante un flujo `JsonReader` en streaming (sin inflar `JSONArray` completo en memoria).
   - Escribir individualmente cada `sessions/{id}.json` con su respectivo `fd.sync()`.
   - Generar `sessions_index.json` atómicamente.
4. **Respaldo y Depuración Segura:**
   - Una vez que todas las sesiones se escribieron y el hash de verificación de mensajes coincide, renombrar el archivo viejo a `chatgpt_local_history.json.bak`.
   - No eliminar el archivo `.bak` hasta pasados 14 días o 3 inicios de sesión exitosos sin errores de lectura.

---

### FASE 3: Prioridad P1 — Concurrencia, Desbloqueo de MCP y Tareas de Fondo

#### 3.1. Eliminación de Head-of-Line (HoL) Blocking en Lotes de Herramientas (Hallazgo C12)
* **Archivo:** `app/src/main/java/com/codex/chat/core/concurrency/ToolBatchExecutor.kt`
* **Acción:**
  - Reutilizar un `CoroutineDispatcher` compartido acotado (`Dispatchers.IO.limitedParallelism(maxConcurrency)`) en lugar de instanciar pools `newFixedThreadPool` efímeros.
  - No iterar con `futures[i].get()` secuencial. Lanzar corrutinas paralelas y canalizar cada resultado inmediatamente conforme finalice mediante un canal (`Channel`) o callback de finalización individual. Esto permite que herramientas rápidas (1 ms) se muestren de inmediato en la UI sin esperar a herramientas lentas o interactivas (10 s).

#### 3.2. Refactorización Asíncrona de `ReasoningTranslator` (Hallazgo C6)
* **Archivo:** `app/src/main/java/com/codex/chat/core/network/ReasoningTranslator.kt`
* **Acción:**
  - Eliminar `thread(name = "reasoning-translate")`.
  - Reemplazar la cola bloqueante por un `Channel<String>(capacity = 64)` consumido por una corrutina en segundo plano.
  - Implementar un timeout explícito de 2 segundos por frase y cancelar traducciones pendientes si la sesión finaliza o el usuario envía un nuevo mensaje.
  - Sincronizar `flush()` con un handler suspend o deferred para asegurar que los segmentos finales se resuelvan antes de cerrar la persistencia.

#### 3.3. Liberación de la Concurrencia WAL en SQLite (Hallazgo C10)
* **Archivo:** `app/src/main/java/com/codex/chat/core/mcp/server/MemorySqliteStore.kt`
* **Acción:**
  - Retirar el bloqueo `synchronized(lock)` de los métodos de lectura (`get`, `searchFts5`, `list`).
  - Permitir que el motor nativo de SQLite administre los bloqueos a través de las conexiones de lectura concurrentes de WAL.
  - Mantener transacciones exclusivas únicamente en operaciones mutantes (`save`, `delete`) encapsuladas en `db.beginTransactionNonExclusive()`.

---

### FASE 4: Prioridad P2 — Fluidez de UI, Streaming y Optimización de Asignaciones

#### 4.1. Prevención de "State Tearing" en `StreamBuffer` (Hallazgo C5)
* **Archivo:** `app/src/main/java/com/codex/chat/core/concurrency/StreamBuffer.kt`
* **Acción:**
  - Sustituir las lecturas separadas `getContent()` y `getReasoning()` por un objeto inmutable único que garantiza consistencia temporal absoluta:
    ```kotlin
    data class StreamSnapshot(
        val content: String,
        val reasoning: String,
        val version: Long
    )
    ```
  - Mantener una referencia atómica `private val currentSnapshot = AtomicReference(StreamSnapshot("", "", 0L))`.
  - El hilo de UI realiza una sola lectura atómica `val snap = streamBuffer.getSnapshot()` y despacha `updateLastMessage(snap.content, snap.reasoning)`, eliminando el riesgo de renderizar estados inconsistentes.

#### 4.2. Corrección del Payload en `ChatAdapter` y Aislamiento de Parsers (Hallazgos C1, C2, C13)
* **Archivos:** `ChatAdapter.kt`, `ToolCodeBlockParser.kt`, `VisualMediaParser.kt`
* **Acción:**
  1. Definir un payload granular explícito:
     ```kotlin
     data class StreamingPayload(val rawContent: String, val rawReasoning: String)
     ```
  2. En `updateStreaming`, si el payload es de streaming puro:
     - **No ejecutar** `ToolCodeBlockParser.parse()` ni `VisualMediaParser.parse()` en el hilo de UI en cada frame.
     - Enviar directamente el texto sin formato o procesado previamente al `TextView`.
  3. Precompilar todas las expresiones regulares en variables `companion object` estáticas (`Regex` compiladas una sola vez en el inicio de la clase, nunca dentro de bucles o funciones).
  4. Mover el cálculo de deduplicación de imágenes y lectura de disco (`isDuplicateImage`) a un hilo en segundo plano (`Dispatchers.IO`), nunca dentro del ciclo de renderizado de la vista.

#### 4.3. Coalescencia de Eventos y Control de Animaciones (Hallazgos C7, C4)
* **Archivos:** `MainActivity.kt`, `item_message_assistant.xml`
* **Acción:**
  1. Aplicar el coalescedor de UI (mínimo 33 ms / 30 fps para actualizaciones de texto) tanto al stream principal como a la continuación de herramientas (`triggerToolContinuationTurn`).
  2. Sustituir `smoothScrollToPosition` constante por un scroll condicional controlado: solo desplazar si el usuario se encuentra al final de la lista y usar `scrollToPosition` inmediato o desacoplar el desplazamiento con un umbral de distancia.
  3. Desactivar `android:textIsSelectable="true"` durante el estado de streaming activo para evitar el costo de re-medición en `StaticLayout`; reactivar la selección únicamente cuando el mensaje ha terminado de generarse.
  4. Optimizar `SseStreamParser` reemplazando el DOM `JSONObject` por un parser en streaming basado en Okio (`JsonReader` o coincidencia directa de campos) y reemplazar las 9 subdivisiones de `findPartialTagStart` por un autómata finito determinista (DFA) que opere carácter por carácter sin asignaciones de strings.

---

## 5. Criterios de Aceptación y Validación Post-Fix

Para declarar cualquier cuello de botella o riesgo como resuelto, la solución debe satisfacer los siguientes umbrales medibles en pruebas sobre hardware físico (dispositivo representativo de gama media / Snapdragon serie 7 o equivalente):

| Área de Validación | Métrica de Aceptación / Umbral Objetivo | Método de Comprobación |
|---|---|---|
| **Integridad de Datos** | **0 corrupciones** y 0 archivos de 0 bytes tras forzar 100 cierres abruptos (`kill -9`) durante escrituras activas. | Script automatizado de pruebas de estrés en segundo plano. |
| **Concurrencia de Sesión** | **0 excepciones `ConcurrentModificationException`** tras generar 500 mensajes concurrentes con persistencia activa. | Prueba de estrés de integración con JUnit y hilos paralelos. |
| **Bases de Datos** | **0 excepciones `SQLiteDatabaseLockedException`** durante ejecución simultánea de 4 herramientas lectoras y 1 proceso de mantenimiento escritor. | Test multi-hilo sobre `MemorySqliteStore`. |
| **Fluidez de Renderizado (UI)** | **Tiempos de cuadro p95 < 16 ms** (en 60 Hz) y **p95 < 8.3 ms** (en 120 Hz) durante generación de tokens continua. Menos del 2% de frames perdidos. | Registro de trazas con Perfetto / `dumpsys gfxinfo`. |
| **Presión de ART GC** | Reducción de al menos **80% en el volumen de memoria transitoria asignada** en el Heap durante un streaming de 2.000 tokens. | Android Studio Memory Profiler (Allocation Tracking). |
| **Responsividad en Lotes MCP** | Herramientas con tiempo de ejecución < 5 ms deben renderizarse en UI en **t < 50 ms**, incluso si otra herramienta en el lote tarda > 5 s. | Prueba de integración con mock de herramienta con retraso artificial. |

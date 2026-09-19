# Auditoría Integral — Codex ChatGPT Mobile (`com.codex.chat`)

| Campo | Valor |
|---|---|
| Fecha | 19 de septiembre de 2026 |
| Artefacto | `Codex-ChatGPT-Mobile.apk` · v1.0.79 · versionCode 80 · 3.488.031 bytes |
| Commit auditado | `e8adec9` (rama `master`) |
| Método | Grafo de conocimiento (`codebase-memory`, 11.329 nodos / 66.653 aristas, modo `full`) + lectura de fuente + ejecución real de la suite de tests |
| Auditoría previa | `AUDITORIA_PROYECTO_0_100.md` — 72/100 (14/09/2026) |
| **Calificación** | **74 / 100 — B− (Apto con reservas críticas)** |

---

## 0. Nota metodológica

Esta auditoría **no hereda** la nota anterior. Todas las métricas se derivaron de nuevo en esta sesión:

- Reindexación completa del repositorio en el grafo de conocimiento (proyecto `chatgpt-apk-audit`,
  artefacto persistido en `.codebase-memory/graph.db.zst`).
- Consultas Cypher sobre complejidad ciclomática/cognitiva, fan-in, profundidad de bucles transitiva
  y trazado de llamadas (`trace_path`) para probar o refutar cada hallazgo.
- Ejecución real de `gradlew testDebugUnitTest` y parseo de los XML de resultados.
- Lectura directa de los ficheros implicados en cada hallazgo (no se reporta nada inferido).

El ADR del proyecto (`PURPOSE / STACK / ARCHITECTURE / PATTERNS / TRADEOFFS / PHILOSOPHY`) ha quedado
persistido en la memoria del grafo y es recuperable en futuras sesiones con
`manage_adr(project="chatgpt-apk-audit", mode="get")`.

---

## 1. Calificación ponderada

| # | Dimensión | Peso | Nota | Veredicto |
|---|---|:---:|:---:|---|
| 1 | Arquitectura y modularidad | 20 % | **64** | Bimodal: núcleo excelente, presentación monolítica |
| 2 | Seguridad y superficie de ataque | 25 % | **56** | Crítico: perímetro roto pese a núcleo sólido |
| 3 | Rendimiento, memoria y durabilidad | 20 % | **84** | Notable, con deuda medible en UI |
| 4 | Integración IA · MCP · streaming | 15 % | **92** | Excelente, diferencial competitivo real |
| 5 | Higiene de código Kotlin/Android | 10 % | **78** | Bueno, con anacronismos deliberados |
| 6 | Pruebas y verificación empírica | 10 % | **88** | Sobresaliente, con un asterisco |
| | **TOTAL PONDERADO** | **100 %** | **74.0** | **B−** |

Cálculo: `64×0,20 + 56×0,25 + 84×0,20 + 92×0,15 + 78×0,10 + 88×0,10 = 74,0`

**Lectura de la nota.** 74 no significa "mediocre". Significa un proyecto cuyo **núcleo técnico
merecería un 85+** y cuyo **perímetro de despliegue arrastra un 50**. La diferencia entre ambos es
exactamente el trabajo pendiente, y es un trabajo acotado: seis ficheros concentran casi todo el
riesgo.

---

## 2. Evidencia empírica recogida en esta sesión

### 2.1 Suite de pruebas unitarias (ejecución real)

```
> gradlew.bat testDebugUnitTest --continue
BUILD SUCCESSFUL in 54s
```

| Métrica | Valor |
|---|---:|
| Clases de test ejecutadas | 82 |
| Casos de prueba | **407** |
| Fallos | **0** |
| Errores | **0** |
| Omitidos | **0** |
| Tiempo total | **14,03 s** |

Suites más densas (todas verdes):

| Suite | Tests |
|---|---:|
| `ToolArgumentDeepInspectionMatrixTest` | 30 |
| `AdversarialPromptInjectionFuzzTest` | 25 |
| `CamelInformationFlowControlMatrixTest` | 25 |
| `SecureCryptoAndKeyStoreDeepTest` | 19 |
| `McpRegistryTest` | 15 |
| `ToolApprovalGateTest` | 14 |
| `ToolApprovalGateConcurrencyAuditTest` | 14 |

### 2.2 Benchmarks en dispositivo (Pixel 10 Pro XL, API 35 — registro del proyecto)

| Benchmark | Resultado | Tiempo |
|---|:---:|---:|
| `DATA_INTEGRITY_STRESS` | PASS | 202 ms |
| `STORAGE_CUTOVER_MIGRATION` | PASS | 2.683 ms |
| `CONCURRENCY_AND_MCP_STRESS` | PASS | 121 ms |
| `UI_RENDERING_AND_STREAMING` | PASS | 2.505 ms |
| `CHAOS_KILL_AND_CORRUPTION` | PASS | 1.868 ms |
| **Total** | **5/5** | **7.379 ms** |

### 2.3 Inventario de código (medido, no estimado)

| Métrica | Valor |
|---|---:|
| Ficheros Kotlin en `src/main` | 98 |
| Líneas Kotlin en `src/main` | 18.504 |
| Ficheros de test JVM | 83 (6.823 líneas) |
| Tests instrumentados | 18 ficheros (1.949 líneas) |
| Recursos XML | 67 ficheros (5.445 líneas) |
| **Total auditado** | **~32.700 líneas** |
| Permisos declarados en el manifiesto | **105** |
| Sitios con `thread { }` crudo | **26** (24 en `MainActivity`, 2 en `AppUpdateManager`) |
| Usos de `lifecycleScope` / `viewModelScope` / `suspend fun` | **0** |
| Clases `ViewModel` | **0** |
| Puntos de guarda `EstopSentinel` | 5 |

### 2.4 Complejidad medida sobre el grafo

| Ámbito | Métodos | Líneas | Ciclomática media | Máxima |
|---|---:|---:|---:|---:|
| `MainActivity.kt` | 86 | 4.015 | **5,48** | **42** |
| `core/**` | 383 | 9.726 | **3,57** | 40 |
| Global `src/main/java` | 570 | 15.807 | 3,64 | 42 |

Los diez métodos más complejos del proyecto — **nueve de ellos viven en `MainActivity.kt`**:

| Método | Fichero | Líneas | Ciclo. | Cognitiva |
|---|---|---:|---:|---:|
| `handleSlashCommand` | MainActivity.kt | 156 | **42** | **120** |
| `showConnectorsBottomSheet` | MainActivity.kt | 231 | 28 | 30 |
| `showPermissionsStatusAndRequest` | MainActivity.kt | 47 | 26 | 29 |
| `startCodexRealTimePolling` | MainActivity.kt | 109 | 24 | 89 |
| `sendCodexPcMessage` | MainActivity.kt | 126 | 22 | 97 |
| `showToolApprovalDialog` | MainActivity.kt | 140 | 21 | 39 |
| `parse` | ToolCodeBlockParser.kt | 126 | 21 | 42 |
| `sendMessage` | MainActivity.kt | 142 | 20 | 37 |
| `executeMediaConnectorGeneration` | MainActivity.kt | 113 | 19 | 27 |
| `executeStreamWithContext` | MainActivity.kt | 174 | 16 | 20 |

---

## 3. Hallazgos críticos

### 3.1 — OTA sin verificación de integridad · 🔴 CRÍTICA

**Fichero:** `app/src/main/java/com/codex/chat/AppUpdateManager.kt`

```
línea  12: import com.codex.chat.core.update.ApkVerifier
línea 120: sha256 = ""
línea 232: promptInstall(activity, apkFile)
```

`ApkVerifier` está implementado y es **correcto** (SHA-256 sobre buffer + comparación en tiempo
constante sobre 64 caracteres). El problema es que **nunca se invoca en producción**. Trazado
inbound de `ApkVerifier.sha256` sobre el grafo:

```
callers de sha256:
  ApkVerifierTest.el_hash_de_contenido_conocido_es_el_esperado      [test]
  ApkVerifierTest.el_mismo_contenido_da_el_mismo_hash               [test]
  ApkVerifierTest.un_solo_byte_distinto_cambia_el_hash              [test]
  ApkVerifierTest.un_apk_manipulado_no_valida_contra_el_hash_...    [test]
  ApkVerifierTest.hashing_de_fichero_grande_...                     [test]
  AppUpdateIsolationTest.test_update_info_model_integrity           [test]
  bump_and_build.get_file_sha256                                    [script de build]
```

**Cero llamadores de producción.** El único consumidor real del hash es el script Python de
empaquetado. El flujo de actualización descarga el APK desde GitHub Releases y lo entrega
directamente a `promptInstall` → `FileProvider` → `ACTION_VIEW`.

Agravante: `versionCode = BuildConfig.VERSION_CODE + 1` es un valor **fabricado**, no leído del
release. La app no sabe realmente qué versión está instalando.

> **Ironía documentada:** la auditoría anterior tiene una sección titulada
> *"3.3 Verificación Criptográfica Segura en OTA (`ApkVerifier`)"*. El componente se escribió, se
> probó con 5 tests y se dejó desconectado. Es el patrón de fallo más peligroso que existe: seguridad
> que aparece en el inventario pero no en la ruta de ejecución.

**Remediación:** publicar el SHA-256 en el cuerpo del release o en un `release_metadata.json`,
parsearlo en `fetchLatest`, y condicionar `promptInstall` a
`ApkVerifier.coincide(esperado, ApkVerifier.sha256(apkFile))`. Rechazar la instalación si el campo
viene vacío — nunca degradar a "sin verificación".

---

### 3.2 — Release firmado con el keystore de debug · 🔴 CRÍTICA

**Fichero:** `app/build.gradle.kts`

```
línea 56: isMinifyEnabled = true
línea 57: isShrinkResources = true
línea 59: signingConfig = signingConfigs.getByName("debug")
```

El build de release se firma con la clave de depuración de Android, cuya clave privada
(`~/.android/debug.keystore`, contraseña `android`) es **pública y universal**. Consecuencias:

1. Cualquiera puede compilar un APK malicioso con el mismo certificado y **el sistema lo aceptará
   como actualización legítima** de la app instalada.
2. Combinado con 3.1, la cadena de confianza de actualización no tiene ni un solo eslabón válido:
   ni hash, ni firma propia.
3. Google Play rechazaría el artefacto de plano.

Obsérvese la desproporción: se activó ofuscación y reducción de recursos (R8) — atención al detalle
en el empaquetado — y simultáneamente se dejó la firma en debug.

---

### 3.3 — Taint tracking de fuente única: CaMeL ciego a la inyección indirecta · 🔴 CRÍTICA

**Fichero:** `app/src/main/java/com/codex/chat/MainActivity.kt:3284`

```kotlin
val isWebTainted = webGrounding.isNotBlank()
```

Esta es la **única** línea de todo `src/main` que produce contaminación. Las 21 apariciones restantes
de `isWebTainted` en el árbol son propagación (`= isWebTainted`), parámetros con `default = false`, o
consumo en la política. Verificado por grep exhaustivo sobre `src/main`.

El invariante constitucional nº2 de `ToolApprovalPolicy` — *"operación ROOT/DESTRUCTIVE/irreversible
con datos contaminados siempre pregunta"* — depende enteramente de ese booleano:

```
ToolApprovalPolicy:44   if (req.isWebTainted) { ... }
ToolApprovalGate:64     val bypassAllowlist = enrichedReq.isWebTainted || inspection.isCriticalDanger
ToolApprovalGate:93     !enrichedReq.isWebTainted && ...
MainActivity:3850       if (req.isWebTainted || req.dangerReason != null)
MainActivity:3863       if (isIrreversible || req.isWebTainted || req.dangerReason != null)
```

**Cadena de explotación concreta (todos los eslabones existen hoy):**

1. El atacante envía un SMS o una notificación de mensajería con texto adversarial.
2. El usuario, en modo `FULL_ACCESS`, pide: *"lee mis últimos mensajes"*.
3. El modelo invoca `read_sms_messages` o `get_captured_notifications`.
4. El texto del atacante entra al contexto. `webGrounding` está vacío → **`isWebTainted = false`**.
5. El invariante nº2 no dispara. Solo queda la red de regex de `ToolArgumentInspector` como defensa.

Es decir: el sistema está blindado contra el vector *menos* probable en móvil (inyección vía
resultados de búsqueda web) y abierto en los vectores *más* probables (SMS, notificaciones,
contactos, calendario, contenido de ficheros locales).

**Remediación:** convertir la contaminación en estado de sesión, no de turno. Cualquier herramienta
que devuelva contenido de origen externo debe marcar la conversación como contaminada de forma
persistente (`taintedSince = turnIndex`), y ese estado debe sobrevivir a los turnos de continuación de
herramientas (`triggerToolContinuationTurn`, `executeToolChainStep`).

---

### 3.4 — Fuga de OTP / 2FA / banca vía notificaciones · 🔴 CRÍTICA

**Ficheros:** `core/service/CodexNotificationListenerService.kt`, `core/mcp/server/SystemSettingsMcpServer.kt:246`

```
línea 18: private const val MAX_STORED = 25
línea 34: val title = extras?.getCharSequence("android.title")?.toString() ?: ""
línea 35: val text  = extras?.getCharSequence("android.text")?.toString() ?: ""
```

Se captura el texto íntegro de **toda** notificación del sistema, sin lista blanca de paquetes y sin
redacción. La herramienta MCP `get_captured_notifications` lo sirve al modelo, y está clasificada
apenas como **SENSITIVE** (no DESTRUCTIVE), de modo que en `ASK_ON_RISK` puede pasar sin fricción.

Lo que sale del dispositivo hacia el proveedor LLM: códigos 2FA de banca y de Google, enlaces mágicos
de inicio de sesión, previsualizaciones de conversaciones privadas, notificaciones médicas.

Esto no es solo un riesgo de privacidad: es un **canal de escalada**. Un atacante que logre inducir
una única llamada a esta herramienta obtiene los segundos factores del usuario. Y por 3.3, ese mismo
contenido entra sin marcar como contaminado.

**Remediación:** lista negra de paquetes (banca, autenticadores, salud), redacción regex de secuencias
numéricas de 4–8 dígitos antes de persistir, reclasificar la herramienta a DESTRUCTIVE, y marcar
contaminación obligatoria en su salida.

---

### 3.5 — Claves API embarcadas con ofuscación XOR reversible · 🔴 CRÍTICA

**Fichero:** `core/security/SecureKeyVault.kt`

El fichero almacena cuatro claves (`MASKED_APINEX` 53 B, `MASKED_BAI` 35 B, `MASKED_CODEX_LOCAL` 71 B,
`MASKED_E2B` 44 B) enmascaradas con un XOR cíclico de 12 bytes. La rutina de desenmascarado está
**en la misma clase, en claro**:

```kotlin
private fun unmask(payload: ByteArray): String {
    val out = ByteArray(payload.size)
    for (i in payload.indices) out[i] = (payload[i].toInt() xor MASK[i % MASK.size].toInt()).toByte()
    return String(out, StandardCharsets.UTF_8)
}
```

Verificación práctica realizada en esta auditoría: reimplementando esas doce líneas se recuperan los
prefijos reales — `sk-apx2cd4…` y `e2b_1084ac…` — **en segundos, sin herramientas especializadas**.

El comentario del fichero afirma que esto *"frustra la extracción estática mediante jadx o apktool"*.
Es falso: frustra `strings`, que es un adversario distinto y trivial. Un atacante con `jadx-gui` lee
la función; uno con Frida engancha `getApinexKey()` y se salta todo.

Agravante: el ProGuard del proyecto mantiene `-keep class com.codex.chat.core.**` sobre
`security` y `mcp.approval`, de modo que R8 ofusca la aplicación pero **deja con nombres legibles
justo las clases de seguridad**.

Contraste justo: `SecureCredentialsStore` (AES-256-GCM, IV de 12 B por operación, AndroidKeyStore) es
criptografía **correcta**. El problema no es capacidad técnica, es que se embarcan credenciales de
servicios de pago en un binario distribuido públicamente.

---

## 4. Hallazgos de severidad alta y media

### 4.1 — `MainActivity` creció tras ser señalada · 🟠 ALTA

La auditoría del 14/09/2026 prescribió explícitamente descomponer `MainActivity` en ViewModels.
Cinco días después:

| | 14/09/2026 | 19/09/2026 | Δ |
|---|---:|---:|---:|
| Líneas | 3.098 | **3.839** | **+741 (+24 %)** |
| ViewModels creados | 0 | **0** | 0 |
| `thread { }` en el fichero | — | **24** | — |

La clase concentra, en un solo fichero: gestión de vistas y animaciones, insets de teclado, drawer,
RecyclerView, bottom sheets de configuración, ciclo SSE completo, bucle agéntico de herramientas
(`triggerToolContinuationTurn`, `executeToolChainStep`), diálogos de aprobación de seguridad,
conectores multimedia con contabilidad de créditos, persistencia de sesión, permisos en tiempo de
ejecución, reconocimiento de voz y actualizaciones OTA.

`handleSlashCommand` tiene complejidad **ciclomática 42 y cognitiva 120**. Ningún test unitario puede
alcanzarlo: requiere una `Activity` viva. La consecuencia medible es que la lógica de la aplicación
más expuesta al usuario es precisamente la **menos cubierta** por los 407 tests.

**No es un hallazgo estético.** Es la causa raíz de 4.2 y de que el bucle agéntico —
`executeToolChainStep`, profundidad transitiva de bucles **52**, la más alta del proyecto — sea
inauditable por unidad.

### 4.2 — Cero concurrencia estructurada · 🟠 ALTA

```
thread { }  : 26 sitios
lifecycleScope / viewModelScope / suspend fun : 0
ViewModel   : 0
```

En una app que en 2026 apunta a `targetSdk 35`, no hay una sola corrutina. Cada `thread { }` en
`MainActivity`:

- captura `this` (la Activity) → sobrevive a rotación y a recreación por presión de memoria →
  **fuga de contexto**;
- reserva ~1 MB de stack nativo, sin reutilización de pool;
- **no es cancelable** — no hay handle, no hay `onDestroy` que los detenga. Un `startCodexRealTimePolling`
  (ciclomática 24, cognitiva 89) lanzado y luego abandonado sigue haciendo polling HTTP contra un
  `Activity` muerta.

El proyecto *sí* demuestra dominio de concurrencia en el núcleo (`StreamBuffer` con
`ReentrantReadWriteLock` y snapshots atómicos; `ToolBatchExecutor` sin head-of-line blocking;
`MemorySqliteStore` que retira el lock en lectura para explotar multi-lector WAL). El conocimiento
existe; simplemente no se aplicó en la capa de UI.

### 4.3 — Datos de créditos fabricados y correo personal embarcado · 🟠 ALTA

**Fichero:** `core/connector/MediaConnectorClient.kt:63-72` (y réplicas en `MainActivity:914,964`,
`MediaConnectorManager:158-167`, `activity_main.xml:259`)

```kotlin
val rem = json.optDouble("credits_remaining", 1050.0)
...
creditsRemaining = if (rem > 0.0) rem else 1050.0,
```

**Defecto lógico probado:** cuando el servidor responde honestamente `credits_remaining: 0.0` —
es decir, cuando el usuario **se ha quedado sin créditos** — la guarda `rem > 0.0` evalúa a `false` y la
app sustituye el cero por **1050,0**. La interfaz muestra saldo completo en el momento exacto en que
no queda nada. El usuario intenta generar, recibe un error 500 opaco, y no tiene forma de entender
por qué.

La corrección es de un carácter: `if (rem >= 0.0)`. El hecho de que el mismo *default* esté replicado
en cuatro ficheros distintos indica ausencia de una única fuente de verdad para el estado de créditos.

Además, `perceojon@gmail.com` aparece embarcado en el código de producción y **en un layout XML**
(`activity_main.xml:259`, texto estático). El repositorio de releases personal también está fijado en
tres URLs de `AppUpdateManager`.

### 4.4 — `TuiBenchmarkActivity` exportada en el manifiesto de release · 🟡 MEDIA

Declarada con `android:exported="true"` y un intent-filter VIEW/DEFAULT, y con soporte explícito de
ejecución headless por parámetro:

```kotlin
val suiteArg = intent.getStringExtra("suite")
if (!suiteArg.isNullOrBlank()) runAllBenchmarksAsync(headless = true)
```

Cualquier aplicación instalada en el mismo dispositivo puede lanzar la suite completa de estrés
(5 benchmarks, ~7,4 s de CPU saturada, escritura intensiva en disco, incluido
`CHAOS_KILL_AND_CORRUPTION`) sin interacción del usuario. Vector de agotamiento de batería y desgaste
de almacenamiento. Una superficie de depuración no debería viajar en el artefacto de release.

### 4.5 — `allowBackup`, `FileProvider` global y legacy storage · 🟡 MEDIA

```
AndroidManifest.xml:113  android:allowBackup="true"
AndroidManifest.xml:120  android:requestLegacyExternalStorage="true"
```

`allowBackup="true"` fue señalado el 14/09/2026 y sigue sin resolver. Permite extraer por `adb backup`
el historial completo de conversaciones y `mcp_memory.sqlite`.

Y `res/xml/file_paths.xml` expone la raíz entera del almacenamiento privado:

```xml
<external-path name="external_files" path="." />
<cache-path    name="cache_files"    path="." />
<files-path    name="internal_files" path="." />   <!-- raíz completa de filesDir -->
```

`filesDir` contiene las sesiones de chat, la base SQLite de memoria, las skills y el propio centinela
`ESTOP`. Debería declararse únicamente el subdirectorio de actualizaciones, que es el único uso real.

### 4.6 — `SkillAstAuditGate` no analiza un AST · 🟡 MEDIA

El nombre promete análisis sintáctico; la implementación son dos listas de regex
(`MALICIOUS_PATTERNS`, `SUSPICIOUS_PATTERNS`). Evadible con concatenación de cadenas, codificación
base64 o unicode. El componente aporta valor real como filtro de primera línea, pero **el nombre
induce a confiar de más** — y en seguridad, sobreestimar una defensa es peor que no tenerla.

Aplica la misma crítica a `ToolArgumentInspector`: su escaneo de inyección recorre únicamente claves
de primer nivel vía `json.optString(key)`; los objetos y arrays anidados quedan sin inspeccionar. Y la
detección de exfiltración cubre `http_get` pero no los cuerpos de `http_post`.

### 4.7 — 105 permisos: ausencia total de mínimo privilegio · 🟡 MEDIA

Incluye `MANAGE_EXTERNAL_STORAGE`, `SEND_SMS`, `READ_SMS`, `READ_CALL_LOG`, `WRITE_SETTINGS`,
`PACKAGE_USAGE_STATS`, `SYSTEM_ALERT_WINDOW`, `QUERY_ALL_PACKAGES`, `REQUEST_INSTALL_PACKAGES`,
`BIND_NOTIFICATION_LISTENER_SERVICE`, `ACCESS_BACKGROUND_LOCATION`, `BODY_SENSORS_BACKGROUND`.

Es coherente con el propósito declarado (un agente que actúa sobre el teléfono), pero implica que
**el sistema operativo no ejerce ninguna contención**. Toda la seguridad recae en
`ToolApprovalGate.decide` — un único punto con fan-in 36. No hay defensa en profundidad: si esa
función falla o es eludida, no hay segunda barrera.

---

## 5. Lo que este proyecto hace genuinamente bien

Una auditoría que solo enumera defectos es una auditoría deshonesta. Lo siguiente está verificado en
código y es de calidad superior a la media del sector:

**Control de flujo de información con fundamento académico real.** La cadena
`ToolRiskClassifier → ToolArgumentInspector → ToolApprovalPolicy → ToolApprovalGate` no es teatro de
seguridad. Es *fail-closed* de verdad (lo desconocido se clasifica DESTRUCTIVE), tiene invariantes
que la configuración del usuario no puede desactivar, y está respaldada por 80 tests adversariales
específicos. Muy pocos clientes de IA móvil implementan algo comparable.

**Durabilidad de almacenamiento a nivel de base de datos.** `PartitionedChatStorage` escribe a
temporal, hace `FileOutputStream.fd.sync()` — *fsync* explícito, no confía en el page cache — y
promueve con `Files.move(ATOMIC_MOVE)` con degradación a `renameTo`. El benchmark
`CHAOS_KILL_AND_CORRUPTION` valida esto contra kills abruptos. Es rigor de ingeniería de sistemas.

**Concurrencia correcta donde se aplicó.** `StreamBuffer` elimina el *state tearing* con snapshots
atómicos bajo `ReentrantReadWriteLock`. `ToolBatchExecutor` despacha callbacks de forma progresiva,
eliminando el head-of-line blocking en lotes. `MemorySqliteStore` retira deliberadamente el lock en
`get` y `searchFts5` para no anular el multi-lector de WAL, manteniéndolo solo en escritura.

**Degradación elegante sistemática.** FTS5 → FTS4, AndroidKeyStore → clave JVM en tests,
`ATOMIC_MOVE` → `renameTo`, mermaid local → CDN. El sistema está diseñado para no romperse en
dispositivos heterogéneos.

**`EstopSentinel`.** Parada de emergencia global, respaldada en disco, *fail-safe* ante excepciones de
I/O (ante la duda, bloquea). Es exactamente el mecanismo que un agente con acceso root debe tener.

**Núcleo medible como limpio.** Complejidad ciclomática media de **3,57** en `core/**` sobre 383
métodos es un dato objetivamente bueno.

**Verificación real, no declarada.** 407 tests JVM verdes en 14 s más 5 benchmarks en hardware real.
El proyecto se mide a sí mismo.

---

## 6. El asterisco sobre la cobertura de pruebas

`app/build.gradle.kts`:

```kotlin
testOptions { unitTests.isReturnDefaultValues = true }
```

Con esta opción, **toda llamada al framework de Android devuelve silenciosamente su valor por
defecto** (`null`, `0`, `false`) en lugar de lanzar. Es pragmático — permite 407 tests en 14 segundos
sin Robolectric — pero significa que una parte de la suite de seguridad valida **lógica pura, no
comportamiento de plataforma**. Un test de `SecureCredentialsStore` que pase por la rama de fallback
JVM no prueba nada sobre AndroidKeyStore real.

A esto se suma el punto ciego estructural de §4.1: los métodos de mayor complejidad del proyecto
(`handleSlashCommand`, ciclomática 42; `sendCodexPcMessage`, cognitiva 97;
`startCodexRealTimePolling`, cognitiva 89) viven en una `Activity` y **no son alcanzables** por la
suite JVM. Los 407 tests son excelentes, pero cubren el núcleo bien factorizado y dejan fuera el
monolito. La cobertura alta y el riesgo alto están en ficheros distintos.

Por eso esta dimensión puntúa 88 y no 95.

---

## 7. Plan de remediación priorizado

### P0 — Bloqueantes de distribución (1–2 días)

| # | Acción | Fichero |
|---|---|---|
| 1 | Publicar SHA-256 en el release, parsearlo y **exigir** `ApkVerifier.coincide` antes de `promptInstall`; rechazar hash vacío | `AppUpdateManager.kt:120,232` |
| 2 | Generar keystore de release propio, fuera del repo, referenciado desde `local.properties`/CI | `build.gradle.kts:59` |
| 3 | Retirar las cuatro claves de `SecureKeyVault`; exigir clave de usuario en ajustes (ya protegida por `SecureCredentialsStore`) o proxy autenticado | `SecureKeyVault.kt` |
| 4 | Leer el `versionCode` real del release en vez de `VERSION_CODE + 1` | `AppUpdateManager.kt` |

### P1 — Blindaje del modelo de amenazas (3–5 días)

| # | Acción | Fichero |
|---|---|---|
| 5 | Contaminación como **estado de sesión**: toda herramienta de origen externo marca taint persistente que sobrevive a los turnos de continuación | `MainActivity.kt:3284`, `ToolApprovalPolicy`, `ToolBatchExecutor` |
| 6 | Lista negra de paquetes + redacción regex de OTP (`\b\d{4,8}\b`) antes de persistir la notificación | `CodexNotificationListenerService.kt` |
| 7 | Reclasificar `get_captured_notifications` y `read_sms_messages` a **DESTRUCTIVE** | `ToolRiskClassifier.kt` |
| 8 | Inspección recursiva de JSON anidado + cobertura de cuerpos `http_post` | `ToolArgumentInspector.kt` |
| 9 | `allowBackup="false"`; `TuiBenchmarkActivity` a `debug` source set o `exported="false"`; `file_paths.xml` acotado al subdirectorio de updates | `AndroidManifest.xml`, `file_paths.xml` |
| 10 | Eliminar `-keep` sobre `core.security` y `core.mcp.approval` | `proguard-rules.pro` |

### P2 — Corrección funcional (1 día)

| # | Acción | Fichero |
|---|---|---|
| 11 | `if (rem >= 0.0)` — un saldo de 0 es un valor legítimo, no ausencia de dato | `MediaConnectorClient.kt:71` + 3 réplicas |
| 12 | Fuente única de verdad para créditos; eliminar los defaults duplicados en 4 ficheros | `MediaConnectorManager`, `MainActivity` |
| 13 | Retirar `perceojon@gmail.com` del código y del layout | `MediaConnectorClient.kt:67`, `activity_main.xml:259` |

### P3 — Salud estructural (2–3 semanas)

| # | Acción |
|---|---|
| 14 | Descomponer `MainActivity` en `ChatViewModel` / `McpToolViewModel` / `ConnectorViewModel` / `UpdateViewModel`. Objetivo: ningún método con ciclomática > 15 y `MainActivity` < 800 líneas |
| 15 | Migrar los 26 `thread { }` a `viewModelScope` + `Dispatchers.IO` con cancelación real |
| 16 | `DiffUtil.calculateDiff` fuera del hilo principal (`AsyncListDiffer`) |
| 17 | Extraer el bucle agéntico (`triggerToolContinuationTurn`, `executeToolChainStep`) a una clase de dominio testeable sin `Activity` |
| 18 | Tests instrumentados para los 10 métodos de mayor complejidad hoy inalcanzables |

---

## 8. Proyección

| | Hoy | Tras P0+P1 | Tras P0–P3 |
|---|:---:|:---:|:---:|
| Arquitectura | 64 | 64 | 88 |
| Seguridad | 56 | 86 | 92 |
| Rendimiento | 84 | 84 | 90 |
| IA · MCP | 92 | 94 | 95 |
| Higiene | 78 | 80 | 90 |
| Pruebas | 88 | 88 | 94 |
| **Global** | **74** | **84** | **91** |

El salto de 74 → 84 cuesta **menos de una semana** y consiste casi por completo en conectar,
reclasificar y configurar componentes **que ya existen**. Ese es el dato más relevante de toda esta
auditoría: el proyecto no necesita capacidad técnica nueva, necesita cerrar el perímetro.

---

## 9. Dictamen

> **74 / 100 — B−. Apto para uso personal informado. No apto para distribución pública en su estado
> actual.**

Codex ChatGPT Mobile es un agente autónomo en dispositivo con un núcleo de ingeniería que supera
claramente a la media del sector: control de flujo de información con base académica y *fail-closed*
real, durabilidad con `fsync` y movimientos atómicos, concurrencia correcta en el motor de streaming,
407 pruebas verdes y 5 benchmarks en hardware real.

Y sin embargo, hoy **la cadena de confianza de su propia actualización no tiene ni un eslabón
válido** — firma de debug y hash vacío —, y su defensa estrella contra la inyección de prompts está
cableada a una única fuente de contaminación que deja desprotegidos precisamente los vectores más
probables en un teléfono.

La distancia entre lo que este proyecto demuestra saber hacer y lo que efectivamente hace en su
perímetro es la crítica central de esta auditoría. `ApkVerifier` lo resume entero: escrito, probado
con cinco tests, documentado en la auditoría anterior como logro de seguridad — y con **cero
llamadores en producción**.

---

*Auditoría generada con verificación empírica en la misma sesión. Grafo de conocimiento persistido en
`.codebase-memory/graph.db.zst` (proyecto `chatgpt-apk-audit`, 11.329 nodos / 66.653 aristas). ADR
arquitectónico almacenado en memoria del grafo.*

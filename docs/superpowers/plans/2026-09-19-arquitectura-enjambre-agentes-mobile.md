# Plan de Implementación: Arquitectura de Enjambre / Panal de Agentes (Mobile Hive)
## Codex ChatGPT Mobile — v1.0.83+

> **Estado:** Aprobado para diseño y ejecución modular  
> **Fecha:** 19 de Septiembre de 2026  
> **Autor:** Codex Apex (Principal Software Architect & Systems Engineer)  
> **Base de partida:** 494 tests JVM passing (90 clases), 0 fallos  
> **Objetivo tras ejecución:** ~530 tests JVM passing, Speedup 3x-5x en tareas complejas, Aislamiento CaMeL  
> **ADR asociado:** `chatgpt-apk-audit` en `codebase-memory-mcp`  

---

## 1. Resumen Ejecutivo y Metas de Arquitectura

### 1.1 El Problema Actual (Monolito Secuencial de Turno Único)
Actualmente, la aplicación opera bajo un modelo de agente único secuencial:
1. El usuario envía un objetivo complejo (*'Analiza el rendimiento de la batería, revisa si hay alertas en las notificaciones y busca qué significa el código de error X en internet'*).
2. Un solo modelo LLM recibe todo el prompt y empieza a ejecutar herramientas en fila india ($T = t_1 + t_2 + t_3$).
3. Los datos crudos (telemetría de 20 KB, volcados de 50 notificaciones, HTML de búsqueda web) se insertan directamente en el historial de conversación principal, saturando la ventana de contexto de tokens.
4. Si una herramienta web contiene una inyección de prompt indirecta, toda la sesión queda contaminada (*tainted*), bloqueando herramientas seguras posteriores.

### 1.2 La Solución: Topología de Panal (Hierarchical Mobile Hive)
Transformar el motor de ejecución en un **Enjambre Jerárquico de Agentes**:
- **Agente Reina (Orquestador / Planner):** Descompone la meta en un Grafo Acíclico Dirigido (DAG) de subtareas.
- **Agentes Obreros Especializados (Workers):** Procesos concurrentes en segundo plano con herramientas estrictamente confinadas por rol (Sensor OS, Código/E2B, Medios/Flow, Búsqueda Web).
- **Pizarra Compartida (Shared Blackboard):** Tuple-space respaldado por el `SubagentLineageStore` existente (SQLite en modo Write-Ahead Logging WAL) para sincronización y persistencia ante fallos.
- **Aislamiento Map-Reduce en el Edge:** Cada obrero procesa datos masivos en su propio contexto y devuelve a la Reina únicamente síntesis compactas y estructuradas.
- **Compartimentación CaMeL:** La contaminación de seguridad (*taint*) se aísla por worker. El compromiso de un worker web no escala a los workers de base de datos o sistema operativo.

---

## 2. Mapa de Fases y Módulos Desacoplados

```
┌────────────────────────────────────────────────────────────────────────┐
│ FASE 0: Modelos de Dominio y Contratos de Protocolo                     │
│   - SwarmModels.kt (SwarmRole, SwarmTask, WorkerStatus, SwarmResult)   │
│   - Test: SwarmProtocolContractTest.kt                                 │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│ FASE 1: Motor Concurrente de Despacho (AgentDispatcher & DepthSentinel)│
│   - SwarmAgentDispatcher.kt (Pool acotado a 4 hilos, timeouts, Future) │
│   - SwarmDepthSentinel.kt (Límite estricto de recursión <= 3 niveles)  │
│   - Test: SwarmAgentDispatcherTest.kt                                  │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│ FASE 2: Servidor de Herramientas MCP del Enjambre                      │
│   - SwarmOrchestratorMcpServer.kt (spawn_worker, await_workers)        │
│   - Registro en McpRegistry.kt y ToolRiskClassifier.kt                 │
│   - Test: SwarmOrchestratorMcpServerTest.kt                            │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│ FASE 3: Pizarra Compartida y Compartimentación CaMeL de Taint          │
│   - SwarmBlackboard.kt (SQLite WAL via SubagentLineageStore)           │
│   - WorkerTaintCompartment.kt (Aislamiento de SessionTaintTracker)     │
│   - Tests: SwarmBlackboardLineageTest.kt & SwarmTaintIsolationTest.kt  │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│ FASE 4: Renderizado Reactivo UI y Sincronización en MainActivity       │
│   - SwarmLiveState.kt (Estado observable del panal para RecyclerView)  │
│   - Integración sin bloqueos en MainActivity.kt                        │
│   - Test: SwarmLiveStateUiTest.kt                                      │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│ FASE 5: Integración End-to-End y Medición de Rendimiento Empírico      │
│   - Test E2E: SwarmEndToEndIntegrationTest.kt                          │
│   - Medición de percentiles de latencia (p50, p90, p99) y speedup      │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Desglose Detallado de Cada Fase con Tests Reales

### FASE 0: Modelos de Dominio y Contratos del Enjambre

#### Responsabilidad
Definir las estructuras inmutables que rigen la comunicación, los estados de ciclo de vida de los workers, los roles especializados y las respuestas devueltas.

#### Archivo a Crear
- `app/src/main/java/com/codex/chat/core/swarm/model/SwarmModels.kt`

#### Tipos y Entidades
1. `enum class SwarmRole`: `ORCHESTRATOR`, `SENSOR_OS`, `CODE_COMPUTE`, `MULTIMEDIA`, `WEB_RESEARCH`, `CRITIC`.
2. `enum class WorkerStatus`: `QUEUED`, `RUNNING`, `COMPLETED`, `TIMED_OUT`, `FAILED`, `CANCELLED`.
3. `data class SwarmTask`: `taskId: String`, `role: SwarmRole`, `prompt: String`, `timeoutMs: Long = 30000L`, `parentTaskId: String? = null`, `depth: Int = 1`.
4. `data class SwarmTaskResult`: `taskId: String`, `role: SwarmRole`, `status: WorkerStatus`, `outputPayload: String`, `executionDurationMs: Long`, `taintOrigin: TaintOrigin? = null`, `errorMessage: String? = null`.
5. `data class WorkerTicket`: `ticketId: String`, `taskId: String`, `role: SwarmRole`, `dispatchedAt: Long`.

#### Test Medial Real
- **Ruta:** `app/src/test/java/com/codex/chat/swarm/SwarmProtocolContractTest.kt`
- **Aserciones concretas:**
  - `tarea_con_profundidad_valida_retiene_invariantes()`: Verifica que `depth` se propaga correctamente.
  - `resultado_exitoso_contiene_payload_y_duracion_positiva()`: Valida estado `COMPLETED` con métricas.
  - `resultado_fallido_contiene_mensaje_de_error_y_payload_vacio()`.
  - `roles_poseen_identificadores_canonicos()`.

---

### FASE 1: Motor Concurrente de Despacho (AgentDispatcher & DepthSentinel)

#### Responsabilidad
Gestionar el pool de hilos de ejecución de workers en segundo plano con control estricto de concurrencia máxima (para evitar sobrecalentamiento del móvil) y prevención de bucles infinitos de subagentes.

#### Archivos a Crear
1. `app/src/main/java/com/codex/chat/core/swarm/engine/SwarmDepthSentinel.kt`:
   - Guardia que audita la profundidad de anidamiento de subagentes. Máximo nivel: `MAX_DEPTH = 3`.
   - Lanza `SwarmRecursionLimitException` si un worker intenta despachar más allá del límite.
2. `app/src/main/java/com/codex/chat/core/swarm/engine/SwarmAgentDispatcher.kt`:
   - Pool de ejecución `ThreadPoolExecutor` acotado (`corePoolSize = 2`, `maxPoolSize = 4`, `keepAliveTime = 60s`).
   - Método `dispatch(task: SwarmTask, workerLogic: () -> String): WorkerTicket`.
   - Método `await(ticket: WorkerTicket, timeoutMs: Long): SwarmTaskResult`.
   - Método `awaitAll(tickets: List<WorkerTicket>, overallTimeoutMs: Long): List<SwarmTaskResult>`.

#### Test Medial Real
- **Ruta:** `app/src/test/java/com/codex/chat/swarm/SwarmAgentDispatcherTest.kt`
- **Aserciones concretas:**
  - `despacho_concurrente_de_4_workers_ejecuta_en_paralelo()`: Verifica que el tiempo total de 4 tareas de 200ms es $< 450ms$ (speedup comprobado vs 800ms secuenciales).
  - `timeout_individual_cancela_worker_lento_sin_bloquear_el_pool()`: Tarea de 5000ms con timeout de 300ms devuelve `TIMED_OUT` en ~300ms.
  - `fallo_en_un_worker_no_afecta_a_los_demas_workers_en_ejecucion()`: Un worker que lanza `RuntimeException` devuelve `FAILED` mientras los otros 3 completan exitosamente.
  - `depth_sentinel_bloquea_recursion_que_supere_nivel_3()`: Intento de despachar con `depth = 4` rechaza con excepción inmediata.
  - `metricas_de_throughput_demuestran_speedup_real()`: Medición empírica de operaciones/seg.

---

### FASE 2: Servidor de Herramientas MCP del Enjambre (SwarmOrchestratorMcpServer)

#### Responsabilidad
Exponer al modelo de lenguaje (Agente Reina) las herramientas de primer nivel para invocar, sincronizar y consultar obreros del enjambre mediante el protocolo estándar MCP.

#### Archivos a Crear / Modificar
1. `app/src/main/java/com/codex/chat/core/mcp/server/SwarmOrchestratorMcpServer.kt`:
   - Implementa `McpServer` con namespace `swarm`.
   - Herramientas:
     - `spawn_worker(role: String, task_prompt: String, timeout_sec: Int = 30): String` (Devuelve JSON con ticketId).
     - `await_workers(ticket_ids: List<String>, timeout_sec: Int = 45): String` (Devuelve lista JSON de resultados).
     - `get_worker_status(ticket_id: String): String` (Sondeo no bloqueante).
2. Modificar `app/src/main/java/com/codex/chat/core/mcp/McpRegistry.kt`:
   - Registrar `SwarmOrchestratorMcpServer` en la inicialización.
3. Modificar `app/src/main/java/com/codex/chat/core/mcp/approval/ToolRiskClassifier.kt`:
   - `spawn_worker` y `await_workers` clasificados como `DESTRUCTIVE` (fail-closed seguro, requiere aprobación si la política lo exige).

#### Test Medial Real
- **Ruta:** `app/src/test/java/com/codex/chat/swarm/SwarmOrchestratorMcpServerTest.kt`
- **Aserciones concretas:**
  - `esquema_mcp_declara_herramientas_con_parametros_completos()`: Valida `tools/list` con tipos string, array y required.
  - `spawn_worker_retorna_ticket_valido_y_registra_en_dispatcher()`.
  - `await_workers_recolecta_multiples_resultados_en_un_solo_payload()`.
  - `tool_risk_classifier_asigna_destructive_a_las_herramientas_swarm()`.

---

### FASE 3: Pizarra Compartida y Compartimentación CaMeL de Taint

#### Responsabilidad
Garantizar persistencia atómica en SQLite WAL de todas las acciones del enjambre para auditoría y recuperación, aislando criptográficamente el estado de contaminación (*taint*) para que un worker con datos de internet no contamine los permisos de los demás.

#### Archivos a Crear / Modificar
1. `app/src/main/java/com/codex/chat/core/swarm/blackboard/SwarmBlackboard.kt`:
   - Fachada de alto nivel sobre `SubagentLineageStore`.
   - Métodos: `postFinding(taskId, key, data)`, `readFindings(parentSessionId)`, `getFinding(taskId, key)`.
2. `app/src/main/java/com/codex/chat/core/swarm/security/WorkerTaintCompartment.kt`:
   - Cada worker recibe su propia instancia de `SessionTaintTracker` aislada.
   - Si el Worker WEB ejecuta `web_search`, su `SessionTaintTracker` local queda marcado con `TaintOrigin.WEB_SEARCH`.
   - El Worker OS (que tiene acceso a `read_sms_messages` o `execute_root_command`) corre con su propio tracker limpio.

#### Tests Mediales Reales
1. **Ruta:** `app/src/test/java/com/codex/chat/swarm/SwarmBlackboardLineageTest.kt`:
   - `escritura_concurrente_en_pizarra_no_produce_bloqueo_gracias_a_wal()`.
   - `consulta_de_hallazgos_por_sesion_recupera_todos_los_outputs_de_workers()`.
   - `limpieza_de_lineaje_al_iniciar_nuevo_chat_purga_registros_anteriores()`.
2. **Ruta:** `app/src/test/java/com/codex/chat/swarm/SwarmTaintCompartmentalizationTest.kt`:
   - `worker_web_contaminado_no_propaga_taint_a_worker_sms_paralelo()`.
   - `worker_sms_puede_ejecutar_herramienta_sensible_mientras_worker_web_esta_tainted()`.
   - `la_reina_solo_hereda_taint_si_consume_directamente_el_payload_crudo_del_worker_web()`.

---

### FASE 4: Renderizado Reactivo UI y Sincronización en MainActivity

#### Responsabilidad
Mostrar al usuario en tiempo real el progreso de cada obrero del enjambre sin congelar la interfaz ni saturar el `RecyclerView` de mensajes desordenados.

#### Archivos a Crear / Modificar
1. `app/src/main/java/com/codex/chat/core/swarm/ui/SwarmLiveState.kt`:
   - Estado observable: `activeWorkers: List<WorkerProgress>`, `completedCount: Int`, `totalCount: Int`, `overallProgressPct: Int`.
2. Integración en `app/src/main/java/com/codex/chat/ChatAdapter.kt`:
   - Soporte para tarjeta viva `SwarmProgressPayload` que actualiza los indicadores de cada obrero (🟢 completado, 🟡 trabajando, 🔴 error) con animación suave.
3. Conexión en `MainActivity.kt`:
   - Instanciación de `SwarmAgentDispatcher` y vinculación con el ciclo de vida de la Activity.

#### Test Medial Real
- **Ruta:** `app/src/test/java/com/codex/chat/swarm/SwarmLiveStateUiTest.kt`
- **Aserciones concretas:**
  - `actualizacion_atomica_de_progreso_calcula_porcentajes_correctos()`.
  - `cambio_de_estado_de_worker_genera_payload_parcial_sin_rebind_total()`.
  - `cierre_de_todos_los_workers_emite_estado_final_para_sintesis_de_la_reina()`.

---

### FASE 5: Integración End-to-End y Benchmarks de Rendimiento

#### Responsabilidad
Verificar el flujo de extremo a extremo (Reina ➔ Despacho de 3 Workers ➔ Escritura en Pizarra ➔ Validación CaMeL ➔ Síntesis final en Chat) y medir empíricamente las mejoras de latencia y throughput.

#### Archivo de Test E2E
- **Ruta:** `app/src/test/java/com/codex/chat/swarm/SwarmEndToEndIntegrationTest.kt`

#### Métricas Empíricas Requeridas
1. **Latencia por percentiles:**
   - Secuencial tradicional vs. Enjambre concurrente (p50, p90, p99).
2. **Throughput:**
   - Tareas resueltas por minuto.
3. **Ratio de Speedup:**
   - $S = \frac{T_{secuencial}}{T_{enjambre}} \ge 2.5x$.
4. **Integridad de la Suite:**
   - Mantenimiento estricto de los 494 tests actuales pasando + ~36 tests nuevos $\rightarrow$ total $\ge 530$ tests con 0 fallos.

---

## 4. Matriz de Riesgos y Protocolo del Abogado del Diablo

| Riesgo Crítico | Causa Potencial | Mitigación Arquitectónica | Test que lo Garantiza |
|---|---|---|---|
| **Agotamiento de Batería / Throttling Térmico** | Demasiados hilos concurrentes de red y CPU en el teléfono. | Bounded ThreadPool con `maxConcurrency = 4`. Rechazo de tareas adicionales a la cola si el pool está saturado. | `SwarmAgentDispatcherTest.despacho_concurrente_respeta_limite_de_hilos()` |
| **Loops Infinitos de Subagentes** | Un subagente despacha otro subagente recursivamente sin fin. | `SwarmDepthSentinel` estricto con límite inmutable de profundidad 3. Si `depth > 3`, aborta inmediatamente. | `SwarmAgentDispatcherTest.depth_sentinel_bloquea_recursion_que_supere_nivel_3()` |
| **Inyección Cruzada de Prompts** | Un worker de búsqueda web ingiere un prompt malicioso y lo inyecta a un worker con permisos de SO. | `WorkerTaintCompartment`: cada worker tiene su propio `SessionTaintTracker` aislado. La Reina sintetiza, no ejecuta herramientas de SO con datos sin validar. | `SwarmTaintCompartmentalizationTest.worker_web_contaminado_no_propaga_taint()` |
| **Corrupción de Base de Datos ante Caídas** | La app se cierra o el proceso es eliminado por Android mientras 3 workers escriben en la pizarra. | SQLite en modo Write-Ahead Logging (WAL) con transacciones atómicas `insertWithOnConflict`. | `SwarmBlackboardLineageTest.escritura_concurrente_en_pizarra_no_produce_bloqueo()` |
| **Regresión en la Suite de Tests Actual** | La introducción del enjambre modifica contratos existentes de `McpRegistry` o `ToolBatchExecutor`. | Arquitectura aditiva: `SwarmOrchestratorMcpServer` se enchufa como un servidor MCP desacoplado sin tocar la lógica interna de herramientas previas. | Validación completa de `validate.ps1` (494 tests existentes intactos). |

---

## 5. Criterios de Aceptación para Despliegue en Producción

1. [ ] **Código Modular:** 100% de los nuevos componentes ubicados en el paquete `com.codex.chat.core.swarm.*` con responsabilidades únicas.
2. [ ] **TDD Estricto:** Cada fase debe contar con sus pruebas unitarias escritas, verificadas en rojo (RED) antes de implementar y en verde (GREEN) tras completar.
3. [ ] **Cero Regresiones:** Los 494 tests unitarios de la línea base deben permanecer en 0 fallos.
4. [ ] **Integración en ADR:** El registro de decisiones arquitectónicas (`manage_adr`) debe reflejar la evolución del enjambre en `codebase-memory-mcp`.
5. [ ] **Build y Verificación OTA:** El APK final compilado (`assembleRelease`) debe firmarse con el keystore de producción RSA-4096 y verificar su instalación sin errores.
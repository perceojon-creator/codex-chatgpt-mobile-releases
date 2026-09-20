# Plan de Implementación: Motor Autónomo 'Perseguir Objetivo' (Mobile Goal Engine & Goal Bar)
## Codex ChatGPT Mobile — v1.0.86+

> **Estado:** En ejecución  
> **Fecha:** 20 de Septiembre de 2026  
> **Arquitectura:** Paridad con DeepSeek Harness `@deepseek-ai/dsh-goal` y `@deepseek-ai/dsh-goal-round-driver`  
> **Línea Base:** 529 tests JVM passing (98 clases), 0 fallos  
> **Objetivo tras ejecución:** ~560 tests JVM passing, 0 fallos, bucle autónomo multi-ronda guiado por CAS  

---

## 1. Visión y Necesidad Técnica

### 1.1 El Problema
Actualmente, la interacción en la aplicación es estrictamente **unidireccional y de turno único**: el usuario envía un prompt, el modelo responde (ejecutando o no herramientas en streaming), y el ciclo se detiene.
Si el usuario plantea un objetivo complejo de larga duración (*'Refactoriza el módulo de red, escribe las pruebas unitarias y reintenta hasta que pasen sin errores'*), el usuario se ve obligado a escribir manualmente *'continúa'*, *'sigue'* o *'¿qué falta?'* en cada turno.

### 1.2 La Solución: 'Perseguir Objetivo' (Autonomous Goal Engine)
Implementar la arquitectura de **Objetivo Persistente** de DeepSeek Harness en Android:
1. **Máquina de estados duradera (`GoalEngine`):** Control optimista de concurrencia (CAS con revisiones numéricas `revision: Int`) y ciclo de vida formal (`ACTIVE`, `PAUSED`, `BLOCKED`, `COMPLETE`).
2. **Conductor de Rondas Autónomo (`GoalRoundDriver`):** Intercepta el evento de finalización del asistente (`onCompleteWithMetrics`). Si hay un objetivo activo y armado (`ARMED`), inyecta automáticamente el bloque `<goal_round>` en la conversación e inicia la siguiente ronda sin intervención humana.
3. **Herramientas MCP para el Modelo (`GoalMcpServer`):** Expone `create_goal`, `get_goal` y `update_goal` para que el modelo pueda gestionar su propio progreso, actualizar su revisión y marcar el objetivo como completado o bloqueado.
4. **Interfaz Reactiva 'Goal Bar' (UI en vivo):** Barra visual superior colapsable que muestra el objetivo actual, el contador de ronda (ej. *'🎯 Ronda 3/10'*), barra de progreso y botones rápidos de pausa/reanudación y cancelación.

---

## 2. Fases de Implementación y Arquitectura Modular

```
┌────────────────────────────────────────────────────────────────────────┐
│ FASE 0: Vocabulario de Dominio y Contratos (GoalModels.kt)             │
│   - GoalId, GoalPhase, GoalActivation, GoalRef, GoalSnapshot,          │
│     GoalBlockReason, GoalMutationResult                                │
│   - Test: GoalProtocolContractTest.kt                                  │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│ FASE 1: Máquina de Estados con CAS y Persistencia (GoalEngine.kt)      │
│   - Control optimista por revisiones numéricas                         │
│   - Persistencia SQLite/SharedPreferences (sobrevive a reinicios)      │
│   - Transiciones: create, edit, pause, resume, complete, block         │
│   - Test: GoalEngineCasStateTest.kt                                    │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│ FASE 2: Conductor Autónomo de Rondas (GoalRoundDriver.kt)              │
│   - Generación del prompt canónico <goal_round>                        │
│   - Detección de límite de rondas (código 'round-limit')               │
│   - Freno de emergencia (ESTOP) y pausa por interacción humana         │
│   - Test: GoalRoundDriverTest.kt                                       │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│ FASE 3: Servidor de Herramientas MCP (GoalMcpServer.kt)                │
│   - create_goal, get_goal, update_goal                                 │
│   - Registro en McpRegistry.kt y ToolRiskClassifier.kt                 │
│   - Test: GoalMcpServerTest.kt & ToolRiskClassifierTest.kt             │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│ FASE 4: Componente UI GoalBar y Enlace en MainActivity                 │
│   - GoalBarView / layout en activity_main.xml                          │
│   - Intercepción de fin de turno en onCompleteWithMetrics              │
│   - Comandos slash: /goal, /pause-goal, /resume-goal, /cancel-goal     │
│   - Test: GoalUiStateTest.kt                                           │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
┌───────────────────────────────────▼────────────────────────────────────┐
│ FASE 5: Integración End-to-End, Compilación Release y Validación Total │
│   - Test E2E: GoalAutonomousLoopIntegrationTest.kt                    │
│   - Validación con validate.ps1 (>= 560 tests passing, 0 fallos)       │
│   - Compilación assembleRelease y verificación criptográfica           │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Detalle de Pruebas Unitarias por Fase (TDD Estricto)

### Fase 0: `GoalProtocolContractTest.kt`
- `invariantes_de_goal_snapshot_retienen_campos_inmutables()`
- `transiciones_de_fase_permiten_active_paused_blocked_complete()`
- `bloqueo_requiere_codigo_kebab_case_y_mensaje()`
- `revision_inicial_es_uno_y_se_incrementa_monotonamente()`

### Fase 1: `GoalEngineCasStateTest.kt`
- `creacion_de_objetivo_inicializa_revision_1_y_fase_active()`
- `update_goal_falla_si_la_revision_no_coincide_cas()` (Prevención de colisiones)
- `update_goal_completa_satisfactoriamente_con_revision_correcta()`
- `pausar_y_reanudar_actualiza_activacion_y_revision()`
- `limite_de_rondas_bloquea_objetivo_automaticamente()`

### Fase 2: `GoalRoundDriverTest.kt`
- `render_prompt_genera_estructura_xml_goal_round_exacta()`
- `driver_admite_siguiente_ronda_cuando_esta_armado()`
- `driver_se_desarma_si_el_usuario_envia_mensaje_manual()`
- `driver_no_admite_ronda_si_se_alcanzo_max_goal_rounds()`

### Fase 3: `GoalMcpServerTest.kt`
- `create_goal_mcp_retorna_ref_con_id_y_revision()`
- `get_goal_mcp_refleja_el_estado_actual_de_la_sesion()`
- `update_goal_mcp_permite_al_modelo_marcar_complete()`
- `clasificacion_de_riesgo_goal_tools_es_fail_closed()`

### Fase 4 y 5: `GoalAutonomousLoopIntegrationTest.kt`
- Simulación de bucle de 3 rondas autónomas: creación ➔ ejecución de herramientas ➔ inyección automática de ronda 2 ➔ llamada a `update_goal(complete)` ➔ finalización limpia con Goal Bar oculta.

---

## 4. Criterios de Aceptación
1. Cero regresiones: 529 tests existentes intactos.
2. Al menos 25 tests nuevos cubriendo el motor de objetivos.
3. Control de concurrencia optimista (CAS) que impide condiciones de carrera.
4. Aislamiento estricto y compatibilidad con el Enjambre de Agentes (Fases previas).
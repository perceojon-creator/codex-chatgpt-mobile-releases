### 🛑 Versión 1.0.92 — Corrección del Bloqueo Permanente por Parada de Emergencia (ESTOP)

Esta versión resuelve la causa raíz por la cual salía 'Parada de Emergencia' y el agente no continuaba:

#### 🛠️ Causa Raíz y Solución:
- **Desacoplamiento de Cierre de Servicio y ESTOP**:
  - `ScreenCaptureService.onDestroy()` y la acción de parada invocaban erróneamente `activeLoop?.abort()`, lo que activaba el centinela global `EstopSentinel.engage()` y guardaba un archivo persistente `ESTOP` en disco.
  - Al crearse una nueva sesión de agente, `EstopSentinel.isEngaged()` leía el archivo del disco y abortaba la tarea antes de empezar con 'Aborted: ESTOP active before start'.
  - Se implementó `AutonomousAgentLoop.stop()` para paradas y destrucciones normales de servicio sin activar el cerrojo de emergencia.
- **Desactivación Automática al Iniciar Nueva Tarea**:
  - Al pulsar 'Start Agent' en el Bottom Sheet, se invoca explícitamente `EstopSentinel.disengage()`, limpiando cualquier bloqueo previo en memoria y en disco para que el nuevo objetivo del usuario se ejecute de inmediato.
- **Botón de Pánico (ESTOP) Preservado**:
  - El botón rojo de STOP en el overlay flotante sigue funcionando como interruptor de emergencia inmediato cuando el usuario lo presiona activamente.
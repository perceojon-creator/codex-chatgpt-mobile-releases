### 🛡️ Versión 1.0.94 — Blindaje contra Paradas de Emergencia Inesperadas ("Suicide Tap" & MediaProjection)

Esta versión resuelve a fondo la investigación técnica sobre por qué salía 'Parada de Emergencia' de forma inesperada (particularmente al reproducir contenido o interactuar en pasos avanzados):

#### 🔍 Causas Raíz Investigadas y Neutralizadas:
1. **Blindaje contra "Suicide Tap" (Toques Sintéticos sobre el Overlay)**:
   - Se añadió el flag `isDispatchingGesture` en `CodexAccessibilityService`. Si un gesto generado por la IA coincide físicamente con el botón STOP del overlay flotante, **es ignorado completamente**. Solo toques de dedos humanos reales pueden activar el ESTOP.
2. **Aislamiento de Jerarquía UI**:
   - En `CodexAccessibilityService.dumpUiHierarchy()`, se filtran todos los nodos pertenecientes a `com.codex.chat`. La IA ya no ve su propio botón de STOP en la jerarquía, impidiendo que lo seleccione como objetivo de toque.
3. **Desacoplamiento de SecurityException y ESTOP**:
   - Se eliminó la captura de `SecurityException` genéricas de Android como paradas de emergencia en `AutonomousAgentLoop`. Fallos del sistema operativo ya no activan la alarma roja de ESTOP.
4. **Instrucción de Captura de Pantalla Completa**:
   - Se añade recordatorio visual para seleccionar **'Compartir toda la pantalla'** en Android 14+; la opción por defecto 'Compartir una sola app' suspende la proyección en cuanto se cambia a YouTube.
5. **Auto-Cierre y Transición Verde en Éxito**:
   - Al terminar de reproducir la música, el overlay cambia a verde ('FINALIZAR') y desaparece solo en 4-5 segundos.
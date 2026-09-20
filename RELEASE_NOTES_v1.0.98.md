### 🪟 Versión 1.0.98 — Overlay Flotante de Control Activo durante Conversación MCP

Esta versión garantiza que el overlay flotante (ChatHead con telemetría en vivo y botón rojo de STOP) aparezca automáticamente en pantalla cuando el modelo del chat ejecute herramientas de control de pantalla:

#### 🎯 Comportamiento Visual Integrado:
1. **Overlay Flotante Automático en Pantalla**:
   - En cuanto el modelo en el chat comienza a operar el teléfono (`mobile_click`, `mobile_swipe`, `mobile_get_screen`, etc.), la app se minimiza y el overlay flotante aparece inmediatamente sobre la pantalla de Android.
2. **Telemetría en Vivo de Pensamiento y Acciones**:
   - Muestra en tiempo real:
     - El pensamiento del modelo (`💭...`).
     - La acción que se está ejecutando (`🛠️ Tocando pantalla...`, `✍️ Escribiendo texto...`).
     - El número de paso (`Paso 1`, `Paso 2`).
     - El botón rojo **STOP (Parada de Emergencia)** para que el usuario siempre mantenga el control total y pueda frenar al agente en cualquier instante.
3. **Cierre Elegante al Terminar**:
   - Al terminar de operar (cuando la música empieza a sonar o la tarea concluye), el overlay cambia a verde (`✓ Objetivo Completado`) y se cierra automáticamente tras 5 segundos.
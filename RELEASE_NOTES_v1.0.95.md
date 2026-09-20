### 🔌 Versión 1.0.95 — Conector Nativo MCP de Control Móvil (Mobile Use en el Chat)

Esta versión revoluciona la interacción con el Agente Autónomo integrándolo como un servidor nativo de Model Context Protocol (`MobileUseMcpServer`) en la lista de Conectores/MCP del APK, utilizable directamente desde el chat conversacional:

#### 🚀 Novedades y Arquitectura Unificada:
- **Control Móvil Nativo vía MCP (`MobileUseMcpServer`)**:
  - Nuevo servidor MCP integrado con 6 herramientas: `mobile_get_screen`, `mobile_click`, `mobile_swipe`, `mobile_type`, `mobile_press_key` y `mobile_wait`.
  - El usuario puede pedirle en el chat normal cualquier tarea (*"Abre YouTube y pon música"*) y el modelo llamará a las herramientas táctiles de forma conversacional.
- **Aislamiento Sensorial (Cero Saturación de Fotos en el Chat)**:
  - Las capturas de pantalla Base64 se envían en privado directamente al contexto visual del modelo LLM.
  - En la interfaz del chat del usuario se visualizan únicamente el bloque colapsable de razonamiento (`💭 Proceso de razonamiento`) y los chips de acción ejecutada (`🛠️ mobile_click`), manteniendo la conversación limpia.
- **Pre-Armado en Ajustes y Conectores MCP**:
  - Al activar el interruptor de 'Mobile Use' en el Administrador MCP, se pre-arma el servicio en primer plano y se gestionan los permisos una sola vez.
- **Minimización Automática y Retorno**:
  - Al invocar herramientas de pantalla, el chat se minimiza automáticamente (`moveTaskToBack`) para despejar la pantalla del objetivo y se despliega la telemetría flotante con botón de seguridad ESTOP.
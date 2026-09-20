### 🤖 Versión 1.0.91 — Fix Crítico de Crashes en Android 14+ y Validación Empírica en Emulador

Esta versión resuelve todos los fallos detectados y validados empíricamente en emulador Android (Pixel con Android 14/15, API 35), logrando que el Agente Autónomo abra YouTube y opere apps externas sin ningún crash ni suspensión.

#### 🛠️ Correcciones Críticas Validadas:
- **MediaProjection Callback Obligatorio (Android 14+)**:
  - Implementación de MediaProjection.registerCallback() antes de createVirtualDisplay(), eliminando el crash fatal IllegalStateException: Must register a callback before starting capture.
- **Orden de Foreground Service MediaProjection**:
  - Llamada estricta a startForeground(NOTIF_ID, notification, FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) ANTES de solicitar el token en MediaProjectionManager.getMediaProjection(), eliminando el crash SecurityException: Media projections require a foreground service of type ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION.
- **ContextThemeWrapper para Overlay Flotante**:
  - Se envuelve el contexto en ContextThemeWrapper(context, R.style.Theme_ChatGPTCustom) en FloatingAgentOverlay, resolviendo el fallo InflateException: The style on this component requires your app theme to be Theme.MaterialComponents al inflar MaterialCardView desde el contexto de la aplicación.
- **Identificadores Reales de Modelos del Proxy**:
  - Mapeo exacto hacia CLIProxyAPI: gemini-3.8-flash-high, glm-5.3-flash y claude-3-7-sonnet-20250219.
- **Fallback Automático de Clave API**:
  - Uso transparente de la clave por defecto proxy-pool si no hay clave configurada en ajustes.
- **Inspección Resiliente de Jerarquía UI**:
  - Fallback a ventanas interactivas activas en dumpUiHierarchy() para capturar cuadros de diálogo del sistema y permisos modales.
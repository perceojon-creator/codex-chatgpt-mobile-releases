# Release v1.0.73: Conectores IA (Google Flow - Imagen 3.1 & Veo 3.1)

### 🔌 Nuevo Hub Extensible de Conectores IA:
- **Google Flow como Primer Conector Oficial**:
  - Arquitectura desacoplada en `MediaConnectorModels.kt`, `MediaConnectorClient.kt` y `MediaConnectorManager.kt`.
  - Diseñado como hub extensible (`ConnectorProvider`) que albergará múltiples proveedores IA a futuro.
- **Generación de Imágenes**:
  - Endpoint REST `POST /v1/images/generations` (soporte para `imagen-3.1`, `imagen-3`, `imagefx`).
  - Extracción automática de formatos OpenAI (`data[].url`, `data[].b64_json`) y Google ImageFX (`candidates`).
- **Generación de Videos**:
  - Endpoint REST `POST /v1/videos/generations` (soporte para `veo-3.1`, `veo-2`, `videofx`).
  - Extracción de URLs de video reproducibles con controles HTML5.

### 🎨 Integración en la Interfaz de Usuario (UI/UX):
- **Menú '+' en ChatGPT Normal y Codex PC**:
  - Acceso directo a "Conectores IA (Google Flow)" desde ambos modos.
  - Bottom Sheet dedicada con chips de selección de modelos, chips de sugerencias rápidas ("un mono bailando"), previsualización y switch para fijar el conector.
  - Barra flotante de estado (`activeConnectorBar`) sobre el input de chat.
- **Renderizado Nativo en Chat**:
  - Markdown integrado con `VisualMediaParser`, permitiendo visualización interactiva, zoom, descarga y compartición de medios generados.
- **Comandos Slash**:
  - `/connectors`, `/flow`, `/imagen`, `/veo` con autocompletado en tiempo real.

### 📊 Verificación y Métricas:
- **Pruebas unitarias al 100%** (`MediaConnectorTest` y suite completa `testDebugUnitTest`).
- **Compilación release optimizada con R8 / Proguard**.

**SHA-256 (Release APK):** `C9536F2E5D3F86EBEF0A31456B41C6F3630D8F2F9A7AABBA118350046327C309`  
**Tamaño:** 3.278.255 bytes (~3,13 MB)

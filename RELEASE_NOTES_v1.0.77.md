# Release Notes v1.0.77 - Visualización de Créditos en Tiempo Real y Matriz de Costes Google Flow

## 💎 Novedades Principales

### 1. Saldo de Créditos en Tiempo Real (Google Flow Live Metrics)
- **Monitoreo Continuo**: Integración con el RPC de batchexecute de Google Flow (`nzlxg` / `/VideoFxService.GetCredits`), sincronizando el saldo restante y el cupo total (`credits_remaining` / `credits_total`).
- **Badge Dinámico en Barra Activa**: Cuando el conector Google Flow está fijado, se muestra el indicador en vivo `💎 X/Y cr` en la parte superior del chat.
- **Tarjeta de Saldo en Bottom Sheet**: Vista detallada con estado de conexión, cuenta vinculada (`perceojon@gmail.com`), y botón de actualización manual (`🔄 Actualizar`).

### 2. Desglose de Coste por Generación (Badges en Mensajes del Asistente)
- Cada respuesta generada mediante el conector multimedia adjunta automáticamente el coste debitado y el saldo resultante:
  - **Imágenes**: `> 💎 **Coste:** -1.0 cr | **Saldo Restante:** X/Y cr | **Cuenta:** perceojon@gmail.com`
  - **Videos**: `> 🎬 **Coste:** -5.0 cr | **Saldo Restante:** X/Y cr | **Cuenta:** perceojon@gmail.com`

### 3. Matriz de Precios y Costes por Modelo (Desplegable Interactivo)
- Tarjeta colapsable dentro del selector de conectores con las tarifas oficiales de Google Flow:
  - **Imágenes (Imagen 3.1 & ImageFX)**:
    - Generación (1024x1024): 1.0 crédito
    - Edición / Inpainting: 1.0 crédito
    - Upsample 2K / 4K: 1.0 crédito
  - **Videos (Veo 3.1 Family)**:
    - Veo 3.1 Lite (4s / 6s / 8s): 5 créditos
    - Veo 3.1 Lite (8s Low Priority): 0 créditos (¡Gratis!)
    - Veo 3.1 Fast (4s / 6s / 8s): 10 créditos
    - Veo 3.1 Quality (1080p): 100 créditos
  - **Videos (Omni 1.1 Flash)**:
    - 360p: 4s = 4 cr | 6s = 5 cr | 8s = 6 cr | 10s = 7 cr (Inpainting = 10 cr)
    - 720p: 4s = 7 cr | 6s = 10 cr | 8s = 12 cr | 10s = 15 cr (Inpainting = 20 cr)
  - **Upsamplers de Video**:
    - Video 1080p: 0 créditos (Gratis)
    - Video 4K: 50 créditos

---
**Build**: 78 | **Versión**: 1.0.77 | **APK**: Codex-ChatGPT-Mobile.apk

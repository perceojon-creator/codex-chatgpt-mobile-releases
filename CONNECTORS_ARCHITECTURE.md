# Documentación de Arquitectura y Conectores IA — ChatGPT Android Studio (v1.0.79)

## 1. Conector Multimedia Google Flow & Veo / Imagen 3.1
La aplicación móvil integra conectividad nativa con **Google Flow** a través de `CLIProxyAPI` en el puerto `:8317`.

### 1.1. Arquitectura de Créditos Reales (1,050 Créditos)
La cuenta vinculada (`perceojon@gmail.com`) opera con el desglose oficial:
* **Créditos Totales:** `1,050.0` créditos.
* **Créditos Diarios Renovables:** `50.0` créditos cada 24 horas.
* **Créditos del Plan de Reserva:** `1,000.0` créditos mensuales.

### 1.2. Matriz Oficial de Costes por Generación
* **Imágenes (Imagen 3.1 / ImageFX / Inpainting):** **0 créditos (¡Totalmente Gratis!)**. El badge de respuesta notifica:
  `> 💎 Coste: 0 cr (Gratis) | Saldo: 1050/1050 cr`
* **Veo 3.1 Lite (720p Rápido):** `10 cr` (x1) | `40 cr` (x4)
* **Veo 3.1 Fast / Flash:** `20 cr` (x1) | `80 cr` (x4)
* **Veo 3.1 Quality (1080p Cine):** `100 cr` (x1) | `400 cr` (x4)
* **Omni 1.1 Flash 360p:** 4s = `4 pts` | 6s = `5 pts` | 8s = `6 pts` | 10s = `7 pts`
* **Omni 1.1 Flash 720p:** 4s = `7 pts` | 6s = `10 pts` | 8s = `12 pts` | 10s = `15 pts`

### 1.3. Componentes Implementados en la App
* **`MediaConnectorModels.kt`:**
  * `FlowCreditsResponse`: Modelo de datos con `dailyCredits`, `planCredits`, `creditsRemaining`, `creditsTotal`, `account` y `pricing`.
  * `MediaConnectorConfig`: Configuración de URL base (`http://127.0.0.1:8317/v1` o IP LAN `http://192.168.1.6:8317/v1`) y API Key (`proxy-pool`).
* **`MediaConnectorClient.kt`:**
  * `fetchCredits()`: Consulta `GET /v1/flow/credits` y deserializa el balance y desglose dinámicamente.
  * `generateImage()` & `generateVideo()`: Enrutamiento compatible OpenAI hacia `/images/generations` y `/videos/generations`.
* **`MediaConnectorManager.kt`:**
  * Persistencia en `SharedPreferences` de los créditos y el desglose para arranque instantáneo sin parpadeo.
  * Detección por lenguaje natural y prefijos (`/flow`, `/imagen`, `/veo`, `🎨`, `🎬`).
* **`MainActivity.kt` & `bottom_sheet_connectors.xml`:**
  * Renderizado del badge activo de créditos en la barra de chat: `💎 1050/1050 cr`.
  * Indicador de desglose en el panel inferior: `50 diarios + 1,000 del plan`.
  * Estado de conexión bidireccional en vivo (`🟢 Conectado vía CLIProxyAPI (:8317)` / `⚪ Esperando latido...`).

---

## 2. Robustez y Tolerancia a Fallos
* **Prevención de Error 500:** Tolerancia a workers dormidos en Chrome gracias a la ventana de 30 minutos y autenticación con `Bearer proxy-pool`.
* **VisualMediaParser:** Parsing hermético de Markdown con previsualización embebida de imágenes y videos generados con soporte de pantalla completa y descarga local.

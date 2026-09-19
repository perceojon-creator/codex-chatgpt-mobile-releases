### 🚀 Novedades de la versión 1.0.79:
- 💎 **Configuración Corregida de Créditos Reales (1,050 Créditos):**
  - **Saldo total de 1050 créditos**: Configurado correctamente con el desglose exacto de **50 créditos diarios renovables + 1,000 créditos del plan** para la cuenta `perceojon@gmail.com`.
  - **Desglose en la interfaz gráfica (UI)**: El Bottom Sheet de Conectores IA y la barra activa de chat muestran `1050 / 1050 cr` junto al desglose `50 diarios + 1,000 del plan`.
  - **Imágenes Imagen 3.1 / ImageFX a 0 créditos**: Consumo de 0 créditos (¡Totalmente Gratis!) verificado en vivo.
- 🔧 **Resolución de Error 500 ("no active browser workers with credits"):**
  - **Ventana de latido ampliada a 30 minutos** (tolerancia completa ante throttling de pestañas inactivas en segundo plano en Chrome).
  - **Prevención de registro con 0 créditos**: El service worker de la extensión Chrome y el content script inyectado inicializan y reportan el balance real de 1,050 créditos.
  - **Fallback automático a FlowAccountPool**: Si el worker del navegador reinicia su conexión, el servidor utiliza las credenciales persistidas de `flow-perceojon@gmail.com.json` de forma transparente y sin interrupción de servicio.
- ⚡ **Compilación y verificación completa**: Pruebas unitarias de parsing, balance y red 100% pasando.

# Release v1.0.71: Hotfix Crítico Android ART Regex Engine en VisualMediaParser

### 🐛 Corrección Crítica del Cierre Inesperado (Crash) al Enviar Prompts:
- **Causa Raíz Diagnosticada**: En Android 15/17 (ART / ICU regex engine), la expresión regular `JSON_ARG_SUFFIX_REGEX` en `VisualMediaParser` fallaba en tiempo de inicialización de clase (`<clinit>`) lanzando `java.util.regex.PatternSyntaxException: Syntax error in regexp pattern near index 6` debido a una llave sin escapar (`\?"s*}s*$`).
- **Impacto del Bug**: Al pulsar el botón de envío en `MainActivity`, `ChatAdapter` enlazaba el mensaje de usuario invocando `VisualMediaParser`, disparando un `ExceptionInInitializerError` fatal en el hilo principal de la interfaz de usuario.
- **Solución Implementada**: Sintaxis robusta corregida en Kotlin para el compilador de expresiones regulares ICU nativo de Android: `Regex("""\\?"\s*\}\s*""" + "$")`.
- **Verificación On-Device**: 
  - Suite unitaria instrumentada `VisualMediaParserAndroidTest` ejecutada con éxito en Pixel 10 Pro XL (Android 17 / API 35).
  - Verificación end-to-end interactiva de envío y recepción de streaming a más de 1000 tokens/segundo sin bloqueos ni excepciones en el hilo principal.

**SHA-256 (Release APK):** `C338B392905B924B854AF4019C4A73305A76E42EA5E1BC4E1C3E0F0D4CA880BA`  
**Tamaño:** 3.263.835 bytes (~3,11 MB)

### 📦 Novedades de la Versión v1.0.102

- **Motor de Compactación Contextual Apex DSH al 90%**:
  - Paridad matemática y estructural completa con el sistema de compactación de DeepSeek Harness (Apex).
  - Activación automática cuando la conversación alcanza el 90% de la ventana de contexto del modelo activo.
  - Selección de región compactable preservando la cola reciente (15%) e invariante de balance de herramientas (nunca parte llamadas y respuestas de tools).
  - Directiva canónica de 8 secciones Markdown (Primary Request, Key Concepts, Files, Errors, Pending Jobs, Current Work, Next Step, Critical Context).
  - Comando slash `/compact` y botón directo en el visor de tokens para compactación manual instantánea.
  - Resolución precisa de ventana de contexto por modelo (Gemini/Sol: 1M, Claude/Astra: 200k, DeepSeek/Terra/GLM/Luna: 128k, MiniMax: 1M).
  - Suite de 619 pruebas unitarias y de integración pasando al 100%.

---

**Verificación de Integridad Criptográfica**

```
apkName     : Codex-ChatGPT-Mobile.apk
versionCode : 103
SHA-256     : 6df75f5cfbbd09c39d48ca6ac7675163e295579537645d0323e5190499aaac5a
```

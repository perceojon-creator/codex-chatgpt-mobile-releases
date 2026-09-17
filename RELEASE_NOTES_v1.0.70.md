Release v1.0.70: Optimizacion de Alto Rendimiento, Erradicacion de State Tearing y Suite de Benchmarks Anti-Regresiones.

### Novedades y Mejoras Principales:
- 🚀 **Erradicacion de State Tearing en UI Streaming**: Snapshot compuesto atomico `StreamSnapshot(content, reasoning, version)` en `StreamBuffer` con versionado monotonico para consistencia temporal libre de cerrojos.
- ⚡ **Aislamiento de Parsers Pesados**: Enrutamiento granular mediante `StreamingTextPayload` en `ChatAdapter`, evitando reconstruir parsers pesados (`VisualMediaParser`, bloques de codigo) durante deltas de streaming de alta frecuencia.
- 📉 **Coalescencia Fluida a 30 FPS**: Limitador reactivo de eventos a ~33 ms y scroll inteligente desacoplado con deteccion de proximidad (`canScrollVertically(1)`).
- 🧠 **Parser SSE Zero-Allocation con DFA**: Extractor directo de tokens de contenido plano sin alocar arboles AST de `JSONObject` completos.
- 🗄️ **Almacenamiento JSON Particionado de Alta Concurrencia**: Arquitectura de archivos de sesion aislados con proteccion atomica NIO, sincronizacion fisica a disco (`fd.sync()`) y migrador en streaming no destructivo con proteccion anti-sobrescritura.
- 🔓 **SQLite WAL Multi-Lector Concurrente**: Desbloqueo de lecturas simultaneas en `MemorySqliteStore` (FTS5) en paralelo sin esperas bloqueantes.
- ⏱️ **Desbloqueo Head-of-Line (HoL) en MCP**: Ejecucion de herramientas en corrutinas asincronas con callbacks progresivos independientes en `ToolBatchExecutor`.
- 🛠️ **Suite de Benchmarks TUI Integrada en el APK**: Consola nativa para ejecucion de pruebas de rendimiento y estres fisico:
  * `DATA_INTEGRITY_STRESS`: 500 escrituras y fsync atomicos concurrentes (202 ms).
  * `STORAGE_CUTOVER_MIGRATION`: Migracion no destructiva de 100 sesiones (2.683 ms).
  * `CONCURRENCY_AND_MCP_STRESS`: 30 tools MCP concurrentes y 100 lecturas WAL (121 ms).
  * `UI_RENDERING_AND_STREAMING`: 200 deltas de texto a 100 tps sin tearing (2.505 ms).
  * `CHAOS_KILL_AND_CORRUPTION`: 100 cortes abruptos durante escritura sin archivos de 0 bytes (1.868 ms).

**SHA-256 (Release APK):** `7F29C890652578C8285D0CE6B9A12C02C104A0B8C65B2FB9450B12D89FB0A7D7`
**Tamano:** 3.263.839 bytes (~3,11 MB)

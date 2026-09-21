### ⚡ Novedades de la Versión v1.0.105

- **Validación y Soporte de Hiperconcurrencia de Herramientas (10 a 20 Simultáneas)**:
  - Arquitectura híbrida particionada (**PTC / Tool Batching**) inspirada en Codex Apex y DeepSeek Harness.
  - **Llamadas Concurrentes Seguras**: Las herramientas de solo lectura y cómputo puro (`get_battery_status`, `read_file`, `list_files`, `search_memory`, `mobile_get_screen`, `web_search`, etc.) se ejecutan simultáneamente en paralelo en un pool de hasta **20 hilos**.
  - **Llamadas Secuenciales con Verificación**: Las herramientas que mutan estado o ejecutan acciones táctiles (`write_file`, `delete_file`, `send_sms`, `mobile_click`, `mobile_swipe`, `mobile_type`, etc.) se ejecutan de forma aislada, una a una, evaluando la puerta de seguridad (`ToolApprovalGate`) y el centinela ESTOP antes de cada acción.
  - **Cero Head-of-Line Blocking**: Entrega progresiva en tiempo real; las herramientas rápidas se muestran de inmediato en la UI sin esperar a las lentas.
  - **Preservación Estricta de Orden Posicional**: Respeta el array indexado original requerido por la especificación OpenAI function-calling.
  - Suite de **630 pruebas unitarias y de integración pasando al 100%**.

---

**Verificación de Integridad Criptográfica**

```
apkName     : Codex-ChatGPT-Mobile.apk
versionCode : 106
SHA-256     : bf0cad2a58dfaf7f47804d1ff5f7a40a9e3b620b8b9b1a039bf8520a6013f864
```

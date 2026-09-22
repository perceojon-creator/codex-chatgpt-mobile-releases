### 🐧 Novedades de la Versión v1.0.106

- **Servidor Nativo Termux MCP Integrado (`TermuxMcpServer`)**:
  - Conexión nativa bidireccional entre ChatGPT y el subsistema Linux de Termux en el dispositivo Android.
  - **Doble Vía de Comunicación**:
    1. **Bridge HTTP Local Loopback** (`http://127.0.0.1:8080/execute`): Respuesta instantánea sin levantar ventanas.
    2. **IPC Oficial Termux** (`com.termux.RUN_COMMAND`): Despacho en segundo plano vía `com.termux.app.RunCommandService`.
  - **5 Herramientas MCP Nativas para ChatGPT**:
    - `termux_execute_command`: Ejecuta comandos y pipelines Bash en Termux (Python, Pip, Git, Curl, Ffmpeg, scripts).
    - `termux_read_file`: Lee archivos de `$HOME` o almacenamiento compartido.
    - `termux_write_file`: Crea y modifica scripts ejecutables (`chmod +x`).
    - `termux_pkg_install`: Instala paquetes oficiales con `pkg install -y`.
    - `termux_get_environment`: Telemetría de instalación, arquitectura, rutas y estado del bridge.
  - Permiso oficial agregado al Manifest: `com.termux.permission.RUN_COMMAND` y visibilidad en `<queries>`.
  - Suite de **654 pruebas unitarias y de integración pasando al 100%**.

---

**Verificación de Integridad Criptográfica**

```
apkName     : Codex-ChatGPT-Mobile.apk
versionCode : 107
SHA-256     : ab8ff387f81f21fda479b0b04c28534baa8acd237bbca75d5644f866cf3fb0a2
```

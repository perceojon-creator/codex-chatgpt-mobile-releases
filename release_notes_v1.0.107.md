### 🐧 Novedades de la Versión v1.0.107

- **Soporte Nativo Bidireccional Termux MCP Embebido**:
  - Compilado y publicado **`Termux-MCP.apk`** con servidor nativo MCP (`127.0.0.1:8080/mcp` y `:8383`).
  - ChatGPT se conecta directamente a Termux vía HTTP loopback de ultra-baja latencia e Intent IPC oficial (`com.termux.RUN_COMMAND`).
  - **Validación del Flujo Completo Real en Emulador**:
    1. Instalación automática de paquetes (`python-yt-dlp`, `faad2`, `lame`).
    2. Descarga del video de YouTube en segundo plano vía `yt-dlp`.
    3. Conversión de alta fidelidad a audio **MP3** (44.1 kHz estéreo a 128 kbps).
    4. Guardado directo y verificado en la carpeta pública `/sdcard/Download/Me at the zoo.mp3`.
  - **10 Herramientas MCP Nativas**:
    - `termux_execute_bash` / `termux_execute_command`: Ejecución de comandos y scripts Bash.
    - `termux_read_terminal_screen`: Captura en vivo del texto visible en la pantalla de Termux.
    - `termux_send_keys`: Enlace interactivo con teclas especiales (CTRL_C, ENTER, TAB).
    - `termux_list_sessions` / `termux_create_session`: Gestión multisesión de pestañas.
    - `termux_pkg_manage` / `termux_pkg_install`: Gestión de paquetes Linux.
    - `termux_read_file` / `termux_write_file`: Manipulación de archivos y scripts con chmod +x.
    - `termux_get_environment`: Telemetría del entorno.
  - **654 pruebas unitarias y de integración pasando al 100%**.

---

**Verificación de Integridad Criptográfica**

```
apkName     : Codex-ChatGPT-Mobile.apk
versionCode : 108
SHA-256     : c9923b24034c9fd39a29c6c59795b4312109a290fc4f4356715df3c43bb1ba88
```

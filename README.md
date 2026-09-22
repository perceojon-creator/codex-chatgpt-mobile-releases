# Codex-ChatGPT Mobile — Canal Oficial de Releases & OTA

[![Releases](https://img.shields.io/github/v/release/perceojon-creator/codex-chatgpt-mobile-releases?color=10A37F&label=Latest%20Release)](https://github.com/perceojon-creator/codex-chatgpt-mobile-releases/releases/latest)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20%28API%2026--35%29-blue)](#)
[![Security](https://img.shields.io/badge/Security-CaMeL%20IFC%20%7C%20ESTOP-green)](#)
[![MCP](https://img.shields.io/badge/MCP-Native%20Termux%20%2B%20Mobile%20Use-orange)](#)

Repositorio oficial público para la distribución y actualizaciones OTA (*Over-The-Air*) automáticas de **Codex-ChatGPT Mobile** y complementos nativos para Android.

---

## 📥 Descargas Oficiales (Versión v1.0.107)

| Artefacto | Versión | Tamaño | SHA-256 | Enlace de Descarga |
| :--- | :--- | :--- | :--- | :--- |
| **Codex-ChatGPT-Mobile.apk** | `v1.0.107` (Code 108) | 3.42 MB | `C9923B24034C9FD39A29C6C59795B4312109A290FC4F4356715DF3C43BB1BA88` | [Descargar APK](https://github.com/perceojon-creator/codex-chatgpt-mobile-releases/releases/download/v1.0.107/Codex-ChatGPT-Mobile.apk) |
| **Termux-MCP.apk** | `v0.118.0` (Universal) | 119 MB | Bootstrap Linux Completo | [Descargar Termux MCP](https://github.com/perceojon-creator/codex-chatgpt-mobile-releases/releases/download/v1.0.107/Termux-MCP.apk) |

---

## 🚀 Novedades Destacadas

- **Integración Nativa Termux MCP**: Control bidireccional de consola Linux nativa en el móvil. ChatGPT puede ejecutar scripts, instalar paquetes (`yt-dlp`, `ffmpeg`, `python`), leer la pantalla de la terminal y gestionar archivos.
- **Motor de Compactación Contextual Apex DSH (90%)**: Auto-compactación al 90% de la ventana de contexto de cada modelo del proxy preservando cola reciente e invariante de herramientas.
- **Límite de Pasos a 1 Millón**: El bucle agéntico autónomo y los turnos encadenados admiten hasta 1,000,000 de pasos para tareas continuas.
- **Canal de Actualización OTA Seguro**: Verificación criptográfica obligatoria con hash SHA-256 antes de instalar paquetes mediante FileProvider.

---

## 🔄 Canal de Actualizaciones OTA

La aplicación consulta automáticamente este repositorio a través del endpoint oficial:
```
https://api.github.com/repos/perceojon-creator/codex-chatgpt-mobile-releases/releases/latest
```

Cualquier nueva versión publicada en este repositorio se despliega de forma inmediata a todos los dispositivos activos.


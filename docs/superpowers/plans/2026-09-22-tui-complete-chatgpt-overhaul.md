# Plan de Implementacion: Overhaul Integral TUI ChatGPT Mobile + Boton Detener/Pausa

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implementar el conjunto completo de mejoras visuales TUI y funcionales de ChatGPT oficial: (1) Boton de envio que muta a boton Detener/Pausar (Stop) durante la generacion, (2) Empty State interactivo con sugerencias Starter Prompts, (3) Overlay de Horizon Orb vinculado a btnMic, (4) Cursor pulsante de streaming (DILStreamingAnimation), (5) Bloques de codigo con cabecera de lenguaje y copia rapida, (6) Micro-feedback haptico del sistema, y (7) Publicacion dual automatica a GitHub.

**Architecture:** Arquitectura reactiva sobre Android Jetpack:
- MainActivity.kt gestiona el ciclo de vida del boton de envio/detener, controlando la cancelacion directa de activeCall?.cancel() en OkHttp.
- layout_chat_empty_state.xml centrado sobre el RecyclerView de mensajes con animaciones Motion.fadeIn/fadeOut basadas en el tamano de la lista.
- HorizonOrbView con shaders GLSL integrado en un modal translucido sobre el microfono.
- ChatAdapter.kt enriquecido con cursor de streaming y renderizado de bloques de codigo.

**Tech Stack:** Kotlin, Android Views/Material3, OpenGL ES 2.0 (Horizon Orb), OkHttp 4.12, Okio.

**Spec:** Especificacion extraida del APK oficial com.openai.chatgpt_1.2026.251 (Valdi framework & shaders Horizon).

## Global Constraints
- Nivel de API: Min SDK 26, Target SDK 35.
- Tipografias: @font/soehne (titulos/chips), @font/menlo (codigo), @font/inter_regular (cuerpo).
- Colores: OLED Dark (#0D0D0D base, #181818 superficie, #10A37F acento OpenAI, #2F2F2F botones/capsula).
- Al finalizar, SIEMPRE compilar release con R8 y publicar a GitHub publico y privado (bump_and_build.py --publish).

---

### Task 1: Boton de Envio Dinamico (Morphing a Detener/Pausar con Cancelacion Activa de Stream)

- [ ] Step 1: Escribir test unitario para la logica de alternancia del boton Stop/Send
- [ ] Step 2: Ejecutar test para verificar pase inicial de la logica
- [ ] Step 3: Crear recursos graficos para el boton Stop de ChatGPT
- [ ] Step 4: Integrar control en MainActivity.kt
- [ ] Step 5: Compilar y verificar con test unitario
- [ ] Step 6: Commit

---

### Task 2: Estado Inicial (Empty State) con Starter Prompts

- [ ] Step 1: Escribir test para el layout de Empty State
- [ ] Step 2: Ejecutar test para verificar fallo
- [ ] Step 3: Crear layout_chat_empty_state.xml y acoplarlo a activity_main.xml
- [ ] Step 4: Conectar listeners y visibilidad dinamica en MainActivity.kt
- [ ] Step 5: Ejecutar test para verificar pase
- [ ] Step 6: Commit

---

### Task 3: Integracion del Horizon Orb Holografico en Modo de Voz

- [ ] Step 1: Escribir test de integracion del dialogo de voz
- [ ] Step 2: Ejecutar test para verificar fallo
- [ ] Step 3: Implementar layout y vincular en MainActivity.kt
- [ ] Step 4: Ejecutar test para verificar pase
- [ ] Step 5: Commit

---

### Task 4: Cursor Pulsante de Streaming y Micro-Feedback Haptico

- [ ] Step 1: Escribir test para el formateador de cursor de streaming
- [ ] Step 2: Ejecutar test unitario
- [ ] Step 3: Implementar cursor dinamico en ChatAdapter.kt y micro-hapticos en MainActivity.kt
- [ ] Step 4: Compilar y verificar suite completa
- [ ] Step 5: Commit

---

### Task 5: Build Oficial de Release y Publicacion Dual a GitHub

- [ ] Step 1: Compilar release minificado con R8 y publicar automaticamente a GitHub publico y privado
- [ ] Step 2: Verificar el release publicado en GitHub
# Plan de Corrección e Implementación: Alineación TUI y Paridad Visual ChatGPT Mobile

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Lograr paridad del 100% en la interfaz de usuario (TUI), animaciones, renderizado de streaming, tipografía y feedback controls con la aplicación oficial de ChatGPT extraída y analizada.

**Architecture:** Implementación modular del sistema de diseño de OpenAI dividido en tres capas: (1) Token System & Interpoladores de Física, (2) Lifecycle de Renderizado del Mensaje (Reasoning -> Streaming -> Markdown -> FeedbackControls), y (3) Shader Holográfico Horizon Orb para Voice Mode.

**Tech Stack:** Android Jetpack (Views/Material3), OpenGL ES 2.0 / GLSurfaceView (Shaders GLSL), Kotlin Coroutines, Custom XML Drawables & PathInterpolators.

**Spec:** Basado en el análisis de ingeniería inversa de `com.openai.chatgpt_1.2026.251` (`res/interpolator/`, `res/values/dimens.xml`, y motor decompreso `chatgpt_conversation.valdimodule`).

## Global Constraints
- Nivel de API: Min SDK 26, Target SDK 35.
- Tipografías oficiales: `@font/soehne` para títulos y badges; `@font/menlo` para código; `@font/inter_regular` para cuerpo.
- Esquema cromático: OLED Dark (#0D0D0D base, #181818 superficie, #212121 elevada, #10A37F acento OpenAI).
- Ninguna animación debe usar rebotes exagerados ni duraciones mayores a 350ms.
- Cobertura de tests unitarios obligatoria antes de cada commit.

---

### Task 1: Curvas de Interpolación Oficiales M3 y Transiciones de Movimiento

**Files:**
- Create: `app/src/main/res/interpolator/m3_sys_motion_easing_emphasized.xml`
- Create: `app/src/main/res/interpolator/m3_sys_motion_easing_emphasized_decelerate.xml`
- Create: `app/src/main/res/interpolator/m3_sys_motion_easing_emphasized_accelerate.xml`
- Modify: `app/src/main/res/anim/bubble_pop_in.xml`
- Modify: `app/src/main/res/anim/tool_card_in.xml`
- Test: `app/src/test/java/com/codex/chat/AnimationResourceTest.kt`

**Interfaces:**
- Consumes: Parámetros Bézier cúbicos extraídos de `chatgpt_official_decompiled/res/interpolator/`.
- Produces: Curvas de interpolación estandarizadas consumibles por ViewPropertyAnimator y XML Sets.

- [ ] **Step 1: Escribir el test unitario que valide la presencia y consistencia de los interpoladores**

```kotlin
package com.codex.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AnimationResourceTest {
    @Test
    fun testOfficialInterpolatorsExistAndHaveValidPathData() {
        val resDir = File("src/main/res/interpolator")
        assertTrue("Directorio de interpoladores debe existir", resDir.exists())
        val emphasized = File(resDir, "m3_sys_motion_easing_emphasized.xml")
        assertTrue("m3_sys_motion_easing_emphasized.xml debe existir", emphasized.exists())
        val content = emphasized.readText()
        assertTrue("Debe contener la curva paramétrica oficial M 0,0 C 0.05...", content.contains("0.05"))
    }
}
```

- [ ] **Step 2: Ejecutar test para verificar fallo**

Run: `.\gradlew.bat testDebugUnitTest --tests com.codex.chat.AnimationResourceTest`
Expected: FAIL (archivos aún no creados)

- [ ] **Step 3: Implementar interpoladores oficiales de OpenAI**

Crear `app/src/main/res/interpolator/m3_sys_motion_easing_emphasized.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<pathInterpolator xmlns:android="http://schemas.android.com/apk/res/android"
    android:pathData="M 0,0 C 0.05, 0, 0.133333, 0.06, 0.166666, 0.4 C 0.208333, 0.82, 0.25, 1, 1, 1" />
```

Crear `app/src/main/res/interpolator/m3_sys_motion_easing_emphasized_decelerate.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<pathInterpolator xmlns:android="http://schemas.android.com/apk/res/android"
    android:controlX1="0.1"
    android:controlY1="0.7"
    android:controlX2="0.1"
    android:controlY2="1.0" />
```

Crear `app/src/main/res/interpolator/m3_sys_motion_easing_emphasized_accelerate.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<pathInterpolator xmlns:android="http://schemas.android.com/apk/res/android"
    android:controlX1="0.3"
    android:controlY1="0.0"
    android:controlX2="0.8"
    android:controlY2="0.2" />
```

Actualizar `bubble_pop_in.xml` y `tool_card_in.xml` para consumir `@interpolator/m3_sys_motion_easing_emphasized`.

- [ ] **Step 4: Ejecutar test para verificar pase**

Run: `.\gradlew.bat testDebugUnitTest --tests com.codex.chat.AnimationResourceTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/interpolator/ app/src/main/res/anim/ app/src/test/java/com/codex/chat/AnimationResourceTest.kt
git commit -m "feat(ui): add official OpenAI M3 cubic-bezier motion interpolators"
```

---

### Task 2: Feedback Controls y Barra de Acciones de Respuesta

**Files:**
- Create: `app/src/main/res/layout/layout_message_feedback_controls.xml`
- Modify: `app/src/main/res/layout/item_message_assistant.xml`
- Modify: `app/src/main/java/com/codex/chat/ChatAdapter.kt`
- Test: `app/src/test/java/com/codex/chat/FeedbackControlsLayoutTest.kt`

**Interfaces:**
- Consumes: `completeMessage` callback de `SseStreamParser.kt` o `LocalChatRepository.kt`.
- Produces: Botonera interactiva post-respuesta (`btnCopy`, `btnRegenerate`, `btnThumbsUp`, `btnThumbsDown`, `btnShare`).

- [ ] **Step 1: Escribir el test para el contenedor de acciones de mensaje**

```kotlin
package com.codex.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FeedbackControlsLayoutTest {
    @Test
    fun testAssistantLayoutIncludesFeedbackControls() {
        val file = File("src/main/res/layout/item_message_assistant.xml")
        val content = file.readText()
        assertTrue("item_message_assistant debe incluir layout_message_feedback_controls",
            content.contains("layout_message_feedback_controls") || content.contains("btnCopyMessage"))
    }
}
```

- [ ] **Step 2: Ejecutar test para verificar fallo**

Run: `.\gradlew.bat testDebugUnitTest --tests com.codex.chat.FeedbackControlsLayoutTest`
Expected: FAIL

- [ ] **Step 3: Implementar layout_message_feedback_controls.xml y acoplar a item_message_assistant.xml**

Crear `app/src/main/res/layout/layout_message_feedback_controls.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/layoutFeedbackControls"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:paddingTop="6dp"
    android:paddingBottom="2dp">

    <ImageButton
        android:id="@+id/btnCopyMessage"
        android:layout_width="32dp"
        android:layout_height="32dp"
        android:background="?attr/selectableItemBackgroundBorderless"
        app:srcCompat="@drawable/ic_edit"
        app:tint="@color/text_secondary"
        android:contentDescription="Copiar respuesta" />

    <ImageButton
        android:id="@+id/btnRegenerateMessage"
        android:layout_width="32dp"
        android:layout_height="32dp"
        android:layout_marginStart="4dp"
        android:background="?attr/selectableItemBackgroundBorderless"
        app:srcCompat="@drawable/ic_refresh"
        app:tint="@color/text_secondary"
        android:contentDescription="Regenerar respuesta" />

    <ImageButton
        android:id="@+id/btnFeedbackThumbsUp"
        android:layout_width="32dp"
        android:layout_height="32dp"
        android:layout_marginStart="4dp"
        android:background="?attr/selectableItemBackgroundBorderless"
        app:srcCompat="@drawable/ic_shield"
        app:tint="@color/text_secondary"
        android:contentDescription="Buena respuesta" />

</LinearLayout>
```

Incluir en `item_message_assistant.xml` al final del flujo del asistente, controlado con visibilidad dinámica en `ChatAdapter.kt`: visible solo cuando la respuesta está completada (no durante streaming activo).

- [ ] **Step 4: Ejecutar test para verificar pase**

Run: `.\gradlew.bat testDebugUnitTest --tests com.codex.chat.FeedbackControlsLayoutTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/layout/layout_message_feedback_controls.xml app/src/main/res/layout/item_message_assistant.xml app/src/main/java/com/codex/chat/ChatAdapter.kt app/src/test/java/com/codex/chat/FeedbackControlsLayoutTest.kt
git commit -m "feat(ui): implement official ChatGPT message feedback and action controls"
```

---

### Task 3: Shaders GLSL y Horizon Orb para Grabación y Modo de Voz

**Files:**
- Create: `app/src/main/java/com/codex/chat/ui/voice/HorizonOrbRenderer.kt`
- Create: `app/src/main/java/com/codex/chat/ui/voice/HorizonOrbView.kt`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Test: `app/src/test/java/com/codex/chat/HorizonOrbShaderValidationTest.kt`

**Interfaces:**
- Consumes: `res/raw/horizon_orb_vert.vsh` y `res/raw/horizon_orb_frag.fsh`.
- Produces: Componente de renderizado de orbe 3D reactivo al nivel de decibelios del micrófono.

- [ ] **Step 1: Escribir el test de validación de shaders de Horizon**

```kotlin
package com.codex.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HorizonOrbShaderValidationTest {
    @Test
    fun testShadersAndNoiseTexturesExist() {
        val rawDir = File("src/main/res/raw")
        val frag = File(rawDir, "horizon_orb_frag.fsh")
        val vert = File(rawDir, "horizon_orb_vert.vsh")
        assertTrue("horizon_orb_frag.fsh debe existir", frag.exists())
        assertTrue("horizon_orb_vert.vsh debe existir", vert.exists())
        
        val assetsDir = File("src/main/assets/horizon")
        val voronoi = File(assetsDir, "horizon_compact_baked_voronoi_position.webp")
        assertTrue("Textura voronoi debe existir", voronoi.exists())
    }
}
```

- [ ] **Step 2: Ejecutar test para verificar estado**

Run: `.\gradlew.bat testDebugUnitTest --tests com.codex.chat.HorizonOrbShaderValidationTest`
Expected: PASS (los recursos fueron extraídos previamente)

- [ ] **Step 3: Crear HorizonOrbRenderer y HorizonOrbView con soporte GLSurfaceView**

Implementar `HorizonOrbRenderer.kt` cargando los shaders oficiales con GLSL ES 2.0 y vinculando los uniforms:
- `u_time` (reloj de animación continuo).
- `u_audio_level` (amplitud RMS del audio del micrófono).
- `u_texture_noise` (textura voronoi precargada).

- [ ] **Step 4: Compilar y verificar suite de tests completa**

Run: `.\gradlew.bat testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL con todos los tests pasando

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/codex/chat/ui/voice/ app/src/test/java/com/codex/chat/HorizonOrbShaderValidationTest.kt
git commit -m "feat(voice): create HorizonOrbRenderer using official OpenAI GLSL shaders"
```

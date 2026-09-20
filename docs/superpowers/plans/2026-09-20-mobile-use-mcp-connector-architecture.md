# Mobile Use MCP Connector Architecture — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transform the on-device Mobile Autonomous Agent from an isolated FAB modal into a native, toggleable MCP Server (`MobileUseMcpServer`) inside the app's MCP/Connectors registry, allowing the conversational chat model to perceive and operate the Android device via standard tool-calling while keeping raw screenshot images hidden from chat bubbles and rendering only collapsible thinking blocks and compact tool chips.

**Architecture:** Model Context Protocol (MCP) server running natively on Android, wrapping `IDeviceController` (`CodexAccessibilityService` + `ScreenCaptureService`). The chat conversation exposes tools (`mobile_get_screen`, `mobile_click`, `mobile_swipe`, `mobile_type`, `mobile_press_key`, `mobile_wait`). Sensory separation ensures high-resolution screenshot images flow privately into the LLM context while the chat UI renders only the model's `💭 Proceso de razonamiento` and `🛠️ layoutToolExecution` badges with auto-minimization and floating ChatHead telemetry.

**Tech Stack:** Kotlin, Android Jetpack, Model Context Protocol (MCP), AccessibilityService (`dispatchGesture`), MediaProjection API, OkHttp 4.12.0, Coroutines 1.8.1, Flow, JUnit4.

**Spec:** Architectural analysis in conversation turn and `docs/superpowers/plans/2026-09-20-android-autonomous-agent-mobile-use.md`.

---

## Global Constraints & Architectural Invariants

1. **Native MCP Registry Integration:** Must implement `com.codex.chat.core.mcp.server.McpServer` and register inside `McpRegistry.kt` with id `"mcp-mobile-use"`.
2. **Zero Image Bloat in Chat Bubbles:** `MessageRole.TOOL` responses containing base64 screenshots must render in `ChatAdapter.kt` as compact sensory summaries (`"📸 Pantalla capturada (WxH, N elementos)"`), never as raw Base64 text or full-screen image cards in the message list.
3. **Private Multimodal Context Forwarding:** The raw Base64 JPEG frame must be delivered exclusively in the OpenAI/multimodal payload sent to the LLM API endpoint.
4. **Pre-Armed Service State via Connectors/Settings:** Toggling the connector ON in the Connectors/MCP sheet acquires permissions once; `ScreenCaptureService` enters idle-ready state so conversational tool calls execute with zero latency.
5. **Human-in-the-Loop Emergency Stop (ESTOP):** Synthetic gestures dispatched by the agent MUST NOT trigger the floating overlay's own STOP button (enforced via `isDispatchingGesture` guard).
6. **Backward Compatibility:** Normal ChatGPT and Codex PC modes must remain 100% operational with zero regression.

---

## Target File Structure

```
app/src/main/java/com/codex/chat/
├── core/
│   └── mcp/
│       └── server/
│           ├── McpServer.kt              # Existing base interface
│           ├── DeviceMcpServer.kt        # Existing hardware telemetry MCP
│           └── MobileUseMcpServer.kt     # [NEW] Native MCP Server for screen perception & gesture actuation
│       ├── McpRegistry.kt                # Register MobileUseMcpServer
│       └── model/McpModels.kt            # Tool definitions and schemas
├── agent/
│   ├── device/
│   │   ├── IDeviceController.kt          # Interface for screen capture, hierarchy dump, gesture dispatch
│   │   ├── CodexAccessibilityService.kt  # Accessibility service implementing IDeviceController
│   │   ├── ScreenCaptureService.kt       # Foreground MediaProjection service (idle-ready mode)
│   │   └── DeviceMetricsProvider.kt      # Resolution and DPI provider
│   └── ui/
│       └── FloatingAgentOverlay.kt       # WindowManager floating card with live thoughts and ESTOP button
├── ui/
│   └── ChatAdapter.kt                    # Message rendering: sensory tool result summarizer
├── MainActivity.kt                       # Tool execution dispatcher, auto-minimize & return
app/src/main/res/
├── layout/
│   ├── bottom_sheet_connectors.xml       # Mobile Use MCP toggle switch
│   └── item_message_assistant.xml        # Collapsible thinking and tool execution layout
└── values/
    └── strings.xml                       # Mobile use MCP descriptions and tool labels
app/src/test/java/com/codex/chat/
├── mcp/
│   └── MobileUseMcpServerTest.kt         # [NEW] JVM unit tests for tool schemas, execution, and sensory formatting
```

---

## Tasks Breakdown & Execution Roadmap

### Task 0: Scaffolding, String Resources & Tool Definitions
- [ ] Step 0.1: Add string resources for Mobile Use MCP server and tools in `app/src/main/res/values/strings.xml`.
- [ ] Step 0.2: Define tool names, JSON input schemas, and descriptions for all 6 mobile tools:
  - `mobile_get_screen`: Returns UI hierarchy and base64 screenshot.
  - `mobile_click`: `{ x: int, y: int, reason: string }`.
  - `mobile_swipe`: `{ startX: int, startY: int, endX: int, endY: int, duration_ms: int, reason: string }`.
  - `mobile_type`: `{ text: string, press_enter: boolean, reason: string }`.
  - `mobile_press_key`: `{ key: "HOME"|"BACK"|"RECENTS", reason: string }`.
  - `mobile_wait`: `{ seconds: int, reason: string }`.
- [ ] Step 0.3: Verify clean build: `.\gradlew.bat compileDebugKotlin`.

### Task 1: MobileUseMcpServer Implementation (TDD)
- [ ] Step 1.1: Write failing JVM unit tests in `app/src/test/java/com/codex/chat/mcp/MobileUseMcpServerTest.kt`:
  - Test tool enumeration (all 6 tools present with correct JSON schemas).
  - Test execution of `mobile_click`, `mobile_swipe`, `mobile_type`, `mobile_press_key`.
  - Test sensory isolation (ensures `mobile_get_screen` produces clean sensory summary for UI vs raw data for LLM).
- [ ] Step 1.2: Implement `MobileUseMcpServer.kt` implementing `McpServer`.
- [ ] Step 1.3: Connect `MobileUseMcpServer` to `CodexAccessibilityService` and `ScreenCaptureService`.
- [ ] Step 1.4: Register `MobileUseMcpServer` in `McpRegistry.kt` under built-in servers.
- [ ] Step 1.5: Execute unit tests and verify 100% green: `.\gradlew.bat testDebugUnitTest --tests "com.codex.chat.mcp.MobileUseMcpServerTest"`.

### Task 2: Chat UI Sensory Isolation (Hide Base64, Show Thinking & Tool Badges)
- [ ] Step 2.1: Update `ChatAdapter.kt` to detect `MessageRole.TOOL` outputs from `mobile_*` tools.
- [ ] Step 2.2: Ensure raw Base64 JPEG strings are intercepted and replaced in the UI with an elegant summary badge:
  - `📸 Pantalla capturada [1344x2992, 42 interactivos visibles]`
- [ ] Step 2.3: Keep `layoutThinking` and `layoutToolExecution` visible and collapsible, showing the model's inner reasoning and executed actions without image spam.
- [ ] Step 2.4: Ensure `CodexPayloadBuilder.kt` continues to attach the actual Base64 image to the outbound LLM payload for multimodal vision.
- [ ] Step 2.5: Verify clean compile and adapter tests: `.\gradlew.bat testDebugUnitTest --tests "com.codex.chat.adapter.*"`.

### Task 3: Settings & Connectors Sheet Integration (Pre-Armed State)
- [ ] Step 3.1: Add a toggle switch in `bottom_sheet_connectors.xml` (or `bottom_sheet_mcp_manager.xml`):
  - `📱 Control de Dispositivo Móvil (Mobile Use)`
- [ ] Step 3.2: Implement pre-arming lifecycle in `MainActivity.kt`:
  - When user toggles ON: checks Accessibility Service -> Window Overlay -> prompts MediaProjection consent once.
  - `ScreenCaptureService` starts in idle-ready foreground mode.
  - When user toggles OFF: unbinds MediaProjection and stops foreground service.
- [ ] Step 3.3: Save enabled state in `SettingsManager.kt` (`isMobileUseMcpEnabled`).
- [ ] Step 3.4: Verify clean compile: `.\gradlew.bat compileDebugKotlin`.

### Task 4: Auto-Minimization, Floating ChatHead & Completion Alert
- [ ] Step 4.1: In `MainActivity.kt`, when a `mobile_*` tool call begins executing:
  - Automatically call `moveTaskToBack(true)` to reveal the target app.
  - Attach `FloatingAgentOverlay` displaying the model's live thought and current tool call (`Actuating Tap...`) with ESTOP button.
- [ ] Step 4.2: When the chat model finishes its tool-calling chain and outputs its final text message:
  - Transition the overlay to green (`✓ Objetivo Completado`).
  - Auto-detach overlay after 4 seconds.
  - Emit an Android system notification: *"Tarea móvil completada: [Resumen de respuesta]"*.
- [ ] Step 4.3: Deprecate or hide the redundant `fabAgentMode` button (as mobile use is now fully native in the chat).
- [ ] Step 4.4: Verify clean compile: `.\gradlew.bat compileDebugKotlin`.

### Task 5: End-to-End Build, Test Suite & Emulator Verification
- [ ] Step 5.1: Execute full test suite: `.\gradlew.bat testDebugUnitTest` (all 500+ unit tests passing).
- [ ] Step 5.2: Assemble release APK: `.\gradlew.bat assembleRelease`.
- [ ] Step 5.3: Install on Android emulator (`adb install -r`) and test live chat prompt:
  - User types in regular chat: *"Abre YouTube y busca música para programar"*.
  - Verify app minimizes automatically.
  - Verify floating overlay shows live thoughts and tool badges.
  - Verify YouTube opens and search is executed.
  - Verify chat shows collapsible thinking and clean tool badges, with zero raw base64 image clutter.
- [ ] Step 5.4: Publish official release via `bump_and_build.py --publish`.

---

## Verification Commands Checklist

```powershell
cd C:\Users\Admin\Desktop\ChatGPT-Android-Studio
.\gradlew.bat compileDebugKotlin
.\gradlew.bat testDebugUnitTest --tests "com.codex.chat.mcp.*"
.\gradlew.bat assembleRelease
python bump_and_build.py "Feat: Mobile Use MCP Connector" --publish
```

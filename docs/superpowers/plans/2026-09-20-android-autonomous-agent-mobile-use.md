# Android Autonomous Mobile Agent - Corrected Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or autonomous disciplined execution. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a 100% autonomous on-device AI Agent inside `codex-chatgpt-mobile` (located at `C:\Users\Admin\Desktop\ChatGPT-Android-Studio`) that operates any Android app using natural language without requiring a PC or USB cables. The mobile APK acts as the Body + Hands (`AccessibilityService` + `MediaProjection`), while your local/LAN `CLIProxyAPI` (port 8317) acts as the high-speed multimodal Brain.

**Architecture:** On-device Kotlin multi-agent loop - 4 clean layers:
1. **System Device Controller:** `CodexAccessibilityService` gesture dispatcher + `ScreenCaptureService` (`MediaProjection`) virtual display screenshot pipeline.
2. **Agent State & Convergence Loop:** `AutonomousAgentLoop` (Capture -> Think -> Dispatch -> Verify).
3. **Multimodal Grounding Client:** OkHttp client communicating with `CLIProxyAPI /v1/chat/completions`.
4. **Floating System Overlay UI:** WindowManager ChatHead overlay with live thoughts, step telemetry, and an instantaneous Emergency Stop (ESTOP) kill switch.

**Tech Stack:** Kotlin, Android Jetpack, Android Accessibility APIs (`dispatchGesture`, `AccessibilityNodeInfo`), Android MediaProjection API, OkHttp 4.12.0, Coroutines 1.8.1, Flow, CLIProxyAPI, JUnit4, MockWebServer.

---

## Critical Issues Found in Original Plan (Adversarial Self-Audit)

### CRITICAL Defects - Block Compilation or Cause Runtime Crashes

1. **`FOREGROUND_SERVICE_MEDIA_PROJECTION` missing from manifest:**
   Required for `ScreenCaptureService` on Android 14+ (targetSdk=35). Without `android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION` declared, Android 14+ throws `SecurityException` upon starting the foreground service.

2. **`EstopSentinel` duplication:**
   The Android codebase ALREADY contains an enterprise-grade `EstopSentinel` at `com.codex.chat.core.security.EstopSentinel` (`engage()`, `disengage()`, `isEngaged()`, `checkOrThrow()`). The original plan attempted to reinvent ESTOP from scratch. The corrected design integrates with `EstopSentinel.engage("User ESTOP")` and verifies `EstopSentinel.checkOrThrow()` at every step.

3. **Missing Coroutines dependencies in `app/build.gradle.kts`:**
   The project had no `kotlinx-coroutines-android` or `kotlinx-coroutines-test` dependencies. `AutonomousAgentLoop` requires `MutableStateFlow`, `launch`, `async`, and coroutine builders. Must add `kotlinx-coroutines-android:1.8.1` and `kotlinx-coroutines-test:1.8.1` before any code compiles.

4. **`MediaProjection` token forwarding chain incomplete:**
   `MediaProjectionManager.createScreenCaptureIntent()` must be launched via `ActivityResultContracts.StartActivityForResult()`. The resulting `resultCode` and `data: Intent` MUST be passed as extras to `ScreenCaptureService`, which must call `startForeground()` within 5 seconds of `onStartCommand()`. The original plan completely omitted this chain.

5. **`GestureDescription` not JVM-testable:**
   `GestureDescription.Builder` throws `ClassNotFoundException` on the JVM test runner (`testDebugUnitTest`). The original plan scheduled `TouchGestureDispatcherTest.kt` as a JVM test using Android SDK classes. The fix decouples pure mathematical path generation (`StrokeData`, testable on JVM) from Android `GestureDescription` building (which runs only on Android).

6. **`dispatchGesture` is callback-based, not suspend:**
   Must bridge `dispatchGesture` with `suspendCancellableCoroutine` and `GestureResultCallback`.

7. **`ImageReader.acquireLatestImage()` MUST be closed in a `finally` block:**
   Without `image.close()`, the 2-slot image buffer fills immediately, causing `acquireLatestImage()` to return `null` perpetually. Mandatory `try ... finally { image.close() }`.

8. **Missing `NotificationChannel` for `ScreenCaptureService`:**
   Android 8+ (minSdk=26) crashes if `startForeground` is called without a valid `NotificationChannel`.

### HIGH Severity Defects

9. **Windows CLI incompatibility:** Original plan used `./gradlew` instead of `.\gradlew.bat`.
10. **Hardcoded Spanish UI strings:** Must use `res/values/strings.xml`.
11. **Missing network test coverage:** No MockWebServer test for `AgentVisionClient` was planned.
12. **VirtualDisplay density omitted:** `DeviceMetricsProvider` must provide `getDensityDpi()`.
13. **Tight coupling / Monolith risk:** Original loop directly depended on concrete singletons instead of `IDeviceController` and `IVisionClient` interfaces.

---

## Global Constraints & Engineering Invariants (Corrected)

1. **Zero PC Tethering:** The APK alone executes gestures and captures the screen.
2. **Zero Root Required:** Standard Android public APIs (`AccessibilityService` + `MediaProjection`).
3. **Low-Token Visual Optimization:** JPEG quality 75%, max 1080px longest edge, Base64 inline (<120 KB).
4. **Strict ESTOP:** Call `EstopSentinel.engage()` on STOP button; check `EstopSentinel.checkOrThrow()` before any action.
5. **Modularity & TDD:** Pure-JVM unit tests for domain logic, parsers, prompt builders, and agent state machines.
6. **Zero Leaks:** `ImageReader.Image` closed in `finally`, services unbound properly.

---

## Corrected Target File Structure (`C:\Users\Admin\Desktop\ChatGPT-Android-Studio`)

```
app/src/main/java/com/codex/chat/
├── agent/
│   ├── core/
│   │   ├── AgentAction.kt              # Sealed class: Tap, DoubleTap, LongPress, Swipe, InputText, PressKey, Wait, Complete, Fail
│   │   ├── AgentStatus.kt              # StateFlow telemetry data class
│   │   ├── AutonomousAgentLoop.kt      # Main coroutine loop (decoupled from singletons)
│   │   └── GroundingPromptBuilder.kt   # System prompt and user message constructor
│   ├── device/
│   │   ├── IDeviceController.kt        # Interface for screen capture, hierarchy dump, gesture dispatch
│   │   ├── CodexAccessibilityService.kt # Accessibility service implementing IDeviceController
│   │   ├── ScreenCaptureService.kt      # Foreground MediaProjection service
│   │   ├── DeviceMetricsProvider.kt     # Resolution and DPI provider (API 30+ WindowMetrics fallback)
│   │   └── TouchGestureDispatcher.kt    # Pure coordinate math StrokeData + Android GestureDescription builder
│   ├── network/
│   │   ├── IVisionClient.kt             # Interface for multimodal vision reasoning
│   │   ├── AgentVisionClient.kt         # OkHttp client targeting CLIProxyAPI /v1/chat/completions
│   │   └── VisionResponseParser.kt      # Resilient JSON parser from markdown fences / raw JSON
│   └── ui/
│       ├── FloatingAgentOverlay.kt      # WindowManager floating card with live thoughts and ESTOP button
│       └── AgentLaunchBottomSheet.kt    # Permission guard chain and goal launcher bottom sheet
app/src/main/res/
├── layout/
│   ├── overlay_agent_bubble.xml         # Floating overlay UI
│   └── bottom_sheet_agent_launcher.xml  # Launcher bottom sheet
├── values/
│   └── strings.xml                      # Agent UI strings
└── xml/
    └── accessibility_service_config.xml # Accessibility config
app/src/test/java/com/codex/chat/agent/
├── GroundingPromptBuilderTest.kt        # JVM unit tests for prompt generation
├── VisionResponseParserTest.kt          # JVM unit tests for JSON action parsing
├── TouchGestureDispatcherTest.kt        # JVM unit tests for stroke coordinates & durations
├── AutonomousAgentLoopTest.kt           # JVM unit tests for loop convergence, ESTOP, anti-loop
└── AgentVisionClientTest.kt             # JVM unit tests using MockWebServer
```

---

## Tasks Breakdown & Execution Roadmap

### Task 0: Dependencies, Manifest, and String Scaffolding
- [ ] Step 0.1: Add `kotlinx-coroutines-android:1.8.1` and `kotlinx-coroutines-test:1.8.1` to `app/build.gradle.kts`.
- [ ] Step 0.2: Add `FOREGROUND_SERVICE_MEDIA_PROJECTION` to `app/src/main/AndroidManifest.xml`.
- [ ] Step 0.3: Add agent string resources to `app/src/main/res/values/strings.xml`.
- [ ] Step 0.4: Verify clean compile: `.\gradlew.bat compileDebugKotlin`.

### Task 1: Interfaces & Core Data Types
- [ ] Step 1.1: Create `AgentAction.kt` with all action types.
- [ ] Step 1.2: Create `AgentStatus.kt` data class for telemetry and StateFlow.
- [ ] Step 1.3: Create `IDeviceController.kt` interface.
- [ ] Step 1.4: Create `IVisionClient.kt` interface.
- [ ] Step 1.5: Verify clean compile: `.\gradlew.bat compileDebugKotlin`.

### Task 2: Pure-Logic Layer & TDD Suite
- [ ] Step 2.1: Write failing JVM unit tests: `GroundingPromptBuilderTest.kt`, `VisionResponseParserTest.kt`, `TouchGestureDispatcherTest.kt`.
- [ ] Step 2.2: Implement `VisionResponseParser.kt`.
- [ ] Step 2.3: Implement `GroundingPromptBuilder.kt`.
- [ ] Step 2.4: Implement `TouchGestureDispatcher.kt` (pure math `StrokeData`).
- [ ] Step 2.5: Execute unit tests and verify 100% green: `.\gradlew.bat testDebugUnitTest --tests "com.codex.chat.agent.*"`.

### Task 3: Accessibility Service & Device Metrics
- [ ] Step 3.1: Create `app/src/main/res/xml/accessibility_service_config.xml`.
- [ ] Step 3.2: Register `CodexAccessibilityService` and `ScreenCaptureService` in `AndroidManifest.xml`.
- [ ] Step 3.3: Implement `DeviceMetricsProvider.kt`.
- [ ] Step 3.4: Implement `CodexAccessibilityService.kt` implementing `IDeviceController`.
- [ ] Step 3.5: Verify clean compile: `.\gradlew.bat compileDebugKotlin`.

### Task 4: ScreenCaptureService (MediaProjection)
- [ ] Step 4.1: Implement foreground service lifecycle with notification channel in `ScreenCaptureService.kt`.
- [ ] Step 4.2: Accept MediaProjection token via Intent extras.
- [ ] Step 4.3: Build `VirtualDisplay` + `ImageReader` pipeline.
- [ ] Step 4.4: Implement `captureScreenshotBase64` with guaranteed `image.close()`.
- [ ] Step 4.5: Verify clean compile: `.\gradlew.bat compileDebugKotlin`.

### Task 5: Multimodal Grounding Client & MockWebServer Test
- [ ] Step 5.1: Write failing `AgentVisionClientTest.kt` using `MockWebServer`.
- [ ] Step 5.2: Implement `AgentVisionClient.kt` implementing `IVisionClient`.
- [ ] Step 5.3: Execute client unit tests and verify green.

### Task 6: Autonomous Agent Loop & Safety Convergence
- [ ] Step 6.1: Write failing `AutonomousAgentLoopTest.kt` (Complete, ESTOP, anti-loop, maxSteps).
- [ ] Step 6.2: Implement `AutonomousAgentLoop.kt` with coroutines and `StateFlow`.
- [ ] Step 6.3: Execute loop unit tests and verify 100% green.

### Task 7: Floating Overlay UI (ChatHead with ESTOP)
- [ ] Step 7.1: Create `app/src/main/res/layout/overlay_agent_bubble.xml`.
- [ ] Step 7.2: Implement `FloatingAgentOverlay.kt` using `WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY`.
- [ ] Step 7.3: Verify layout and clean compile.

### Task 8: MainActivity Integration & End-to-End Build
- [ ] Step 8.1: Create `app/src/main/res/layout/bottom_sheet_agent_launcher.xml`.
- [ ] Step 8.2: Implement `AgentLaunchBottomSheet.kt` with 3-step permission guard (Accessibility -> Overlay -> MediaProjection).
- [ ] Step 8.3: Add agent launcher trigger to `MainActivity.kt` without breaking existing modes.
- [ ] Step 8.4: Execute full test suite: `.\gradlew.bat testDebugUnitTest`.
- [ ] Step 8.5: Assemble debug APK: `.\gradlew.bat assembleDebug` and verify artifact.

---

## Verification Commands Checklist

```powershell
cd C:\Users\Admin\Desktop\ChatGPT-Android-Studio
.\gradlew.bat compileDebugKotlin
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
Test-Path app\build\outputs\apk\debug\app-debug.apk
```
package com.codex.chat.agent.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.codex.chat.agent.core.AgentAction
import com.codex.chat.core.security.EstopSentinel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import kotlin.coroutines.resume

/**
 * System Device Controller for the autonomous agent.
 * Acts as the "Hands & Eyes" via Android's Accessibility APIs:
 * - Dispatches low-latency taps, long-presses, and bezier swipes.
 * - Extracts live UI hierarchy trees with element coordinates.
 * - Injects text into focused input fields.
 * - Enforces EstopSentinel safety checks before any physical actuation.
 */
class CodexAccessibilityService : AccessibilityService(), IDeviceController {

    private val gestureDispatcher = TouchGestureDispatcher()

    companion object {
        @Volatile
        var instance: CodexAccessibilityService? = null
            private set
    }

    override val isAvailable: Boolean
        get() = instance != null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Active polling is used; passive events can be consumed if needed
    }

    override fun onInterrupt() {
        // System requested an interruption
    }

    override suspend fun captureScreenshotBase64(maxDimension: Int, quality: Int): String {
        val captureService = ScreenCaptureService.instance
            ?: return ""
        return captureService.captureScreenshotBase64(maxDimension, quality)
    }

    override fun dumpUiHierarchy(): String {
        val root = rootInActiveWindow
            ?: runCatching { windows.firstOrNull { it.isFocused || it.isActive }?.root }.getOrNull()
            ?: runCatching { windows.firstOrNull()?.root }.getOrNull()
            ?: return "{}"
        val elements = JSONArray()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        val outBounds = Rect()
        var count = 0
        val maxElements = 60

        while (queue.isNotEmpty() && count < maxElements) {
            val node = queue.poll() ?: continue

            val isVisible = node.isVisibleToUser
            val isClickable = node.isClickable
            val isEditable = node.isEditable
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""
            val viewId = node.viewIdResourceName ?: ""

            // Keep nodes that are clickable, editable, or have descriptive text
            if (isVisible && (isClickable || isEditable || text.isNotEmpty() || desc.isNotEmpty())) {
                node.getBoundsInScreen(outBounds)
                if (outBounds.width() > 0 && outBounds.height() > 0) {
                    val elem = JSONObject().apply {
                        put("class", node.className?.toString()?.substringAfterLast('.') ?: "")
                        if (text.isNotEmpty()) put("text", text.take(50))
                        if (desc.isNotEmpty()) put("desc", desc.take(50))
                        if (viewId.isNotEmpty()) put("id", viewId.substringAfterLast('/'))
                        put("clickable", isClickable)
                        put("editable", isEditable)
                        put("bounds", JSONArray().apply {
                            put(outBounds.left)
                            put(outBounds.top)
                            put(outBounds.right)
                            put(outBounds.bottom)
                        })
                    }
                    elements.put(elem)
                    count++
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    queue.add(child)
                }
            }
        }

        return JSONObject().apply {
            put("count", count)
            put("elements", elements)
        }.toString()
    }

    override suspend fun dispatch(action: AgentAction): Boolean {
        // Enforce fail-safe ESTOP check before any actuation
        EstopSentinel.checkOrThrow()

        val screenW = DeviceMetricsProvider.getScreenWidth(this)
        val screenH = DeviceMetricsProvider.getScreenHeight(this)

        return when (action) {
            is AgentAction.Tap -> {
                val stroke = gestureDispatcher.buildTapStroke(action.x, action.y, screenW, screenH)
                dispatchStroke(stroke)
            }

            is AgentAction.DoubleTap -> {
                val stroke1 = gestureDispatcher.buildTapStroke(action.x, action.y, screenW, screenH)
                val ok1 = dispatchStroke(stroke1)
                delay(80L)
                val stroke2 = gestureDispatcher.buildTapStroke(action.x, action.y, screenW, screenH)
                ok1 && dispatchStroke(stroke2)
            }

            is AgentAction.LongPress -> {
                val stroke = gestureDispatcher.buildLongPressStroke(
                    action.x, action.y, action.durationMs, screenW, screenH
                )
                dispatchStroke(stroke)
            }

            is AgentAction.Swipe -> {
                val stroke = gestureDispatcher.buildSwipeStroke(
                    action.startX, action.startY, action.endX, action.endY,
                    action.durationMs, screenW, screenH
                )
                dispatchStroke(stroke)
            }

            is AgentAction.InputText -> {
                inputText(action.text, action.pressEnter)
            }

            is AgentAction.PressKey -> {
                when (action.key) {
                    AgentAction.GlobalKey.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
                    AgentAction.GlobalKey.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
                    AgentAction.GlobalKey.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
                }
            }

            is AgentAction.Wait -> {
                delay(action.seconds * 1000L)
                true
            }

            is AgentAction.Complete, is AgentAction.Fail -> true
        }
    }

    suspend fun dispatchStroke(stroke: StrokeData): Boolean {
        if (stroke.points.isEmpty()) return false
        val path = Path()
        val first = stroke.points.first()
        path.moveTo(first.first, first.second)
        for (i in 1 until stroke.points.size) {
            val pt = stroke.points[i]
            path.lineTo(pt.first, pt.second)
        }

        val strokeDesc = GestureDescription.StrokeDescription(path, 0, stroke.durationMs)
        val gesture = GestureDescription.Builder().addStroke(strokeDesc).build()

        return dispatchGestureAsync(gesture)
    }

    suspend fun dispatchGestureAsync(gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { continuation ->
            val result = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                },
                null
            )
            if (!result && continuation.isActive) {
                continuation.resume(false)
            }
        }

    private fun inputText(text: String, pressEnter: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditable(root)
            ?: return false

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val setOk = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        if (pressEnter) {
            // Try standard IME search / enter click
            focused.performAction(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY)
        }
        return setOk
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val res = findFirstEditable(child)
            if (res != null) return res
        }
        return null
    }
}

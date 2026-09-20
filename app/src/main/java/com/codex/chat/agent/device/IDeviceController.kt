package com.codex.chat.agent.device

import com.codex.chat.agent.core.AgentAction

/**
 * Abstraction layer separating low-level Android hardware/accessibility drivers
 * from the high-level autonomous agent loop. Enables 100% pure-JVM unit testing.
 */
interface IDeviceController {
    /** True if accessibility service and screen capture are active and ready */
    val isAvailable: Boolean

    /** Captures current screen scaled down to maxDimension, returns Base64-encoded JPEG */
    suspend fun captureScreenshotBase64(maxDimension: Int = 1080, quality: Int = 75): String

    /** Dumps the active window UI hierarchy as compact JSON (< 10 KB) */
    fun dumpUiHierarchy(): String

    /** Dispatches an atomic gesture or key event onto the device */
    suspend fun dispatch(action: AgentAction): Boolean
}

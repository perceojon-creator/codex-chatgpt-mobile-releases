package com.codex.chat.agent.core

import com.codex.chat.agent.device.IDeviceController
import com.codex.chat.agent.network.IVisionClient
import com.codex.chat.core.security.EstopSentinel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Autonomous Convergence Loop for on-device mobile agent.
 * Executes the cyclical perception-reasoning-actuation pipeline:
 * 1. Capture: Retrieves Base64 screenshot and compact UI accessibility hierarchy.
 * 2. Think: Queries multimodal brain (CLIProxyAPI) with grounding prompts.
 * 3. Verify: Detects infinite action loops and checks global ESTOP status.
 * 4. Dispatch: Actuates gestures via IDeviceController.
 */
class AutonomousAgentLoop(
    private val device: IDeviceController,
    private val vision: IVisionClient,
    private val maxSteps: Int = 20,
    private val externalScope: CoroutineScope,
    private val stepDelayMs: Long = 800L
) {

    private val _status = MutableStateFlow(AgentStatus())
    val status: StateFlow<AgentStatus> = _status.asStateFlow()

    private var activeJob: Job? = null
    private val completionDeferred = CompletableDeferred<AgentStatus>()

    fun start(goal: String) {
        activeJob = externalScope.launch {
            val actionHistory = mutableListOf<AgentAction>()
            var stepIndex = 0

            // 1. Initial fail-safe ESTOP check
            if (EstopSentinel.isEngaged()) {
                val abortedStatus = AgentStatus(
                    goal = goal,
                    stepIndex = 0,
                    maxSteps = maxSteps,
                    statusText = "Aborted: ESTOP active before start",
                    isAborted = true
                )
                _status.value = abortedStatus
                if (!completionDeferred.isCompleted) {
                    completionDeferred.complete(abortedStatus)
                }
                return@launch
            }

            _status.value = AgentStatus(
                goal = goal,
                stepIndex = 0,
                maxSteps = maxSteps,
                statusText = "Initializing autonomous agent loop..."
            )

            while (isActive && stepIndex < maxSteps) {
                // 2. Continuous ESTOP sentinel verification
                if (EstopSentinel.isEngaged()) {
                    val abortedStatus = _status.value.copy(
                        isAborted = true,
                        statusText = "Aborted: ESTOP engaged during step " + stepIndex
                    )
                    _status.value = abortedStatus
                    if (!completionDeferred.isCompleted) {
                        completionDeferred.complete(abortedStatus)
                    }
                    return@launch
                }

                _status.value = _status.value.copy(
                    stepIndex = stepIndex,
                    statusText = "Capturing device screen and UI hierarchy..."
                )

                // 3. Parallel state perception
                val screenshotDeferred = async { device.captureScreenshotBase64() }
                val hierarchy = device.dumpUiHierarchy()
                val screenshot = screenshotDeferred.await()
                android.util.Log.i("AgentLoop", "Step $stepIndex: Perceived screen (${screenshot.length} B64 chars) and hierarchy (${hierarchy.length} chars)")

                if (EstopSentinel.isEngaged()) {
                    val abortedStatus = _status.value.copy(
                        isAborted = true,
                        statusText = "Aborted: ESTOP engaged"
                    )
                    _status.value = abortedStatus
                    if (!completionDeferred.isCompleted) {
                        completionDeferred.complete(abortedStatus)
                    }
                    return@launch
                }

                _status.value = _status.value.copy(
                    statusText = "Reasoning step with multimodal brain..."
                )

                // 4. Multimodal grounding reasoning
                val action = try {
                    vision.think(goal, stepIndex, screenshot, hierarchy, actionHistory.toList())
                } catch (e: Throwable) {
                    if (e is com.codex.chat.core.security.EstopEngagedException || e is SecurityException) {
                        val abortedStatus = _status.value.copy(
                            isAborted = true,
                            statusText = "Aborted: " + e.message
                        )
                        _status.value = abortedStatus
                        if (!completionDeferred.isCompleted) {
                            completionDeferred.complete(abortedStatus)
                        }
                        return@launch
                    }
                    AgentAction.Fail("Reasoning error: " + e.message)
                }

                // 5. Anti-loop safeguard: abort if identical action is repeated >= 3 times
                if (actionHistory.size >= 3) {
                    val last3 = actionHistory.takeLast(3)
                    if (last3.all { it == action } && (action is AgentAction.Tap || action is AgentAction.Swipe)) {
                        val loopFail = AgentAction.Fail("Loop detected: same action repeated 3+ times in succession")
                        val finishedStatus = _status.value.copy(
                            lastAction = loopFail,
                            isComplete = true,
                            statusText = "Halted: loop detected"
                        )
                        _status.value = finishedStatus
                        if (!completionDeferred.isCompleted) {
                            completionDeferred.complete(finishedStatus)
                        }
                        return@launch
                    }
                }

                actionHistory.add(action)
                android.util.Log.i("AgentLoop", "Step $stepIndex: Actuating ${action.javaClass.simpleName} -> $action")
                _status.value = _status.value.copy(
                    lastAction = action,
                    statusText = "Actuating: " + action.javaClass.simpleName
                )

                // 6. Action dispatch & terminal evaluation
                when (action) {
                    is AgentAction.Complete -> {
                        val finishedStatus = _status.value.copy(
                            isComplete = true,
                            statusText = "Completed: " + action.result
                        )
                        _status.value = finishedStatus
                        if (!completionDeferred.isCompleted) {
                            completionDeferred.complete(finishedStatus)
                        }
                        return@launch
                    }

                    is AgentAction.Fail -> {
                        val finishedStatus = _status.value.copy(
                            isComplete = true,
                            statusText = "Failed: " + action.error
                        )
                        _status.value = finishedStatus
                        if (!completionDeferred.isCompleted) {
                            completionDeferred.complete(finishedStatus)
                        }
                        return@launch
                    }

                    else -> {
                        device.dispatch(action)
                        delay(stepDelayMs)
                    }
                }

                stepIndex++
            }

            // Reached max steps without Complete/Fail
            val maxStepsFail = AgentAction.Fail("Exceeded maximum allocated steps (" + maxSteps + ")")
            val finishedStatus = _status.value.copy(
                stepIndex = stepIndex,
                lastAction = maxStepsFail,
                isComplete = true,
                statusText = "Failed: maximum steps reached"
            )
            _status.value = finishedStatus
            if (!completionDeferred.isCompleted) {
                completionDeferred.complete(finishedStatus)
            }
        }
    }

    fun abort(reason: String = "User Emergency Stop") {
        EstopSentinel.engage(reason)
        activeJob?.cancel()
        val abortedStatus = _status.value.copy(
            isAborted = true,
            statusText = "Emergency Stop Engaged: " + reason
        )
        _status.value = abortedStatus
        if (!completionDeferred.isCompleted) {
            completionDeferred.complete(abortedStatus)
        }
    }

    suspend fun awaitCompletion(): AgentStatus = completionDeferred.await()
}
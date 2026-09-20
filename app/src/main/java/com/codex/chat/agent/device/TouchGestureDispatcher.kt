package com.codex.chat.agent.device

/**
 * Pure coordinate and stroke mathematical representation.
 * Completely decoupled from android.* dependencies to allow full JVM unit testing.
 */
data class StrokeData(
    val points: List<Pair<Float, Float>>,
    val durationMs: Long
)

/**
 * Mathematical gesture path generator and bounding-box clamper.
 */
class TouchGestureDispatcher {

    fun buildTapStroke(x: Int, y: Int, screenW: Int, screenH: Int): StrokeData {
        val clampedX = x.coerceIn(0, screenW).toFloat()
        val clampedY = y.coerceIn(0, screenH).toFloat()
        return StrokeData(
            points = listOf(clampedX to clampedY),
            durationMs = 50L
        )
    }

    fun buildLongPressStroke(
        x: Int,
        y: Int,
        durationMs: Long,
        screenW: Int,
        screenH: Int
    ): StrokeData {
        val clampedX = x.coerceIn(0, screenW).toFloat()
        val clampedY = y.coerceIn(0, screenH).toFloat()
        return StrokeData(
            points = listOf(clampedX to clampedY),
            durationMs = durationMs.coerceAtLeast(100L)
        )
    }

    fun buildSwipeStroke(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long,
        screenW: Int,
        screenH: Int
    ): StrokeData {
        val sx = startX.coerceIn(0, screenW).toFloat()
        val sy = startY.coerceIn(0, screenH).toFloat()
        val ex = endX.coerceIn(0, screenW).toFloat()
        val ey = endY.coerceIn(0, screenH).toFloat()

        val steps = 10
        val points = (0..steps).map { i ->
            val t = i.toFloat() / steps
            val px = sx + (ex - sx) * t
            val py = sy + (ey - sy) * t
            px to py
        }

        return StrokeData(
            points = points,
            durationMs = durationMs.coerceAtLeast(50L)
        )
    }
}

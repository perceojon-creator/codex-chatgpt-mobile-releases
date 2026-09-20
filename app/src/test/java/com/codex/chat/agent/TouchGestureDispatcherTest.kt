package com.codex.chat.agent

import com.codex.chat.agent.device.TouchGestureDispatcher
import org.junit.Assert.*
import org.junit.Test

class TouchGestureDispatcherTest {
    private val dispatcher = TouchGestureDispatcher()

    @Test
    fun testTapStrokeDurationIs50ms() {
        val stroke = dispatcher.buildTapStroke(500, 900, 1080, 1920)
        assertEquals(50L, stroke.durationMs)
        assertEquals(1, stroke.points.size)
        assertEquals(500f, stroke.points[0].first, 0.001f)
        assertEquals(900f, stroke.points[0].second, 0.001f)
    }

    @Test
    fun testSwipeStrokeHasCorrectEndpointsAndInterpolation() {
        val stroke = dispatcher.buildSwipeStroke(100, 500, 100, 200, 300L, 1080, 1920)
        assertEquals(300L, stroke.durationMs)
        assertTrue(stroke.points.size >= 2)
        val first = stroke.points.first()
        val last = stroke.points.last()
        assertEquals(100f, first.first, 0.001f)
        assertEquals(500f, first.second, 0.001f)
        assertEquals(100f, last.first, 0.001f)
        assertEquals(200f, last.second, 0.001f)
    }

    @Test
    fun testCoordinatesAreClampedToScreenBounds() {
        val stroke = dispatcher.buildTapStroke(-50, 9999, 1080, 1920)
        val pt = stroke.points[0]
        assertEquals(0f, pt.first, 0.001f)
        assertEquals(1920f, pt.second, 0.001f)
    }

    @Test
    fun testLongPressStrokeDurationIsPreserved() {
        val stroke = dispatcher.buildLongPressStroke(500, 500, 1500L, 1080, 1920)
        assertEquals(1500L, stroke.durationMs)
        assertEquals(1, stroke.points.size)
    }
}

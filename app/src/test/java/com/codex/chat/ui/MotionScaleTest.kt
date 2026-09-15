package com.codex.chat.ui

import org.junit.Assert.*
import org.junit.Test

class MotionScaleTest {

    @Test
    fun escala_de_duraciones_es_monotona_y_material() {
        // La escala de duraciones debe ser monótona y seguir Material 3
        assertTrue(Motion.DURATION_XS < Motion.DURATION_S)
        assertTrue(Motion.DURATION_S < Motion.DURATION_M)
        assertTrue(Motion.DURATION_M < Motion.DURATION_L)
        assertTrue(Motion.DURATION_L < Motion.DURATION_XL)
        assertEquals(220L, Motion.DURATION_M) // estándar M3
    }
}
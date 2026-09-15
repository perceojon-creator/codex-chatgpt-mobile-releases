package com.codex.chat.parser

import com.codex.chat.core.media.SvgNativeRenderer
import org.junit.Assert.*
import org.junit.Test

class SvgNativeRendererTest {

    @Test
    fun validate_valid_svg_structure() {
        val validSvg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100"><circle cx="50" cy="50" r="40" fill="red" /></svg>""".trimIndent()
        val isValid = SvgNativeRenderer.isValidSvg(validSvg)
        assertTrue("Debe reconocer un SVG válido", isValid)
    }

    @Test
    fun reject_malformed_svg_gracefully() {
        val invalidSvg = "<svg><notClosed>circle" 
        val isValid = SvgNativeRenderer.isValidSvg(invalidSvg)
        assertFalse("No debe lanzar excepción en SVG malformado", isValid)
    }
}
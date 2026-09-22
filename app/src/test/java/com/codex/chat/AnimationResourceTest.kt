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
        assertTrue("Debe contener la curva parametrica oficial M 0,0 C 0.05...", content.contains("0.05"))
    }
}

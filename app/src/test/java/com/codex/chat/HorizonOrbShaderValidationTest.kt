package com.codex.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HorizonOrbShaderValidationTest {
    @Test
    fun testShadersAndNoiseTexturesExist() {
        val rawDir = File("src/main/res/raw")
        assertTrue("Directorio res/raw debe existir", rawDir.exists())
        val frag = File(rawDir, "horizon_orb_frag.fsh")
        val vert = File(rawDir, "horizon_orb_vert.vsh")
        assertTrue("horizon_orb_frag.fsh debe existir", frag.exists())
        assertTrue("horizon_orb_vert.vsh debe existir", vert.exists())
        
        val assetsDir = File("src/main/assets/horizon")
        assertTrue("Directorio assets/horizon debe existir", assetsDir.exists())
        val voronoi = File(assetsDir, "horizon_compact_baked_voronoi_position.webp")
        assertTrue("Textura voronoi debe existir", voronoi.exists())
    }
}

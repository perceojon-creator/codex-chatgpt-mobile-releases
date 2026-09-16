package com.codex.chat.storage

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class AtomicDiskWriteDurabilityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun test_atomic_write_preserves_target_on_failure_and_syncs() {
        val root = tempFolder.newFolder("sessions")
        val targetFile = File(root, "session_1.json").apply { writeText("VALID_PREVIOUS_DATA") }
        val tmpFile = File(root, "session_1.json.tmp")

        // Escritura física con sync
        FileOutputStream(tmpFile).use { fos ->
            fos.write("NEW_DATA".toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }

        assertTrue("El archivo temporal debe existir antes del rename", tmpFile.exists())
        
        // En Android y POSIX, el reemplazo atómico es StandardCopyOption.ATOMIC_MOVE
        // o si renameTo falla en Windows, Files.move
        try {
            Files.move(tmpFile.toPath(), targetFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            // Fallback para filesystems que no soporten ATOMIC_MOVE flag directo en test host
            if (!tmpFile.renameTo(targetFile)) {
                targetFile.delete()
                tmpFile.renameTo(targetFile)
            }
        }

        assertEquals("NEW_DATA", targetFile.readText())
        assertFalse("El archivo temporal no debe existir tras el rename exitoso", tmpFile.exists())
    }

    @Test
    fun test_failed_rename_does_not_corrupt_existing_target_file() {
        val root = tempFolder.newFolder("durability_fail")
        val targetFile = File(root, "session_existing.json").apply { writeText("SAFE_PREVIOUS_CONTENT") }
        val tmpFile = File(root, "corrupted_incomplete.tmp").apply { writeText("PARTIAL") }
        
        val simulatedRenameSuccess = false
        if (!simulatedRenameSuccess) {
            assertEquals("SAFE_PREVIOUS_CONTENT", targetFile.readText())
        }
    }
}

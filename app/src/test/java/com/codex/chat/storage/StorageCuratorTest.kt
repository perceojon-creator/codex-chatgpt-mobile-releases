package com.codex.chat.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageCuratorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testPurgeDirectoryRecursivelyRemovesFilesAndReportsBytes() {
        val root = tempFolder.newFolder("test_cache")
        val file1 = File(root, "file1.txt")
        file1.writeText("1234567890") // 10 bytes

        val subDir = File(root, "sub")
        subDir.mkdirs()
        val file2 = File(subDir, "file2.txt")
        file2.writeText("abcdefghij") // 10 bytes

        val (deletedCount, bytesFreed) = StorageCurator.purgeDirectoryRecursively(root)

        assertEquals(2, deletedCount)
        assertEquals(20L, bytesFreed)
        assertFalse(file1.exists())
        assertFalse(file2.exists())
        assertFalse(subDir.exists())
    }
}

package com.codex.chat.storage

import android.content.Context
import android.util.Log
import com.codex.chat.core.mcp.server.MemorySqliteStore
import java.io.File
import java.util.Locale

data class CleanupReport(
    val filesDeleted: Int,
    val bytesFreed: Long,
    val walTruncated: Boolean,
    val summary: String
)

object StorageCurator {

    private const val TAG = "StorageCurator"

    /**
     * Limpia archivos temporales, cachés volátiles de imágenes/HTTP y trunca el archivo WAL
     * de la base de datos SQLite para compactar el almacenamiento móvil.
     */
    fun performCleanup(context: Context): CleanupReport {
        var filesDeleted = 0
        var bytesFreed = 0L

        // 1. Limpieza de cacheDir
        val cacheDir = context.cacheDir
        if (cacheDir != null && cacheDir.exists()) {
            val (f, b) = purgeDirectory(cacheDir)
            filesDeleted += f
            bytesFreed += b
        }

        // 2. Limpieza de externalCacheDir (si existe)
        try {
            val extCache = context.externalCacheDir
            if (extCache != null && extCache.exists()) {
                val (f, b) = purgeDirectory(extCache)
                filesDeleted += f
                bytesFreed += b
            }
        } catch (e: Exception) {
            Log.w(TAG, "Aviso durante purga de externalCacheDir: ${e.message}")
        }

        // 3. Limpieza de archivos temporales .tmp en filesDir
        try {
            val filesDir = context.filesDir
            if (filesDir != null && filesDir.exists()) {
                val tmpFiles = filesDir.listFiles { file -> file.name.endsWith(".tmp") || file.name.startsWith("temp_") }
                if (tmpFiles != null) {
                    for (t in tmpFiles) {
                        val len = t.length()
                        if (t.delete()) {
                            filesDeleted++
                            bytesFreed += len
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Aviso durante purga de archivos temporales .tmp: ${e.message}")
        }

        // 4. Truncamiento y compactación de SQLite WAL
        var walOk = false
        try {
            val memoryStore = MemorySqliteStore.getInstance(context)
            walOk = memoryStore.truncateWal()
        } catch (e: Exception) {
            Log.w(TAG, "Aviso durante truncamiento WAL en limpieza: ${e.message}")
        }

        val mibFreed = bytesFreed.toDouble() / (1024.0 * 1024.0)
        val summary = String.format(
            Locale.US,
            "Limpieza completada: %d archivos eliminados, %.2f MiB liberados. SQLite WAL compactado: %s",
            filesDeleted,
            mibFreed,
            if (walOk) "Sí" else "No requerido"
        )

        return CleanupReport(
            filesDeleted = filesDeleted,
            bytesFreed = bytesFreed,
            walTruncated = walOk,
            summary = summary
        )
    }

    fun purgeDirectoryRecursively(dir: File): Pair<Int, Long> {
        return purgeDirectory(dir)
    }

    private fun purgeDirectory(dir: File): Pair<Int, Long> {
        var count = 0
        var bytes = 0L
        val children = dir.listFiles() ?: return Pair(0, 0L)
        for (child in children) {
            if (child.isDirectory) {
                val (subCount, subBytes) = purgeDirectory(child)
                count += subCount
                bytes += subBytes
                child.delete()
            } else {
                val len = child.length()
                if (child.delete()) {
                    count++
                    bytes += len
                }
            }
        }
        return Pair(count, bytes)
    }
}

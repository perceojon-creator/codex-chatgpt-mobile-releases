package com.codex.chat.core.swarm.blackboard

import android.content.Context
import android.util.Log
import com.codex.chat.core.subagent.SubagentLineageStore
import com.codex.chat.core.subagent.SubagentStatus
import java.util.concurrent.ConcurrentHashMap

/**
 * Pizarra Central de Coordinación del Enjambre (Tuple-Space Blackboard Architecture).
 * Permite a los subagentes obreros publicar y consultar hallazgos intermedios.
 * Respaldada por SQLite en modo Write-Ahead Logging (WAL) para persistencia ante caídas de proceso.
 */
class SwarmBlackboard(
    private val context: Context? = null
) {
    // Memoria intermedia en concurrente para baja latencia en memoria
    private val memoryStore = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
    private val lineageStore: SubagentLineageStore? = context?.let { SubagentLineageStore.getInstance(it) }

    fun postFinding(sessionId: String, taskId: String, key: String, data: String): Boolean {
        val sessionMap = memoryStore.computeIfAbsent(sessionId) { ConcurrentHashMap() }
        sessionMap[key] = data

        lineageStore?.let { store ->
            try {
                store.recordSubagentStart(
                    parentSessionId = sessionId,
                    subagentId = taskId + "_" + key,
                    name = key,
                    description = "Finding: " + key,
                    prompt = key
                )
                store.recordSubagentCompletion(
                    subagentId = taskId + "_" + key,
                    status = SubagentStatus.COMPLETED,
                    resultOutput = data
                )
            } catch (e: Exception) {
                Log.w("SwarmBlackboard", "Aviso: no se pudo persistir linaje en SQLite (fallback en memoria activo): ${e.message}")
            }
        }
        return true
    }

    fun getFinding(sessionId: String, key: String): String? {
        return memoryStore[sessionId]?.get(key)
    }

    fun getAllFindings(sessionId: String): Map<String, String> {
        return memoryStore[sessionId]?.toMap() ?: emptyMap()
    }

    fun clearSession(sessionId: String) {
        memoryStore.remove(sessionId)
        lineageStore?.deleteLineageForSession(sessionId)
    }
}
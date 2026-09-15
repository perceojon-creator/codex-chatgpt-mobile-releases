package com.codex.chat.core.checkpoint

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONArray

data class SessionCheckpoint(
    val id: Long = 0L,
    val sessionId: String,
    val summary: String,
    val facts: List<String>,
    val goals: List<String>,
    val filesTouched: List<String>,
    val createdAt: Long
)

/**
 * Almacenamiento de puntos de control de sesión persistente en SQLite con WAL (Paridad con DSH memory.cjs checkpoint).
 * Resiste Process Death, Memory Trimming y reinicios de la aplicación sin pérdida de contexto ni tokens de re-lectura.
 */
class SessionCheckpointStore(context: Context, dbName: String = "codex_checkpoints.db") {

    companion object {
        private const val TAG = "SessionCheckpointStore"
        private const val DB_VERSION = 1
        private const val TABLE_NAME = "session_checkpoints"

        @Volatile
        private var instance: SessionCheckpointStore? = null

        fun getInstance(context: Context): SessionCheckpointStore {
            return instance ?: synchronized(this) {
                instance ?: SessionCheckpointStore(context.applicationContext).also { instance = it }
            }
        }
    }

    private inner class DbHelper(ctx: Context, name: String) : SQLiteOpenHelper(ctx, name, null, DB_VERSION) {
        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            try {
                db.enableWriteAheadLogging()
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo habilitar WAL: ${e.message}")
            }
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS $TABLE_NAME (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "session_id TEXT NOT NULL, " +
                "summary TEXT NOT NULL, " +
                "facts_json TEXT NOT NULL, " +
                "goals_json TEXT NOT NULL, " +
                "files_json TEXT NOT NULL, " +
                "created_at INTEGER NOT NULL);"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_chk_session ON $TABLE_NAME(session_id);")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_chk_created ON $TABLE_NAME(created_at);")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
            onCreate(db)
        }
    }

    private val dbHelper = DbHelper(context, dbName)

    fun saveCheckpoint(
        sessionId: String,
        summary: String,
        facts: List<String> = emptyList(),
        goals: List<String> = emptyList(),
        files: List<String> = emptyList()
    ): Long {
        val now = System.currentTimeMillis()
        val factsJson = JSONArray(facts).toString()
        val goalsJson = JSONArray(goals).toString()
        val filesJson = JSONArray(files).toString()

        val values = ContentValues().apply {
            put("session_id", sessionId)
            put("summary", summary)
            put("facts_json", factsJson)
            put("goals_json", goalsJson)
            put("files_json", filesJson)
            put("created_at", now)
        }

        val db = dbHelper.writableDatabase
        return db.insert(TABLE_NAME, null, values)
    }

    fun getLatestCheckpoint(sessionId: String): SessionCheckpoint? {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT id, session_id, summary, facts_json, goals_json, files_json, created_at " +
            "FROM $TABLE_NAME WHERE session_id = ? ORDER BY created_at DESC, id DESC LIMIT 1",
            arrayOf(sessionId)
        )
        return cursor.use {
            if (it.moveToFirst()) {
                val id = it.getLong(0)
                val sId = it.getString(1)
                val summary = it.getString(2)
                val facts = parseJsonStringList(it.getString(3))
                val goals = parseJsonStringList(it.getString(4))
                val files = parseJsonStringList(it.getString(5))
                val createdAt = it.getLong(6)
                SessionCheckpoint(id, sId, summary, facts, goals, files, createdAt)
            } else null
        }
    }

    fun listCheckpoints(sessionId: String, limit: Int = 10): List<SessionCheckpoint> {
        val db = dbHelper.readableDatabase
        val list = mutableListOf<SessionCheckpoint>()
        val cursor = db.rawQuery(
            "SELECT id, session_id, summary, facts_json, goals_json, files_json, created_at " +
            "FROM $TABLE_NAME WHERE session_id = ? ORDER BY created_at DESC, id DESC LIMIT ?",
            arrayOf(sessionId, limit.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    SessionCheckpoint(
                        id = it.getLong(0),
                        sessionId = it.getString(1),
                        summary = it.getString(2),
                        facts = parseJsonStringList(it.getString(3)),
                        goals = parseJsonStringList(it.getString(4)),
                        filesTouched = parseJsonStringList(it.getString(5)),
                        createdAt = it.getLong(6)
                    )
                )
            }
        }
        return list
    }

    fun deleteCheckpointsForSession(sessionId: String): Int {
        val db = dbHelper.writableDatabase
        return db.delete(TABLE_NAME, "session_id = ?", arrayOf(sessionId))
    }

    fun pruneOldCheckpoints(sessionId: String? = null, keepPerSession: Int = 5) {
        val db = dbHelper.writableDatabase
        if (sessionId != null) {
            db.execSQL(
                "DELETE FROM $TABLE_NAME WHERE session_id = ? AND id NOT IN (" +
                "SELECT id FROM $TABLE_NAME WHERE session_id = ? ORDER BY id DESC LIMIT ?" +
                ");",
                arrayOf(sessionId, sessionId, keepPerSession.toString())
            )
        } else {
            val cursor = db.rawQuery("SELECT DISTINCT session_id FROM $TABLE_NAME", null)
            val sessionIds = mutableListOf<String>()
            cursor.use {
                while (it.moveToNext()) {
                    sessionIds.add(it.getString(0))
                }
            }
            for (sId in sessionIds) {
                db.execSQL(
                    "DELETE FROM $TABLE_NAME WHERE session_id = ? AND id NOT IN (" +
                    "SELECT id FROM $TABLE_NAME WHERE session_id = ? ORDER BY id DESC LIMIT ?" +
                    ");",
                    arrayOf(sId, sId, keepPerSession.toString())
                )
            }
        }
    }

    private fun parseJsonStringList(jsonStr: String?): List<String> {
        if (jsonStr.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                list.add(arr.getString(i))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }
}

package com.codex.chat.core.subagent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

enum class SubagentStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class SubagentRecord(
    val id: Long = 0L,
    val parentSessionId: String,
    val subagentId: String,
    val name: String,
    val status: SubagentStatus,
    val description: String,
    val prompt: String,
    val resultOutput: String?,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Registro y trazabilidad genealógica de subagentes en SQLite con WAL (Paridad con DSH memory.cjs record-subagent).
 * Rastrea la delegación de subtareas, prompts aislados, estados y resultados devueltos.
 */
class SubagentLineageStore(context: Context, dbName: String = "codex_subagent_lineage.db") {

    companion object {
        private const val TAG = "SubagentLineageStore"
        private const val DB_VERSION = 1
        private const val TABLE_NAME = "subagent_lineage"

        @Volatile
        private var instance: SubagentLineageStore? = null

        fun getInstance(context: Context): SubagentLineageStore {
            return instance ?: synchronized(this) {
                instance ?: SubagentLineageStore(context.applicationContext).also { instance = it }
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
                "parent_session_id TEXT NOT NULL, " +
                "subagent_id TEXT UNIQUE NOT NULL, " +
                "name TEXT NOT NULL, " +
                "status TEXT NOT NULL, " +
                "description TEXT NOT NULL, " +
                "prompt TEXT NOT NULL, " +
                "result_output TEXT, " +
                "created_at INTEGER NOT NULL, " +
                "updated_at INTEGER NOT NULL);"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_sub_parent ON $TABLE_NAME(parent_session_id);")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_sub_status ON $TABLE_NAME(status);")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
            onCreate(db)
        }
    }

    private val dbHelper = DbHelper(context, dbName)

    fun recordSubagentStart(
        parentSessionId: String,
        subagentId: String,
        name: String,
        description: String,
        prompt: String
    ): Long {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("parent_session_id", parentSessionId)
            put("subagent_id", subagentId)
            put("name", name)
            put("status", SubagentStatus.RUNNING.name)
            put("description", description)
            put("prompt", prompt)
            put("result_output", null as String?)
            put("created_at", now)
            put("updated_at", now)
        }

        val db = dbHelper.writableDatabase
        return db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun recordSubagentCompletion(
        subagentId: String,
        status: SubagentStatus,
        resultOutput: String?
    ): Boolean {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("status", status.name)
            put("result_output", resultOutput)
            put("updated_at", now)
        }

        val db = dbHelper.writableDatabase
        val rows = db.update(TABLE_NAME, values, "subagent_id = ?", arrayOf(subagentId))
        return rows > 0
    }

    fun getSubagent(subagentId: String): SubagentRecord? {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT id, parent_session_id, subagent_id, name, status, description, prompt, result_output, created_at, updated_at " +
            "FROM $TABLE_NAME WHERE subagent_id = ? LIMIT 1",
            arrayOf(subagentId)
        )
        return cursor.use {
            if (it.moveToFirst()) {
                SubagentRecord(
                    id = it.getLong(0),
                    parentSessionId = it.getString(1),
                    subagentId = it.getString(2),
                    name = it.getString(3),
                    status = SubagentStatus.valueOf(it.getString(4)),
                    description = it.getString(5),
                    prompt = it.getString(6),
                    resultOutput = it.getString(7),
                    createdAt = it.getLong(8),
                    updatedAt = it.getLong(9)
                )
            } else null
        }
    }

    fun listSubagentsForSession(parentSessionId: String): List<SubagentRecord> {
        val db = dbHelper.readableDatabase
        val list = mutableListOf<SubagentRecord>()
        val cursor = db.rawQuery(
            "SELECT id, parent_session_id, subagent_id, name, status, description, prompt, result_output, created_at, updated_at " +
            "FROM $TABLE_NAME WHERE parent_session_id = ? ORDER BY created_at ASC",
            arrayOf(parentSessionId)
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    SubagentRecord(
                        id = it.getLong(0),
                        parentSessionId = it.getString(1),
                        subagentId = it.getString(2),
                        name = it.getString(3),
                        status = SubagentStatus.valueOf(it.getString(4)),
                        description = it.getString(5),
                        prompt = it.getString(6),
                        resultOutput = it.getString(7),
                        createdAt = it.getLong(8),
                        updatedAt = it.getLong(9)
                    )
                )
            }
        }
        return list
    }

    fun deleteLineageForSession(parentSessionId: String): Int {
        val db = dbHelper.writableDatabase
        return db.delete(TABLE_NAME, "parent_session_id = ?", arrayOf(parentSessionId))
    }
}

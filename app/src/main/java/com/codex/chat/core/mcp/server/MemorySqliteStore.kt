package com.codex.chat.core.mcp.server

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

class MemorySqliteStore(private val context: Context, private val dbFile: File? = null) {

    companion object {
        private const val DB_NAME = "mcp_memory.sqlite"
        private const val DB_VERSION = 2
        private const val MAX_WAL_SIZE_BYTES = 64L * 1024L * 1024L
        private const val TAG = "MEMORY_SQLITE"

        @Volatile
        private var instance: MemorySqliteStore? = null

        fun getInstance(context: Context): MemorySqliteStore {
            return instance ?: synchronized(this) {
                instance ?: MemorySqliteStore(context.applicationContext).also { instance = it }
            }
        }
    }

    private var isFts5Supported = false

    private inner class DatabaseHelper(context: Context, name: String?) :
        SQLiteOpenHelper(context, name, null, DB_VERSION) {

        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            try {
                db.enableWriteAheadLogging()
            } catch (e: Exception) {
                Log.w(TAG, "WAL enable error", e)
            }
        }

        override fun onCreate(db: SQLiteDatabase) {
            createSchema(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS memories_fts")
            db.execSQL("DROP TABLE IF EXISTS memories")
            createSchema(db)
        }

        private fun createSchema(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS memories (" +
                "key TEXT PRIMARY KEY, " +
                "value TEXT NOT NULL, " +
                "category TEXT NOT NULL DEFAULT 'general', " +
                "created_at INTEGER NOT NULL, " +
                "updated_at INTEGER NOT NULL);"
            )

            // Intenta FTS5 primero; si el SO no lo incluye, usa FTS4 (estándar universal en Android)
            try {
                db.execSQL(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(" +
                    "key, value, category, tokenize = 'unicode61');"
                )
                isFts5Supported = true
                Log.i(TAG, "Virtual table initialized with FTS5")
            } catch (e: Throwable) {
                Log.w(TAG, "FTS5 not present in OS SQLite, falling back to FTS4: " + e.message)
                try {
                    db.execSQL(
                        "CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts4(" +
                        "key, value, category, tokenize=unicode61);"
                    )
                    isFts5Supported = false
                    Log.i(TAG, "Virtual table initialized with FTS4")
                } catch (e2: Throwable) {
                    Log.e(TAG, "Neither FTS5 nor FTS4 supported: " + e2.message)
                }
            }
        }
    }

    private val dbHelper: DatabaseHelper = if (dbFile != null) {
        DatabaseHelper(context, dbFile.absolutePath)
    } else {
        DatabaseHelper(context, DB_NAME)
    }

    private val lock = Any()
    var isAvailable: Boolean = false
        private set

    init {
        try {
            val db = dbHelper.writableDatabase
            db.rawQuery("PRAGMA journal_mode = WAL;", null).use { it.moveToFirst() }
            db.rawQuery("PRAGMA synchronous = NORMAL;", null).use { it.moveToFirst() }
            checkAndTruncateWal(db)
            isAvailable = true
        } catch (e: Throwable) {
            Log.e(TAG, "Init failed", e)
            isAvailable = false
        }
    }

    fun save(key: String, value: String, category: String = "general"): Boolean {
        if (key.isBlank()) return false
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                val cv = ContentValues().apply {
                    put("key", key.trim())
                    put("value", value.trim())
                    put("category", category.trim().lowercase(Locale.ROOT))
                    put("updated_at", now)
                }

                val exists = db.rawQuery("SELECT 1 FROM memories WHERE key = ?", arrayOf(key.trim())).use {
                    it.moveToFirst()
                }

                if (exists) {
                    db.update("memories", cv, "key = ?", arrayOf(key.trim()))
                    try {
                        db.execSQL("DELETE FROM memories_fts WHERE key = ?", arrayOf(key.trim()))
                    } catch (e: Exception) {
                        // ignore if fts table missing
                    }
                } else {
                    cv.put("created_at", now)
                    db.insert("memories", null, cv)
                }

                try {
                    db.execSQL(
                        "INSERT INTO memories_fts(key, value, category) VALUES (?, ?, ?)",
                        arrayOf(key.trim(), value.trim(), category.trim().lowercase(Locale.ROOT))
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "FTS insert ignored: " + e.message)
                }

                db.setTransactionSuccessful()
                checkAndTruncateWal(db)
                return true
            } catch (e: Throwable) {
                Log.e(TAG, "Save failed for key: $key", e)
                throw e
            } finally {
                db.endTransaction()
            }
        }
    }

    fun get(key: String): String? {
        if (key.isBlank()) return null
        synchronized(lock) {
            val db = dbHelper.readableDatabase
            db.rawQuery("SELECT value FROM memories WHERE key = ?", arrayOf(key.trim())).use { cursor ->
                if (cursor.moveToFirst()) {
                    return cursor.getString(0)
                }
            }
        }
        return null
    }

    fun searchFts5(query: String, limit: Int = 10): JSONArray {
        val results = JSONArray()
        if (query.isBlank()) return results
        val cleanQuery = query.trim().filter { it != '"' && it != '\'' }
        if (cleanQuery.isEmpty()) return results

        val tokens = cleanQuery.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return results

        synchronized(lock) {
            val db = dbHelper.readableDatabase

            // 1. Intento con FTS
            var ftsSuccess = false
            try {
                val ftsQuery = tokens.joinToString(" ") { "$it*" }
                val sql = if (isFts5Supported) {
                    "SELECT m.key, m.value, m.category, m.updated_at, " +
                    "snippet(memories_fts, 1, '<b>', '</b>', '...', 15) as snippet_text, " +
                    "bm25(memories_fts) as rank " +
                    "FROM memories_fts " +
                    "JOIN memories m ON m.key = memories_fts.key " +
                    "WHERE memories_fts MATCH ? " +
                    "ORDER BY rank ASC " +
                    "LIMIT ?;"
                } else {
                    "SELECT m.key, m.value, m.category, m.updated_at, " +
                    "snippet(memories_fts, '<b>', '</b>', '...', -1, 15) as snippet_text, " +
                    "1.0 as rank " +
                    "FROM memories_fts " +
                    "JOIN memories m ON m.key = memories_fts.key " +
                    "WHERE memories_fts MATCH ? " +
                    "LIMIT ?;"
                }

                db.rawQuery(sql, arrayOf(ftsQuery, limit.coerceIn(1, 50).toString())).use { cursor ->
                    while (cursor.moveToNext()) {
                        val item = JSONObject().apply {
                            put("key", cursor.getString(0))
                            put("value", cursor.getString(1))
                            put("category", cursor.getString(2))
                            put("updated_at", cursor.getLong(3))
                            put("snippet", cursor.getString(4))
                            put("score_bm25", cursor.getDouble(5))
                        }
                        results.put(item)
                    }
                }
                ftsSuccess = true
            } catch (e: Exception) {
                Log.w(TAG, "FTS MATCH failed, falling back to multi-token LIKE: " + e.message)
            }

            // 2. Fallback multi-token LIKE si FTS falló o no retornó
            if (!ftsSuccess || results.length() == 0) {
                try {
                    val whereClauses = tokens.map { "(key LIKE ? OR value LIKE ?)" }.joinToString(" AND ")
                    val whereArgs = mutableListOf<String>()
                    for (t in tokens) {
                        val p = "%$t%"
                        whereArgs.add(p)
                        whereArgs.add(p)
                    }
                    whereArgs.add(limit.toString())

                    val fallbackSql = "SELECT key, value, category, updated_at FROM memories WHERE $whereClauses LIMIT ?;"
                    db.rawQuery(fallbackSql, whereArgs.toTypedArray()).use { cursor ->
                        while (cursor.moveToNext()) {
                            val key = cursor.getString(0)
                            // Evitar duplicados si FTS ya tenía alguno
                            var alreadyPresent = false
                            for (i in 0 until results.length()) {
                                if (results.getJSONObject(i).optString("key") == key) {
                                    alreadyPresent = true
                                    break
                                }
                            }
                            if (!alreadyPresent) {
                                val item = JSONObject().apply {
                                    put("key", key)
                                    put("value", cursor.getString(1))
                                    put("category", cursor.getString(2))
                                    put("updated_at", cursor.getLong(3))
                                    put("snippet", cursor.getString(1).take(80))
                                    put("score_bm25", 1.0)
                                }
                                results.put(item)
                            }
                        }
                    }
                } catch (e2: Exception) {
                    Log.e(TAG, "Multi-token LIKE fallback failed", e2)
                }
            }
        }
        return results
    }

    fun list(category: String? = null, limit: Int = 100): JSONArray {
        val results = JSONArray()
        synchronized(lock) {
            val db = dbHelper.readableDatabase
            val sql: String
            val args: Array<String>
            if (category.isNullOrBlank()) {
                sql = "SELECT key, value, category, updated_at FROM memories ORDER BY updated_at DESC LIMIT ?"
                args = arrayOf(limit.toString())
            } else {
                sql = "SELECT key, value, category, updated_at FROM memories WHERE category = ? ORDER BY updated_at DESC LIMIT ?"
                args = arrayOf(category.trim().lowercase(Locale.ROOT), limit.toString())
            }

            db.rawQuery(sql, args).use { cursor ->
                while (cursor.moveToNext()) {
                    results.put(JSONObject().apply {
                        put("key", cursor.getString(0))
                        put("value", cursor.getString(1))
                        put("category", cursor.getString(2))
                        put("updated_at", cursor.getLong(3))
                    })
                }
            }
        }
        return results
    }

    fun delete(key: String): Boolean {
        if (key.isBlank()) return false
        synchronized(lock) {
            val db = dbHelper.writableDatabase
            db.beginTransaction()
            try {
                val deleted = db.delete("memories", "key = ?", arrayOf(key.trim())) > 0
                if (deleted) {
                    try {
                        db.execSQL("DELETE FROM memories_fts WHERE key = ?", arrayOf(key.trim()))
                    } catch (e: Exception) {
                        // ignore
                    }
                }
                db.setTransactionSuccessful()
                return deleted
            } finally {
                db.endTransaction()
            }
        }
    }

    fun walStatus(): JSONObject {
        synchronized(lock) {
            val db = dbHelper.readableDatabase
            val dbPath = db.path
            val walFile = File(dbPath + "-wal")
            val walSize = if (walFile.exists()) walFile.length() else 0L

            var journalMode = "unknown"
            try {
                db.rawQuery("PRAGMA journal_mode;", null).use {
                    if (it.moveToFirst()) journalMode = it.getString(0)
                }
            } catch (e: Exception) {}

            var count = 0
            try {
                db.rawQuery("SELECT COUNT(*) FROM memories;", null).use {
                    if (it.moveToFirst()) count = it.getInt(0)
                }
            } catch (e: Exception) {}

            return JSONObject().apply {
                put("database_path", dbPath)
                put("wal_path", walFile.absolutePath)
                put("wal_exists", walFile.exists())
                put("wal_size_bytes", walSize)
                put("wal_size_mib", String.format(Locale.US, "%.2f", walSize.toDouble() / (1024 * 1024)))
                put("journal_mode", journalMode)
                put("total_records", count)
                put("fts_engine", if (isFts5Supported) "FTS5" else "FTS4")
            }
        }
    }

    fun truncateWal(): Boolean {
        synchronized(lock) {
            val db = dbHelper.writableDatabase
            return try {
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE);", null).use { cursor ->
                    cursor.moveToFirst()
                }
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    private fun checkAndTruncateWal(db: SQLiteDatabase) {
        try {
            val walFile = File(db.path + "-wal")
            if (walFile.exists() && walFile.length() > MAX_WAL_SIZE_BYTES) {
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE);", null).use { it.moveToFirst() }
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    fun close() {
        try {
            dbHelper.close()
        } catch (e: Exception) {
            // Ignore
        }
    }
}

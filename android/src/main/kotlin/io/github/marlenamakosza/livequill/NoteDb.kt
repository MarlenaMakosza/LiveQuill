package io.github.marlenamakosza.livequill

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.marlenamakosza.livequill.shared.Op
import io.github.marlenamakosza.livequill.shared.Proto
import io.github.marlenamakosza.livequill.shared.SeqOp

/**
 * Local storage.
 * - ops: every op this tablet knows, local and remote.
 * - outbox: local ops the server did not ack yet. AUTOINCREMENT keeps seq numbers unique forever.
 * - kv: settings and sync cursors.
 */
class NoteDb(ctx: Context) : SQLiteOpenHelper(ctx, "notes.db", null, 1) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE ops (id TEXT PRIMARY KEY, json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE outbox (seq INTEGER PRIMARY KEY AUTOINCREMENT, json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE kv (k TEXT PRIMARY KEY, v TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private inline fun <T> tx(block: SQLiteDatabase.() -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try {
            return db.block().also { db.setTransactionSuccessful() }
        } finally {
            db.endTransaction()
        }
    }

    private fun SQLiteDatabase.insertOp(op: Op, json: String): Boolean {
        val cv = ContentValues().apply {
            put("id", op.id)
            put("json", json)
        }
        return insertWithOnConflict("ops", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    /** Stores a local op and queues it for the server in one transaction. */
    fun insertLocal(op: Op) = tx {
        val json = Proto.encodeOp(op)
        insertOp(op, json)
        insert("outbox", null, ContentValues().apply { put("json", json) })
    }

    /** Stores remote ops. Returns only the ops not seen before. */
    fun insertRemote(items: List<SeqOp>): List<Op> = tx {
        val fresh = items.map { it.op }.filter { insertOp(it, Proto.encodeOp(it)) }
        // Cursor moves by received items only. The server head may be ahead while chunks are still coming.
        val cursor = maxOf(kvLong("lastServerSeq"), items.maxOfOrNull { it.seq } ?: 0)
        setKv("lastServerSeq", cursor.toString())
        fresh
    }

    fun allOps(): List<Op> =
        readableDatabase.rawQuery("SELECT json FROM ops ORDER BY rowid", null).use { c ->
            buildList { while (c.moveToNext()) runCatching { Proto.decodeOp(c.getString(0)) }.onSuccess(::add) }
        }

    fun outbox(after: Long, limit: Int): List<SeqOp> =
        readableDatabase.rawQuery(
            "SELECT seq, json FROM outbox WHERE seq > ? ORDER BY seq LIMIT ?",
            arrayOf(after.toString(), limit.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(SeqOp(c.getLong(0), Proto.decodeOp(c.getString(1)))) }
        }

    fun ackOutbox(upTo: Long) {
        writableDatabase.delete("outbox", "seq <= ?", arrayOf(upTo.toString()))
    }

    fun outboxCount(): Long = DatabaseUtils.queryNumEntries(readableDatabase, "outbox")

    fun kv(k: String): String? =
        readableDatabase.rawQuery("SELECT v FROM kv WHERE k = ?", arrayOf(k)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    fun kvLong(k: String): Long = kv(k)?.toLongOrNull() ?: 0

    fun setKv(k: String, v: String?) {
        writableDatabase.insertWithOnConflict(
            "kv", null,
            ContentValues().apply {
                put("k", k)
                put("v", v)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }
}

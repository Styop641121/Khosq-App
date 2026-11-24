package am.khosq.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * My tasks, kept in a small database on the phone only (the app's private folder, outside any backup).
 * Screens read [tasks]; every change reloads it. Every function is quick and safe to call from any thread,
 * but none belongs on the main thread's hot path, so callers use a background thread.
 */
object TaskStore {
    private val lock = Any()
    private var helper: Helper? = null
    private val state = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = state.asStateFlow()

    /** Called once when the app starts. Opens nothing yet; the first list is read in the background. */
    fun init(ctx: Context) {
        synchronized(lock) {
            if (helper != null) return
            helper = Helper(ctx.applicationContext)
        }
        Thread {
            try {
                publish()
            } catch (e: Exception) {
                // the list stays empty until the next change
            }
        }.start()
    }

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "tasks.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE tasks (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "title TEXT NOT NULL, " +
                    "note TEXT NOT NULL DEFAULT '', " +
                    "due INTEGER NOT NULL DEFAULT 0, " +
                    "remind INTEGER NOT NULL DEFAULT 0, " +
                    "remind_at INTEGER NOT NULL DEFAULT 0, " +
                    "done INTEGER NOT NULL DEFAULT 0, " +
                    "done_at INTEGER NOT NULL DEFAULT 0, " +
                    "created INTEGER NOT NULL DEFAULT 0, " +
                    "chat_id TEXT NOT NULL DEFAULT '', " +
                    "chat_name TEXT NOT NULL DEFAULT '', " +
                    "msg_id TEXT NOT NULL DEFAULT '')",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // version 1 is the first one; later versions add their columns here
        }
    }

    private fun db(): SQLiteDatabase {
        val h = helper ?: throw IllegalStateException("TaskStore.init was not called")
        return h.writableDatabase
    }

    private fun values(t: Task): ContentValues {
        val v = ContentValues()
        v.put("title", t.title)
        v.put("note", t.note)
        v.put("due", t.due)
        v.put("remind", if (t.remind) 1 else 0)
        v.put("remind_at", t.remindAt)
        v.put("done", if (t.done) 1 else 0)
        v.put("done_at", t.doneAt)
        v.put("created", t.created)
        v.put("chat_id", t.chatId)
        v.put("chat_name", t.chatName)
        v.put("msg_id", t.msgId)
        return v
    }

    private fun row(c: Cursor): Task = Task(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        title = c.getString(c.getColumnIndexOrThrow("title")) ?: "",
        note = c.getString(c.getColumnIndexOrThrow("note")) ?: "",
        due = c.getLong(c.getColumnIndexOrThrow("due")),
        remind = c.getInt(c.getColumnIndexOrThrow("remind")) != 0,
        remindAt = c.getLong(c.getColumnIndexOrThrow("remind_at")),
        done = c.getInt(c.getColumnIndexOrThrow("done")) != 0,
        doneAt = c.getLong(c.getColumnIndexOrThrow("done_at")),
        created = c.getLong(c.getColumnIndexOrThrow("created")),
        chatId = c.getString(c.getColumnIndexOrThrow("chat_id")) ?: "",
        chatName = c.getString(c.getColumnIndexOrThrow("chat_name")) ?: "",
        msgId = c.getString(c.getColumnIndexOrThrow("msg_id")) ?: "",
    )

    private fun readWhere(where: String?, args: Array<String>?): List<Task> {
        val out = ArrayList<Task>()
        val c = db().query("tasks", null, where, args, null, null, "id ASC")
        try {
            while (c.moveToNext()) out.add(row(c))
        } finally {
            c.close()
        }
        return out
    }

    private fun publish() {
        synchronized(lock) {
            state.value = readWhere(null, null)
        }
    }

    fun get(id: Long): Task? = synchronized(lock) {
        readWhere("id = ?", arrayOf(id.toString())).firstOrNull()
    }

    /** Tasks whose reminder is still waiting. */
    fun waiting(): List<Task> = synchronized(lock) {
        readWhere("remind_at > 0 AND done = 0", null)
    }

    /** Stores a new task and returns it with its number; null when the list is full. */
    fun insert(t: Task): Task? = synchronized(lock) {
        val d = db()
        val n = android.database.DatabaseUtils.queryNumEntries(d, "tasks")
        if (n >= TaskLogic.MAX_TASKS) return@synchronized null
        val id = d.insert("tasks", null, values(t))
        if (id < 0L) return@synchronized null
        publish()
        t.copy(id = id)
    }

    fun update(t: Task) {
        synchronized(lock) {
            db().update("tasks", values(t), "id = ?", arrayOf(t.id.toString()))
            publish()
        }
    }

    fun delete(id: Long) {
        synchronized(lock) {
            db().delete("tasks", "id = ?", arrayOf(id.toString()))
            publish()
        }
    }

    /** Removes every finished task and returns their numbers. */
    fun clearDone(): List<Long> = synchronized(lock) {
        val ids = readWhere("done = 1", null).map { x -> x.id }
        db().delete("tasks", "done = 1", null)
        publish()
        ids
    }

    /**
     * The reminder of task [id] is going off now. Returns the task only the first time and only when its reminder
     * is really due, and marks it as rung, so a reminder is never shown twice even if two parts of the app notice it.
     */
    fun claimFire(id: Long, now: Long): Task? = synchronized(lock) {
        val t = readWhere("id = ?", arrayOf(id.toString())).firstOrNull() ?: return@synchronized null
        if (t.done || t.remindAt <= 0L || t.remindAt > now + TaskLogic.SLACK_MS) return@synchronized null
        val v = ContentValues()
        v.put("remind_at", 0L)
        db().update("tasks", v, "id = ?", arrayOf(id.toString()))
        publish()
        t
    }
}

package am.khosq.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The key that seals the saved texts. It is 32 random bytes, kept on the phone sealed by a key that never leaves the
 * phone's secure hardware (Android Keystore). The raw key is only held in memory while the app runs, so a search
 * opens thousands of texts quickly, without asking the secure hardware every time.
 */
private object MemoryKey {
    private const val ALIAS = "khosq_memory_wrap"
    private const val PREFS = "khosq_memory"
    private const val STORED = "k"

    @Volatile
    private var raw: ByteArray? = null

    /** True once, when the old key could not be read any more and a new one had to be made: the saved texts are gone. */
    @Volatile
    var renewed = false

    private fun wrapper(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        val have = ks.getKey(ALIAS, null) as? SecretKey
        if (have != null) return have
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return g.generateKey()
    }

    private fun wrap(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wrapper())
        return c.iv + c.doFinal(plain)
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, wrapper(), GCMParameterSpec(128, blob, 0, 12))
        return c.doFinal(blob, 12, blob.size - 12)
    }

    @Synchronized
    fun get(ctx: Context): ByteArray {
        val have = raw
        if (have != null) return have
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(STORED, null)
        if (stored != null) {
            try {
                val k = unwrap(Base64.decode(stored, Base64.NO_WRAP))
                raw = k
                return k
            } catch (e: Exception) {
                // the phone lost the wrapping key (a reset of the secure hardware, a restore): the old texts cannot be opened
                renewed = true
            }
        }
        val fresh = ByteArray(32)
        SecureRandom().nextBytes(fresh)
        val sealed = Base64.encodeToString(wrap(fresh), Base64.NO_WRAP)
        // written at once (not "apply"): a text sealed with this key must never outlive the key
        if (!prefs.edit().putString(STORED, sealed).commit()) throw IllegalStateException("could not store the Memory key")
        raw = fresh
        return fresh
    }
}

/**
 * KHOSQ Memory: the texts of the chats I switched Memory on for, kept only on this phone, each one sealed
 * ([MemorySeal]) with a key of the phone's secure hardware. Nothing is sent anywhere. A chat with Memory off has no rows.
 * Safe to call from any thread, but never from the main thread's hot path: callers use a background thread.
 */
object MemoryStore {
    private val lock = Any()
    private var helper: Helper? = null
    private var appCtx: Context? = null
    private val state = MutableStateFlow<Map<String, MemChat>>(emptyMap())

    /** The chats with Memory on, by chat number. */
    val chats: StateFlow<Map<String, MemChat>> = state.asStateFlow()

    // per chat: message number to a mark of what was saved last, so an unchanged message is not written again
    private val seen = HashMap<String, HashMap<String, Int>>()
    private const val GONE = Int.MIN_VALUE

    // an item the person changed by hand, or took out: the chat's own text never overwrites it again
    private const val SAVED = 0
    private const val EDITED = 1
    private const val REMOVED = 2

    fun init(ctx: Context) {
        synchronized(lock) {
            if (helper != null) return
            appCtx = ctx.applicationContext
            helper = Helper(ctx.applicationContext)
        }
        Thread {
            try {
                synchronized(lock) { publish() }
            } catch (e: Exception) {
                // the list stays empty until the next change
            }
        }.start()
    }

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "memory.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE mem_chats (chat_id TEXT PRIMARY KEY, name TEXT NOT NULL, since INTEGER NOT NULL, synced INTEGER NOT NULL DEFAULT 0)")
            db.execSQL(
                "CREATE TABLE mem_items (" +
                    "chat_id TEXT NOT NULL, msg_id TEXT NOT NULL, ts INTEGER NOT NULL, mine INTEGER NOT NULL, " +
                    "who TEXT NOT NULL DEFAULT '', body BLOB NOT NULL, state INTEGER NOT NULL DEFAULT 0, " +
                    "PRIMARY KEY (chat_id, msg_id))",
            )
            db.execSQL("CREATE INDEX mem_items_ts ON mem_items (ts)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // version 1 is the first one; later versions change the tables here
        }
    }

    private fun db(): SQLiteDatabase {
        val h = helper ?: throw IllegalStateException("MemoryStore.init was not called")
        return h.writableDatabase
    }

    /** The key; if the phone had to make a new one, everything saved with the old one is dropped first. */
    private fun key(): ByteArray {
        val ctx = appCtx ?: throw IllegalStateException("MemoryStore.init was not called")
        val k = MemoryKey.get(ctx)
        if (MemoryKey.renewed) {
            MemoryKey.renewed = false
            db().delete("mem_items", null, null)
            seen.clear()
            publish()
        }
        return k
    }

    private fun publish() {
        val d = db()
        val counts = HashMap<String, Int>()
        val c1 = d.rawQuery("SELECT chat_id, COUNT(*) FROM mem_items WHERE state != $REMOVED GROUP BY chat_id", null)
        try {
            while (c1.moveToNext()) counts[c1.getString(0)] = c1.getInt(1)
        } finally {
            c1.close()
        }
        val out = LinkedHashMap<String, MemChat>()
        val c2 = d.rawQuery("SELECT chat_id, name, since, synced FROM mem_chats ORDER BY name COLLATE NOCASE", null)
        try {
            while (c2.moveToNext()) {
                val id = c2.getString(0)
                out[id] = MemChat(id, c2.getString(1) ?: "", c2.getLong(2), c2.getLong(3), counts[id] ?: 0)
            }
        } finally {
            c2.close()
        }
        state.value = out
    }

    fun isOn(chatId: String): Boolean = state.value.containsKey(chatId)

    /** Switches Memory on for a chat. Nothing is saved until the chat's messages are given to [sync]. */
    fun enable(chatId: String, name: String) {
        synchronized(lock) {
            val d = db()
            val v = ContentValues()
            v.put("chat_id", chatId)
            v.put("name", name)
            v.put("since", System.currentTimeMillis())
            v.put("synced", 0L)
            if (d.insertWithOnConflict("mem_chats", null, v, SQLiteDatabase.CONFLICT_IGNORE) < 0L) {
                val u = ContentValues()
                u.put("name", name)
                d.update("mem_chats", u, "chat_id = ?", arrayOf(chatId))
            }
            publish()
        }
    }

    /** Switches Memory off for a chat and deletes everything saved from it. */
    fun disable(chatId: String) {
        synchronized(lock) {
            val d = db()
            d.beginTransaction()
            try {
                d.delete("mem_items", "chat_id = ?", arrayOf(chatId))
                d.delete("mem_chats", "chat_id = ?", arrayOf(chatId))
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
            seen.remove(chatId)
            publish()
        }
    }

    /** Switches Memory off everywhere and deletes everything. */
    fun disableAll() {
        synchronized(lock) {
            val d = db()
            d.beginTransaction()
            try {
                d.delete("mem_items", null, null)
                d.delete("mem_chats", null, null)
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
            seen.clear()
            publish()
        }
    }

    /**
     * Saves what the opened chat shows. Does nothing for a chat with Memory off. A message deleted for everyone is
     * dropped here too. A text I changed or took out by hand is not overwritten.
     */
    fun sync(chatId: String, name: String, entries: List<MemEntry>) {
        synchronized(lock) {
            if (!state.value.containsKey(chatId)) return
            val k = key()
            val d = db()
            val here = seen.getOrPut(chatId) { HashMap() }
            d.beginTransaction()
            try {
                for (e in entries) {
                    if (e.deleted) {
                        if (here[e.msgId] != GONE) {
                            d.delete("mem_items", "chat_id = ? AND msg_id = ?", arrayOf(chatId, e.msgId))
                            here[e.msgId] = GONE
                        }
                        continue
                    }
                    val text = e.text ?: continue
                    val mark = 31 * text.hashCode() + java.lang.Long.hashCode(e.ts) + (if (e.mine) 1 else 0)
                    if (here[e.msgId] == mark) continue
                    var st = SAVED
                    val c = d.rawQuery("SELECT state FROM mem_items WHERE chat_id = ? AND msg_id = ?", arrayOf(chatId, e.msgId))
                    try {
                        if (c.moveToFirst()) st = c.getInt(0)
                    } finally {
                        c.close()
                    }
                    if (st == EDITED || st == REMOVED) {
                        here[e.msgId] = mark
                        continue
                    }
                    val v = ContentValues()
                    v.put("chat_id", chatId)
                    v.put("msg_id", e.msgId)
                    v.put("ts", e.ts)
                    v.put("mine", if (e.mine) 1 else 0)
                    v.put("who", e.who)
                    v.put("body", MemorySeal.seal(k, chatId, e.msgId, text))
                    v.put("state", SAVED)
                    d.insertWithOnConflict("mem_items", null, v, SQLiteDatabase.CONFLICT_REPLACE)
                    here[e.msgId] = mark
                }
                val u = ContentValues()
                u.put("name", name)
                u.put("synced", System.currentTimeMillis())
                d.update("mem_chats", u, "chat_id = ?", arrayOf(chatId))
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
            publish()
        }
    }

    /** The newest messages that contain every word of [query], newest first. */
    fun search(query: String): List<MemoryHit> {
        val words = MemoryLogic.words(query)
        if (words.isEmpty()) return emptyList()
        return synchronized(lock) {
            val k = key()
            val names = state.value
            val out = ArrayList<MemoryHit>()
            val c = db().rawQuery(
                "SELECT chat_id, msg_id, ts, mine, who, body, state FROM mem_items WHERE state != $REMOVED ORDER BY ts DESC",
                null,
            )
            try {
                while (c.moveToNext() && out.size < MemoryLogic.MAX_HITS) {
                    val chatId = c.getString(0)
                    val msgId = c.getString(1)
                    val text = MemorySeal.open(k, chatId, msgId, c.getBlob(5)) ?: continue
                    if (!MemoryLogic.matches(MemoryLogic.fold(text), words)) continue
                    out.add(
                        MemoryHit(
                            chatId = chatId,
                            chatName = names[chatId]?.name ?: "",
                            msgId = msgId,
                            ts = c.getLong(2),
                            mine = c.getInt(3) != 0,
                            who = c.getString(4) ?: "",
                            text = text,
                            edited = c.getInt(6) == EDITED,
                        ),
                    )
                }
            } finally {
                c.close()
            }
            out
        }
    }

    /**
     * For the assistant: saved messages from [from] to [to] that have at least [need] of the [words] (already folded and
     * shortened) in their text, the sender's name or the chat's name. Best match first, then newest first.
     * With no words, every saved message of that time. Never more than [AssistantLogic.MAX_ROWS].
     */
    fun find(words: List<String>, from: Long, to: Long, need: Int): List<MemoryHit> {
        return synchronized(lock) {
            val k = key()
            val names = state.value
            val got = ArrayList<Pair<Int, MemoryHit>>()
            val c = db().rawQuery(
                "SELECT chat_id, msg_id, ts, mine, who, body, state FROM mem_items " +
                    "WHERE state != $REMOVED AND ts >= $from AND ts <= $to ORDER BY ts DESC",
                null,
            )
            try {
                while (c.moveToNext() && got.size < 400) {
                    val chatId = c.getString(0)
                    val msgId = c.getString(1)
                    val text = MemorySeal.open(k, chatId, msgId, c.getBlob(5)) ?: continue
                    val who = c.getString(4) ?: ""
                    val chatName = names[chatId]?.name ?: ""
                    val score = if (words.isEmpty()) 1 else MemoryLogic.score(MemoryLogic.fold(text + "\n" + who + "\n" + chatName), words)
                    if (score < need || score == 0) continue
                    got.add(
                        score to MemoryHit(
                            chatId = chatId,
                            chatName = chatName,
                            msgId = msgId,
                            ts = c.getLong(2),
                            mine = c.getInt(3) != 0,
                            who = who,
                            text = text,
                            edited = c.getInt(6) == EDITED,
                        ),
                    )
                }
            } finally {
                c.close()
            }
            got
                .sortedWith(compareByDescending<Pair<Int, MemoryHit>> { p -> p.first }.thenByDescending { p -> p.second.ts })
                .map { p -> p.second }
                .take(AssistantLogic.MAX_ROWS)
        }
    }

    /** Changes the saved copy of a message (the real message in the chat stays as it is). False when it is empty. */
    fun edit(chatId: String, msgId: String, text: String): Boolean {
        val t = text.trim().take(MemoryLogic.MAX_TEXT)
        if (t.isEmpty()) return false
        return synchronized(lock) {
            val k = key()
            val v = ContentValues()
            v.put("body", MemorySeal.seal(k, chatId, msgId, t))
            v.put("state", EDITED)
            val n = db().update("mem_items", v, "chat_id = ? AND msg_id = ?", arrayOf(chatId, msgId))
            seen.remove(chatId)
            publish()
            n > 0
        }
    }

    /** Takes one saved message out. It is not saved again while Memory stays on. */
    fun remove(chatId: String, msgId: String) {
        synchronized(lock) {
            val v = ContentValues()
            v.put("body", ByteArray(0))
            v.put("state", REMOVED)
            db().update("mem_items", v, "chat_id = ? AND msg_id = ?", arrayOf(chatId, msgId))
            seen.remove(chatId)
            publish()
        }
    }
}

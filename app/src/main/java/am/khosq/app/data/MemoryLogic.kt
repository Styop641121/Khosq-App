package am.khosq.app.data

/** One found message: where it is, who wrote it and the text (already opened). */
data class MemoryHit(
    val chatId: String,
    val chatName: String,
    val msgId: String,
    val ts: Long,
    val mine: Boolean,
    val who: String,
    val text: String,
    /** True when I changed the saved text by hand. */
    val edited: Boolean = false,
)

/** One chat that has Memory switched on, and how much it holds. */
data class MemChat(
    val chatId: String,
    val name: String,
    val since: Long,
    val synced: Long,
    val count: Int,
)

/** What a chat tells the Memory about one of its messages. [text] is null when the message is not worth keeping. */
data class MemEntry(
    val msgId: String,
    val ts: Long,
    val mine: Boolean,
    val who: String,
    val text: String?,
    /** The message was deleted for everyone: its saved copy goes too. */
    val deleted: Boolean,
)

/**
 * Seals and opens the saved texts: AES-256-GCM with a fresh random nonce each time. The chat and message numbers are
 * part of what is signed, so a row cannot be moved to another message without being noticed.
 */
object MemorySeal {
    private const val NONCE = 12
    private val rnd = java.security.SecureRandom()

    fun seal(key: ByteArray, chatId: String, msgId: String, text: String): ByteArray {
        val iv = ByteArray(NONCE)
        rnd.nextBytes(iv)
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, iv))
        c.updateAAD((chatId + "|" + msgId).toByteArray(Charsets.UTF_8))
        return iv + c.doFinal(text.toByteArray(Charsets.UTF_8))
    }

    /** Null when the data was changed, belongs to another message, or the key is wrong. */
    fun open(key: ByteArray, chatId: String, msgId: String, blob: ByteArray): String? {
        if (blob.size < NONCE + 16) return null
        return try {
            val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            c.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"), javax.crypto.spec.GCMParameterSpec(128, blob, 0, NONCE))
            c.updateAAD((chatId + "|" + msgId).toByteArray(Charsets.UTF_8))
            String(c.doFinal(blob, NONCE, blob.size - NONCE), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}

/** The rules of KHOSQ Memory: what is worth saving and how a search matches. No Android in here, so they can be checked anywhere. */
object MemoryLogic {
    const val MAX_TEXT = 4000
    const val MAX_QUERY = 100
    const val MAX_HITS = 80
    const val SNIPPET = 140

    /**
     * The text to keep for a message, or null when it must not be kept: deleted messages, anything that is not words
     * (photos, voice, places, system lines), empty text and the "could not be opened" marker.
     * For a poll the caller passes its question as [pollQuestion].
     */
    fun indexText(kind: String, text: String, deleted: Boolean, failedMarkers: Set<String>, pollQuestion: String? = null): String? {
        if (deleted) return null
        val raw = when (kind) {
            "text" -> text
            "poll" -> if (pollQuestion.isNullOrBlank()) "" else "📊 " + pollQuestion
            else -> ""
        }
        val t = raw.trim()
        if (t.isEmpty() || t in failedMarkers) return null
        return t.take(MAX_TEXT)
    }

    /** Lower case; "ё" the same as "е"; the Armenian letter "և" the same as "եվ" (capitals have no "և"). A search cares about none of these. */
    fun fold(s: String): String = s.lowercase().replace("և", "եվ").replace('ё', 'е')

    /** The words of a search, folded and without repeats. At most six, so a long paste cannot make a search slow. */
    fun words(query: String): List<String> =
        fold(query.trim().take(MAX_QUERY)).split(' ', '\n', '\t').filter { w -> w.isNotEmpty() }.distinct().take(6)

    /** True when every word of the search is somewhere in the text. */
    fun matches(foldedText: String, words: List<String>): Boolean {
        if (words.isEmpty()) return false
        for (w in words) if (!foldedText.contains(w)) return false
        return true
    }

    /** A short piece of [text] around the first found word, with … where it was cut. */
    fun snippet(text: String, words: List<String>): String {
        val flat = text.replace('\n', ' ')
        if (flat.length <= SNIPPET) return flat
        val low = fold(flat)
        var at = -1
        for (w in words) {
            val i = low.indexOf(w)
            if (i >= 0 && (at < 0 || i < at)) at = i
        }
        if (at < 0) return flat.take(SNIPPET - 1).trimEnd() + "…"
        val start = (at - SNIPPET / 3).coerceAtLeast(0)
        val end = (start + SNIPPET).coerceAtMost(flat.length)
        val cut = flat.substring(start, end).trim()
        return (if (start > 0) "…" else "") + cut + (if (end < flat.length) "…" else "")
    }
}

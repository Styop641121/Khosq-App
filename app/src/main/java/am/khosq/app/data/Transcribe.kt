package am.khosq.app.data

/** What the server heard in one voice note. [empty] is true when there was no speech (then [text] is ""). */
data class TranscribeResult(
    val text: String,
    /** The language asked for: "auto", "hy", "ru" or "en". */
    val lang: String = "auto",
    /** Which model answered ("whisper-turbo" or "whisper"), shown small so a wrong answer can be traced. */
    val via: String = "",
    val empty: Boolean = false,
)

/** The rules of voice transcription. No Android in here, so they can be checked on any computer. */
object TranscribeLogic {
    /** The server refuses more than this many bytes (a voice note of the app is at most 900,000). */
    const val MAX_BYTES = 1_000_000

    /** The server refuses less than this (it is not a recording). */
    const val MIN_BYTES = 200

    /** "auto" lets the model find the language itself; the others tell it. */
    val LANGS = listOf("auto", "hy", "ru", "en")

    fun isLang(code: String): Boolean = code in LANGS

    /** The sheet opens on "auto": the notes of a chat may be in any of the three languages, so the model finds it itself. */
    const val DEFAULT_LANG = "auto"

    fun fits(bytes: ByteArray?): Boolean = bytes != null && bytes.size in MIN_BYTES..MAX_BYTES

    fun cacheKey(messageId: String, lang: String): String = lang + "\u0000" + messageId

    /** A text worth showing: the server's answer, trimmed. */
    fun clean(text: String): String = text.trim()
}

/** The last few transcriptions of this run of the app, so opening the same note twice does not ask the server twice. Never saved. */
class TranscribeCache(private val max: Int = 20) {
    private val map = object : LinkedHashMap<String, TranscribeResult>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TranscribeResult>?): Boolean = size > max
    }

    @Synchronized
    fun get(messageId: String, lang: String): TranscribeResult? = map[TranscribeLogic.cacheKey(messageId, lang)]

    /** A note with no speech in it is not kept: asking again may be what the person wants. */
    @Synchronized
    fun put(messageId: String, lang: String, r: TranscribeResult) {
        if (r.empty || r.text.isEmpty()) return
        map[TranscribeLogic.cacheKey(messageId, lang)] = r
    }

    @Synchronized
    fun clear() = map.clear()

    @Synchronized
    fun size(): Int = map.size
}

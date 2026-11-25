package am.khosq.app.data

/** What the server answered for one text. [same] is true when the text was already in the wanted language (then [text] is the original). */
data class TranslateResult(
    val text: String,
    val from: String,
    val to: String,
    val same: Boolean = false,
    /** Which model answered ("gemma" or "m2m100"), shown small so a wrong answer can be traced. */
    val via: String = "",
)

/** The rules of translation. No Android in here, so they can be checked on any computer. */
object TranslateLogic {
    /** The server refuses more than this. */
    const val MAX_CHARS = 1500

    /** The languages the app offers (the Worker knows a few more). */
    val LANGS = listOf("hy", "ru", "en")

    fun isLang(code: String): Boolean = code in LANGS

    /** The language of a text from its letters, the same way the server does it: Armenian, Cyrillic, Georgian, Arabic, else English. */
    fun guess(text: String): String {
        var hy = 0
        var ru = 0
        var ka = 0
        var ar = 0
        var en = 0
        var i = 0
        while (i < text.length) {
            val c = text.codePointAt(i)
            i += Character.charCount(c)
            when {
                c in 0x531..0x58F -> hy++
                c in 0x400..0x4FF -> ru++
                c in 0x10A0..0x10FF -> ka++
                c in 0x600..0x6FF -> ar++
                c in 65..90 || c in 97..122 -> en++
            }
        }
        // the first of the biggest wins, in this order
        var best = "en"
        var max = 0
        for ((code, n) in listOf("hy" to hy, "ru" to ru, "ka" to ka, "ar" to ar, "en" to en)) {
            if (n > max) {
                max = n
                best = code
            }
        }
        return best
    }

    /** The language to translate into when the person did not choose: their own language, or another one if the text is already in it. */
    fun defaultTarget(text: String, appLang: String): String {
        val mine = if (isLang(appLang)) appLang else "hy"
        if (guess(text) != mine) return mine
        return when (mine) {
            "hy" -> "ru"
            "ru" -> "hy"
            else -> "hy"
        }
    }

    fun fits(text: String): Boolean = text.trim().isNotEmpty() && text.trim().length <= MAX_CHARS

    fun cacheKey(text: String, to: String): String = to + "\u0000" + text.trim()
}

/** The last few translations of this run of the app, so pressing the same button twice does not ask the server twice. Never saved. */
class TranslateCache(private val max: Int = 40) {
    private val map = object : LinkedHashMap<String, TranslateResult>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TranslateResult>?): Boolean = size > max
    }

    @Synchronized
    fun get(text: String, to: String): TranslateResult? = map[TranslateLogic.cacheKey(text, to)]

    @Synchronized
    fun put(text: String, to: String, r: TranslateResult) {
        map[TranslateLogic.cacheKey(text, to)] = r
    }

    @Synchronized
    fun clear() = map.clear()

    @Synchronized
    fun size(): Int = map.size
}

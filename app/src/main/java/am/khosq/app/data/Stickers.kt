package am.khosq.app.data

/** One sticker: a picture (an emoji) and an Armenian phrase. [id] is what travels in the message (see [StickerLogic.valid]). */
class Sticker(val id: String, val emoji: String, val text: String)

/**
 * A pack of stickers. [nameKey] is the Armenian name of the pack (the screen translates it); the colours are ARGB: [top] and
 * [bottom] make the soft gradient of the card, [ink] is the colour of the phrase.
 */
class StickerPack(
    val id: String,
    val icon: String,
    val nameKey: String,
    val top: Long,
    val bottom: Long,
    val ink: Long,
    val items: List<Sticker>,
)

/**
 * The built-in Armenian stickers. A sticker message carries only its id (sealed like any text), and every phone draws the sticker
 * itself, so there is no picture to send or to store. Nothing Android in here, so it can be checked anywhere.
 *
 * The emoji are single symbols of Unicode 10 or older (no flags, no joined sequences), so they show on every phone that the
 * app installs on (Android 8 and up).
 */
object StickerLogic {
    const val RECENT_KEY = "stk.recent"
    const val MAX_RECENT = 12

    private val ID = Regex("^[a-z]{2,10}\\.[a-z0-9]{1,16}$")

    // the colours of the Armenian flag, softened: orange, red, blue
    private val GREET = Triple(0xFFFFE9B8L, 0xFFFFCF70L, 0xFF4A3000L)
    private val FEEL = Triple(0xFFFFD9DAL, 0xFFFFA3A8L, 0xFF5A0A12L)
    private val REPLY = Triple(0xFFD9E6FFL, 0xFFA6C4FFL, 0xFF0B2457L)

    private fun s(pack: String, slug: String, emoji: String, text: String) = Sticker("$pack.$slug", emoji, text)

    val packs: List<StickerPack> = listOf(
        StickerPack(
            id = "greet",
            icon = "👋",
            nameKey = "Ողջույններ",
            top = GREET.first,
            bottom = GREET.second,
            ink = GREET.third,
            items = listOf(
                s("greet", "hello", "👋", "Բարև"),
                s("greet", "morning", "🌅", "Բարի լույս"),
                s("greet", "day", "☀️", "Բարի օր"),
                s("greet", "evening", "🌆", "Բարի երեկո"),
                s("greet", "night", "🌙", "Բարի գիշեր"),
                s("greet", "bye", "✌️", "Ցտեսություն"),
                s("greet", "hi", "🙌", "Ողջույն"),
                s("greet", "welcome", "🤗", "Բարի գալուստ"),
            ),
        ),
        StickerPack(
            id = "feel",
            icon = "❤️",
            nameKey = "Զգացմունքներ",
            top = FEEL.first,
            bottom = FEEL.second,
            ink = FEEL.third,
            items = listOf(
                s("feel", "thanks", "🙏", "Շնորհակալություն"),
                s("feel", "bravo", "👏", "Ապրես"),
                s("feel", "love", "❤️", "Սիրում եմ քեզ"),
                s("feel", "great", "✨", "Հիանալի է"),
                s("feel", "congrats", "🎉", "Շնորհավոր"),
                s("feel", "luck", "🍀", "Հաջողություն"),
                s("feel", "sorry", "😔", "Կներես"),
                s("feel", "glad", "😊", "Ուրախ եմ"),
                s("feel", "jan", "💛", "Ջան ջան"),
            ),
        ),
        StickerPack(
            id = "reply",
            icon = "💬",
            nameKey = "Պատասխաններ",
            top = REPLY.first,
            bottom = REPLY.second,
            ink = REPLY.third,
            items = listOf(
                s("reply", "yes", "✅", "Այո"),
                s("reply", "no", "❌", "Ոչ"),
                s("reply", "ok", "👍", "Լավ"),
                s("reply", "got", "👌", "Հասկացա"),
                s("reply", "idk", "🤷", "Չգիտեմ"),
                s("reply", "moment", "⏳", "Մի րոպե"),
                s("reply", "coming", "🏃", "Գալիս եմ"),
                s("reply", "road", "🚗", "Ճանապարհին եմ"),
                s("reply", "call", "📞", "Հիմա կզանգեմ"),
                s("reply", "busy", "💼", "Զբաղված եմ"),
                s("reply", "wait", "✋", "Սպասիր, խնդրում եմ"),
                s("reply", "urgent", "🚨", "Շտապ է"),
                s("reply", "sleep", "😴", "Քնում եմ"),
                s("reply", "hungry", "🍞", "Սոված եմ"),
                s("reply", "coffee", "☕", "Սուրճ խմենք"),
                s("reply", "meet", "🤝", "Հանդիպենք"),
            ),
        ),
    )

    private val byId: Map<String, Sticker> = packs.flatMap { p -> p.items }.associateBy { st -> st.id }

    /** Is this text shaped like a sticker id (a pack, a dot, a name)? Anything else is never treated as a sticker. */
    fun valid(id: String): Boolean = ID.matches(id)

    /** The sticker with this id, or null when this version of the app does not know it (a newer app may have more). */
    fun find(id: String): Sticker? = byId[id.trim()]

    /** The pack the sticker belongs to. */
    fun packOf(id: String): StickerPack? {
        val head = id.substringBefore('.')
        return packs.firstOrNull { p -> p.id == head }
    }

    /**
     * The size of the phrase in sp: a long word must fit the width of the card, so the longest word decides. A card is 168 dp wide,
     * and an Armenian capital letter in bold is about 0.62 of the font size.
     */
    fun fontSp(text: String): Int {
        var longest = 0
        for (w in text.split(' ', ',')) if (w.length > longest) longest = w.length
        return when {
            longest >= 15 -> 14
            longest >= 11 -> 16
            longest >= 8 -> 19
            else -> 22
        }
    }

    /** The list of recently sent stickers kept as text (ids with commas) with [id] put first; the same id is never twice. */
    fun pushRecent(csv: String, id: String, max: Int = MAX_RECENT): String {
        if (find(id) == null) return recentClean(csv, max)
        val out = ArrayList<String>()
        out.add(id)
        for (x in csv.split(',')) if (find(x) != null && !out.contains(x)) out.add(x)
        return out.take(max).joinToString(",")
    }

    /** The recent ids that this version knows, newest first (an unknown or repeated id is dropped). */
    fun recentList(csv: String, max: Int = MAX_RECENT): List<Sticker> {
        val seen = HashSet<String>()
        val out = ArrayList<Sticker>()
        for (x in csv.split(',')) {
            val st = find(x) ?: continue
            if (seen.add(st.id)) out.add(st)
            if (out.size >= max) break
        }
        return out
    }

    private fun recentClean(csv: String, max: Int): String = recentList(csv, max).joinToString(",") { st -> st.id }
}

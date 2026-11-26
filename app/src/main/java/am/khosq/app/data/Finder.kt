package am.khosq.app.data

/**
 * KHOSQ Real Estate Finder: one saved search of apartments on list.am. The search itself lives in the KHOSQ Worker (it looks at
 * list.am every few minutes and pushes a notification when a new listing appears); the phone only edits it and shows what was found.
 * [on] is the "monitoring" switch (off = paused). [min] and [max] are 0 when there is no limit; [rooms] 0 means any number, otherwise
 * "this many or more". [lang] is the language of list.am's pages (and of the notification text).
 */
data class FinderCfg(
    val on: Boolean = false,
    val cat: String = FinderLogic.SALE,
    val region: Int = 1,
    val min: Long = 0L,
    val max: Long = 0L,
    val cur: String = "USD",
    val rooms: Int = 0,
    val lang: String = "hy",
)

/** One listing: [facts] is the line list.am shows ("3 rm., 85 sq.m., 3/8 floor"), [photos] are list.am's photo numbers, [sample] marks the examples shown at the start. */
data class FinderHit(
    val id: String,
    val price: String,
    val title: String,
    val facts: String,
    val place: String,
    val photos: List<String>,
    val ts: Long,
    val sample: Boolean,
)

/** What the Worker knows: the search, the newest matches, and how the last look at list.am went ([ok], [note], [run] = when). */
data class FinderState(
    val cfg: FinderCfg?,
    val hits: List<FinderHit>,
    val ok: Boolean,
    val note: String,
    val run: Long,
)

/** The facts line taken apart: the rooms and the size when it can be told, the floor as it was written. */
data class FinderFacts(val rooms: String, val size: String, val floor: String)

enum class FinderStatus { NONE, PAUSED, WAITING, WATCHING, PROBLEM }

/** The rules of the Finder. No Android in here, so they can be checked on any computer. */
object FinderLogic {
    const val SALE = "sale"
    const val RENT = "rent"
    const val MAX_AMOUNT = 1_000_000_000L
    const val ROOMS_MAX = 5

    private val REGIONS = arrayOf(
        // number on list.am, Armenian, English, Russian
        arrayOf("1", "Երևան", "Yerevan", "Ереван"),
        arrayOf("2", "Աջափնյակ", "Ajapnyak", "Аджапняк"),
        arrayOf("3", "Արաբկիր", "Arabkir", "Арабкир"),
        arrayOf("4", "Ավան", "Avan", "Аван"),
        arrayOf("5", "Դավթաշեն", "Davtashen", "Давташен"),
        arrayOf("6", "Էրեբունի", "Erebuni", "Эребуни"),
        arrayOf("7", "Քանաքեռ-Զեյթուն", "Kanaker-Zeytun", "Канакер-Зейтун"),
        arrayOf("8", "Կենտրոն", "Kentron", "Кентрон"),
        arrayOf("9", "Մալաթիա-Սեբաստիա", "Malatia-Sebastia", "Малатия-Себастия"),
        arrayOf("10", "Նոր Նորք", "Nor Nork", "Нор Норк"),
        arrayOf("11", "Նորք-Մարաշ", "Nork-Marash", "Норк-Мараш"),
        arrayOf("12", "Նուբարաշեն", "Nubarashen", "Нубарашен"),
        arrayOf("13", "Շենգավիթ", "Shengavit", "Шенгавит"),
    )

    /** The numbers of every place that can be chosen, in the order they are shown. */
    val regions: List<Int> = REGIONS.map { r -> r[0].toInt() }

    fun regionName(region: Int, lang: String): String {
        val r = REGIONS.firstOrNull { x -> x[0].toInt() == region } ?: REGIONS[0]
        return when (lang) {
            "hy" -> r[1]
            "en" -> r[2]
            else -> r[3]
        }
    }

    /** The address of a listing on list.am, in the language of the search. */
    fun itemUrl(id: String, lang: String): String {
        val prefix = when (lang) {
            "en" -> "/en"
            "ru" -> "/ru"
            else -> ""
        }
        return "https://www.list.am$prefix/item/$id"
    }

    /** The address of one of the listing's photos. Only digits are accepted, so nothing but a list.am photo can be asked for. */
    fun photoUrl(photoId: String): String? {
        if (photoId.length < 4 || photoId.length > 12 || !photoId.all { c -> c in '0'..'9' }) return null
        return "https://img.list.am/g/" + photoId.takeLast(3) + "/" + photoId + ".webp"
    }

    /** The unread listings: newer than the last one the person saw. The examples shown at the start never count. */
    fun unread(hits: List<FinderHit>, seenTs: Long): Int = hits.count { h -> !h.sample && h.ts > seenTs }

    fun status(s: FinderState?): FinderStatus {
        if (s == null) return FinderStatus.NONE
        val c = s.cfg ?: return FinderStatus.NONE
        return when {
            !c.on -> FinderStatus.PAUSED
            s.run <= 0L -> FinderStatus.WAITING
            !s.ok -> FinderStatus.PROBLEM
            else -> FinderStatus.WATCHING
        }
    }

    /** What went wrong with the last look at list.am, as a kind the person can understand: "blocked", "net" or "page". */
    fun problemKind(note: String): String = when {
        note.startsWith("http 403") || note.startsWith("http 429") || note == "challenge" -> "blocked"
        note.startsWith("http") || note == "net" -> "net"
        else -> "page"
    }

    /** Digits only, as a whole number; empty or nonsense gives 0. */
    fun parseAmount(text: String): Long {
        val d = text.filter { c -> c in '0'..'9' }.take(10)
        if (d.isEmpty()) return 0L
        return minOf(d.toLong(), MAX_AMOUNT)
    }

    private fun grouped(v: Long): String {
        val s = v.toString()
        val sb = StringBuilder()
        for (i in s.indices) {
            if (i > 0 && (s.length - i) % 3 == 0) sb.append(',')
            sb.append(s[i])
        }
        return sb.toString()
    }

    /** 100000 -> "100k", 1500000 -> "1.5M", 95500 -> "95,500". */
    fun compact(v: Long): String {
        if (v >= 1_000_000L && v % 100_000L == 0L) {
            val m = v / 1_000_000L
            val d = (v % 1_000_000L) / 100_000L
            return if (d == 0L) "${m}M" else "$m.${d}M"
        }
        if (v >= 1_000L && v % 1_000L == 0L) return "${v / 1_000L}k"
        return grouped(v)
    }

    fun money(v: Long, cur: String): String = if (cur == "AMD") compact(v) + " ֏" else "$" + compact(v)

    /** "<$100k", "≥$50k", "$50k–$100k"; empty when there is no limit at all. */
    fun budgetText(c: FinderCfg): String = when {
        c.min > 0L && c.max > 0L -> money(c.min, c.cur) + "–" + money(c.max, c.cur)
        c.max > 0L -> "<" + money(c.max, c.cur)
        c.min > 0L -> "≥" + money(c.min, c.cur)
        else -> ""
    }

    /** "2+" ; empty when any number of rooms will do. */
    fun roomsText(c: FinderCfg): String = if (c.rooms > 0) c.rooms.toString() + "+" else ""

    /** A search that is the same for the Worker: the switch does not make it a different search. */
    fun sameSearch(a: FinderCfg, b: FinderCfg): Boolean =
        a.cat == b.cat && a.region == b.region && a.min == b.min && a.max == b.max && a.cur == b.cur && a.rooms == b.rooms && a.lang == b.lang

    /**
     * list.am writes the facts as "3 rm., 85 sq.m., 3/8 floor" (in the language of the page): the number before the first comma is
     * the rooms, the number before the second is the size. When the line looks different, only the rooms or the size may be empty.
     */
    fun facts(line: String): FinderFacts {
        val parts = line.split(",").map { p -> p.trim() }
        fun num(p: String?): String {
            if (p == null) return ""
            val m = Regex("^(\\d{1,4}(?:[.,]\\d{1,2})?)").find(p)
            return m?.value ?: ""
        }
        val rooms = num(parts.getOrNull(0))
        // a single part ("2 rm.") has no size
        val size = if (parts.size >= 2) num(parts.getOrNull(1)) else ""
        val floor = if (parts.size >= 3) parts[2] else ""
        return FinderFacts(rooms, size, floor)
    }

    /** What to search for on a maps app: the place of the listing and the city, always in English letters for the city. */
    fun mapQuery(place: String, region: Int): String {
        val where = if (place.isNotBlank()) place.trim() else regionName(region, "en")
        return if (region == 1 || where.contains("Yerevan", ignoreCase = true)) "$where, Armenia" else "$where, Yerevan, Armenia"
    }

    /** The title of the task made from a listing: "$95,000 · Kentron". */
    fun taskTitle(h: FinderHit): String {
        val t = h.price + (if (h.place.isNotEmpty()) " · " + h.place else "")
        return TaskLogic.cleanTitle("list.am: $t")
    }

    /** The note of that task: the listing's title, its facts and its address. */
    fun taskNote(h: FinderHit, lang: String): String {
        val lines = ArrayList<String>()
        if (h.title.isNotEmpty()) lines.add(h.title)
        if (h.facts.isNotEmpty()) lines.add(h.facts)
        lines.add(itemUrl(h.id, lang))
        return TaskLogic.cleanNote(lines.joinToString("\n"))
    }
}

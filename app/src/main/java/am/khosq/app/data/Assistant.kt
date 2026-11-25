package am.khosq.app.data

import java.util.Calendar

/** Which tasks a question is about. */
enum class TaskFilter { OPEN, DONE, OVERDUE, ALL }

/** A period the person named ("tomorrow", "last week", "on Friday"). */
enum class RangeKind { TODAY, TOMORROW, DAY_AFTER, YESTERDAY, THIS_WEEK, LAST_WEEK, NEXT_WEEK, THIS_MONTH, LAST_MONTH, WEEKDAY, LAST_DAYS }

/** What the assistant cannot do yet (it needs a language model, which KHOSQ does not use here). */
enum class LaterKind { SUMMARY, TRANSLATE }

/** A period of time: [from] and [to] are moments (both inside the period). [n] is the weekday (Calendar.MONDAY…) or the number of days. */
data class DayRange(val kind: RangeKind, val from: Long, val to: Long, val n: Int = 0)

/** What a sentence asks for, found by plain rules (no language model, nothing leaves the phone). */
sealed class AsstIntent {
    data object Help : AsstIntent()

    /** "What do I have tomorrow", "unfinished tasks". */
    data class ListTasks(val filter: TaskFilter, val range: DayRange?) : AsstIntent()

    /** "Remind me tomorrow at 10 to call Arman". [title] may be empty when the sentence named no task. [due] 0 = no date. */
    data class AddTask(val title: String, val due: Long, val remind: Boolean) : AsstIntent()

    /** "Find the address sent last week". [words] are the words that are searched for; they may be empty when only a period was named. */
    data class Find(val words: List<String>, val range: DayRange?) : AsstIntent()

    data class Later(val kind: LaterKind) : AsstIntent()
}

/**
 * One question of the person and what the assistant answered. Kept only while the app runs (never saved).
 * [tasks] and [hits] are the sources of the answer; [memChats] are the chats whose saved texts were searched.
 */
data class AsstTurn(
    val id: Long,
    val question: String,
    val at: Long,
    val intent: AsstIntent,
    val tasks: List<Task> = emptyList(),
    /** How many more matching tasks there were than shown. */
    val moreTasks: Int = 0,
    val hits: List<MemoryHit> = emptyList(),
    val memChats: List<String> = emptyList(),
    /** True when Memory is on in no chat, so no message could be searched. */
    val memOff: Boolean = false,
    /** True when the shown messages miss one of the words (no message had them all). */
    val approx: Boolean = false,
    val busy: Boolean = false,
    /**
     * The task the assistant added for the person (as stored, with its number once saved) and what became of it:
     * 0 not saved (still saving, or saving failed), 1 added, 2 taken back by the person.
     */
    val proposal: Task? = null,
    val proposalState: Int = 0,
)

/**
 * Reads a sentence in Armenian, Russian or English and finds out what it asks for. Simple rules over words: it knows
 * a fixed set of requests and says so when it does not understand. No Android in here, so it can be checked anywhere.
 */
object AssistantParser {
    const val MAX_INPUT = 300
    private const val MAX_TOKENS = 60
    private const val MAX_WORDS = 6

    private class Tok(val raw: String, val f: String) {
        var used = false
    }

    private sealed class DateRef {
        data class Rel(val days: Int) : DateRef()
        data class Week(val offset: Int) : DateRef()
        data class Month(val offset: Int) : DateRef()
        data class Weekday(val dow: Int) : DateRef()
        data class LastDays(val n: Int) : DateRef()
    }

    // ---- word lists (folded: lower case, ё = е, և = եվ)

    private val HELP = arrayOf("help", "օգնություն", "помощь", "справка", "commands", "հրամաններ", "команды")
    private val HELP_ONE = arrayOf("օգնիր", "помоги", "օգնել")
    private val GREETINGS = arrayOf("բարև", "բարեվ", "բարի", "ողջույն", "հայ", "hello", "hi", "hey", "привет", "здравств", "добрый")
    private val REMIND_EXACT = setOf("remind", "напомни", "напомните", "напомнить")
    private val REMIND_START = arrayOf("հիշեցր", "հիշացր", "հիշեցն", "հիշացն", "հիշցր")
    private val ADD_VERBS = arrayOf("ավելացր", "ավելաց", "ստեղծ", "նոր", "add", "create", "new", "set", "нов", "добав", "созда", "постав", "установ")
    private val ADD_NOUNS = arrayOf("առաջադրանք", "հիշեցում", "task", "reminder", "задач", "напоминани")
    private val SEARCH_VERBS = arrayOf("գտ", "որոն", "փնտր", "որտեղ", "find", "search", "look", "where", "найд", "найти", "поищ", "ищи", "искат", "где")
    private val TASK_NOUNS = arrayOf(
        "առաջադրանք", "հիշեցում", "հանդիպում", "պլան", "օրակարգ", "ժամանակացույց", "միջոցառում",
        "task", "todo", "reminder", "meeting", "appointment", "event", "agenda", "schedule", "plan",
        "задач", "напоминани", "встреч", "расписани", "план", "дела", "мероприят",
    )
    private val OVERDUE_WORDS = arrayOf("ուշացած", "ժամկետանց", "overdue", "missed", "просрочен")
    private val DONE_WORDS = arrayOf("կատարված", "ավարտված", "done", "completed", "finished", "выполненн", "сделанн", "завершенн")
    private val OPEN_WORDS = arrayOf(
        "չավարտ", "անավարտ", "չկատար", "անկատար", "բաց", "ակտիվ",
        "unfinished", "incomplete", "open", "pending", "undone", "outstanding", "remaining",
        "невыполн", "незаверш", "несделан", "открыт", "актуальн",
    )
    private val ALL_WORDS = arrayOf("բոլոր", "all", "все", "всех")
    private val SUMMARY_WORDS = arrayOf("ամփոփ", "summar", "резюмир", "суммир", "перескаж", "сводк")
    private val TRANSLATE_WORDS = arrayOf("թարգման", "translat", "переведи", "перевест", "перевод")
    private val MARKERS = setOf("ժամը", "ժամին", "at", "в", "во", "к", "around")

    private val STOP = setOf(
        // Armenian
        "ինչ", "ինչը", "ինչեր", "ինչպես", "ինչքան", "ով", "որը", "որտեղ", "երբ", "ու", "եվ", "ես", "ինձ", "իմ", "իմը", "դու", "ձեզ", "մեզ",
        "է", "էր", "եմ", "են", "ենք", "եք", "որ", "թե", "այս", "այդ", "այն", "մի", "մեկ", "մասին", "հետ", "համար", "նամակ", "նամակը",
        "նամակներ", "նամակները", "ցույց", "տուր", "խնդրում", "խնդրեմ", "բոլոր", "ուղարկեց", "ուղարկել", "ուղարկած", "գրեց", "ասաց",
        "որևէ", "մեկը", "կարող", "նաև", "արդյոք", "ունեմ", "ունեի",
        // Russian
        "что", "как", "кто", "где", "когда", "мне", "меня", "мой", "моя", "мое", "мои", "я", "ты", "он", "она", "и", "в", "во", "на", "за", "из",
        "от", "по", "о", "об", "с", "со", "к", "у", "а", "но", "это", "этот", "эту", "сообщение", "сообщения", "сообщениях", "пожалуйста",
        "все", "всех", "отправил", "отправила", "прислал", "прислала", "писал", "говорил", "какой", "какие", "было", "был", "есть", "ли", "да", "нет",
        // English
        "what", "whats", "when", "who", "which", "how", "did", "do", "does", "i", "me", "my", "you", "he", "she", "they", "we", "a", "an", "the",
        "to", "of", "in", "on", "at", "for", "with", "from", "by", "about", "is", "are", "was", "were", "be", "show", "please", "message",
        "messages", "sent", "send", "sends", "told", "said", "tell", "all", "any", "someone", "somebody", "anyone", "that", "this", "these",
        "those", "it", "and", "or", "have", "had", "has", "last", "next", "previous", "latest",
    )

    private val LEAD_TITLE = setOf(
        "on", "at", "in", "by", "for", "to", "that", "about", "me", "please", "and",
        "на", "в", "во", "к", "за", "чтобы", "что", "о", "об", "мне", "пожалуйста", "и",
        "ժամը", "ինձ", "որ", "խնդրում", "եմ", "խնդրեմ", "ու", "եվ",
    )
    private val TRAIL_TITLE = setOf("on", "at", "in", "by", "for", "на", "в", "во", "к", "за", "ժամը", "and", "и", "ու", "եվ")

    // ---- the entry point

    fun parse(text: String, now: Long): AsstIntent {
        var toks = tokenize(text)
        if (toks.isEmpty()) return AsstIntent.Help
        if (isHelp(toks)) return AsstIntent.Help
        val addKind = addKind(toks)
        if (addKind != 0) {
            val a = parseAdd(toks, now, addKind == 1)
            if (a != null) return a
            // not a task after all: read the sentence again from the start
            toks = tokenize(text)
        }
        val head = toks.take(5)
        if (head.any { x -> starts(x, SUMMARY_WORDS) }) return AsstIntent.Later(LaterKind.SUMMARY)
        if (head.any { x -> starts(x, TRANSLATE_WORDS) }) return AsstIntent.Later(LaterKind.TRANSLATE)
        val search = toks.any { x -> starts(x, SEARCH_VERBS) }
        val tasky = toks.any { x -> starts(x, TASK_NOUNS) } || asksWhatIHave(toks)
        if (!search && tasky) return parseList(toks, now)
        return parseFind(toks, now)
    }

    // ---- reading the words

    private fun tokenize(text: String): List<Tok> {
        val out = ArrayList<Tok>()
        for (p in text.take(MAX_INPUT).split(' ', '\n', '\t')) {
            val raw = p.trim { c -> !c.isLetterOrDigit() }
            val clean = raw.filter { c -> c.isLetterOrDigit() || c == ':' }.trim(':')
            if (clean.isEmpty()) continue
            out.add(Tok(raw, MemoryLogic.fold(clean)))
            if (out.size >= MAX_TOKENS) break
        }
        return out
    }

    private fun starts(t: Tok, prefixes: Array<String>): Boolean {
        for (p in prefixes) if (t.f.startsWith(p)) return true
        return false
    }

    private fun use(vararg toks: Tok) {
        for (x in toks) x.used = true
    }

    private fun isHelp(t: List<Tok>): Boolean {
        if (t.size <= 3 && t.any { x -> starts(x, HELP) }) return true
        if (t.size == 1 && starts(t[0], HELP_ONE)) return true
        if (t.size <= 2 && t.all { x -> starts(x, GREETINGS) || x.f == "ապեր" }) return true
        if (t.size > 6) return false
        if (t.any { x -> x.f.startsWith("կարող") } && t.any { x -> x.f.startsWith("ինչ") }) return true
        if (t.any { x -> x.f == "what" } && t.any { x -> x.f == "can" } && t.any { x -> x.f == "you" }) return true
        if (t.any { x -> x.f.startsWith("умеешь") || x.f.startsWith("умеете") }) return true
        return false
    }

    /** "What do I have tomorrow" without any noun such as "tasks". */
    private fun asksWhatIHave(t: List<Tok>): Boolean {
        if (t.any { x -> x.f.startsWith("ինչ") } && t.any { x -> x.f.startsWith("ունեմ") }) return true
        if (t.any { x -> x.f == "что" } && t.any { x -> x.f.startsWith("меня") }) return true
        if (t.any { x -> x.f == "what" || x.f == "whats" } && t.any { x -> x.f == "have" || x.f == "got" || x.f == "planned" || x.f == "scheduled" }) return true
        return false
    }

    /** 1 = "remind me …", 2 = "add task …", 0 = neither. Only the first words count, so a search that mentions a reminder is not taken for one. */
    private fun addKind(t: List<Tok>): Int {
        val head = t.take(4)
        for (x in head) if (x.f in REMIND_EXACT || starts(x, REMIND_START)) return 1
        if (head.any { x -> starts(x, ADD_VERBS) } && head.any { x -> starts(x, ADD_NOUNS) }) return 2
        return 0
    }

    // ---- "remind me …" and "add task …"

    private fun parseAdd(t: List<Tok>, now: Long, remindWord: Boolean): AsstIntent? {
        val head = t.take(4)
        if (remindWord) {
            for (x in head) if (x.f in REMIND_EXACT || starts(x, REMIND_START)) {
                use(x)
                break
            }
        } else {
            for (x in head) if (starts(x, ADD_VERBS) || starts(x, ADD_NOUNS)) use(x)
        }
        val relMin = scanRelative(t)
        val ref = scanDate(t, false)
        val clock = scanClock(t)
        var due = 0L
        if (relMin > 0L) {
            due = (now / 60_000L + relMin) * 60_000L
        } else if (ref != null) {
            val day = resolveRange(ref, now, true).from
            due = if (clock != null) TaskLogic.at(day, clock.first, clock.second) else TaskLogic.at(day, 9, 0)
        } else if (clock != null) {
            due = TaskLogic.withTime(0L, clock.first, clock.second, now)
        }
        val title = buildTitle(t)
        if (title.isEmpty() && !remindWord) return null
        return AsstIntent.AddTask(title, due, due > 0L)
    }

    private fun buildTitle(t: List<Tok>): String {
        val rest = ArrayList<Tok>()
        for (x in t) if (!x.used) rest.add(x)
        var a = 0
        var b = rest.size
        while (a < b && rest[a].f in LEAD_TITLE) a++
        while (b > a && rest[b - 1].f in TRAIL_TITLE) b--
        val joined = rest.subList(a, b).joinToString(" ") { x -> x.raw }
        val title = TaskLogic.cleanTitle(joined)
        return if (title.isEmpty()) "" else title.replaceFirstChar { c -> c.uppercase() }
    }

    private fun unitMinutes(f: String): Long = when {
        f == "minute" || f == "minutes" || f == "min" || f == "mins" || f.startsWith("минут") || f.startsWith("րոպե") -> 1L
        f == "hour" || f == "hours" || f == "hr" || f == "hrs" || f == "час" || f == "часа" || f == "часов" || f.startsWith("ժամ") -> 60L
        f == "day" || f == "days" || f == "день" || f == "дня" || f == "дней" || f.startsWith("օր") -> 1440L
        else -> 0L
    }

    /** "in 2 hours", "через 30 минут", "2 ժամից": the minutes from now, or 0. */
    private fun scanRelative(t: List<Tok>): Long {
        for (i in t.indices) {
            val a = t[i]
            if (a.used) continue
            if (a.f == "in" || a.f == "через") {
                var j = i + 1
                var n = 1L
                if (j < t.size && !t[j].used) {
                    val d = t[j].f.toLongOrNull()
                    if (d != null) {
                        n = d
                        j++
                    } else if (t[j].f == "an" || t[j].f == "a" || t[j].f == "one") {
                        j++
                    }
                }
                if (j < t.size && !t[j].used) {
                    val u = unitMinutes(t[j].f)
                    if (u > 0L && n in 1L..1000L) {
                        for (k in i..j) t[k].used = true
                        return minOf(n * u, 30L * 1440L)
                    }
                }
            }
            if (a.f == "ժամից" || a.f == "րոպեից" || a.f == "օրից") {
                var n = 1L
                var from = i
                if (i > 0 && !t[i - 1].used) {
                    val d = t[i - 1].f.toLongOrNull() ?: (if (t[i - 1].f == "մեկ") 1L else null)
                    if (d != null) {
                        n = d
                        from = i - 1
                    }
                }
                val u = unitMinutes(a.f.dropLast(2))
                if (u > 0L && n in 1L..1000L) {
                    for (k in from..i) t[k].used = true
                    return minOf(n * u, 30L * 1440L)
                }
            }
        }
        return 0L
    }

    private enum class Part { MORNING, NOON, AFTERNOON, EVENING, NIGHT }

    private fun partOf(f: String): Part? = when {
        f.startsWith("առավոտ") || f.startsWith("утр") || f == "morning" -> Part.MORNING
        f.startsWith("կեսօր") || f.startsWith("полд") || f == "noon" -> Part.NOON
        f.startsWith("ցերեկ") || f == "afternoon" || f == "днем" || f == "дня" -> Part.AFTERNOON
        f.startsWith("երեկո") || f.startsWith("вечер") || f == "evening" -> Part.EVENING
        f.startsWith("գիշեր") || f.startsWith("ноч") || f == "night" -> Part.NIGHT
        else -> null
    }

    private val CLOCK = Regex("^(\\d{1,2})(?::(\\d{2}))?(\\p{L}*)$")

    /** A time of day written as "10:30", "at 10", "ժամը 10-ին", "в 10 вечера", "5pm", or only "evening". Hour and minute, 24-hour clock. */
    private fun scanClock(t: List<Tok>): Pair<Int, Int>? {
        var pod: Part? = null
        var podTok: Tok? = null
        for (x in t) {
            if (x.used) continue
            val p = partOf(x.f)
            if (p != null) {
                pod = p
                podTok = x
                break
            }
        }
        for (i in t.indices) {
            val x = t[i]
            if (x.used) continue
            val m = CLOCK.matchEntire(x.f) ?: continue
            val h = m.groupValues[1].toInt()
            val hasMin = m.groupValues[2].isNotEmpty()
            val min = if (hasMin) m.groupValues[2].toInt() else 0
            val suffix = m.groupValues[3]
            val marker = i > 0 && !t[i - 1].used && t[i - 1].f in MARKERS
            val ampm = suffix == "am" || suffix == "pm"
            if (suffix.isNotEmpty() && !ampm && suffix != "ին") continue
            if (!hasMin && !marker && !ampm && suffix != "ին") continue
            if (min > 59) continue
            if (ampm && (h < 1 || h > 12)) continue
            if (h > 23) continue
            var hour = h
            if (suffix == "pm" && h < 12) hour = h + 12
            if (suffix == "am" && h == 12) hour = 0
            if (!ampm && pod != null) {
                if ((pod == Part.AFTERNOON || pod == Part.EVENING) && h in 1..11) hour = h + 12
                if (pod == Part.NIGHT) {
                    if (h in 6..11) hour = h + 12
                    if (h == 12) hour = 0
                }
            }
            x.used = true
            if (marker) t[i - 1].used = true
            if (podTok != null) podTok.used = true
            // written apart: "05:14 ին"
            if (i + 1 < t.size && !t[i + 1].used && t[i + 1].f == "ին") t[i + 1].used = true
            return hour to min
        }
        if (pod != null && podTok != null) {
            podTok.used = true
            val h = when (pod) {
                Part.MORNING -> 9
                Part.NOON -> 12
                Part.AFTERNOON -> 15
                Part.EVENING -> 18
                Part.NIGHT -> 21
            }
            return h to 0
        }
        return null
    }

    // ---- dates

    private fun modOf(f: String): Int? = when {
        f == "այս" || f == "ընթացիկ" || f == "this" || f.startsWith("эт") -> 0
        f == "անցյալ" || f == "նախորդ" || f == "անցած" || f == "last" || f == "previous" || f.startsWith("прошл") || f.startsWith("предыдущ") -> -1
        f == "հաջորդ" || f == "գալիք" || f == "եկող" || f == "next" || f.startsWith("следующ") || f.startsWith("будущ") -> 1
        else -> null
    }

    private fun isWeekWord(f: String) = f.startsWith("շաբաթ") || f.startsWith("недел") || f == "week"

    private fun isMonthWord(f: String) = f.startsWith("ամիս") || f.startsWith("ամս") || f.startsWith("месяц") || f == "month"

    private fun isDayWord(f: String) = f.startsWith("օր") || f == "day" || f == "days" || f.startsWith("дн") || f == "день"

    private fun weekdayOf(f: String): Int? = when {
        f.startsWith("երկուշաբթ") || f == "monday" || f.startsWith("понедельник") -> Calendar.MONDAY
        f.startsWith("երեքշաբթ") || f == "tuesday" || f.startsWith("вторник") -> Calendar.TUESDAY
        f.startsWith("չորեքշաբթ") || f == "wednesday" || f == "среда" || f == "среду" || f == "среды" || f == "среде" -> Calendar.WEDNESDAY
        f.startsWith("հինգշաբթ") || f == "thursday" || f.startsWith("четверг") -> Calendar.THURSDAY
        f.startsWith("ուրբաթ") || f == "friday" || f.startsWith("пятниц") -> Calendar.FRIDAY
        f == "saturday" || f.startsWith("суббот") -> Calendar.SATURDAY
        f.startsWith("կիրակի") || f == "sunday" || f.startsWith("воскресень") -> Calendar.SUNDAY
        else -> null
    }

    /** Finds a day or a longer period in the words and marks its words as used. With [span] false only single days count. */
    private fun scanDate(t: List<Tok>, span: Boolean): DateRef? {
        for (i in t.indices) {
            val a = t[i]
            if (a.used) continue
            val f = a.f
            val b = if (i + 1 < t.size && !t[i + 1].used) t[i + 1] else null
            val c = if (i + 2 < t.size && !t[i + 2].used) t[i + 2] else null
            if (f == "day" && b != null && b.f == "after" && c != null && c.f.startsWith("tomorrow")) {
                use(a, b, c)
                return DateRef.Rel(2)
            }
            if (f == "մյուս" && b != null && b.f.startsWith("օր")) {
                use(a, b)
                return DateRef.Rel(2)
            }
            val mod = modOf(f)
            if (mod != null && b != null && weekdayOf(b.f) != null) {
                // "next Friday", "հաջորդ ուրբաթ": the weekday itself decides the day
                use(a, b)
                if (c != null && c.f.startsWith("օր")) use(c)
                return DateRef.Weekday(weekdayOf(b.f)!!)
            }
            if (mod != null && b != null) {
                if (isWeekWord(b.f) && c != null && c.f.startsWith("օր") && b.f.startsWith("շաբաթ")) {
                    use(a, b, c)
                    return DateRef.Weekday(Calendar.SATURDAY)
                }
                if (span && isWeekWord(b.f)) {
                    use(a, b)
                    return DateRef.Week(mod)
                }
                if (span && isMonthWord(b.f)) {
                    use(a, b)
                    return DateRef.Month(mod)
                }
            }
            if (span && (f == "վերջին" || f.startsWith("последн") || f == "last" || f == "past") && b != null) {
                val n = b.f.toIntOrNull()
                if (n != null && n in 1..366 && c != null && isDayWord(c.f)) {
                    use(a, b, c)
                    return DateRef.LastDays(n)
                }
                if (isWeekWord(b.f)) {
                    use(a, b)
                    return DateRef.LastDays(7)
                }
                if (isMonthWord(b.f)) {
                    use(a, b)
                    return DateRef.LastDays(30)
                }
            }
            if (f.startsWith("այսօր") || f.startsWith("сегодня") || f == "today") {
                use(a)
                return DateRef.Rel(0)
            }
            if (f == "վաղը" || f == "վաղվա" || f == "վաղն" || f == "վաղվանից" || f.startsWith("завтра") || f == "tomorrow") {
                use(a)
                return DateRef.Rel(1)
            }
            if (f.startsWith("послезавтра")) {
                use(a)
                return DateRef.Rel(2)
            }
            if (f == "երեկ" || f == "երեկվա" || f.startsWith("вчера") || f == "yesterday") {
                use(a)
                return DateRef.Rel(-1)
            }
            if (f.startsWith("շաբաթ") && b != null && b.f.startsWith("օր")) {
                use(a, b)
                return DateRef.Weekday(Calendar.SATURDAY)
            }
            val wd = weekdayOf(f)
            if (wd != null) {
                use(a)
                // "ուրբաթ օրը" = "on Friday"
                if (b != null && b.f.startsWith("օր")) use(b)
                return DateRef.Weekday(wd)
            }
        }
        return null
    }

    private fun startOfWeek(now: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = TaskLogic.startOfDay(now)
        val back = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
        c.add(Calendar.DAY_OF_YEAR, -back)
        return c.timeInMillis
    }

    private fun dayRange(kind: RangeKind, anyMomentOfDay: Long, n: Int = 0): DayRange =
        DayRange(kind, TaskLogic.startOfDay(anyMomentOfDay), TaskLogic.endOfDay(anyMomentOfDay), n)

    private fun resolveRange(ref: DateRef, now: Long, future: Boolean): DayRange = when (ref) {
        is DateRef.Rel -> {
            val kind = when (ref.days) {
                0 -> RangeKind.TODAY
                1 -> RangeKind.TOMORROW
                2 -> RangeKind.DAY_AFTER
                else -> RangeKind.YESTERDAY
            }
            dayRange(kind, TaskLogic.addDays(TaskLogic.startOfDay(now), ref.days))
        }
        is DateRef.Week -> {
            val mon = TaskLogic.addDays(startOfWeek(now), 7 * ref.offset)
            val kind = if (ref.offset < 0) RangeKind.LAST_WEEK else if (ref.offset > 0) RangeKind.NEXT_WEEK else RangeKind.THIS_WEEK
            DayRange(kind, mon, TaskLogic.endOfDay(TaskLogic.addDays(mon, 6)))
        }
        is DateRef.Month -> {
            val c = Calendar.getInstance()
            c.timeInMillis = TaskLogic.startOfDay(now)
            c.set(Calendar.DAY_OF_MONTH, 1)
            c.add(Calendar.MONTH, ref.offset)
            val from = c.timeInMillis
            c.add(Calendar.MONTH, 1)
            DayRange(if (ref.offset < 0) RangeKind.LAST_MONTH else RangeKind.THIS_MONTH, from, c.timeInMillis - 1L)
        }
        is DateRef.Weekday -> {
            val c = Calendar.getInstance()
            c.timeInMillis = now
            val today = c.get(Calendar.DAY_OF_WEEK)
            val diff = if (future) (ref.dow - today + 7) % 7 else -((today - ref.dow + 7) % 7)
            dayRange(RangeKind.WEEKDAY, TaskLogic.addDays(TaskLogic.startOfDay(now), diff), ref.dow)
        }
        is DateRef.LastDays -> {
            val from = TaskLogic.startOfDay(TaskLogic.addDays(now, -(ref.n - 1)))
            DayRange(RangeKind.LAST_DAYS, from, TaskLogic.endOfDay(now), ref.n)
        }
    }

    // ---- "what do I have", "unfinished tasks"

    private fun parseList(t: List<Tok>, now: Long): AsstIntent {
        var filter = TaskFilter.OPEN
        var explicit = false
        for (i in t.indices) {
            val x = t[i]
            if (starts(x, OVERDUE_WORDS)) {
                filter = TaskFilter.OVERDUE
                explicit = true
                break
            }
        }
        if (!explicit) {
            for (i in t.indices) {
                val x = t[i]
                val notWord = i > 0 && t[i - 1].f == "не"
                if (notWord && (x.f.startsWith("сделан") || x.f.startsWith("выполнен") || x.f.startsWith("завершен"))) {
                    filter = TaskFilter.OPEN
                    explicit = true
                    break
                }
            }
        }
        if (!explicit) {
            for (x in t) {
                if (starts(x, OPEN_WORDS)) {
                    filter = TaskFilter.OPEN
                    explicit = true
                    break
                }
            }
        }
        if (!explicit) {
            for (x in t) {
                if (starts(x, DONE_WORDS)) {
                    filter = TaskFilter.DONE
                    explicit = true
                    break
                }
            }
        }
        if (!explicit && t.any { x -> starts(x, ALL_WORDS) }) filter = TaskFilter.ALL
        val ref = scanDate(t, true)
        val range = if (ref == null) null else resolveRange(ref, now, true)
        return AsstIntent.ListTasks(filter, range)
    }

    // ---- "find …"

    private fun parseFind(t: List<Tok>, now: Long): AsstIntent {
        val ref = scanDate(t, true)
        val range = if (ref == null) null else resolveRange(ref, now, false)
        val words = ArrayList<String>()
        for (x in t) {
            if (x.used) continue
            if (x.f in STOP || starts(x, SEARCH_VERBS) || starts(x, TASK_NOUNS)) continue
            if (x.f.length < 2 && x.f.toIntOrNull() == null) continue
            if (x.f !in words) words.add(x.f)
            if (words.size >= MAX_WORDS) break
        }
        if (words.isEmpty() && range == null) return AsstIntent.Help
        return AsstIntent.Find(words, range)
    }
}

/** What the assistant does with what the parser found: which tasks to show, which words to look for. Pure, so it can be checked anywhere. */
object AssistantLogic {
    const val MAX_ROWS = 30

    /** The tasks a question asks for (at most [MAX_ROWS]) and how many matched in all. */
    fun pickTasks(all: List<Task>, filter: TaskFilter, range: DayRange?, now: Long): Pair<List<Task>, Int> {
        val inRange = { ts: Long -> range == null || (ts >= range.from && ts <= range.to) }
        val list: List<Task> = when (filter) {
            TaskFilter.OPEN -> all
                .filter { x -> !x.done && (range == null || (x.due > 0L && inRange(x.due))) }
                .sortedWith(compareBy<Task>({ x -> x.due == 0L }, { x -> x.due }, { x -> x.created }))
            TaskFilter.OVERDUE -> all
                .filter { x -> TaskLogic.isOverdue(x, now) && inRange(x.due) }
                .sortedBy { x -> x.due }
            TaskFilter.DONE -> all
                .filter { x -> x.done && (range == null || inRange(x.doneAt)) }
                .sortedByDescending { x -> x.doneAt }
            TaskFilter.ALL -> all
                .filter { x -> range == null || (x.due > 0L && inRange(x.due)) }
                .sortedWith(compareBy<Task>({ x -> x.done }, { x -> x.due == 0L }, { x -> x.due }, { x -> x.created }))
        }
        return list.take(MAX_ROWS) to list.size
    }

    /**
     * A word shortened so that other forms of it match too ("հասցեն" finds "հասցե", "встречу" finds "встреча"):
     * a word of six letters or more loses its last two letters, but never goes below four.
     */
    fun stem(w: String): String = if (w.length >= 6) w.take(maxOf(4, w.length - 2)) else w

    fun stems(words: List<String>): List<String> = words.map { w -> stem(w) }.distinct()

    /** How many words a result must have: all of them, or (when [loose] and there are three or more) all but one. */
    fun need(n: Int, loose: Boolean): Int = if (loose && n >= 3) n - 1 else n

    /** My tasks that match the words (title, note or the chat they came from) and, when a period is given, are due, made or done in it. */
    fun findTasks(all: List<Task>, words: List<String>, range: DayRange?, need: Int): List<Task> {
        val st = stems(words)
        val out = ArrayList<Pair<Int, Task>>()
        for (x in all) {
            if (range != null) {
                val hit = (x.due > 0L && x.due >= range.from && x.due <= range.to) ||
                    (x.created >= range.from && x.created <= range.to) ||
                    (x.done && x.doneAt >= range.from && x.doneAt <= range.to)
                if (!hit) continue
            }
            val score = if (st.isEmpty()) 1 else MemoryLogic.score(MemoryLogic.fold(x.title + "\n" + x.note + "\n" + x.chatName), st)
            if (score < maxOf(1, minOf(need, st.size))) continue
            out.add(score to x)
        }
        return out
            .sortedWith(compareByDescending<Pair<Int, Task>> { p -> p.first }.thenByDescending { p -> p.second.created })
            .map { p -> p.second }
            .take(MAX_ROWS)
    }
}

package am.khosq.app.data

import java.util.Calendar

/**
 * One task of mine. Tasks live only on this phone (never sent anywhere).
 * [due] is a moment in time (0 = no date). [remind] is what the person chose; [remindAt] is the moment the phone will
 * ring next (0 = nothing waits): it is set from [due], cleared once the reminder has gone off, and moved by "snooze".
 * [chatId], [chatName] and [msgId] tell which message the task was made from (empty for a task written by hand).
 */
data class Task(
    val id: Long = 0L,
    val title: String = "",
    val note: String = "",
    val due: Long = 0L,
    val remind: Boolean = false,
    val remindAt: Long = 0L,
    val done: Boolean = false,
    val doneAt: Long = 0L,
    val created: Long = 0L,
    val chatId: String = "",
    val chatName: String = "",
    val msgId: String = "",
)

/** The sections of the task list, in the order they are shown. */
enum class TaskGroup { OVERDUE, TODAY, UPCOMING, NO_DATE, DONE }

/** The rules of tasks and reminders. No Android in here, so they can be checked on any computer. */
object TaskLogic {
    const val MAX_TITLE = 200
    const val MAX_NOTE = 1000
    const val MAX_TASKS = 1000
    const val SNOOZE_MS = 10L * 60_000L

    /** A reminder that is less than this late/early still counts as "now" (alarms never fire to the exact millisecond). */
    const val SLACK_MS = 5_000L

    private const val DAY_MS = 86_400_000.0

    fun cleanTitle(s: String): String = s.trim().replace(Regex("\\s*\\n\\s*"), " ").take(MAX_TITLE).trim()

    fun cleanNote(s: String): String = s.trim().take(MAX_NOTE).trim()

    /**
     * The task as it should be stored: cleaned texts, and the reminder time worked out from the date, the
     * "remind me" choice and the done mark. Null when there is no title. A moment that has already passed
     * never gets a reminder.
     */
    fun prepare(t: Task, now: Long): Task? {
        val title = cleanTitle(t.title)
        if (title.isEmpty()) return null
        val due = if (t.due > 0L) t.due else 0L
        val remind = t.remind && due > 0L
        val ring = remind && !t.done && due > now
        return t.copy(
            title = title,
            note = cleanNote(t.note),
            due = due,
            remind = remind,
            remindAt = if (ring) due else 0L,
            doneAt = if (t.done) (if (t.doneAt > 0L) t.doneAt else now) else 0L,
            created = if (t.created > 0L) t.created else now,
        )
    }

    /** Done or not done again. Taking the mark off brings the reminder back if its time has not passed yet. */
    fun withDone(t: Task, done: Boolean, now: Long): Task {
        val d = t.copy(done = done, doneAt = if (done) now else 0L)
        val ring = d.remind && !done && d.due > now
        return d.copy(remindAt = if (ring) d.due else 0L)
    }

    /** "Later": the next ring is [SNOOZE_MS] from now; the task keeps its date. */
    fun snoozed(t: Task, now: Long): Task = t.copy(remindAt = now + SNOOZE_MS)

    fun isOverdue(t: Task, now: Long): Boolean = !t.done && t.due > 0L && t.due < now

    fun groupOf(t: Task, now: Long): TaskGroup {
        if (t.done) return TaskGroup.DONE
        if (t.due <= 0L) return TaskGroup.NO_DATE
        if (t.due < now) return TaskGroup.OVERDUE
        return if (t.due <= endOfDay(now)) TaskGroup.TODAY else TaskGroup.UPCOMING
    }

    /** The non-empty sections in display order, each sorted the way a person expects to read it. */
    fun sections(all: List<Task>, now: Long): List<Pair<TaskGroup, List<Task>>> {
        val out = ArrayList<Pair<TaskGroup, List<Task>>>()
        for (g in TaskGroup.values()) {
            val part = all.filter { x -> groupOf(x, now) == g }
            if (part.isEmpty()) continue
            val sorted = when (g) {
                TaskGroup.DONE -> part.sortedByDescending { x -> x.doneAt }
                TaskGroup.NO_DATE -> part.sortedBy { x -> x.created }
                else -> part.sortedBy { x -> x.due }
            }
            out.add(g to sorted)
        }
        return out
    }

    // ---- days and times (the phone's own time zone)

    fun startOfDay(ts: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ts
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun endOfDay(ts: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = startOfDay(ts)
        c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis - 1L
    }

    /** How many calendar days [ts] is after [now] (0 today, 1 tomorrow, -1 yesterday). */
    fun dayDiff(ts: Long, now: Long): Int = Math.round((startOfDay(ts) - startOfDay(now)) / DAY_MS).toInt()

    /** The day of [dayTs] at hour:minute. */
    fun at(dayTs: Long, hour: Int, minute: Int): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = dayTs
        c.set(Calendar.HOUR_OF_DAY, hour)
        c.set(Calendar.MINUTE, minute)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    fun hourOf(ts: Long): Int {
        val c = Calendar.getInstance()
        c.timeInMillis = ts
        return c.get(Calendar.HOUR_OF_DAY)
    }

    fun minuteOf(ts: Long): Int {
        val c = Calendar.getInstance()
        c.timeInMillis = ts
        return c.get(Calendar.MINUTE)
    }

    /** The person picked a day: the time of day stays what it was (09:00 when nothing was set yet). */
    fun withDate(due: Long, year: Int, month: Int, day: Int): Long {
        val c = Calendar.getInstance()
        val h = if (due > 0L) hourOf(due) else 9
        val m = if (due > 0L) minuteOf(due) else 0
        c.clear()
        c.set(year, month, day, h, m, 0)
        return c.timeInMillis
    }

    /** The person picked a time: the day stays; with no day yet it is today, or tomorrow if that time has gone. */
    fun withTime(due: Long, hour: Int, minute: Int, now: Long): Long {
        if (due > 0L) return at(due, hour, minute)
        val today = at(now, hour, minute)
        return if (today > now) today else at(addDays(now, 1), hour, minute)
    }

    fun addDays(ts: Long, n: Int): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = ts
        c.add(Calendar.DAY_OF_YEAR, n)
        return c.timeInMillis
    }

    /** "In an hour", to the whole minute. */
    fun inAnHour(now: Long): Long = (now / 60_000L + 60L) * 60_000L

    /** Today at 18:00, or 0 when that time is nearly here or gone. */
    fun thisEvening(now: Long): Long {
        val t = at(now, 18, 0)
        return if (t > now + 5L * 60_000L) t else 0L
    }

    /** Tomorrow at 09:00. */
    fun tomorrowMorning(now: Long): Long = at(addDays(now, 1), 9, 0)

    /** A task from a chat message: a short first line as the title, the whole text as the note. */
    fun fromMessage(text: String): Pair<String, String> {
        val t = text.trim()
        val first = t.lineSequence().map { x -> x.trim() }.firstOrNull { x -> x.isNotEmpty() } ?: ""
        val title = if (first.length > 80) first.take(79).trimEnd() + "…" else first
        val note = if (t == title) "" else cleanNote(t)
        return title to note
    }
}

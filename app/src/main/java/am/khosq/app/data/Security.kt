package am.khosq.app.data

/** How one point of the security centre stands. [NOTE] is "nothing to do here (yet)": it is not counted. */
enum class SecLevel { GOOD, TODO, NOTE }

/** The points of the security centre, in the order they are shown. */
enum class SecPoint { PHONE_LOCK, APP_LOCK, SHIELD, CIRCLE, VERIFIED, BATTERY }

/** What the screen knows about the phone and the app right now. */
data class SecInput(
    /** The phone itself has a PIN, a pattern or a password. */
    val phoneLock: Boolean,
    /** The person switched the lock of KHOSQ on. */
    val appLock: Boolean,
    val shield: Boolean,
    /** People of the SOS circle who are still accepted contacts. */
    val circleCount: Int,
    /** Accepted one-to-one contacts. */
    val contacts: Int,
    /** Contacts who are verified and whose key has not changed since. */
    val verified: Int,
    /** Contacts who were verified and whose key has changed since. */
    val changed: Int,
    /** The phone does not stop KHOSQ in the background to save battery. */
    val batteryFree: Boolean,
)

data class SecItem(val point: SecPoint, val level: SecLevel)

/** [good] of [total] counted points are done (points with the level NOTE are not counted). */
data class SecReport(val items: List<SecItem>, val good: Int, val total: Int, val changed: Int)

object SecurityLogic {
    fun report(i: SecInput): SecReport {
        val lockWorks = i.appLock && i.phoneLock
        val verifiedLevel = when {
            i.changed > 0 -> SecLevel.TODO
            i.contacts <= 0 -> SecLevel.NOTE
            i.verified > 0 -> SecLevel.GOOD
            else -> SecLevel.TODO
        }
        val items = listOf(
            SecItem(SecPoint.PHONE_LOCK, if (i.phoneLock) SecLevel.GOOD else SecLevel.TODO),
            SecItem(SecPoint.APP_LOCK, if (lockWorks) SecLevel.GOOD else SecLevel.TODO),
            SecItem(SecPoint.SHIELD, if (i.shield) SecLevel.GOOD else SecLevel.TODO),
            SecItem(SecPoint.CIRCLE, if (i.circleCount > 0) SecLevel.GOOD else SecLevel.TODO),
            SecItem(SecPoint.VERIFIED, verifiedLevel),
            SecItem(SecPoint.BATTERY, if (i.batteryFree) SecLevel.GOOD else SecLevel.TODO),
        )
        val counted = items.filter { x -> x.level != SecLevel.NOTE }
        return SecReport(items, counted.count { x -> x.level == SecLevel.GOOD }, counted.size, i.changed)
    }
}

/** When the lock of the app closes the app again. */
object AppLockLogic {
    /** "Lock after": at once, or after this many minutes away from the app. */
    val GRACE_MINUTES: List<Int> = listOf(0, 1, 5, 15)
    const val DEFAULT_GRACE = 1

    fun clampGrace(m: Int): Int = if (m in GRACE_MINUTES) m else DEFAULT_GRACE

    fun parseGrace(s: String): Int = clampGrace(s.trim().toIntOrNull() ?: DEFAULT_GRACE)

    /**
     * Must the app ask for the phone's unlock now? [leftAt] is when the last screen of the app went away (0 = it has not since the
     * app started), [now] is the same clock in milliseconds. A call that is going on never gets interrupted.
     */
    fun shouldLock(enabled: Boolean, alreadyLocked: Boolean, leftAt: Long, now: Long, graceMinutes: Int, callOn: Boolean): Boolean {
        if (!enabled) return false
        if (alreadyLocked) return true
        if (callOn) return false
        if (leftAt <= 0L) return false
        // the clock was turned back: better to ask
        if (now < leftAt) return true
        return now - leftAt >= clampGrace(graceMinutes) * 60_000L
    }
}

/** What a notification may say when the person asked to keep the text of messages off the screen. */
object NotifyPrivacy {
    /** An SOS must stay readable ("🆘 SOS" holds no place, only the word), whatever the switch says. */
    fun hidden(hide: Boolean, preview: String): Boolean = hide && !preview.trimStart().startsWith("🆘")

    fun text(hide: Boolean, preview: String, generic: String): String = if (hidden(hide, preview)) generic else preview
}

package am.khosq.app.data

/**
 * One reading of the place: where ([lat], [lng]), how exact ([accuracy] in metres, smaller is better, [UNKNOWN] when the phone did not
 * say), how old ([ageMs]) and who gave it ([source]: "gps", "network", "fused", ...). [ref] lets the caller carry its own object
 * (the Android Location) through the choice.
 */
class Fix(
    val lat: Double,
    val lng: Double,
    val accuracy: Float,
    val ageMs: Long,
    val source: String,
    val ref: Any? = null,
)

/**
 * Chooses a place without waiting longer than needed. The first idea of the old code was to wait for the GPS alone, which indoors
 * can take 10-20 seconds or more. Now the phone's own recent knowledge, the network and the GPS are all asked at once, and the first
 * reading that is exact enough ends the wait. Nothing Android in here, so it can be checked anywhere.
 */
object LocationPick {
    /** A reading no older than this is "now". */
    const val FRESH_MS = 120_000L

    /** Exact enough to stop at once (about what Wi-Fi or GPS gives). */
    const val GOOD_M = 60f

    /** Exact enough after the grace time (a rough place is better than none, but the GPS gets a few seconds to improve it). */
    const val OK_M = 250f
    const val GRACE_MS = 6_000L

    /** After this the best reading there is, whatever its accuracy, is taken. */
    const val MAX_MS = 20_000L

    /** A reading older than this is never offered as the place "now". */
    const val STALE_MS = 600_000L

    /** What the phone gives when it does not know the accuracy. */
    const val UNKNOWN = 100_000f

    /** The accuracy to use: the phone's number when it has one, otherwise [UNKNOWN]. */
    fun accuracyOf(raw: Float, has: Boolean): Float = if (!has || raw.isNaN() || raw <= 0f) UNKNOWN else raw

    fun valid(f: Fix): Boolean =
        !f.lat.isNaN() && !f.lng.isNaN() && f.lat in -90.0..90.0 && f.lng in -180.0..180.0 && !(f.lat == 0.0 && f.lng == 0.0)

    /** Usable as "my place now": a real place that is not stale. */
    fun usable(f: Fix): Boolean = valid(f) && f.ageMs in 0..STALE_MS

    /** Lower is better: the accuracy in metres plus 5 metres for every second the reading has aged (a person moves meanwhile). */
    fun score(f: Fix): Float = f.accuracy + (f.ageMs.coerceAtLeast(0L) / 1000f) * 5f

    /** The better of two readings; an unusable one never wins over a usable one. */
    fun best(a: Fix?, b: Fix?): Fix? {
        if (a == null) return if (b != null && usable(b)) b else null
        if (b == null || !usable(b)) return if (usable(a)) a else null
        return if (score(b) < score(a)) b else a
    }

    /**
     * May the wait end now? [elapsedMs] is the time since the request started. A fresh and exact reading ends it at once; a fairly
     * exact one after the grace time; at the end of the time any usable reading does.
     */
    fun enough(best: Fix?, elapsedMs: Long): Boolean {
        if (best == null || !usable(best)) return false
        if (elapsedMs >= MAX_MS) return true
        val fresh = best.ageMs <= FRESH_MS
        if (fresh && best.accuracy <= GOOD_M) return true
        return fresh && elapsedMs >= GRACE_MS && best.accuracy <= OK_M
    }

    /** How to tell the person how exact the place is: 0 = exact, 1 = about, 2 = rough (the phone could only guess). */
    fun grade(accuracy: Float): Int = when {
        accuracy <= 50f -> 0
        accuracy <= 300f -> 1
        else -> 2
    }

    /** The accuracy for the screen: whole metres, rounded up to a multiple of 5 below 100 m and of 50 above. */
    fun metres(accuracy: Float): Int {
        val a = if (accuracy.isNaN() || accuracy < 1f) 1f else accuracy
        val step = if (a < 100f) 5 else 50
        val m = Math.ceil((a / step).toDouble()).toInt() * step
        return m.coerceAtMost(99_999)
    }
}

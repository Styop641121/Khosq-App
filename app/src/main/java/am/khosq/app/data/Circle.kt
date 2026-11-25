package am.khosq.app.data

import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One recorded place: [t] is in seconds since 1970. */
data class SosPoint(val lat: Double, val lng: Double, val t: Long)

/** Why the SOS went out: the person pressed the button, or the timer ran out without an answer. */
enum class SosReason(val code: String) {
    BUTTON("B"),
    TIMER("T"),
}

/**
 * What an SOS card carries. [firedAt] and [startedAt] are in milliseconds (startedAt = 0 for the button). [ended] is set when the person
 * says they are fine, so the circle sees that it is over. [points] are the places in order of time, oldest first.
 */
data class SosInfo(
    val reason: SosReason,
    val firedAt: Long,
    val startedAt: Long,
    val minutes: Int,
    val ended: Boolean,
    val points: List<SosPoint>,
)

/** The text of an SOS message. It is sealed like any text, so the server only sees scrambled letters. */
object SosCodec {
    const val TAG = "KSOS1"

    /** Most places one message carries; more are thinned out, the first and the last always stay. */
    const val MAX_POINTS = 80

    fun encode(s: SosInfo): String {
        val pts = TrailLogic.thin(s.points, MAX_POINTS).joinToString(";") { p -> TrailLogic.line(p) }
        return listOf(TAG, s.reason.code, s.firedAt.toString(), s.startedAt.toString(), s.minutes.toString(), if (s.ended) "1" else "0", pts)
            .joinToString("|")
    }

    /** Null when the text is not an SOS message (or is broken). */
    fun decode(text: String): SosInfo? {
        val p = text.split("|")
        if (p.size != 7 || p[0] != TAG) return null
        val reason = when (p[1]) {
            "B" -> SosReason.BUTTON
            "T" -> SosReason.TIMER
            else -> return null
        }
        val fired = p[2].toLongOrNull() ?: return null
        val started = p[3].toLongOrNull() ?: return null
        val minutes = p[4].toIntOrNull() ?: return null
        if (fired < 0L || started < 0L || minutes < 0) return null
        val ended = when (p[5]) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        val pts = ArrayList<SosPoint>()
        if (p[6].isNotEmpty()) {
            for (item in p[6].split(";")) {
                val pt = TrailLogic.parseLine(item) ?: continue
                pts.add(pt)
                if (pts.size >= 200) break
            }
        }
        return SosInfo(reason, fired, started, minutes, ended, pts)
    }
}

/** Recording a walk: which places are worth keeping, how they are written down, and how to open them in a maps app. */
object TrailLogic {
    /** A place is kept when the person has moved this far since the last kept place... */
    const val MIN_METERS = 25.0

    /** ...or when this long has passed (so a person who stands still still leaves a sign of life). */
    const val MAX_GAP_SECONDS = 120L

    /** Places less exact than this (metres) are ignored, except the very first. */
    const val GOOD_ACCURACY = 100f

    /** The most places kept on the phone for one walk. */
    const val MAX_STORED = 1500

    fun meters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lng2 - lng1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(min(1.0, sqrt(a)))
    }

    /** Is a new fix worth adding after [last]? [accuracy] and [maxAccuracy] are in metres; [t] in seconds. */
    fun accept(last: SosPoint?, lat: Double, lng: Double, t: Long, accuracy: Float, maxAccuracy: Float): Boolean {
        if (lat < -90.0 || lat > 90.0 || lng < -180.0 || lng > 180.0) return false
        if (last == null) return true
        if (t <= last.t) return false
        if (accuracy > maxAccuracy) return false
        if (meters(last.lat, last.lng, lat, lng) >= MIN_METERS) return true
        return t - last.t >= MAX_GAP_SECONDS
    }

    /** At most [limit] places, the first and the last always among them, the others evenly spread. */
    fun thin(points: List<SosPoint>, limit: Int): List<SosPoint> {
        if (limit < 2 || points.size <= limit) return points
        val n = points.size
        val out = ArrayList<SosPoint>(limit)
        var prev = -1
        for (i in 0 until limit) {
            val idx = ((i.toLong() * (n - 1)) / (limit - 1)).toInt()
            if (idx != prev) out.add(points[idx])
            prev = idx
        }
        return out
    }

    /** "40.18111,44.51361,1732500000" (five decimals are about a metre, always with a dot). */
    fun line(p: SosPoint): String = String.format(Locale.US, "%.5f,%.5f,%d", p.lat, p.lng, p.t)

    fun parseLine(s: String): SosPoint? {
        val a = s.trim().split(",")
        if (a.size != 3) return null
        val lat = a[0].toDoubleOrNull() ?: return null
        val lng = a[1].toDoubleOrNull() ?: return null
        val t = a[2].toLongOrNull() ?: return null
        if (lat.isNaN() || lng.isNaN()) return null
        if (lat < -90.0 || lat > 90.0 || lng < -180.0 || lng > 180.0 || t < 0L) return null
        return SosPoint(lat, lng, t)
    }

    private fun ll(p: SosPoint): String = String.format(Locale.US, "%.5f,%.5f", p.lat, p.lng)

    /** How far the places go in total, in metres. */
    fun length(points: List<SosPoint>): Double {
        var sum = 0.0
        for (i in 1 until points.size) sum += meters(points[i - 1].lat, points[i - 1].lng, points[i].lat, points[i].lng)
        return sum
    }

    /**
     * A link that shows the walk in the phone's maps app: from the first place to the last one through up to 8 places in between.
     * Maps draws its own way between them (it is not the exact path), so it is only a guide. Null with fewer than two places.
     */
    fun routeUrl(points: List<SosPoint>): String? {
        if (points.size < 2) return null
        val few = thin(points, 10)
        val sb = StringBuilder("https://www.google.com/maps/dir/?api=1&origin=")
        sb.append(ll(few.first())).append("&destination=").append(ll(few.last())).append("&travelmode=walking")
        val mid = few.subList(1, few.size - 1)
        if (mid.isNotEmpty()) sb.append("&waypoints=").append(mid.joinToString("%7C") { p -> ll(p) })
        return sb.toString()
    }
}

/** The timer ("I will write that I am fine within ...") and the moments it goes through. */
object SosTimer {
    val PRESETS: List<Int> = listOf(5, 15, 30, 60, 120, 240)
    const val MAX_MINUTES = 240

    /** The person is warned this long before the time runs out. */
    const val WARN_MS = 60_000L

    /** After the SOS has gone out, the circle gets the newest places this often. */
    const val UPDATE_MS = 120_000L

    /** ...for at most this long (then the live updates stop by themselves). */
    const val LIVE_MAX_MS = 3L * 60L * 60L * 1000L

    enum class Phase { RUNNING, WARNING, DUE, LIVE, OVER }

    /**
     * One running timer or one SOS. [startedAt] and [firedAt] are in milliseconds; [firedAt] is 0 until the SOS has gone out.
     * For the button, [minutes] is 0 and [startedAt] equals [firedAt].
     */
    data class Rec(val startedAt: Long, val minutes: Int, val firedAt: Long, val reason: SosReason) {
        val deadline: Long get() = startedAt + minutes * 60_000L
    }

    fun clampMinutes(m: Int): Int = max(1, min(MAX_MINUTES, m))

    fun phase(r: Rec, now: Long): Phase = when {
        r.firedAt > 0L -> if (now - r.firedAt > LIVE_MAX_MS) Phase.OVER else Phase.LIVE
        now >= r.deadline -> Phase.DUE
        now >= r.deadline - WARN_MS -> Phase.WARNING
        else -> Phase.RUNNING
    }

    fun remainingMs(r: Rec, now: Long): Long = max(0L, r.deadline - now)

    /** "24:13", or "1:05:09" from an hour up. */
    fun clock(ms: Long): String {
        val total = max(0L, (ms + 999L) / 1000L)
        val h = total / 3600L
        val m = (total % 3600L) / 60L
        val s = total % 60L
        return if (h > 0L) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
    }

    fun encode(r: Rec): String = listOf(r.startedAt.toString(), r.minutes.toString(), r.firedAt.toString(), r.reason.code).joinToString("|")

    fun decode(s: String): Rec? {
        val p = s.split("|")
        if (p.size != 4) return null
        val started = p[0].toLongOrNull() ?: return null
        val minutes = p[1].toIntOrNull() ?: return null
        val fired = p[2].toLongOrNull() ?: return null
        val reason = when (p[3]) {
            "B" -> SosReason.BUTTON
            "T" -> SosReason.TIMER
            else -> return null
        }
        if (started <= 0L || minutes < 0 || minutes > MAX_MINUTES || fired < 0L) return null
        return Rec(started, minutes, fired, reason)
    }
}

/** The trusted circle: up to five people who get the SOS. Stored as their ids (the chat is worked out from my id and theirs). */
object CircleLogic {
    const val MAX = 5

    fun parse(s: String): List<String> = s.split(",").map { x -> x.trim() }.filter { x -> x.isNotEmpty() }.distinct().take(MAX)

    fun join(list: List<String>): String = list.distinct().take(MAX).joinToString(",")

    /** Takes the person out when they are in; puts them in when there is room; otherwise nothing changes. */
    fun toggle(list: List<String>, uid: String): List<String> = when {
        uid in list -> list.filter { x -> x != uid }
        list.size < MAX -> list + uid
        else -> list
    }

    /** Only people who are still accepted contacts count. */
    fun usable(list: List<String>, active: Set<String>): List<String> = list.filter { x -> x in active }
}

/** Which message of which person holds my SOS card (so later updates change that card): person -> message id and whether it was sealed. */
object SosRefs {
    data class Ref(val msgId: String, val sealed: Boolean)

    fun encode(m: Map<String, Ref>): String = m.entries.joinToString(",") { e -> e.key + "=" + e.value.msgId + "=" + (if (e.value.sealed) "1" else "0") }

    fun decode(s: String): Map<String, Ref> {
        val out = LinkedHashMap<String, Ref>()
        for (item in s.split(",")) {
            val p = item.split("=")
            if (p.size != 3 || p[0].isEmpty() || p[1].isEmpty()) continue
            out[p[0]] = Ref(p[1], p[2] == "1")
        }
        return out
    }
}

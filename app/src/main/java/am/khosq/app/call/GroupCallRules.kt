package am.khosq.app.call

/**
 * The rules of a group call that need nothing of the phone (no WebRTC, no Firestore): the numbers, who is in a call, who makes the offer.
 * They are the very same as in docs/gcalls.js of the web page (the page and the phone are in one call together), and worker/webgcalls.test.mjs
 * reads both files and checks that the numbers and the field names are the same.
 *
 * What is written, and where:
 *   gcalls/<id>        { chat, host, hostName, title, kind: "audio" | "video", state: "active" | "ended", ts, con?, dur?,
 *                        parts: { <uid>: { sid, j, at, name, m, cam } } }     parts = who is in the call now
 *   gcalls/<id>/sig/*  { from, to, fsid, tsid, lid, k: "offer" | "answer" | "cand", sdp?, c?: [{m, i, s}], at }
 *   chats/<group>.gcall { id, host, hostName, kind, ts }     while a call goes on (what makes the other devices ring)
 */
object GroupCallRules {
    /** At most this many people in one call (the page has the same number). */
    const val MAX_PARTS = 6
    const val RING_MS = 45_000L
    const val RING_SHOW_MS = 90_000L
    const val BEAT_MS = 25_000L
    const val LIVE_MS = 75_000L
    const val FIRST_SIGHT_MS = 240_000L
    const val ALONE_MS = 20_000L
    const val CONNECT_MS = 30_000L
    const val GRACE_MS = 8_000L
    const val RETRY_MS = 3_000L
    const val RETRIES = 2
    const val BATCH_MS = 250L
    const val DEAD_MS = 180_000L
    const val NOTE_MS = 1_800L
    const val NOTE_LONG_MS = 5_000L
    const val FIRST_DOC_MS = 8_000L

    /** What a sender of a picture is told when n people are in the call (a mesh sends the picture to everybody: n-1 times). */
    data class Quality(val maxBitrate: Int, val scale: Double)

    fun qualityFor(n: Int): Quality = when {
        n <= 2 -> Quality(0, 1.0)
        n == 3 -> Quality(500_000, 1.0)
        n == 4 -> Quality(300_000, 1.5)
        else -> Quality(200_000, 2.0)
    }

    /** The sign in a chat document: the call that goes on in that group. */
    data class Pointer(val id: String, val host: String, val hostName: String, val video: Boolean, val ts: Long)

    fun pointerOf(g: Map<*, *>?): Pointer? {
        if (g == null) return null
        val id = (g["id"] as? String) ?: ""
        if (id.isEmpty() || id.length > 64 || id.contains('/')) return null
        return Pointer(
            id = id,
            host = (g["host"] as? String) ?: "",
            hostName = (g["hostName"] as? String) ?: "",
            video = g["kind"] == "video",
            ts = (g["ts"] as? Number)?.toLong() ?: 0L,
        )
    }

    /** One person who is in a call: the entry of the call document. */
    data class Person(val uid: String, val sid: String, val j: Long, val at: Long, val name: String, val muted: Boolean, val cam: Boolean)

    /** True when I (not the other) must make the offer: the one who came later offers (larger j, then larger uid). */
    fun iOffer(myJ: Long, myUid: String, pJ: Long, pUid: String): Boolean =
        myJ > pJ || (myJ == pJ && myUid > pUid)

    /**
     * Who is in a call, by the entries of its document. An entry counts while its "at" keeps changing (seen on MY clock, so a wrong clock somewhere
     * does not matter); a person who closed the page without leaving stops counting after LIVE_MS. Returns the people oldest first (j, then uid).
     */
    class Liveness(private val now: () -> Long) {
        private class Seen(var at: Long, var t: Long)

        private val seen = HashMap<String, Seen>()

        fun live(parts: Map<*, *>?): List<Person> {
            val t = now()
            val out = ArrayList<Person>()
            val keep = HashSet<String>()
            if (parts != null) {
                for ((k, v) in parts) {
                    val uid = k as? String ?: continue
                    val p = v as? Map<*, *> ?: continue
                    val sid = p["sid"] as? String ?: continue
                    if (sid.isEmpty()) continue
                    val at = (p["at"] as? Number)?.toLong() ?: 0L
                    val key = "$uid|$sid"
                    keep.add(key)
                    val s = seen[key]
                    if (s == null) {
                        seen[key] = Seen(at, if (Math.abs(t - at) > FIRST_SIGHT_MS) t - LIVE_MS - 1 else t)
                    } else if (s.at != at) {
                        seen[key] = Seen(at, t)
                    }
                    val cur = seen[key] ?: continue
                    if (t - cur.t >= LIVE_MS) continue
                    out.add(
                        Person(
                            uid = uid,
                            sid = sid,
                            j = (p["j"] as? Number)?.toLong() ?: 0L,
                            at = at,
                            name = ((p["name"] as? String) ?: "").take(60),
                            muted = p["m"] == true,
                            cam = p["cam"] == true,
                        ),
                    )
                }
            }
            val gone = ArrayList<String>()
            for (k in seen.keys) if (!keep.contains(k)) gone.add(k)
            for (k in gone) seen.remove(k)
            out.sortWith(compareBy<Person>({ it.j }, { it.uid }))
            return out
        }
    }
}

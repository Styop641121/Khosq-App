package am.khosq.app.push

import am.khosq.app.data.FinderCfg
import am.khosq.app.data.FinderHit
import am.khosq.app.data.FinderState
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the KHOSQ Cloudflare Worker about the Real Estate Finder (signed in with the person's own login). What goes out is the
 * search itself: the kind (sale or rent), the place, the price limits, the number of rooms, the language. No message, contact or
 * key is ever part of it. The Worker keeps the search, looks at list.am every few minutes and answers with what it found.
 */
object FinderClient {
    /** The Worker said no. [code] is the HTTP status (0 = the app has no Worker address). */
    class Failure(val code: Int) : Exception("finder $code")

    /** An answer: the state, and the text it was read from (kept on the phone as a copy to show at once next time). */
    class Reply(val state: FinderState, val raw: String)

    suspend fun state(): Reply = call("/finder/state", JSONObject())

    /** An extra look right now (the Worker allows one in 30 seconds). It does not send a notification: what it finds is shown in the app. */
    suspend fun check(): Reply = call("/finder/check", JSONObject())

    suspend fun save(cfg: FinderCfg): Reply = call("/finder/save", JSONObject().put("cfg", FinderJson.cfg(cfg)))

    /** Removes the search and everything the Worker kept about it. */
    suspend fun clear(): Reply = call("/finder/clear", JSONObject())

    private suspend fun call(path: String, body: JSONObject): Reply = withContext(Dispatchers.IO) {
        if (PushConfig.WORKER_URL.isEmpty()) throw Failure(0)
        val user = FirebaseAuth.getInstance().currentUser ?: throw Failure(401)
        val idToken = user.getIdToken(false).await().token ?: throw Failure(401)
        val c = URL(PushConfig.WORKER_URL + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            // the Worker may need a few seconds to ask list.am
            c.readTimeout = 25_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer $idToken")
            c.outputStream.use { out -> out.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) throw Failure(code)
            val raw = c.inputStream.bufferedReader().use { r -> r.readText() }
            Reply(FinderJson.state(JSONObject(raw)), raw)
        } finally {
            c.disconnect()
        }
    }
}

/** Reads and writes the Worker's texts. A field that is missing or odd becomes its plain default: a strange answer never stops the app. */
object FinderJson {
    fun cfg(c: FinderCfg): JSONObject = JSONObject()
        .put("on", c.on)
        .put("cat", c.cat)
        .put("region", c.region)
        .put("min", c.min)
        .put("max", c.max)
        .put("cur", c.cur)
        .put("rooms", c.rooms)
        .put("lang", c.lang)

    fun state(j: JSONObject): FinderState {
        val c = j.optJSONObject("cfg")
        val cfg = if (c == null) {
            null
        } else {
            FinderCfg(
                on = c.optBoolean("on", false),
                cat = if (c.optString("cat") == "rent") "rent" else "sale",
                region = c.optInt("region", 1),
                min = c.optLong("min", 0L),
                max = c.optLong("max", 0L),
                cur = if (c.optString("cur") == "AMD") "AMD" else "USD",
                rooms = c.optInt("rooms", 0),
                lang = c.optString("lang", "hy").ifEmpty { "hy" },
            )
        }
        val st = j.optJSONObject("st")
        return FinderState(
            cfg = cfg,
            hits = hits(j.optJSONArray("hits")),
            ok = st?.optBoolean("ok", false) ?: false,
            note = st?.optString("note", "") ?: "",
            run = st?.optLong("run", 0L) ?: 0L,
        )
    }

    private fun hits(a: JSONArray?): List<FinderHit> {
        val out = ArrayList<FinderHit>()
        if (a == null) return out
        for (i in 0 until minOf(a.length(), 30)) {
            val h = a.optJSONObject(i) ?: continue
            val id = h.optString("id", "")
            if (id.isEmpty()) continue
            val photos = ArrayList<String>()
            val ph = h.optJSONArray("ph")
            if (ph != null) {
                for (n in 0 until minOf(ph.length(), 6)) {
                    val p = ph.optString(n, "")
                    if (p.isNotEmpty()) photos.add(p)
                }
            }
            out.add(
                FinderHit(
                    id = id,
                    price = h.optString("p", ""),
                    title = h.optString("t", ""),
                    facts = h.optString("a", ""),
                    place = h.optString("l", ""),
                    photos = photos,
                    ts = h.optLong("ts", 0L),
                    sample = h.optInt("b", 0) == 1,
                ),
            )
        }
        // the same listing twice would break a list that needs a different key for every row
        return out.distinctBy { x -> x.id }
    }
}

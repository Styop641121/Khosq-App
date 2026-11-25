package am.khosq.app.push

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The Trust Shield link check, only when the person has allowed it. The NAMES of the sites in received links (never the whole
 * link, the message or the sender) go to the KHOSQ Cloudflare Worker (signed in with the person's own login), which asks
 * Cloudflare's malware-blocking DNS about each name and keeps nothing.
 */
object LinkCheckClient {
    /** The Worker said no. [code] is the HTTP status (0 = the app has no Worker address). */
    class Failure(val code: Int) : Exception("linkcheck $code")

    /** The most names the Worker takes at once. */
    const val BATCH = 5

    /** For every name asked: "blocked" (on Cloudflare's list of dangerous sites), "ok" (not on it) or "unknown" (no answer). */
    suspend fun check(hosts: List<String>): Map<String, String> = withContext(Dispatchers.IO) {
        if (hosts.isEmpty()) return@withContext emptyMap()
        if (hosts.size > BATCH) throw Failure(400)
        if (PushConfig.WORKER_URL.isEmpty()) throw Failure(0)
        val user = FirebaseAuth.getInstance().currentUser ?: throw Failure(401)
        val idToken = user.getIdToken(false).await().token ?: throw Failure(401)
        val c = URL(PushConfig.WORKER_URL + "/ai/linkcheck").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer $idToken")
            val body = JSONObject().put("hosts", JSONArray(hosts))
            c.outputStream.use { out -> out.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) throw Failure(code)
            val j = JSONObject(c.inputStream.bufferedReader().use { r -> r.readText() }).optJSONObject("results") ?: throw Failure(502)
            val out = HashMap<String, String>()
            for (h in hosts) out[h] = j.optString(h, "unknown")
            out
        } finally {
            c.disconnect()
        }
    }
}

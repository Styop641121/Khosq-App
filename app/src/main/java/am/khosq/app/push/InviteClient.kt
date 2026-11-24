package am.khosq.app.push

import am.khosq.app.tr
import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Group invite links. The links are made and checked by the Cloudflare Worker (it holds the signing secret), so nothing
 * secret is in the app. An admin asks for a link; whoever opens it asks to look at the group and then to join.
 */
object InviteClient {
    /** The Worker said no. [code] is the HTTP status. */
    class Failure(val code: Int) : Exception("invite $code")

    data class Info(val chatId: String, val title: String, val count: Int, val member: Boolean)

    private val TOKEN = Regex("^[A-Za-z0-9_.-]{20,120}$")

    /** The token inside a link the phone was asked to open (khosq://join/TOKEN or WORKER/j/TOKEN), or null. */
    fun tokenFrom(uri: Uri?): String? {
        if (uri == null) return null
        val seg = uri.pathSegments
        val t = when {
            uri.scheme == "khosq" && uri.host == "join" -> seg.firstOrNull()
            uri.scheme == "https" && uri.host == Uri.parse(PushConfig.WORKER_URL).host && seg.size == 2 && seg[0] == "j" -> seg[1]
            else -> null
        } ?: return null
        return if (TOKEN.matches(t)) t else null
    }

    private suspend fun post(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        if (PushConfig.WORKER_URL.isEmpty()) throw Failure(0)
        val user = FirebaseAuth.getInstance().currentUser ?: throw Failure(401)
        val idToken = user.getIdToken(false).await().token ?: throw Failure(401)
        val c = URL(PushConfig.WORKER_URL + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer $idToken")
            c.outputStream.use { out -> out.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) throw Failure(code)
            JSONObject(c.inputStream.bufferedReader().use { r -> r.readText() })
        } finally {
            c.disconnect()
        }
    }

    private fun info(j: JSONObject) = Info(j.getString("chatId"), j.optString("title"), j.optInt("count"), j.optBoolean("member"))

    /** The invite link of a group (admins only). With [reset] every older link stops working. */
    suspend fun link(chatId: String, reset: Boolean): String =
        post("/group/invite", JSONObject().put("chatId", chatId).put("reset", reset)).getString("link")

    /** Looks at the group behind a link without joining it. */
    suspend fun look(token: String): Info = info(post("/group/join", JSONObject().put("token", token).put("preview", true)))

    /** Joins the group behind a link. */
    suspend fun join(token: String): Info = info(post("/group/join", JSONObject().put("token", token)))

    /** What to tell the person when a call failed. [forLink] is true when an admin asked for a link, false when somebody opened one. */
    fun explain(e: Exception, forLink: Boolean): String = when ((e as? Failure)?.code) {
        403 -> if (forLink) tr("Հղում կարող է ստանալ միայն խմբի ադմինը") else tr("Հրավերի հղումը անվավեր է")
        404 -> tr("Խումբը չի գտնվել")
        410 -> tr("Այս հղումը չեղարկված է։ Խնդրիր նոր հղում։")
        409 -> tr("Խումբը լցված է (առավելագույնը 50 անդամ)")
        429 -> tr("Չափազանց շատ հարցումներ. փորձիր քիչ հետո")
        501 -> tr("Հրավերների ծառայությունը դեռ միացված չէ")
        401 -> tr("Մուտք չի կատարված")
        else -> tr("Կապ չկա կամ սերվերը չի պատասխանում")
    }
}

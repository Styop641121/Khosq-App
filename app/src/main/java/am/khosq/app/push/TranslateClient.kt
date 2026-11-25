package am.khosq.app.push

import am.khosq.app.data.TranslateResult
import am.khosq.app.tr
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Translation of one text, on the person's request. The text goes to the KHOSQ Cloudflare Worker (signed in with the
 * person's own login, so nobody else can use it) and from there to Cloudflare Workers AI. The Worker keeps nothing.
 */
object TranslateClient {
    /** The Worker said no. [code] is the HTTP status (0 = the app has no Worker address). */
    class Failure(val code: Int) : Exception("translate $code")

    suspend fun translate(text: String, to: String): TranslateResult = withContext(Dispatchers.IO) {
        if (PushConfig.WORKER_URL.isEmpty()) throw Failure(0)
        val user = FirebaseAuth.getInstance().currentUser ?: throw Failure(401)
        val idToken = user.getIdToken(false).await().token ?: throw Failure(401)
        val c = URL(PushConfig.WORKER_URL + "/ai/translate").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = 40_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer $idToken")
            val body = JSONObject().put("text", text.trim()).put("to", to)
            c.outputStream.use { out -> out.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code !in 200..299) throw Failure(code)
            val j = JSONObject(c.inputStream.bufferedReader().use { r -> r.readText() })
            val out = j.optString("text")
            if (out.isEmpty()) throw Failure(502)
            TranslateResult(text = out, from = j.optString("from"), to = j.optString("to", to), same = j.optBoolean("same"), via = j.optString("via"))
        } finally {
            c.disconnect()
        }
    }

    /** What to tell the person when the translation failed. */
    fun explain(e: Exception): String = when ((e as? Failure)?.code) {
        401 -> tr("Մուտք չի կատարված")
        413 -> tr("Տեքստը չափազանց երկար է թարգմանելու համար")
        429 -> tr("Չափազանց շատ հարցումներ. փորձիր քիչ հետո")
        501, 0 -> tr("Թարգմանությունը սերվերում դեռ միացված չէ")
        502 -> tr("Թարգմանիչը հիմա չի պատասխանում. փորձիր քիչ հետո")
        503 -> tr("Այսօրվա անվճար սահմանաչափը սպառվել է. փորձիր վաղը")
        else -> tr("Կապ չկա կամ սերվերը չի պատասխանում")
    }
}

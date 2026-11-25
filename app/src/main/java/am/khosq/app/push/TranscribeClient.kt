package am.khosq.app.push

import am.khosq.app.data.TranscribeResult
import am.khosq.app.tr
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Transcription of one voice note, on the person's request. The audio of that one note goes to the KHOSQ Cloudflare
 * Worker (signed in with the person's own login, so nobody else can use it) and from there to Cloudflare Workers AI.
 * The Worker keeps nothing.
 */
object TranscribeClient {
    /** The Worker said no. [code] is the HTTP status (0 = the app has no Worker address). [detail] is the short reason the Worker gave, if any. */
    class Failure(val code: Int, val detail: String = "") : Exception("transcribe $code")

    suspend fun transcribe(audio: ByteArray, lang: String): TranscribeResult = withContext(Dispatchers.IO) {
        if (PushConfig.WORKER_URL.isEmpty()) throw Failure(0)
        val user = FirebaseAuth.getInstance().currentUser ?: throw Failure(401)
        val idToken = user.getIdToken(false).await().token ?: throw Failure(401)
        val c = URL(PushConfig.WORKER_URL + "/ai/transcribe?lang=" + lang).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10_000
            c.readTimeout = 70_000
            c.doOutput = true
            c.setFixedLengthStreamingMode(audio.size)
            c.setRequestProperty("Content-Type", "audio/mp4")
            c.setRequestProperty("Authorization", "Bearer $idToken")
            c.outputStream.use { out -> out.write(audio) }
            val code = c.responseCode
            if (code !in 200..299) {
                val why = try {
                    c.errorStream?.bufferedReader()?.use { r -> r.readText() }.orEmpty().replace(Regex("\\s+"), " ").trim().take(200)
                } catch (e: Exception) {
                    ""
                }
                throw Failure(code, why)
            }
            val j = JSONObject(c.inputStream.bufferedReader().use { r -> r.readText() })
            val text = j.optString("text").trim()
            TranscribeResult(text = text, lang = j.optString("from", lang), via = j.optString("via"), empty = j.optBoolean("empty") || text.isEmpty())
        } finally {
            c.disconnect()
        }
    }

    /** What to tell the person when the transcription failed. */
    fun explain(e: Exception): String = when ((e as? Failure)?.code) {
        401 -> tr("Մուտք չի կատարված")
        400 -> tr("Ձայնագրությունը չի ճանաչվել")
        413 -> tr("Ձայնային նամակը չափազանց մեծ է տառադարձելու համար")
        429 -> tr("Չափազանց շատ հարցումներ. փորձիր քիչ հետո")
        501, 0 -> tr("Տառադարձումը սերվերում դեռ միացված չէ")
        502 -> tr("Տառադարձիչը չկարողացավ մշակել այս ձայնագրությունը")
        503 -> tr("Այսօրվա անվճար սահմանաչափը սպառվել է. փորձիր վաղը")
        else -> tr("Կապ չկա կամ սերվերը չի պատասխանում")
    }

    /** The Worker's own short reason for a failure (shown in small print so it can be reported), or "". */
    fun detail(e: Exception): String = (e as? Failure)?.detail.orEmpty()
}

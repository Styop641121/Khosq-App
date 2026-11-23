package am.khosq.app.push

import com.google.firebase.auth.FirebaseAuth
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Asks the Cloudflare Worker to send an FCM push to the other person in a chat. */
object PushClient {
    fun notify(chatId: String, type: String, title: String, body: String) {
        if (PushConfig.WORKER_URL.isEmpty()) return
        val user = FirebaseAuth.getInstance().currentUser ?: return
        user.getIdToken(false).addOnSuccessListener { res ->
            val token = res.token ?: return@addOnSuccessListener
            Thread {
                try {
                    val json = JSONObject()
                        .put("chatId", chatId)
                        .put("type", type)
                        .put("title", title.take(60))
                        .put("body", body.take(120))
                    val c = URL(PushConfig.WORKER_URL).openConnection() as HttpURLConnection
                    c.requestMethod = "POST"
                    c.connectTimeout = 8000
                    c.readTimeout = 8000
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.setRequestProperty("Authorization", "Bearer $token")
                    c.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
                    c.responseCode
                    c.disconnect()
                } catch (e: Exception) {
                    // push is best effort; the message itself is already delivered through Firestore
                }
            }.start()
        }
    }
}

package am.khosq.app.push

import am.khosq.app.crypto.E2E
import am.khosq.app.data.LinkLogic
import am.khosq.app.data.LinkRequest
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** How linking a browser went. */
enum class LinkResult { OK, TAKEN, OFFLINE, SIGNED_OUT, SLOW, OFF, REJECTED, FAILED }

/**
 * Hands the person's identity to the web version (see [LinkLogic]). The identity is sealed here, on the phone, for the one-time key of the
 * page that showed the QR code; the Worker only carries the sealed packet for three minutes and cannot open it.
 */
object LinkClient {
    suspend fun send(req: LinkRequest): LinkResult = withContext(Dispatchers.IO) {
        if (PushConfig.WORKER_URL.isEmpty()) return@withContext LinkResult.OFF
        val user = FirebaseAuth.getInstance().currentUser ?: return@withContext LinkResult.SIGNED_OUT
        val idToken = try {
            user.getIdToken(false).await().token
        } catch (e: Exception) {
            null
        } ?: return@withContext LinkResult.SIGNED_OUT
        // my private key (a copy: it is wiped as soon as it has been sealed)
        val (priv, pub) = E2E.exportIdentity()
        val packet = try {
            LinkLogic.seal(req, user.uid, pub, priv)
        } finally {
            priv.fill(0)
        } ?: return@withContext LinkResult.FAILED
        val body = "{\"sid\":\"${packet.sid}\",\"eph\":\"${packet.eph}\",\"iv\":\"${packet.iv}\",\"ct\":\"${packet.ct}\",\"rh\":\"${packet.rh}\"}"
        try {
            val c = URL(PushConfig.WORKER_URL + "/link/put").openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("Authorization", "Bearer $idToken")
                c.outputStream.use { out -> out.write(body.toByteArray(Charsets.UTF_8)) }
                when (c.responseCode) {
                    in 200..299 -> LinkResult.OK
                    409 -> LinkResult.TAKEN
                    401 -> LinkResult.SIGNED_OUT
                    429 -> LinkResult.SLOW
                    501 -> LinkResult.OFF
                    400 -> LinkResult.REJECTED
                    else -> LinkResult.FAILED
                }
            } finally {
                c.disconnect()
            }
        } catch (e: IOException) {
            LinkResult.OFFLINE
        }
    }
}

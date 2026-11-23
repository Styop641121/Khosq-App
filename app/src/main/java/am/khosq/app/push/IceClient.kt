package am.khosq.app.push

import com.google.firebase.auth.FirebaseAuth
import org.json.JSONObject
import org.webrtc.PeerConnection
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gets fresh TURN/STUN settings for calls from the Cloudflare Worker (which asks Cloudflare's TURN service).
 * If anything fails the call code falls back to the built-in servers, so calls never depend on this.
 */
object IceClient {
    private const val FRESH_MS = 20L * 3600L * 1000L
    private const val REFRESH_MS = 6L * 3600L * 1000L

    @Volatile private var servers: List<PeerConnection.IceServer> = emptyList()
    @Volatile private var fetchedAt = 0L
    @Volatile private var busy = false

    /** Servers fetched earlier and still valid, or an empty list. */
    fun fresh(): List<PeerConnection.IceServer> =
        if (servers.isNotEmpty() && System.currentTimeMillis() - fetchedAt < FRESH_MS) servers else emptyList()

    /** Asks for new servers in the background (at most every few hours). */
    fun refresh() {
        if (PushConfig.WORKER_URL.isEmpty() || busy) return
        if (servers.isNotEmpty() && System.currentTimeMillis() - fetchedAt < REFRESH_MS) return
        val user = FirebaseAuth.getInstance().currentUser ?: return
        busy = true
        user.getIdToken(false)
            .addOnSuccessListener { res ->
                val token = res.token
                if (token == null) {
                    busy = false
                    return@addOnSuccessListener
                }
                Thread {
                    try {
                        val c = URL(PushConfig.WORKER_URL).openConnection() as HttpURLConnection
                        c.requestMethod = "POST"
                        c.connectTimeout = 8000
                        c.readTimeout = 8000
                        c.doOutput = true
                        c.setRequestProperty("Content-Type", "application/json")
                        c.setRequestProperty("Authorization", "Bearer $token")
                        c.outputStream.use { o -> o.write("{\"type\":\"ice\"}".toByteArray(Charsets.UTF_8)) }
                        if (c.responseCode == 200) {
                            val text = c.inputStream.bufferedReader().use { r -> r.readText() }
                            val list = parse(text)
                            if (list.isNotEmpty()) {
                                servers = list
                                fetchedAt = System.currentTimeMillis()
                            }
                        }
                        c.disconnect()
                    } catch (e: Exception) {
                        // keep using the built-in servers
                    } finally {
                        busy = false
                    }
                }.start()
            }
            .addOnFailureListener { busy = false }
    }

    private fun parse(text: String): List<PeerConnection.IceServer> {
        val arr = JSONObject(text).optJSONArray("iceServers") ?: return emptyList()
        val out = ArrayList<PeerConnection.IceServer>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val urls = ArrayList<String>()
            val u = o.opt("urls")
            if (u is String) urls.add(u)
            if (u is org.json.JSONArray) {
                for (k in 0 until u.length()) urls.add(u.getString(k))
            }
            // port 53 is blocked on many networks and only slows things down
            val usable = urls.filter { x -> !x.contains(":53") }
            if (usable.isEmpty()) continue
            val b = PeerConnection.IceServer.builder(usable)
            val name = o.optString("username", "")
            val pass = o.optString("credential", "")
            if (name.isNotEmpty()) b.setUsername(name)
            if (pass.isNotEmpty()) b.setPassword(pass)
            out.add(b.createIceServer())
        }
        return out
    }
}

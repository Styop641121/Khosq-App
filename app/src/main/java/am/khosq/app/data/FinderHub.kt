package am.khosq.app.data

import am.khosq.app.Prefs
import am.khosq.app.push.FinderClient
import am.khosq.app.push.FinderJson
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * What the screens and the chat list know about the Real Estate Finder. A copy of the last answer of the Worker is kept on the phone,
 * so the chat list can show the bot at once; the Worker is asked when the screen opens and once each time the app starts.
 * Nothing in here can stop the app: every call is wrapped, and a failure only becomes a number in [failure].
 */
object FinderHub {
    val state = MutableStateFlow<FinderState?>(null)
    val busy = MutableStateFlow(false)

    /** 0 = the last call worked; otherwise the HTTP status that failed, or -1 when there was no network. */
    val failure = MutableStateFlow(0)

    /** The time of the newest listing the person has already looked at. */
    val seen = MutableStateFlow(0L)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loadedFor = ""
    private var askedFor = ""

    private fun uid(): String = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    private fun copyKey(u: String): String = "finder_copy_$u"

    private fun seenKey(u: String): String = "finder_seen_$u"

    /** Puts the saved copy of the last answer in [state] (once for each signed-in person). */
    fun load() {
        val u = uid()
        if (u.isEmpty() || u == loadedFor) return
        loadedFor = u
        seen.value = Prefs.getLong(seenKey(u), 0L)
        val raw = Prefs.getString(copyKey(u), "")
        state.value = null
        if (raw.isNotEmpty()) {
            try {
                state.value = FinderJson.state(JSONObject(raw))
            } catch (e: Exception) {
                // a damaged copy is simply not used
            }
        }
    }

    /** Called when the chat list shows: loads the copy and, once for each start of the app, asks the Worker if a search exists. */
    fun start() {
        load()
        val u = uid()
        if (u.isEmpty() || u == askedFor) return
        askedFor = u
        if (state.value?.cfg != null) refresh()
    }

    fun refresh() = launchCall { FinderClient.state() }

    fun check() = launchCall { FinderClient.check() }

    fun save(cfg: FinderCfg) = launchCall { FinderClient.save(cfg) }

    fun clear() = launchCall { FinderClient.clear() }

    /** The person has looked at the listings: the badge goes away. */
    fun markSeen() {
        val u = uid()
        val newest = state.value?.hits?.maxOfOrNull { h -> h.ts } ?: 0L
        if (u.isNotEmpty() && newest > seen.value) {
            seen.value = newest
            Prefs.putLong(seenKey(u), newest)
        }
    }

    fun seenTs(): Long = seen.value

    private fun launchCall(call: suspend () -> FinderClient.Reply) {
        if (!busy.compareAndSet(false, true)) return
        val u = uid()
        scope.launch {
            try {
                val r = call()
                // the answer belongs to the person who asked: a sign-out in between must not hand it to someone else
                if (u == uid()) {
                    state.value = r.state
                    failure.value = 0
                    Prefs.putString(copyKey(u), r.raw)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: FinderClient.Failure) {
                failure.value = if (e.code > 0) e.code else -1
            } catch (e: Exception) {
                failure.value = -1
            } finally {
                busy.value = false
            }
        }
    }
}

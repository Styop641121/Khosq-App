package am.khosq.app

import kotlinx.coroutines.flow.MutableStateFlow

/** Small process-wide switches shared by the activity and the background service. */
object AppState {
    @Volatile
    var inForeground: Boolean = false

    /** A chat the user asked to open from a notification. */
    val pendingChat = MutableStateFlow<String?>(null)

    /** True while the activity is shown as the small floating window (picture-in-picture). */
    val inPip = MutableStateFlow(false)

    // how many screens of the app are on show right now (the app and the call screen are two screens)
    private var shown = 0

    fun started() {
        shown += 1
    }

    /** Returns true when no screen of the app is on show any more. */
    fun stopped(): Boolean {
        shown = if (shown > 0) shown - 1 else 0
        return shown == 0
    }
}

package am.khosq.app

import kotlinx.coroutines.flow.MutableStateFlow

/** Small process-wide switches shared by the activity and the background service. */
object AppState {
    @Volatile
    var inForeground: Boolean = false

    /** A chat the user asked to open from a notification. */
    val pendingChat = MutableStateFlow<String?>(null)
}

package am.khosq.app

import kotlinx.coroutines.flow.MutableStateFlow

/** A message to show when its chat opens (from a search result, a task or a star). [at] makes an old request expire. */
data class Jump(val chatId: String, val msgId: String, val at: Long = System.currentTimeMillis())

/** Small process-wide switches shared by the activity and the background service. */
object AppState {
    @Volatile
    var inForeground: Boolean = false

    /** A chat the user asked to open from a notification. */
    val pendingChat = MutableStateFlow<String?>(null)

    /** The token of a group invite link the phone was asked to open; the app asks before joining. */
    val pendingInvite = MutableStateFlow<String?>(null)

    /** A tap on a reminder asked to show the task list. */
    val openTasks = MutableStateFlow(false)

    /** The message the chat that is about to open should scroll to. The chat takes it (sets it back to null) and flashes the message. */
    val jump = MutableStateFlow<Jump?>(null)

    fun jumpTo(chatId: String, msgId: String) {
        if (msgId.isNotEmpty()) jump.value = Jump(chatId, msgId)
    }

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

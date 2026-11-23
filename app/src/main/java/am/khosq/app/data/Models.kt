package am.khosq.app.data

data class Profile(val uid: String, val handle: String, val name: String)

data class Chat(
    val id: String,
    val peerUid: String,
    val peerName: String,
    val peerHandle: String,
    val lastText: String,
    val lastTs: Long,
    val lastFrom: String,
    val unread: Long,
    val peerRead: Long,
    val peerTyping: Long = 0L,
    val status: String = "active",
    val requester: String = "",
    val blockedBy: List<String> = emptyList(),
    val isGroup: Boolean = false,
    val members: List<String> = emptyList(),
    val memberNames: Map<String, String> = emptyMap(),
    val memberHandles: Map<String, String> = emptyMap(),
    val admins: List<String> = emptyList(),
)

data class Message(
    val id: String,
    val from: String,
    val text: String,
    val ts: Long,
    val pending: Boolean,
    val kind: String = "text",
    val data: ByteArray? = null,
    val dur: Int = 0,
    val reply: String = "",
    val replyName: String = "",
    val deleted: Boolean = false,
    val reactions: Map<String, String> = emptyMap(),
    val fwd: Boolean = false,
)

/** One line of the call history, seen from my side. dur = -1 means the call never connected. */
data class CallRecord(
    val id: String,
    val peerUid: String,
    val peerName: String,
    val outgoing: Boolean,
    val video: Boolean,
    val ts: Long,
    val dur: Int,
    val state: String,
) {
    val answered: Boolean get() = dur >= 0 || state == "accepted"
    val missed: Boolean get() = !outgoing && !answered && state != "declined"
}

data class Story(
    val id: String,
    val owner: String,
    val ownerName: String,
    val kind: String,
    val text: String,
    val bg: Int,
    val data: ByteArray?,
    val ts: Long,
    val exp: Long,
    val views: List<String>,
)

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
)

data class Message(
    val id: String,
    val from: String,
    val text: String,
    val ts: Long,
    val pending: Boolean,
)

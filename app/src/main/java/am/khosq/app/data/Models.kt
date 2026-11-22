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
    val kind: String = "text",
    val data: ByteArray? = null,
    val dur: Int = 0,
)

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

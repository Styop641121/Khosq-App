package am.khosq.app.data

/** pv = version of my photo (0 = no photo). about/hideSeen are my own settings. */
data class Profile(
    val uid: String,
    val handle: String,
    val name: String,
    val about: String = "",
    val hideSeen: Boolean = false,
    val pv: Long = 0L,
)

/** What I know about one other person: last seen (0 = hidden or never), the "about" line, whether they hide it. */
data class UserInfo(val seen: Long = 0L, val about: String = "", val hideSeen: Boolean = false)

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
    val lastKind: String = "",
    val pv: Map<String, Long> = emptyMap(),
    val dlv: Map<String, Long> = emptyMap(),
    val peerDelivered: Long = 0L,
    val saved: Boolean = false,
    val desc: String = "",
    val gpv: Long = 0L,
    /** True while [lastText] is still sealed (end-to-end); the repository opens it before the screens see it. */
    val lastEnc: Boolean = false,
    /** For a group: the key of the last message, wrapped for every member. Only needed to open [lastText]. */
    val lastW: Map<String, String> = emptyMap(),
    /** Id of the message pinned at the top of this chat ("" = none). Only the id is stored: the text stays sealed in the message. */
    val pinId: String = "",
)

/**
 * My private choices, kept in my own space: chats pinned to the top, chats whose notifications are off,
 * archived chats and chats I marked as unread.
 */
data class UserPrefs(
    val pins: Set<String> = emptySet(),
    val muted: Set<String> = emptySet(),
    val archived: Set<String> = emptySet(),
    val marked: Set<String> = emptySet(),
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
    val code: String = "",
    val arg: String = "",
    val vid: Boolean = false,
    val edited: Boolean = false,
    val replyFrom: String = "",
    val wave: String = "",
    /** Was sent end-to-end encrypted. The text, the quote and the bytes here are already opened. */
    val enc: Boolean = false,
    /** For a group: the message key wrapped for every member. The repository uses it to open the message and then drops it. */
    val wraps: Map<String, String> = emptyMap(),
)

/** A starred message: a small private copy kept in my own space, so the list never has to open the chats. */
data class Star(
    val key: String,
    val chatId: String,
    val chatName: String,
    val fromName: String,
    val mine: Boolean,
    val kind: String,
    val text: String,
    val ts: Long,
    val at: Long,
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

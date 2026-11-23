package am.khosq.app.ui

import am.khosq.app.data.CallRecord
import am.khosq.app.data.Chat
import am.khosq.app.data.Message
import am.khosq.app.data.Profile
import am.khosq.app.data.Repository
import am.khosq.app.data.Story
import am.khosq.app.AppState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

sealed class Boot {
    data object Loading : Boot()
    data object NeedSetup : Boot()
    data class Ready(val me: Profile) : Boot()
    data class Failed(val text: String) : Boot()
}

class AppViewModel : ViewModel() {
    private val repo = Repository()
    private var uid: String = ""

    private val _boot = MutableStateFlow<Boot>(Boot.Loading)
    val boot: StateFlow<Boot> = _boot.asStateFlow()

    private val _chats = MutableStateFlow<List<Chat>>(emptyList())
    val chats: StateFlow<List<Chat>> = _chats.asStateFlow()

    private val _calls = MutableStateFlow<List<CallRecord>>(emptyList())
    val calls: StateFlow<List<CallRecord>> = _calls.asStateFlow()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _stories = MutableStateFlow<List<Story>>(emptyList())
    val stories: StateFlow<List<Story>> = _stories.asStateFlow()

    private val _replyTo = MutableStateFlow<Message?>(null)
    val replyTo: StateFlow<Message?> = _replyTo.asStateFlow()

    private val _peerSeen = MutableStateFlow(0L)
    val peerSeen: StateFlow<Long> = _peerSeen.asStateFlow()

    private var seenJob: Job? = null
    private var beatJob: Job? = null
    private var lastTypingSent = 0L

    private var chatsJob: Job? = null
    private var callsJob: Job? = null
    private var storiesJob: Job? = null
    private var messagesJob: Job? = null

    init {
        start()
    }

    fun start() {
        _boot.value = Boot.Loading
        viewModelScope.launch {
            try {
                uid = repo.ensureSignedIn()
                val p = repo.loadProfile(uid)
                if (p == null) _boot.value = Boot.NeedSetup else ready(p)
            } catch (e: Exception) {
                _boot.value = Boot.Failed(describe(e))
            }
        }
    }

    private fun ready(p: Profile) {
        _boot.value = Boot.Ready(p)
        am.khosq.app.call.CallManager.listen(p.uid)
        beatJob?.cancel()
        beatJob = viewModelScope.launch {
            while (true) {
                if (AppState.inForeground) repo.touchSeen(p.uid)
                delay(45_000L)
            }
        }
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token
            .addOnSuccessListener { t -> am.khosq.app.push.KhosqMessagingService.saveToken(t) }
        chatsJob?.cancel()
        chatsJob = viewModelScope.launch {
            repo.chats(p.uid)
                .resilient("Զրույցներ")
                .collect { _chats.value = it }
        }
        callsJob?.cancel()
        callsJob = viewModelScope.launch {
            repo.callHistory(p.uid)
                .resilient("Զանգեր")
                .collect { _calls.value = it }
        }
        storiesJob?.cancel()
        storiesJob = viewModelScope.launch {
            combine(
                repo.storiesForMe(p.uid).onStart { emit(emptyList<Story>()) },
                repo.myStories(p.uid).onStart { emit(emptyList<Story>()) },
            ) { a, b -> (a + b).distinctBy { st -> st.id } }
                .resilient("Սթորիներ")
                .collect { list ->
                    _stories.value = list
                    val now = System.currentTimeMillis()
                    for (st in list) {
                        if (st.owner == p.uid && st.exp < now) repo.deleteStory(st.id)
                    }
                }
        }
    }

    /**
     * Firestore ends a listener for good after one error (for example while new rules are being published).
     * This starts it again after a short wait, and only shows a notice if it keeps failing.
     */
    private fun <T> Flow<T>.resilient(what: String): Flow<T> =
        retryWhen { cause, attempt ->
            if (attempt >= 2L) _notice.value = what + ": " + describe(cause)
            delay(minOf(30_000L, 3_000L * (attempt + 1L)))
            true
        }.onEach {
            val n = _notice.value
            if (n != null && n.startsWith(what + ": ")) _notice.value = null
        }

    fun register(name: String, handle: String, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                repo.register(uid, handle, name)
                ready(Profile(uid, handle, name))
            } catch (e: Exception) {
                onError(if (isTaken(e)) "Այս ID-ն արդեն զբաղված է" else describe(e))
            }
        }
    }

    /** Calls back with (chatId, null) on success or (null, errorText). */
    fun addContact(handleRaw: String, onDone: (String?, String?) -> Unit) {
        val me = (_boot.value as? Boot.Ready)?.me
        val handle = handleRaw.trim().lowercase().removePrefix("@")
        if (me == null || handle.isEmpty()) {
            onDone(null, "Գրիր ընկերոջդ ID-ն")
            return
        }
        viewModelScope.launch {
            try {
                val peer = repo.findByHandle(handle)
                if (peer == null) {
                    onDone(null, "Այդպիսի ID չի գտնվել")
                } else if (peer.uid == me.uid) {
                    onDone(null, "Սա քո սեփական ID-ն է")
                } else {
                    onDone(repo.startChat(me, peer), null)
                }
            } catch (e: Exception) {
                onDone(null, describe(e))
            }
        }
    }

    fun openChat(id: String) {
        messagesJob?.cancel()
        _messages.value = emptyList()
        _replyTo.value = null
        _peerSeen.value = 0L
        seenJob?.cancel()
        val peer = if (id.contains("_")) id.split("_").firstOrNull { p -> p != uid } else null
        if (peer != null) {
            seenJob = viewModelScope.launch {
                repo.userSeen(peer).catch { }.collect { t -> _peerSeen.value = t }
            }
        }
        repo.markRead(id, uid)
        messagesJob = viewModelScope.launch {
            repo.messages(id)
                .resilient("Նամակներ")
                .collect { list ->
                    _messages.value = list
                    val last = list.lastOrNull()
                    if (last != null && last.from != uid && !last.pending) repo.markRead(id, uid)
                }
        }
    }

    fun closeChat() {
        seenJob?.cancel()
        _replyTo.value = null
        messagesJob?.cancel()
        _messages.value = emptyList()
    }

    /** Everyone who should get the "unread" counter: the other person, or all other group members. */
    private fun recipients(chatId: String, fallback: String): List<String> {
        val c = _chats.value.firstOrNull { x -> x.id == chatId }
        return if (c != null && c.isGroup) c.members.filter { m -> m != uid } else listOf(c?.peerUid ?: fallback)
    }

    private fun groupTitle(chatId: String): String {
        val c = _chats.value.firstOrNull { x -> x.id == chatId }
        return if (c != null && c.isGroup) c.peerName else ""
    }

    fun send(chatId: String, peerUid: String, text: String) {
        val me = (_boot.value as? Boot.Ready)?.me ?: return
        if (text.isBlank()) return
        val r = _replyTo.value
        val chat = _chats.value.firstOrNull { c -> c.id == chatId }
        val rn = when {
            r == null -> ""
            r.from == me.uid -> "Դու"
            chat != null && chat.isGroup -> chat.memberNames[r.from] ?: ""
            else -> chat?.peerName ?: ""
        }
        repo.send(chatId, me, recipients(chatId, peerUid), text, r, rn, groupTitle(chatId))
        _replyTo.value = null
        lastTypingSent = 0L
        repo.setTyping(chatId, me.uid, false)
    }

    fun acceptChat(chatId: String) {
        repo.acceptChat(chatId)
    }

    fun setBlocked(chatId: String, on: Boolean) {
        if (uid.isNotEmpty()) repo.setBlocked(chatId, uid, on)
    }

    fun typing(chatId: String) {
        val now = System.currentTimeMillis()
        if (now - lastTypingSent < 3000L || uid.isEmpty()) return
        lastTypingSent = now
        repo.setTyping(chatId, uid, true)
    }

    fun setReply(m: Message?) {
        _replyTo.value = m
    }

    fun react(chatId: String, m: Message, emoji: String) {
        val current = m.reactions[uid]
        repo.react(chatId, m.id, uid, if (current == emoji) null else emoji)
    }

    fun deleteMessage(chatId: String, m: Message) {
        if (m.from == uid) repo.deleteForAll(chatId, m.id)
    }

    fun sendImage(chatId: String, peerUid: String, bytes: ByteArray) {
        val me = (_boot.value as? Boot.Ready)?.me ?: return
        repo.sendMedia(chatId, me, recipients(chatId, peerUid), "img", bytes, 0, groupTitle(chatId))
    }

    fun sendVoice(chatId: String, peerUid: String, bytes: ByteArray, seconds: Int) {
        val me = (_boot.value as? Boot.Ready)?.me ?: return
        repo.sendMedia(chatId, me, recipients(chatId, peerUid), "voice", bytes, seconds, groupTitle(chatId))
    }

    fun postStory(kind: String, text: String, bg: Int, bytes: ByteArray?) {
        val me = (_boot.value as? Boot.Ready)?.me ?: return
        val audience = _chats.value.filter { c -> !c.isGroup && c.status == "active" && c.blockedBy.isEmpty() }.map { c -> c.peerUid }
        repo.postStory(me, audience, kind, text, bg, bytes)
    }

    fun viewStory(id: String) {
        val me = (_boot.value as? Boot.Ready)?.me ?: return
        repo.markStoryViewed(id, me.uid)
    }

    fun deleteStory(id: String) {
        repo.deleteStory(id)
    }

    fun replyToStory(story: Story, text: String) {
        val me = (_boot.value as? Boot.Ready)?.me ?: return
        val chat = _chats.value.firstOrNull { c -> !c.isGroup && c.peerUid == story.owner } ?: return
        if (text.isBlank()) return
        repo.send(chat.id, me, listOf(story.owner), "📖 Սթորի՝ " + text.trim())
    }

    /** Chats a message can be forwarded to: accepted, not blocked one-to-one chats and my groups. */
    fun shareTargets(): List<Chat> =
        _chats.value.filter { c -> c.isGroup || (c.status == "active" && c.blockedBy.isEmpty()) }

    fun forward(m: Message, targets: List<Chat>) {
        val me = (_boot.value as? Boot.Ready)?.me ?: return
        for (c in targets) {
            val others = if (c.isGroup) c.members.filter { x -> x != me.uid } else listOf(c.peerUid)
            repo.forward(m, c, me, others)
        }
    }

    /** Contacts I can put in a group: accepted, not blocked, one-to-one chats. */
    fun contacts(): List<Chat> = _chats.value.filter { c -> !c.isGroup && c.status == "active" && c.blockedBy.isEmpty() }

    fun createGroup(title: String, picks: List<Chat>, onDone: (String?, String?) -> Unit) {
        val me = (_boot.value as? Boot.Ready)?.me ?: return
        viewModelScope.launch {
            try {
                onDone(repo.createGroup(me, title.trim(), picks), null)
            } catch (e: Exception) {
                onDone(null, describe(e))
            }
        }
    }

    fun addMembers(chatId: String, picks: List<Chat>) {
        val me = (_boot.value as? Boot.Ready)?.me ?: return
        repo.addMembers(chatId, me, picks)
    }

    fun removeMember(chatId: String, uid: String, name: String) {
        val me = (_boot.value as? Boot.Ready)?.me ?: return
        repo.removeMember(chatId, me, uid, name)
    }

    fun renameGroup(chatId: String, title: String) {
        val me = (_boot.value as? Boot.Ready)?.me ?: return
        if (title.isNotBlank()) repo.renameGroup(chatId, me, title.trim())
    }

    fun leaveGroup(chat: Chat) {
        val me = (_boot.value as? Boot.Ready)?.me ?: return
        repo.leaveGroup(chat, me)
    }

    fun clearNotice() {
        _notice.value = null
    }

    private fun isTaken(e: Throwable?): Boolean {
        var t = e
        while (t != null) {
            if (t is am.khosq.app.data.HandleTaken || t.message?.contains("handle taken") == true) return true
            t = t.cause
        }
        return false
    }

    private fun describe(e: Throwable): String {
        val m = e.message ?: e.toString()
        return when {
            m.contains("PERMISSION_DENIED") ->
                "Firestore-ի անվտանգության կանոնները դեռ չեն տեղադրված (Rules)։"
            m.contains("restricted", ignoreCase = true) || m.contains("OPERATION_NOT_ALLOWED") ->
                "Firebase-ում Anonymous մուտքը միացված չէ։"
            m.contains("network", ignoreCase = true) || m.contains("UNAVAILABLE") ->
                "Ինտերնետ կապ չկա։"
            else -> "Սխալ՝ $m"
        }
    }
}

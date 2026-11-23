package am.khosq.app.ui

import am.khosq.app.data.Chat
import am.khosq.app.data.Message
import am.khosq.app.data.Profile
import am.khosq.app.data.Repository
import am.khosq.app.data.Story
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
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

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _stories = MutableStateFlow<List<Story>>(emptyList())
    val stories: StateFlow<List<Story>> = _stories.asStateFlow()

    private var chatsJob: Job? = null
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
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token
            .addOnSuccessListener { t -> am.khosq.app.push.KhosqMessagingService.saveToken(t) }
        chatsJob?.cancel()
        chatsJob = viewModelScope.launch {
            repo.chats(p.uid)
                .catch { _notice.value = describe(it) }
                .collect { _chats.value = it }
        }
        storiesJob?.cancel()
        storiesJob = viewModelScope.launch {
            combine(
                repo.storiesForMe(p.uid).onStart { emit(emptyList<Story>()) },
                repo.myStories(p.uid).onStart { emit(emptyList<Story>()) },
            ) { a, b -> (a + b).distinctBy { st -> st.id } }
                .catch { _notice.value = describe(it) }
                .collect { list ->
                    _stories.value = list
                    val now = System.currentTimeMillis()
                    for (st in list) {
                        if (st.owner == p.uid && st.exp < now) repo.deleteStory(st.id)
                    }
                }
        }
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
        repo.markRead(id, uid)
        messagesJob = viewModelScope.launch {
            repo.messages(id)
                .catch { _notice.value = describe(it) }
                .collect { list ->
                    _messages.value = list
                    val last = list.lastOrNull()
                    if (last != null && last.from != uid && !last.pending) repo.markRead(id, uid)
                }
        }
    }

    fun closeChat() {
        messagesJob?.cancel()
        _messages.value = emptyList()
    }

    fun send(chatId: String, peerUid: String, text: String) {
        val me = (_boot.value as? Boot.Ready)?.me ?: return
        if (text.isBlank()) return
        repo.send(chatId, me, peerUid, text)
    }

    fun sendImage(chatId: String, peerUid: String, bytes: ByteArray) {
        val me = (_boot.value as? Boot.Ready)?.me ?: return
        repo.sendMedia(chatId, me, peerUid, "img", bytes, 0)
    }

    fun sendVoice(chatId: String, peerUid: String, bytes: ByteArray, seconds: Int) {
        val me = (_boot.value as? Boot.Ready)?.me ?: return
        repo.sendMedia(chatId, me, peerUid, "voice", bytes, seconds)
    }

    fun postStory(kind: String, text: String, bg: Int, bytes: ByteArray?) {
        val me = (_boot.value as? Boot.Ready)?.me ?: return
        val audience = _chats.value.map { c -> c.peerUid }
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
        val chat = _chats.value.firstOrNull { c -> c.peerUid == story.owner } ?: return
        if (text.isBlank()) return
        repo.send(chat.id, me, story.owner, "📖 Սթորի՝ " + text.trim())
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

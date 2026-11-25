package am.khosq.app.ui

import am.khosq.app.tr
import am.khosq.app.trf

import am.khosq.app.data.AccountLogic
import am.khosq.app.data.AuthKind
import am.khosq.app.data.CallRecord
import am.khosq.app.data.Chat
import am.khosq.app.data.Message
import am.khosq.app.data.PollCodec
import am.khosq.app.data.Profile
import am.khosq.app.data.QrLogic
import am.khosq.app.data.QrVerdict
import am.khosq.app.data.Star
import am.khosq.app.data.MemChat
import am.khosq.app.data.MemEntry
import am.khosq.app.data.MemoryHit
import am.khosq.app.data.MemoryLogic
import am.khosq.app.data.MemoryStore
import am.khosq.app.data.AsstIntent
import am.khosq.app.data.AsstTurn
import am.khosq.app.data.AssistantLogic
import am.khosq.app.data.AssistantParser
import am.khosq.app.data.ShieldLogic
import am.khosq.app.data.TranscribeCache
import am.khosq.app.data.TranscribeLogic
import am.khosq.app.data.TranscribeResult
import am.khosq.app.data.TranslateCache
import am.khosq.app.data.TranslateLogic
import am.khosq.app.data.TranslateResult
import am.khosq.app.push.LinkCheckClient
import am.khosq.app.push.TranscribeClient
import am.khosq.app.push.TranslateClient
import am.khosq.app.Lang
import am.khosq.app.data.Task
import am.khosq.app.data.TaskStore
import am.khosq.app.Reminders
import am.khosq.app.Strings
import am.khosq.app.data.ProfileStore
import am.khosq.app.data.Delivery
import am.khosq.app.data.UserInfo
import am.khosq.app.data.UserPrefs
import am.khosq.app.Prefs
import am.khosq.app.data.Repository
import am.khosq.app.data.Story
import am.khosq.app.AppState
import am.khosq.app.crypto.E2E
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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

    private val _peerInfo = MutableStateFlow(UserInfo())
    val peerInfo: StateFlow<UserInfo> = _peerInfo.asStateFlow()

    private val _prefs = MutableStateFlow(UserPrefs())
    val prefs: StateFlow<UserPrefs> = _prefs.asStateFlow()

    private var prefsJob: Job? = null

    private val _stars = MutableStateFlow<List<Star>>(emptyList())
    val stars: StateFlow<List<Star>> = _stars.asStateFlow()

    private var starsJob: Job? = null

    /** null while it is being checked; true when the open chat is protected end-to-end; false when somebody has an old app. */
    private val _secure = MutableStateFlow<Boolean?>(null)
    val secure: StateFlow<Boolean?> = _secure.asStateFlow()

    /** For the open group: the members whose app has no encryption key yet (the group is then not encrypted). */
    private val _groupMissing = MutableStateFlow<List<String>>(emptyList())
    val groupMissing: StateFlow<List<String>> = _groupMissing.asStateFlow()
    private var openId = ""
    private var keyJob: Job? = null

    private val healed = HashSet<String>()

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

    private val _accountEmail = MutableStateFlow<String?>(null)

    /** The email of this account, or null while it has none (then the account cannot be opened on another phone). */
    val accountEmail: StateFlow<String?> = _accountEmail.asStateFlow()

    fun refreshAccount() {
        _accountEmail.value = repo.accountEmail()
    }

    suspend fun linkEmail(email: String, password: String): AuthKind {
        val k = repo.linkEmail(AccountLogic.normEmail(email), password)
        if (k == AuthKind.OK) refreshAccount()
        return k
    }

    suspend fun resetPassword(email: String): AuthKind = repo.resetPassword(AccountLogic.normEmail(email))

    // ---- a new phone: opening the account that was made on the old one
    private var recovering: Profile? = null

    /** Signs in with the email and password. Nothing of the app starts yet: the key file may still have to be put back first. */
    suspend fun recoverSignIn(email: String, password: String): AuthKind {
        val k = repo.signInEmail(AccountLogic.normEmail(email), password)
        if (k != AuthKind.OK) return k
        return try {
            uid = repo.currentUid()
            recovering = repo.loadProfile(uid)
            AuthKind.OK
        } catch (e: Exception) {
            AuthKind.NETWORK
        }
    }

    /** After a key file was put back: the restored public key goes to my profile again (the next start of the app would do it too). */
    fun republishKey() {
        val id = uid
        if (id.isEmpty()) return
        viewModelScope.launch {
            try {
                repo.publishKey(id)
            } catch (e: Exception) {
                // tried again at the next start
            }
        }
    }

    /** The id of the account that was just opened. */
    fun recoverUid(): String = uid

    fun recoverHasProfile(): Boolean = recovering != null

    /** The app starts with the opened account (or asks for a name, when that account has no profile). */
    fun recoverFinish() {
        val p = recovering
        recovering = null
        if (p == null) _boot.value = Boot.NeedSetup else ready(p)
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
        refreshAccount()
        am.khosq.app.call.CallManager.listen(p.uid)
        beatJob?.cancel()
        beatJob = viewModelScope.launch {
            while (true) {
                val cur = (_boot.value as? Boot.Ready)?.me
                if (AppState.inForeground && cur != null && !cur.hideSeen) repo.touchSeen(p.uid)
                delay(45_000L)
            }
        }
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token
            .addOnSuccessListener { t -> am.khosq.app.push.KhosqMessagingService.saveToken(t) }
        ProfileStore.want(viewModelScope, repo, p.uid, p.pv)
        // other phones need my public key to write to me; it is put on my profile once
        viewModelScope.launch {
            try {
                repo.publishKey(p.uid)
            } catch (e: Exception) {
                // tried again at the next start
            }
        }
        // fetch the TURN settings for calls early, so even the first call of a session can use them
        am.khosq.app.push.IceClient.refresh()
        viewModelScope.launch {
            try {
                repo.ensureSaved(p)
            } catch (e: Exception) {
                // tried again at the next start
            }
        }
        prefsJob?.cancel()
        prefsJob = viewModelScope.launch {
            repo.prefs(p.uid)
                .retryWhen { _, _ ->
                    delay(5_000L)
                    true
                }
                .collect { pr ->
                    _prefs.value = pr
                    Prefs.putSet("muted", pr.muted)
                }
        }
        starsJob?.cancel()
        starsJob = viewModelScope.launch {
            repo.stars(p.uid)
                .retryWhen { _, _ ->
                    delay(5_000L)
                    true
                }
                .collect { list -> _stars.value = list }
        }
        chatsJob?.cancel()
        chatsJob = viewModelScope.launch {
            repo.chats(p.uid)
                .resilient(tr("Զրույցներ"))
                .collect { list ->
                    _chats.value = list
                    syncPeople(list)
                    prepareKeys(list)
                    memoryCatchUp(list)
                }
        }
        callsJob?.cancel()
        callsJob = viewModelScope.launch {
            repo.callHistory(p.uid)
                .resilient(tr("Զանգեր"))
                .collect { _calls.value = it }
        }
        storiesJob?.cancel()
        storiesJob = viewModelScope.launch {
            combine(
                repo.storiesForMe(p.uid).onStart { emit(emptyList<Story>()) },
                repo.myStories(p.uid).onStart { emit(emptyList<Story>()) },
            ) { a, b -> (a + b).distinctBy { st -> st.id } }
                .resilient(tr("Սթորիներ"))
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
     * Downloads the photos of the people in my chats, and makes sure every chat shows my current name and photo
     * (this is also how a new name or photo reaches the others).
     */
    private fun syncPeople(list: List<Chat>) {
        val me = (_boot.value as? Boot.Ready)?.me ?: return
        // the newest photo version anyone's chats know about (a chat that was not updated yet may still say an older one)
        val best = HashMap<String, Long>()
        for (c in list) {
            val people = if (c.isGroup) c.members else listOf(c.peerUid)
            for (m in people) {
                if (m != me.uid) best[m] = maxOf(best[m] ?: 0L, c.pv[m] ?: 0L)
            }
            Delivery.mark(c.id, me.uid, c.lastTs, c.lastFrom, c.dlv[me.uid] ?: 0L)
            val stale = c.memberNames[me.uid] != me.name || (c.pv[me.uid] ?: 0L) != me.pv
            if (stale && healed.add(c.id + "|" + me.name + "|" + me.pv)) {
                repo.syncSelf(c.id, me.uid, me.name, me.pv)
            }
        }
        for (c in list) {
            if (c.isGroup && !c.saved) best[c.id] = c.gpv
        }
        for ((u, v) in best) ProfileStore.want(viewModelScope, repo, u, v)
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
                onError(if (isTaken(e)) tr("Այս ID-ն արդեն զբաղված է") else describe(e))
            }
        }
    }

    /** Calls back with (chatId, null) on success or (null, errorText). */
    fun addContact(handleRaw: String, onDone: (String?, String?) -> Unit) {
        val me = (_boot.value as? Boot.Ready)?.me
        val handle = handleRaw.trim().lowercase().removePrefix("@")
        if (me == null || handle.isEmpty()) {
            onDone(null, tr("Գրիր ընկերոջդ ID-ն"))
            return
        }
        viewModelScope.launch {
            try {
                val peer = repo.findByHandle(handle)
                if (peer == null) {
                    onDone(null, tr("Այդպիսի ID չի գտնվել"))
                } else if (peer.uid == me.uid) {
                    onDone(null, tr("Սա քո սեփական ID-ն է"))
                } else {
                    onDone(repo.startChat(me, peer), null)
                }
            } catch (e: Exception) {
                onDone(null, describe(e))
            }
        }
    }

    /** What came of adding somebody from a scanned code: [chatId] on success ([verified]: the key was matched too), else [error]. */
    class QrAdd(val chatId: String?, val name: String, val verified: Boolean, val error: String?)

    private fun qrText(v: QrVerdict, adding: Boolean, name: String): String = when (v) {
        QrVerdict.MATCH -> ""
        QrVerdict.BAD -> tr("Սա KHOSQ-ի կոդ չէ")
        QrVerdict.SELF -> tr("Սա քո սեփական կոդն է")
        QrVerdict.WRONG_PERSON -> trf("Սա {0}-ի կոդը չէ։ Սկանավորիր հենց նրա կոդը։", name)
        QrVerdict.NO_KEY -> tr("Նրա բանալին դեռ չկա։ Թող նա բացի հավելվածը, և փորձեք նորից։")
        QrVerdict.MISMATCH ->
            if (adding) {
                tr("Կոդի բանալին չի համընկնում այն բանալու հետ, որ սերվերը տալիս է այս մարդու համար։ Դա կարող է նշանակել, որ ինչ-որ մեկը կանգնել է ձեր միջև, կամ որ կոդը կեղծ է։ Կոնտակտը չի ավելացվել։")
            } else {
                tr("Կոդի բանալին չի համընկնում այն բանալու հետ, որ սերվերը տալիս է այս մարդու համար։ Դա կարող է նշանակել, որ ինչ-որ մեկը կանգնել է ձեր միջև, կամ որ կոդը կեղծ է։ Հաստատումը չի կատարվել։")
            }
    }

    /**
     * A code scanned in "New contact": find the person, check the code against the key the server holds, start the chat, and mark
     * the key as verified. Nothing is added when the key does not match.
     */
    fun addByQr(text: String, onDone: (QrAdd) -> Unit) {
        val me = (_boot.value as? Boot.Ready)?.me
        if (me == null) {
            onDone(QrAdd(null, "", false, tr("Գրիր ընկերոջդ ID-ն")))
            return
        }
        viewModelScope.launch {
            try {
                val code = QrLogic.parse(text)
                if (code == null || code.uid == me.uid) {
                    onDone(QrAdd(null, "", false, qrText(QrLogic.judge(code, me.uid, null, null), true, "")))
                    return@launch
                }
                val peer = repo.findByHandle(code.handle)
                if (peer == null || peer.uid != code.uid) {
                    // the ID in the code is not that account's ID: a forged or an old code
                    onDone(QrAdd(null, "", false, tr("Կոդը չի համապատասխանում այս հաշվին։ Մի վստահիր դրան։")))
                    return@launch
                }
                val pub = E2E.serverKey(peer.uid)
                val verdict = QrLogic.judge(code, me.uid, null, pub)
                if (verdict != QrVerdict.MATCH) {
                    onDone(QrAdd(null, peer.name, false, qrText(verdict, true, peer.name)))
                    return@launch
                }
                val chatId = repo.startChat(me, peer)
                val ok = E2E.verifyByQr(peer.uid, me.uid, pub ?: "")
                onDone(QrAdd(chatId, peer.name, ok, null))
            } catch (e: Exception) {
                onDone(QrAdd(null, "", false, describe(e)))
            }
        }
    }

    /** A code scanned on the card of the contact [peerUid]: check it and mark the contact verified. Calls back with null on success, else the text to show. */
    fun verifyScanned(text: String, peerUid: String, peerName: String, onDone: (String?) -> Unit) {
        val me = (_boot.value as? Boot.Ready)?.me
        if (me == null) {
            onDone(tr("Գրիր ընկերոջդ ID-ն"))
            return
        }
        viewModelScope.launch {
            try {
                val code = QrLogic.parse(text)
                val pub = if (code != null && code.uid == peerUid) E2E.serverKey(peerUid) else null
                val verdict = QrLogic.judge(code, me.uid, peerUid, pub)
                if (verdict != QrVerdict.MATCH) {
                    onDone(qrText(verdict, false, peerName))
                    return@launch
                }
                val ok = E2E.verifyByQr(peerUid, me.uid, pub ?: "")
                onDone(if (ok) null else tr("Չհաջողվեց հաստատել։ Փորձիր նորից։"))
            } catch (e: Exception) {
                onDone(describe(e))
            }
        }
    }

    /** Gets the encryption keys of every chat ready, so sending never has to wait for them. */
    private fun prepareKeys(list: List<Chat>) {
        keyJob?.cancel()
        keyJob = viewModelScope.launch {
            for (c in list) {
                try {
                    if (c.isGroup && !c.saved) prepareGroup(c) else E2E.keyFor(c.id, uid)
                } catch (e: Exception) {
                    // this chat stays unencrypted until the key can be made
                }
            }
        }
    }

    /** Gets the keys of all the members of a group ready; for the group on screen it also tells whether everybody can be written to securely. */
    private suspend fun prepareGroup(c: Chat) {
        val missing = E2E.prepareGroup(c.members, uid)
        if (openId == c.id) {
            _groupMissing.value = missing
            _secure.value = missing.isEmpty()
        }
    }

    /** Runs [block] once the chat's keys are known, so a message goes out encrypted whenever everybody can read it. */
    private fun withKey(chatId: String, block: () -> Unit) {
        viewModelScope.launch {
            try {
                val c = _chats.value.firstOrNull { x -> x.id == chatId }
                if (E2E.isGroup(chatId, uid)) {
                    if (c != null) E2E.prepareGroup(c.members, uid)
                } else {
                    E2E.keyFor(chatId, uid)
                }
            } catch (e: Exception) {
                // sent without encryption only if the key really cannot be made
            }
            block()
        }
    }

    fun openChat(id: String) {
        messagesJob?.cancel()
        openId = id
        _secure.value = null
        _messages.value = emptyList()
        _replyTo.value = null
        _peerInfo.value = UserInfo()
        seenJob?.cancel()
        val peer = if (id.contains("_")) id.split("_").firstOrNull { p -> p != uid } else null
        if (peer != null) {
            seenJob = viewModelScope.launch {
                repo.userInfo(peer)
                    .retryWhen { _, _ ->
                        delay(5_000L)
                        true
                    }
                    .collect { info -> _peerInfo.value = info }
            }
        }
        _groupMissing.value = emptyList()
        viewModelScope.launch {
            try {
                if (peer != null) E2E.refreshPeer(peer, uid)
                if (E2E.isGroup(id, uid)) {
                    // a member may have a new phone: look at every key again, then see whether the whole group can be sealed
                    val c = _chats.value.firstOrNull { x -> x.id == id }
                    if (c != null) {
                        for (m in c.members) if (m != uid) E2E.refreshPeer(m, uid)
                        prepareGroup(c)
                    }
                } else {
                    val ok = E2E.keyFor(id, uid) != null
                    if (openId == id) _secure.value = ok
                }
            } catch (e: Exception) {
                if (openId == id) _secure.value = false
            }
        }
        repo.markRead(id, uid)
        // opening a chat takes off the "unread" mark I put on it by hand
        if (id in _prefs.value.marked) setPrefFlag("marked", id, false)
        messagesJob = viewModelScope.launch {
            repo.messages(id)
                .resilient(tr("Նամակներ"))
                .collect { list ->
                    _messages.value = list
                    memorySync(id, list)
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
        val to = recipients(chatId, peerUid)
        val title = groupTitle(chatId)
        withKey(chatId) { repo.send(chatId, me, to, text, r, title) }
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

    fun togglePin(chatId: String) {
        val on = chatId !in _prefs.value.pins
        _prefs.value = _prefs.value.copy(pins = if (on) _prefs.value.pins + chatId else _prefs.value.pins - chatId)
        if (uid.isNotEmpty()) repo.setPref(uid, "pins", chatId, on)
    }

    fun toggleMute(chatId: String) {
        val on = chatId !in _prefs.value.muted
        val next = if (on) _prefs.value.muted + chatId else _prefs.value.muted - chatId
        _prefs.value = _prefs.value.copy(muted = next)
        Prefs.putSet("muted", next)
        if (uid.isNotEmpty()) repo.setPref(uid, "muted", chatId, on)
    }

    /** Puts a chat into my archive, or takes it out again. Private: the other side never sees it. */
    fun toggleArchive(chatId: String) {
        setPrefFlag("archived", chatId, chatId !in _prefs.value.archived)
    }

    /** "Mark as unread": a private dot on the chat row until I open the chat. */
    fun toggleMarkUnread(chatId: String) {
        setPrefFlag("marked", chatId, chatId !in _prefs.value.marked)
    }

    /** "Mark as read" from the chat list: clears the counter and any hand-made mark. */
    fun markChatRead(chatId: String) {
        if (uid.isEmpty()) return
        repo.markRead(chatId, uid)
        if (chatId in _prefs.value.marked) setPrefFlag("marked", chatId, false)
    }

    /** Changes one of my private lists ("archived" or "marked") on the screen at once and in my space in Firestore. */
    private fun setPrefFlag(field: String, chatId: String, on: Boolean) {
        val p = _prefs.value
        _prefs.value = when (field) {
            "archived" -> p.copy(archived = if (on) p.archived + chatId else p.archived - chatId)
            else -> p.copy(marked = if (on) p.marked + chatId else p.marked - chatId)
        }
        if (uid.isNotEmpty()) repo.setPref(uid, field, chatId, on)
    }

    /** Pins a message at the top of the open chat (or takes the pin off when [msgId] is null). */
    fun setPinned(chatId: String, msgId: String?) {
        repo.setPinned(chatId, msgId)
    }

    /** Changes the text of one of my own messages. */
    fun editMessage(chatId: String, m: Message, text: String) {
        val t = text.trim()
        if (m.from != uid || m.kind != "text" || t.isEmpty() || t == m.text) return
        val lastReal = _messages.value.lastOrNull { x -> x.kind == "text" || x.kind == "img" || x.kind == "voice" || x.kind == "loc" || x.kind == "poll" }
        repo.editMessage(chatId, m.id, t, lastReal?.id == m.id, m.enc)
    }

    fun deleteMessage(chatId: String, m: Message) {
        if (m.from == uid) repo.deleteForAll(chatId, m.id)
    }

    fun sendImage(chatId: String, peerUid: String, bytes: ByteArray) {
        val me = (_boot.value as? Boot.Ready)?.me ?: return
        val to = recipients(chatId, peerUid)
        val title = groupTitle(chatId)
        withKey(chatId) { repo.sendMedia(chatId, me, to, "img", bytes, 0, title) }
    }

    fun sendVoice(chatId: String, peerUid: String, bytes: ByteArray, seconds: Int, wave: String) {
        val me = (_boot.value as? Boot.Ready)?.me ?: return
        val to = recipients(chatId, peerUid)
        val title = groupTitle(chatId)
        withKey(chatId) { repo.sendMedia(chatId, me, to, "voice", bytes, seconds, title, wave) }
    }

    /** Sends my place once. [lat] and [lng] are written with 5 decimals (about a metre), always with a dot. */
    fun sendLocation(chatId: String, peerUid: String, lat: Double, lng: Double) {
        val me = (_boot.value as? Boot.Ready)?.me ?: return
        val to = recipients(chatId, peerUid)
        val title = groupTitle(chatId)
        val coords = String.format(java.util.Locale.US, "%.5f,%.5f", lat, lng)
        withKey(chatId) { repo.sendLocation(chatId, me, to, coords, title) }
    }

    /** Sends a poll. False when the question and options do not make a valid poll (a question and 2 to 6 options). */
    fun sendPoll(chatId: String, peerUid: String, question: String, options: List<String>): Boolean {
        val me = (_boot.value as? Boot.Ready)?.me ?: return false
        val poll = PollCodec.clean(question, options) ?: return false
        val to = recipients(chatId, peerUid)
        val title = groupTitle(chatId)
        withKey(chatId) { repo.sendPoll(chatId, me, to, poll, title) }
        return true
    }

    /** Votes for an option; choosing the option I already chose takes my vote back. */
    fun vote(chatId: String, m: Message, option: Int) {
        val poll = PollCodec.decode(m.text) ?: return
        if (option !in poll.options.indices) return
        val mine = PollCodec.tally(m.votes, poll.options.size)[uid]
        viewModelScope.launch {
            try {
                repo.vote(chatId, m, uid, if (mine == option) null else option)
            } catch (e: Exception) {
                // the vote did not go out; the poll simply stays as it was
            }
        }
    }

    /** Puts a message on my starred list, or takes it off again. */
    fun toggleStar(chatId: String, m: Message) {
        val me = (_boot.value as? Boot.Ready)?.me ?: return
        val key = chatId + "~" + m.id
        if (_stars.value.any { s -> s.key == key }) {
            repo.removeStar(me.uid, key)
            return
        }
        val chat = _chats.value.firstOrNull { c -> c.id == chatId }
        val from = if (m.from == me.uid) me.name else (chat?.memberNames?.get(m.from) ?: chat?.peerName ?: "")
        repo.setStar(
            me.uid,
            Star(
                key = key,
                chatId = chatId,
                // "Saved messages" has no name of its own; the list writes it in the reader's language
                chatName = if (chat == null || chat.saved) "" else chat.peerName,
                fromName = from,
                mine = m.from == me.uid,
                kind = m.kind,
                text = if (m.kind == "text") m.text.take(600) else "",
                ts = m.ts,
                at = System.currentTimeMillis(),
            ),
        )
    }

    fun removeStar(key: String) {
        val me = (_boot.value as? Boot.Ready)?.me ?: return
        repo.removeStar(me.uid, key)
    }

    /** bytes = null removes the group's picture. Only admins may do this (the rules check it too). */
    fun setGroupPhoto(chatId: String, bytes: ByteArray?) {
        val me = (_boot.value as? Boot.Ready)?.me ?: return
        viewModelScope.launch {
            try {
                if (bytes == null) {
                    repo.removeGroupPhoto(chatId)
                    ProfileStore.clearLocal(chatId)
                } else {
                    val ver = System.currentTimeMillis()
                    ProfileStore.setLocal(chatId, bytes, ver)
                    repo.setGroupPhoto(chatId, me, bytes, ver)
                }
            } catch (e: Exception) {
                _notice.value = describe(e)
            }
        }
    }

    fun setGroupDesc(chatId: String, text: String) {
        val me = (_boot.value as? Boot.Ready)?.me ?: return
        repo.setGroupDesc(chatId, me, text.trim().take(200))
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
        val line = "📖 " + text.trim()
        withKey(chat.id) { repo.send(chat.id, me, listOf(story.owner), line) }
    }

    /* ---------- my profile ---------- */

    private fun updateMe(f: (Profile) -> Profile) {
        val me = (_boot.value as? Boot.Ready)?.me ?: return
        _boot.value = Boot.Ready(f(me))
        syncPeople(_chats.value)
    }

    fun saveProfile(name: String, about: String) {
        val me = (_boot.value as? Boot.Ready)?.me ?: return
        val n = name.trim().take(30).ifEmpty { me.name }
        val a = about.trim().take(140)
        repo.saveMyInfo(me.uid, n, a)
        updateMe { p -> p.copy(name = n, about = a) }
    }

    /** bytes = null removes the photo. */
    fun setPhoto(bytes: ByteArray?) {
        val me = (_boot.value as? Boot.Ready)?.me ?: return
        viewModelScope.launch {
            try {
                if (bytes == null) {
                    repo.removePhoto(me.uid)
                    ProfileStore.clearLocal(me.uid)
                    updateMe { p -> p.copy(pv = 0L) }
                } else {
                    val ver = System.currentTimeMillis()
                    ProfileStore.setLocal(me.uid, bytes, ver)
                    repo.savePhoto(me.uid, bytes, ver)
                    updateMe { p -> p.copy(pv = ver) }
                }
            } catch (e: Exception) {
                _notice.value = describe(e)
            }
        }
    }

    fun setHideSeen(on: Boolean) {
        val me = (_boot.value as? Boot.Ready)?.me ?: return
        repo.setHideSeen(me.uid, on)
        updateMe { p -> p.copy(hideSeen = on) }
    }

    /** Chats a message can be forwarded to: accepted, not blocked one-to-one chats and my groups. */
    fun shareTargets(): List<Chat> =
        _chats.value.filter { c -> c.isGroup || (c.status == "active" && c.blockedBy.isEmpty()) }

    fun forward(m: Message, targets: List<Chat>) {
        val me = (_boot.value as? Boot.Ready)?.me ?: return
        for (c in targets) {
            val others = if (c.isGroup) c.members.filter { x -> x != me.uid } else listOf(c.peerUid)
            withKey(c.id) { repo.forward(m, c, me, others) }
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

    fun setAdminOnly(chatId: String, on: Boolean) {
        val me = (_boot.value as? Boot.Ready)?.me ?: return
        repo.setAdminOnly(chatId, me, on)
    }

    fun setAdmin(chatId: String, uid: String, name: String, on: Boolean) {
        val me = (_boot.value as? Boot.Ready)?.me ?: return
        repo.setAdmin(chatId, me, uid, name, on)
    }

    fun leaveGroup(chat: Chat) {
        val me = (_boot.value as? Boot.Ready)?.me ?: return
        repo.leaveGroup(chat, me)
    }

    fun clearNotice() {
        _notice.value = null
    }

    /** A short message that goes away by itself. */
    private fun flash(text: String) {
        _notice.value = text
        viewModelScope.launch {
            delay(2500)
            if (_notice.value == text) _notice.value = null
        }
    }

    // ---- KHOSQ Memory: the texts of the chats I switched Memory on for, kept only on this phone (sealed)

    val memoryChats: StateFlow<Map<String, MemChat>> = MemoryStore.chats

    private val _memoryHits = MutableStateFlow<List<MemoryHit>>(emptyList())
    val memoryHits: StateFlow<List<MemoryHit>> = _memoryHits.asStateFlow()
    private var memorySearchJob: Job? = null
    private var memoryQuery = ""
    private var pullJob: Job? = null
    private val pullQueue = LinkedHashSet<String>()
    private val pulledAt = HashMap<String, Long>()

    /** The "could not be opened" line in every language: it must never be saved as if it were a message. */
    private fun failedMarkers(): Set<String> {
        val hy = "Նամակը չհաջողվեց բացել"
        val t = Strings.map[hy]
        return if (t == null) setOf(hy) else setOf(hy, t[0], t[1])
    }

    /** Gives what a chat shows to the Memory. Does nothing unless Memory is on for that chat. */
    private fun memorySync(chatId: String, list: List<Message>) {
        if (!MemoryStore.isOn(chatId)) return
        val chat = _chats.value.firstOrNull { x -> x.id == chatId }
        val name = chat?.label() ?: ""
        val me = uid
        val you = tr("Դու")
        val failed = failedMarkers()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entries = list.map { m ->
                    val who = if (m.from == me) you else (chat?.memberNames?.get(m.from)?.takeIf { n -> n.isNotEmpty() } ?: chat?.peerName ?: "")
                    val question = if (m.kind == "poll") PollCodec.decode(m.text)?.question else null
                    MemEntry(m.id, m.ts, m.from == me, who, MemoryLogic.indexText(m.kind, m.text, m.deleted, failed, question), m.deleted)
                }
                MemoryStore.sync(chatId, name, entries)
            } catch (e: Exception) {
                // saving is best effort: the chat itself is not affected
            }
        }
    }

    /** Reads a chat that is not open for a few seconds, so what arrived meanwhile is saved too. One chat at a time. */
    private fun memoryPull(chatId: String) {
        pullQueue.add(chatId)
        if (pullJob?.isActive == true) return
        pullJob = viewModelScope.launch {
            while (pullQueue.isNotEmpty()) {
                val id = pullQueue.first()
                pullQueue.remove(id)
                pulledAt[id] = System.currentTimeMillis()
                if (!MemoryStore.isOn(id)) continue
                try {
                    withTimeoutOrNull(8_000L) {
                        repo.messages(id).collect { list -> memorySync(id, list) }
                    }
                } catch (e: Exception) {
                    // the next chat list update tries again
                }
            }
        }
    }

    /** A chat with Memory on got something new while it was not open: read it. */
    private fun memoryCatchUp(list: List<Chat>) {
        val on = MemoryStore.chats.value
        if (on.isEmpty()) return
        val now = System.currentTimeMillis()
        for (c in list) {
            val m = on[c.id] ?: continue
            if (c.lastTs <= m.synced) continue
            if (openId == c.id && messagesJob?.isActive == true) continue
            if (now - (pulledAt[c.id] ?: 0L) < 120_000L) continue
            memoryPull(c.id)
        }
    }

    fun memoryEnable(chatId: String, name: String) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    MemoryStore.enable(chatId, name)
                    true
                } catch (e: Exception) {
                    false
                }
            }
            if (!ok) {
                flash(tr("Հիշողությունը չհաջողվեց միացնել"))
                return@launch
            }
            if (openId == chatId && messagesJob?.isActive == true) memorySync(chatId, _messages.value) else memoryPull(chatId)
        }
    }

    private fun memoryWork(block: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    block()
                } catch (e: Exception) {
                    flash(tr("Հիշողությունը չհաջողվեց փոխել"))
                }
            }
            searchMemory(memoryQuery)
        }
    }

    fun memoryDisable(chatId: String) = memoryWork { MemoryStore.disable(chatId) }

    fun memoryDisableAll() = memoryWork { MemoryStore.disableAll() }

    fun memoryEdit(h: MemoryHit, text: String) = memoryWork {
        if (!MemoryStore.edit(h.chatId, h.msgId, text)) flash(tr("Հիշողությունը չհաջողվեց փոխել"))
    }

    fun memoryRemove(h: MemoryHit) = memoryWork { MemoryStore.remove(h.chatId, h.msgId) }

    /** Looks for the words in everything saved. Waits a moment for the person to stop typing. */
    fun searchMemory(q: String) {
        memorySearchJob?.cancel()
        memoryQuery = q.trim()
        val query = memoryQuery
        if (query.isEmpty()) {
            _memoryHits.value = emptyList()
            return
        }
        memorySearchJob = viewModelScope.launch {
            delay(200)
            val found = withContext(Dispatchers.IO) {
                try {
                    MemoryStore.search(query)
                } catch (e: Exception) {
                    emptyList<MemoryHit>()
                }
            }
            _memoryHits.value = found
        }
    }

    // ---- tasks and reminders: kept on this phone only

    val tasks: StateFlow<List<Task>> = TaskStore.tasks

    private fun taskWork(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: Exception) {
                flash(tr("Առաջադրանքը չհաջողվեց պահպանել"))
            }
        }
    }

    /** Saves a new task (number 0) or a changed one. [announce] says "added" when it is done. */
    fun saveTask(t: Task, announce: Boolean = false) {
        taskWork {
            if (!Reminders.save(t)) flash(tr("Առաջադրանքը չհաջողվեց պահպանել"))
            else if (announce) flash(tr("Ավելացվեց առաջադրանքներին"))
        }
    }

    fun setTaskDone(id: Long, done: Boolean) = taskWork { Reminders.setDone(id, done) }

    fun deleteTask(id: Long) = taskWork { Reminders.delete(id) }

    fun clearDoneTasks() = taskWork { Reminders.clearDone() }

    // ---- KHOSQ Assistant: answers from my tasks and the Memory by plain rules (no language model). The talk is kept only while the app runs.

    private val _asst = MutableStateFlow<List<AsstTurn>>(emptyList())
    val assistant: StateFlow<List<AsstTurn>> = _asst.asStateFlow()
    private var asstSeq = 0L

    private fun asstChange(id: Long, change: (AsstTurn) -> AsstTurn) {
        _asst.update { list -> list.map { x -> if (x.id == id) change(x) else x } }
    }

    fun assistantClear() {
        _asst.value = emptyList()
    }

    /** Reads the question, finds the answer in what the person allowed (own tasks; chats with Memory on) and adds both to the talk. */
    fun assistantAsk(text: String) {
        val q = text.trim().take(AssistantParser.MAX_INPUT)
        if (q.isEmpty()) return
        val now = System.currentTimeMillis()
        val intent = AssistantParser.parse(q, now)
        val id = ++asstSeq
        var turn = AsstTurn(id = id, question = q, at = now, intent = intent)
        if (intent is AsstIntent.ListTasks) {
            val got = AssistantLogic.pickTasks(TaskStore.tasks.value, intent.filter, intent.range, now)
            turn = turn.copy(tasks = got.first, moreTasks = got.second - got.first.size)
        } else if (intent is AsstIntent.AddTask) {
            if (intent.title.isNotEmpty()) turn = turn.copy(proposal = Task(title = intent.title, due = intent.due, remind = intent.remind), busy = true)
        } else if (intent is AsstIntent.Find) {
            turn = turn.copy(busy = true)
        } else if (intent is AsstIntent.Translate && intent.text.isNotEmpty()) {
            // nothing leaves the phone before the person has allowed translation
            turn = if (_translateOn.value) turn.copy(busy = true) else turn.copy(needsAllow = true)
        }
        val shown = turn
        _asst.update { list -> (list + shown).takeLast(30) }
        if (intent is AsstIntent.Find) assistantFind(id, intent)
        if (intent is AsstIntent.Translate && shown.busy) assistantTranslate(id, intent)
        val offered = shown.proposal
        if (intent is AsstIntent.AddTask && offered != null) assistantAdd(id, offered)
    }

    /** The person asked for it, so the task is added at once; "Change" and "Undo" stay on the answer. If saving fails the person can still press "Add". */
    private fun assistantAdd(turnId: Long, t: Task) {
        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) {
                try {
                    Reminders.store(t)
                } catch (e: Exception) {
                    null
                }
            }
            if (stored != null) {
                asstChange(turnId) { x -> x.copy(busy = false, proposal = stored, proposalState = 1) }
            } else {
                asstChange(turnId) { x -> x.copy(busy = false) }
                flash(tr("Առաջադրանքը չհաջողվեց պահպանել"))
            }
        }
    }

    private fun assistantFind(id: Long, f: AsstIntent.Find) {
        val range = f.range
        val from = range?.from ?: 0L
        val to = range?.to ?: Long.MAX_VALUE
        val stems = AssistantLogic.stems(f.words)
        val mine = TaskStore.tasks.value
        val chatNames = MemoryStore.chats.value.values.map { c -> c.name }
        viewModelScope.launch {
            var loose = false
            var tasksFound = AssistantLogic.findTasks(mine, f.words, range, stems.size)
            var hits: List<MemoryHit> = emptyList()
            if (chatNames.isNotEmpty()) {
                hits = withContext(Dispatchers.IO) {
                    try {
                        MemoryStore.find(stems, from, to, stems.size)
                    } catch (e: Exception) {
                        emptyList<MemoryHit>()
                    }
                }
            }
            if (hits.isEmpty() && tasksFound.isEmpty() && stems.size >= 3) {
                // nothing has every word: show what has all but one, and say so
                loose = true
                val need = AssistantLogic.need(stems.size, true)
                tasksFound = AssistantLogic.findTasks(mine, f.words, range, need)
                if (chatNames.isNotEmpty()) {
                    hits = withContext(Dispatchers.IO) {
                        try {
                            MemoryStore.find(stems, from, to, need)
                        } catch (e: Exception) {
                            emptyList<MemoryHit>()
                        }
                    }
                }
            }
            val found = tasksFound
            val seen = hits
            val approx = loose && (found.isNotEmpty() || seen.isNotEmpty())
            asstChange(id) { x -> x.copy(busy = false, tasks = found, hits = seen, memChats = chatNames, memOff = chatNames.isEmpty(), approx = approx) }
        }
    }

    /** Saving failed the first time: the person presses "Add" to try again. */
    fun assistantAddTask(turnId: Long) {
        val turn = _asst.value.firstOrNull { x -> x.id == turnId } ?: return
        val t = turn.proposal ?: return
        if (turn.proposalState != 0 || turn.busy) return
        asstChange(turnId) { x -> x.copy(busy = true) }
        assistantAdd(turnId, t)
    }

    /** The person changed the added task in the task form. */
    fun assistantChangeTask(turnId: Long, changed: Task) {
        val turn = _asst.value.firstOrNull { x -> x.id == turnId } ?: return
        if (turn.proposal == null) return
        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) {
                try {
                    Reminders.store(changed)
                } catch (e: Exception) {
                    null
                }
            }
            if (stored != null) {
                asstChange(turnId) { x -> x.copy(proposal = stored, proposalState = 1) }
            } else {
                flash(tr("Առաջադրանքը չհաջողվեց պահպանել"))
            }
        }
    }

    /** "Undo": the task the assistant added is deleted again. Without a saved task it just closes the offer. */
    fun assistantUndoTask(turnId: Long) {
        val turn = _asst.value.firstOrNull { x -> x.id == turnId } ?: return
        val t = turn.proposal ?: return
        if (turn.proposalState == 1 && t.id != 0L) deleteTask(t.id)
        asstChange(turnId) { x -> x.copy(proposalState = 2) }
    }

    private fun assistantTranslate(id: Long, f: AsstIntent.Translate) {
        val to = f.to.ifEmpty { TranslateLogic.defaultTarget(f.text, Lang.code) }
        viewModelScope.launch {
            try {
                if (!TranslateLogic.fits(f.text)) throw TranslateClient.Failure(413)
                val r = translateText(f.text, to)
                asstChange(id) { x -> x.copy(busy = false, translation = r, transError = "") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                asstChange(id) { x -> x.copy(busy = false, transError = TranslateClient.explain(e)) }
            }
        }
    }

    /** The person allowed translation on the answer card: it is switched on, and this question is translated. */
    fun assistantAllowTranslate(turnId: Long) {
        val turn = _asst.value.firstOrNull { x -> x.id == turnId } ?: return
        val f = turn.intent as? AsstIntent.Translate ?: return
        if (turn.busy) return
        setTranslateOn(true)
        asstChange(turnId) { x -> x.copy(needsAllow = false, busy = true, transError = "") }
        assistantTranslate(turnId, f)
    }

    /** Translating failed: try the same text again. */
    fun assistantRetryTranslate(turnId: Long) {
        val turn = _asst.value.firstOrNull { x -> x.id == turnId } ?: return
        val f = turn.intent as? AsstIntent.Translate ?: return
        if (turn.busy) return
        if (!_translateOn.value) {
            asstChange(turnId) { x -> x.copy(needsAllow = true, transError = "") }
            return
        }
        asstChange(turnId) { x -> x.copy(busy = true, transError = "") }
        assistantTranslate(turnId, f)
    }

    // ---- Translation: one text at a time, only when the person asks for it, and only after they allowed it once.

    private val _translateOn = MutableStateFlow(Prefs.getString("translate_ok") == "1")
    val translateOn: StateFlow<Boolean> = _translateOn.asStateFlow()
    private val translateCache = TranslateCache()

    /** The switch in Settings and the "Allow" button of the translation sheet. Switching it off also forgets the translations kept for this run. */
    fun setTranslateOn(on: Boolean) {
        Prefs.putString("translate_ok", if (on) "1" else "0")
        _translateOn.value = on
        if (!on) translateCache.clear()
    }

    /** The translation of [text] into [to] (the last answers are kept while the app runs). Throws when the server says no: see [TranslateClient.explain]. */
    suspend fun translateText(text: String, to: String): TranslateResult {
        translateCache.get(text, to)?.let { return it }
        val r = TranslateClient.translate(text, to)
        translateCache.put(text, to, r)
        return r
    }

    // ---- Voice transcription: one voice note at a time, only when the person asks for it, and only after they allowed it once
    // (a separate permission from translation: here it is sound that leaves the phone, not text).

    private val _transcribeOn = MutableStateFlow(Prefs.getString("transcribe_ok") == "1")
    val transcribeOn: StateFlow<Boolean> = _transcribeOn.asStateFlow()
    private val transcribeCache = TranscribeCache()

    /** The switch in Settings and the "Allow" button of the transcription sheet. Switching it off also forgets the transcripts kept for this run. */
    fun setTranscribeOn(on: Boolean) {
        Prefs.putString("transcribe_ok", if (on) "1" else "0")
        _transcribeOn.value = on
        if (!on) transcribeCache.clear()
    }

    /**
     * The words of one voice note ([messageId] only names it for the cache; [audio] is the opened sound). Nothing is saved on the phone
     * or in the chat. Throws when the server says no: see [TranscribeClient.explain].
     */
    suspend fun transcribeVoice(messageId: String, audio: ByteArray, lang: String): TranscribeResult {
        if (!TranscribeLogic.isLang(lang)) throw TranscribeClient.Failure(400)
        if (audio.size > TranscribeLogic.MAX_BYTES) throw TranscribeClient.Failure(413)
        if (audio.size < TranscribeLogic.MIN_BYTES) throw TranscribeClient.Failure(400)
        transcribeCache.get(messageId, lang)?.let { return it }
        val r = TranscribeClient.transcribe(audio, lang)
        transcribeCache.put(messageId, lang, r)
        return r
    }

    // ---- Trust Shield: received messages are looked at on the phone (nothing leaves it). Only when the person has also allowed
    // the link check, the NAMES of the sites in received links go to the server; see LinkCheckClient.

    private val _shieldOn = MutableStateFlow(Prefs.getString("shield_on") != "0")
    val shieldOn: StateFlow<Boolean> = _shieldOn.asStateFlow()
    private val _shieldLinks = MutableStateFlow(Prefs.getString("shield_link_ok") == "1")
    val shieldLinks: StateFlow<Boolean> = _shieldLinks.asStateFlow()
    private val _hostVerdicts = MutableStateFlow<Map<String, String>>(emptyMap())

    /** What the server said about the sites of links seen in this run of the app (site to "blocked" / "ok" / "unknown"). Never saved. */
    val hostVerdicts: StateFlow<Map<String, String>> = _hostVerdicts.asStateFlow()
    private val hostsAsked = HashSet<String>()

    fun setShieldOn(on: Boolean) {
        Prefs.putString("shield_on", if (on) "1" else "0")
        _shieldOn.value = on
    }

    /** The switch in Settings and the "Allow" button of the Trust Shield sheet. Switching it off forgets the answers kept for this run. */
    fun setShieldLinks(on: Boolean) {
        Prefs.putString("shield_link_ok", if (on) "1" else "0")
        _shieldLinks.value = on
        if (!on) {
            _hostVerdicts.value = emptyMap()
            synchronized(hostsAsked) { hostsAsked.clear() }
        }
    }

    /** Asks the server about sites not asked yet (at most 10 at a time, in groups of five). A failure is remembered as "unknown", so nothing is asked over and over. */
    fun shieldCheckHosts(hosts: List<String>) {
        if (!_shieldOn.value || !_shieldLinks.value) return
        val fresh = synchronized(hostsAsked) { hosts.filter { h -> h !in hostsAsked }.distinct().take(10).also { l -> hostsAsked.addAll(l) } }
        if (fresh.isEmpty()) return
        viewModelScope.launch {
            for (chunk in fresh.chunked(LinkCheckClient.BATCH)) {
                val got = try {
                    LinkCheckClient.check(chunk)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    chunk.associateWith { "unknown" }
                }
                if (_shieldLinks.value) _hostVerdicts.value = _hostVerdicts.value + got
            }
        }
    }

    /** The sites in the links of the received text messages among [list] (the most recent 40), for [shieldCheckHosts]. */
    fun shieldHostsOf(list: List<am.khosq.app.data.Message>, myUid: String): List<String> =
        list.asReversed().asSequence()
            .filter { m -> m.kind == "text" && !m.deleted && m.from != myUid }
            .take(40)
            .flatMap { m -> ShieldLogic.links(m.text).asSequence().map { l -> l.host } }
            .distinct()
            .toList()

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
                tr("Firestore-ի անվտանգության կանոնները դեռ չեն տեղադրված (Rules)։")
            m.contains("restricted", ignoreCase = true) || m.contains("OPERATION_NOT_ALLOWED") ->
                tr("Firebase-ում Anonymous մուտքը միացված չէ։")
            m.contains("network", ignoreCase = true) || m.contains("UNAVAILABLE") ->
                tr("Ինտերնետ կապ չկա։")
            else -> trf("Սխալ՝ {0}", m)
        }
    }
}

package am.khosq.app.data

import am.khosq.app.Prefs
import am.khosq.app.crypto.E2E
import am.khosq.app.tr
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.tasks.await

class HandleTaken : Exception("handle taken")

/** The only place that talks to Firebase. Screens never see Firestore types. */
class Repository {
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    suspend fun ensureSignedIn(): String {
        val current = auth.currentUser
        if (current != null) return current.uid
        val result = auth.signInAnonymously().await()
        return result.user?.uid ?: throw IllegalStateException("sign-in failed")
    }

    suspend fun loadProfile(uid: String): Profile? {
        val d = db.collection("users").document(uid).get().await()
        if (!d.exists()) return null
        val handle = d.getString("handle") ?: return null
        return Profile(
            uid = uid,
            handle = handle,
            name = d.getString("name") ?: handle,
            about = d.getString("about") ?: "",
            hideSeen = d.getBoolean("hideSeen") ?: false,
            pv = d.getLong("pv") ?: 0L,
        )
    }

    suspend fun loadPhoto(uid: String): ByteArray? {
        val d = db.collection("photos").document(uid).get().await()
        return d.getBlob("data")?.toBytes()
    }

    /** Saves my photo (about 20 KB) in its own document, so the often-changing "last seen" never drags it along. */
    suspend fun savePhoto(uid: String, bytes: ByteArray, ver: Long) {
        db.collection("photos").document(uid).set(hashMapOf<String, Any>("data" to Blob.fromBytes(bytes), "ts" to ver)).await()
        db.collection("users").document(uid).set(hashMapOf<String, Any>("pv" to ver), SetOptions.merge()).await()
    }

    suspend fun removePhoto(uid: String) {
        db.collection("photos").document(uid).delete().await()
        db.collection("users").document(uid).set(hashMapOf<String, Any>("pv" to 0L), SetOptions.merge()).await()
    }

    /** Puts my public encryption key on my profile, so other phones can write to me. Does nothing when it is already there. */
    suspend fun publishKey(uid: String) {
        val pk = E2E.myPublic()
        if (Prefs.getString("pk_sent") == "$uid:$pk") return
        db.collection("users").document(uid).set(hashMapOf<String, Any>("pk" to pk), SetOptions.merge()).await()
        Prefs.putString("pk_sent", "$uid:$pk")
    }

    fun saveMyInfo(uid: String, name: String, about: String) {
        db.collection("users").document(uid).set(hashMapOf<String, Any>("name" to name, "about" to about), SetOptions.merge())
    }

    fun setHideSeen(uid: String, on: Boolean) {
        db.collection("users").document(uid).set(hashMapOf<String, Any>("hideSeen" to on), SetOptions.merge())
    }

    /** Makes the chat documents say the same name and photo version as my profile. */
    fun syncSelf(chatId: String, uid: String, name: String, pv: Long) {
        db.collection("chats").document(chatId).update(
            hashMapOf<String, Any>("names.$uid" to name, "pv.$uid" to pv),
        )
    }

    /** Claims the handle and creates the profile in one step, so two people can never get the same ID. */
    suspend fun register(uid: String, handle: String, name: String) {
        val handleRef = db.collection("handles").document(handle)
        val userRef = db.collection("users").document(uid)
        db.runTransaction(Transaction.Function<Void?> { tx ->
            if (tx.get(handleRef).exists()) throw HandleTaken()
            tx.set(handleRef, mapOf("uid" to uid))
            tx.set(
                userRef,
                mapOf("handle" to handle, "name" to name, "createdAt" to System.currentTimeMillis()),
            )
            null
        }).await()
    }

    suspend fun findByHandle(handle: String): Profile? {
        val h = db.collection("handles").document(handle).get().await()
        val uid = h.getString("uid") ?: return null
        return loadProfile(uid)
    }

    fun chatId(a: String, b: String): String = if (a < b) a + "_" + b else b + "_" + a

    suspend fun startChat(me: Profile, peer: Profile): String {
        val id = chatId(me.uid, peer.uid)
        val ref = db.collection("chats").document(id)
        val exists = try {
            ref.get().await().exists()
        } catch (e: Exception) {
            false
        }
        if (!exists) {
            val data = hashMapOf<String, Any>(
                "members" to listOf(me.uid, peer.uid),
                "names" to mapOf(me.uid to me.name, peer.uid to peer.name),
                "handles" to mapOf(me.uid to me.handle, peer.uid to peer.handle),
                "status" to "pending",
                "requester" to me.uid,
                "pv" to mapOf(me.uid to me.pv),
            )
            ref.set(data).await()
        }
        return id
    }

    fun acceptChat(chatId: String) {
        db.collection("chats").document(chatId).update("status", "active")
    }

    fun setBlocked(chatId: String, uid: String, on: Boolean) {
        db.collection("chats").document(chatId).update("blocked." + uid, if (on) true else FieldValue.delete())
    }

    /** The chat list with the previews of encrypted chats opened. */
    fun chats(uid: String): Flow<List<Chat>> = rawChats(uid).transform { list ->
        if (list.none { c -> c.lastEnc }) {
            emit(list)
        } else {
            // first a quick list without the sealed previews, then the real one
            if (list.any { c -> c.lastEnc && !E2E.previewReady(c.id, uid, c.lastFrom) }) {
                emit(list.map { c -> if (c.lastEnc) c.copy(lastText = "", lastEnc = false, lastW = emptyMap()) else c })
            }
            emit(coroutineScope { list.map { c -> async { openPreview(c, uid) } }.awaitAll() })
        }
    }.flowOn(Dispatchers.Default)

    private suspend fun openPreview(c: Chat, me: String): Chat {
        if (!c.lastEnc) return c
        val plain = try {
            if (c.lastText.isEmpty()) null else E2E.openLast(c.id, me, c.lastFrom, c.lastText, c.lastW)
        } catch (e: Exception) {
            null
        }
        return c.copy(lastText = plain ?: tr("Գաղտնագրված նամակ"), lastEnc = false, lastW = emptyMap())
    }

    private fun rawChats(uid: String): Flow<List<Chat>> = callbackFlow {
        val reg = db.collection("chats").whereArrayContains("members", uid)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    close(err)
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener
                val list = snap.documents.mapNotNull { toChat(it, uid) }
                trySend(list.sortedByDescending { it.lastTs })
            }
        awaitClose { reg.remove() }
    }

    private fun stringMap(raw: Any?): Map<String, String> {
        val out = HashMap<String, String>()
        val m = raw as? Map<*, *> ?: return out
        for ((k, v) in m) {
            if (k is String && v is String) out[k] = v
        }
        return out
    }

    private fun longMap(raw: Any?): Map<String, Long> {
        val out = HashMap<String, Long>()
        val m = raw as? Map<*, *> ?: return out
        for ((k, v) in m) {
            if (k is String && v is Number) out[k] = v.toLong()
        }
        return out
    }

    private fun toGroup(d: DocumentSnapshot, me: String): Chat? {
        val members = (d.get("members") as? List<*>)?.filterIsInstance<String>() ?: return null
        if (me !in members) return null
        val others = members.filter { m -> m != me }
        val reads = d.get("read") as? Map<*, *>
        val typing = d.get("typing") as? Map<*, *>
        val unread = d.get("unread") as? Map<*, *>
        val dlvMap = longMap(d.get("dlv"))
        return Chat(
            id = d.id,
            peerUid = d.id,
            peerName = d.getString("title") ?: tr("Խումբ"),
            peerHandle = "",
            lastText = d.getString("lastText") ?: "",
            lastTs = d.getLong("lastTs") ?: 0L,
            lastFrom = d.getString("lastFrom") ?: "",
            unread = (unread?.get(me) as? Number)?.toLong() ?: 0L,
            peerRead = if (others.isEmpty()) 0L else others.minOf { o -> (reads?.get(o) as? Number)?.toLong() ?: 0L },
            peerTyping = others.maxOfOrNull { o -> (typing?.get(o) as? Number)?.toLong() ?: 0L } ?: 0L,
            isGroup = true,
            members = members,
            memberNames = stringMap(d.get("names")),
            memberHandles = stringMap(d.get("handles")),
            admins = (d.get("admins") as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            lastKind = d.getString("lastKind") ?: "",
            pv = longMap(d.get("pv")),
            dlv = longMap(d.get("dlv")),
            peerDelivered = if (others.isEmpty()) 0L else others.minOf { o -> (dlvMap[o] ?: 0L) },
            saved = d.getBoolean("saved") == true,
            desc = d.getString("desc") ?: "",
            gpv = d.getLong("gpv") ?: 0L,
            lastEnc = d.getBoolean("lastEnc") == true,
            lastW = stringMap(d.get("lastW")),
            pinId = d.getString("pin") ?: "",
            adminOnly = d.getString("sendMode") == "admins",
        )
    }

    private fun toChat(d: DocumentSnapshot, me: String): Chat? {
        if (d.getBoolean("group") == true) return toGroup(d, me)
        val members = d.get("members") as? List<*> ?: return null
        val peer = members.firstOrNull { it != me } as? String ?: return null
        val names = d.get("names") as? Map<*, *>
        val handles = d.get("handles") as? Map<*, *>
        val unread = d.get("unread") as? Map<*, *>
        val read = d.get("read") as? Map<*, *>
        return Chat(
            id = d.id,
            peerUid = peer,
            peerName = (names?.get(peer) as? String) ?: peer,
            peerHandle = (handles?.get(peer) as? String) ?: "",
            lastText = d.getString("lastText") ?: "",
            lastTs = d.getLong("lastTs") ?: 0L,
            lastFrom = d.getString("lastFrom") ?: "",
            unread = (unread?.get(me) as? Number)?.toLong() ?: 0L,
            peerRead = (read?.get(peer) as? Number)?.toLong() ?: 0L,
            peerTyping = ((d.get("typing") as? Map<*, *>)?.get(peer) as? Number)?.toLong() ?: 0L,
            memberNames = stringMap(d.get("names")),
            status = d.getString("status") ?: "active",
            requester = d.getString("requester") ?: "",
            blockedBy = (d.get("blocked") as? Map<*, *>)?.entries
                ?.filter { e -> e.value == true }?.mapNotNull { e -> e.key as? String } ?: emptyList(),
            lastKind = d.getString("lastKind") ?: "",
            pv = longMap(d.get("pv")),
            dlv = longMap(d.get("dlv")),
            peerDelivered = longMap(d.get("dlv"))[peer] ?: 0L,
            lastEnc = d.getBoolean("lastEnc") == true,
            pinId = d.getString("pin") ?: "",
        )
    }

    /* ---------- my private choices (pins, muted chats) ---------- */

    private fun strSet(raw: Any?): Set<String> = (raw as? List<*>)?.filterIsInstance<String>()?.toSet() ?: emptySet()

    fun prefs(uid: String): Flow<UserPrefs> = callbackFlow {
        val reg = db.collection("prefs").document(uid).addSnapshotListener { d, err ->
            if (err != null) {
                close(err)
                return@addSnapshotListener
            }
            trySend(
                UserPrefs(
                    pins = strSet(d?.get("pins")),
                    muted = strSet(d?.get("muted")),
                    archived = strSet(d?.get("archived")),
                    marked = strSet(d?.get("marked")),
                ),
            )
        }
        awaitClose { reg.remove() }
    }

    /** field is "pins", "muted", "archived" or "marked". */
    fun setPref(uid: String, field: String, chatId: String, on: Boolean) {
        val change: Any = if (on) FieldValue.arrayUnion(chatId) else FieldValue.arrayRemove(chatId)
        db.collection("prefs").document(uid).set(hashMapOf<String, Any>(field to change), SetOptions.merge())
    }

    /* ---------- starred messages (private copies under prefs/{me}/stars) ---------- */

    fun stars(uid: String): Flow<List<Star>> = callbackFlow {
        val reg = db.collection("prefs").document(uid).collection("stars")
            .orderBy("at", Query.Direction.DESCENDING)
            .limit(300)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    close(err)
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener
                trySend(
                    snap.documents.map { d ->
                        val rawText = d.getString("text") ?: ""
                        // starred text is sealed with a key only this phone can make
                        val text = if (d.getBoolean("enc") == true && rawText.isNotEmpty()) {
                            try {
                                E2E.openSelf("star|" + d.id, rawText) ?: ""
                            } catch (e: Exception) {
                                ""
                            }
                        } else {
                            rawText
                        }
                        Star(
                            key = d.id,
                            chatId = d.getString("chatId") ?: "",
                            chatName = d.getString("chatName") ?: "",
                            fromName = d.getString("fromName") ?: "",
                            mine = d.getBoolean("mine") ?: false,
                            kind = d.getString("kind") ?: "text",
                            text = text,
                            ts = d.getLong("ts") ?: 0L,
                            at = d.getLong("at") ?: 0L,
                        )
                    },
                )
            }
        awaitClose { reg.remove() }
    }

    fun setStar(uid: String, s: Star) {
        val sealed = if (s.text.isEmpty()) null else try {
            E2E.sealSelf("star|" + s.key, s.text)
        } catch (e: Exception) {
            null
        }
        db.collection("prefs").document(uid).collection("stars").document(s.key).set(
            hashMapOf<String, Any>(
                "chatId" to s.chatId,
                "chatName" to s.chatName,
                "fromName" to s.fromName,
                "mine" to s.mine,
                "kind" to s.kind,
                "text" to (sealed ?: s.text),
                "enc" to (sealed != null),
                "ts" to s.ts,
                "at" to s.at,
            ),
        )
    }

    fun removeStar(uid: String, key: String) {
        db.collection("prefs").document(uid).collection("stars").document(key).delete()
    }

    /** "Saved messages": a chat with myself. It is a one-member group, so the existing rules already allow it. */
    suspend fun ensureSaved(me: Profile) {
        val ref = db.collection("chats").document("saved" + me.uid)
        if (ref.get().await().exists()) return
        val data = hashMapOf<String, Any>(
            "group" to true,
            "saved" to true,
            "title" to "",
            "members" to listOf(me.uid),
            "admins" to listOf(me.uid),
            "names" to mapOf(me.uid to me.name),
            "handles" to mapOf(me.uid to me.handle),
            "pv" to mapOf(me.uid to me.pv),
            "status" to "active",
            "lastText" to "",
            "lastTs" to System.currentTimeMillis(),
            "lastFrom" to me.uid,
        )
        ref.set(data).await()
    }

    /** Calls I made plus calls made to me, newest first. Two listeners, merged here (no index needed). */
    fun callHistory(uid: String): Flow<List<CallRecord>> = callbackFlow {
        var made = emptyList<CallRecord>()
        var got = emptyList<CallRecord>()
        fun push() {
            trySend((made + got).sortedByDescending { it.ts }.take(200))
        }
        val r1 = db.collection("calls").whereEqualTo("caller", uid)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    close(err)
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener
                made = snap.documents.mapNotNull { toCall(it, uid) }
                push()
            }
        val r2 = db.collection("calls").whereEqualTo("callee", uid)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    close(err)
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener
                got = snap.documents.mapNotNull { toCall(it, uid) }
                push()
            }
        awaitClose {
            r1.remove()
            r2.remove()
        }
    }

    private fun toCall(d: DocumentSnapshot, me: String): CallRecord? {
        val caller = d.getString("caller") ?: return null
        val callee = d.getString("callee") ?: return null
        val outgoing = caller == me
        return CallRecord(
            id = d.id,
            peerUid = if (outgoing) callee else caller,
            peerName = (if (outgoing) d.getString("calleeName") else d.getString("callerName")) ?: "",
            outgoing = outgoing,
            video = d.getString("kind") == "video",
            ts = d.getLong("ts") ?: 0L,
            dur = (d.getLong("dur") ?: -1L).toInt(),
            state = d.getString("state") ?: "",
        )
    }

    /** The messages of a chat, with everything that was sent encrypted already opened. */
    fun messages(chatId: String): Flow<List<Message>> =
        rawMessages(chatId).map { list -> openAll(chatId, list) }.flowOn(Dispatchers.Default)

    private suspend fun openAll(chatId: String, list: List<Message>): List<Message> {
        if (list.none { m -> m.enc }) return list
        val me = auth.currentUser?.uid ?: ""
        val group = E2E.isGroup(chatId, me)
        // one-to-one chats have a single key; in a group every message has its own, wrapped for me
        val chatKey = if (group) null else try {
            E2E.keyFor(chatId, me)
        } catch (e: Exception) {
            null
        }
        val source = if (group) withoutOlderThanMe(list, me) else list
        return source.map { m ->
            if (!m.enc) {
                m
            } else {
                val key = if (group) {
                    try {
                        E2E.groupKey(chatId, m.from, me, m.wraps)
                    } catch (e: Exception) {
                        null
                    }
                } else {
                    chatKey
                }
                openOne(chatId, key, m)
            }
        }
    }

    /**
     * Someone who joined a group later (by a link, or added by an admin) has no key for the older sealed messages.
     * Those are left out, instead of a line of "could not open" for every one of them. A sealed message counts as older
     * than me when it comes before the first sealed message that has a key for me (or that I wrote myself).
     */
    private fun withoutOlderThanMe(list: List<Message>, me: String): List<Message> {
        val first = list.indexOfFirst { m -> m.enc && (m.from == me || me in m.wraps) }
        if (first == 0) return list
        val cut = if (first < 0) list.size else first
        return list.filterIndexed { i, m -> i >= cut || !m.enc }
    }

    private fun openOne(chatId: String, key: ByteArray?, m: Message): Message {
        if (m.deleted) return m.copy(wraps = emptyMap(), votes = emptyMap())
        if (key == null) {
            return m.copy(text = if (m.text.isEmpty()) "" else tr("Նամակը չհաջողվեց բացել"), reply = "", data = null, wraps = emptyMap(), votes = emptyMap())
        }
        var text = m.text
        if (text.isNotEmpty()) text = E2E.openText(key, chatId, m.from, "t", text) ?: tr("Նամակը չհաջողվեց բացել")
        var reply = m.reply
        if (reply.isNotEmpty()) reply = E2E.openText(key, chatId, m.from, "r", reply) ?: ""
        val raw = m.data
        val bytes = if (raw == null) null else E2E.openBytes(key, chatId, m.from, "d", raw)
        // each vote is sealed with the message's own key, so the server sees who voted but never what
        val votes = HashMap<String, String>()
        for ((voter, sealed) in m.votes) {
            val v = E2E.openText(key, chatId, voter, "v", sealed)
            if (v != null) votes[voter] = v
        }
        // a poll keeps its wrapped keys: they are needed to seal my own vote later
        return m.copy(text = text, reply = reply, data = bytes, wraps = if (m.kind == "poll") m.wraps else emptyMap(), votes = votes)
    }

    private fun rawMessages(chatId: String): Flow<List<Message>> = callbackFlow {
        val reg = db.collection("chats").document(chatId).collection("messages")
            .orderBy("ts")
            .addSnapshotListener(MetadataChanges.INCLUDE) { snap, err ->
                if (err != null) {
                    close(err)
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener
                val list = snap.documents.map { d ->
                    val ts = d.getTimestamp("ts", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    Message(
                        id = d.id,
                        from = d.getString("from") ?: "",
                        text = d.getString("text") ?: "",
                        ts = ts?.toDate()?.time ?: System.currentTimeMillis(),
                        pending = d.metadata.hasPendingWrites(),
                        kind = d.getString("kind") ?: "text",
                        data = d.getBlob("data")?.toBytes(),
                        dur = (d.getLong("dur") ?: 0L).toInt(),
                        reply = d.getString("reply") ?: "",
                        replyName = d.getString("replyName") ?: "",
                        deleted = d.getBoolean("deleted") ?: false,
                        reactions = readReactions(d.get("reactions")),
                        fwd = d.getBoolean("fwd") ?: false,
                        code = d.getString("code") ?: "",
                        arg = d.getString("arg") ?: "",
                        vid = d.getBoolean("vid") ?: false,
                        edited = d.getBoolean("edited") ?: false,
                        replyFrom = d.getString("replyFrom") ?: "",
                        wave = d.getString("wave") ?: "",
                        enc = d.getBoolean("e") ?: false,
                        wraps = if (d.getBoolean("e") == true) stringMap(d.get("w")) else emptyMap(),
                        votes = readReactions(d.get("votes")),
                    )
                }
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    /** Works offline too: Firestore keeps the write and sends it when the network is back. */
    private fun readReactions(raw: Any?): Map<String, String> {
        val m = raw as? Map<*, *> ?: return emptyMap()
        val out = HashMap<String, String>()
        for ((k, v) in m) {
            if (k is String && v is String) out[k] = v
        }
        return out
    }

    /** What is stored for a quoted message: the text, or a marker for a photo / voice message. */
    private fun replyOf(m: Message): String = when (m.kind) {
        "img" -> am.khosq.app.REPLY_IMG
        "voice" -> am.khosq.app.REPLY_VOICE
        "loc" -> am.khosq.app.REPLY_LOC
        "poll" -> "📊 " + (PollCodec.decode(m.text)?.question ?: "").take(70)
        "sos" -> "🆘 SOS"
        else -> m.text.take(80)
    }

    /** Short text kept in the chat document (older app versions show it as it is). Language-neutral kind goes to lastKind. */
    private fun lastOf(kind: String, text: String): String = when (kind) {
        "img" -> "📷"
        "voice" -> "🎤"
        "loc" -> "📍"
        "poll" -> "📊"
        "sos" -> "🆘"
        else -> text.take(80)
    }

    /** Pins one message at the top of the chat for everybody (msgId = null takes the pin off). Only the id is stored. */
    fun setPinned(chatId: String, msgId: String?) {
        db.collection("chats").document(chatId).update("pin", msgId ?: FieldValue.delete())
    }

    /**
     * A location sent once: the coordinates travel as the text of the message (sealed like any text), and nothing
     * follows afterwards. The chat list only gets the pin emoji.
     */
    fun sendLocation(chatId: String, me: Profile, others: List<String>, coords: String, groupTitle: String = "") {
        val chat = db.collection("chats").document(chatId)
        val msg = chat.collection("messages").document()
        val batch = db.batch()
        val seal = E2E.begin(chatId, msg.id, me.uid, others)
        val key = seal?.key
        val body = hashMapOf<String, Any>(
            "from" to me.uid,
            "text" to (if (key != null) E2E.sealText(key, chatId, me.uid, "t", coords) else coords),
            "kind" to "loc",
            "ts" to FieldValue.serverTimestamp(),
        )
        if (seal != null) markSealed(body, seal)
        batch.set(msg, body)
        val preview = lastOf("loc", "")
        batch.update(
            chat,
            bump(
                hashMapOf<String, Any>(
                    "lastText" to preview,
                    "lastEnc" to false,
                    "lastW" to FieldValue.delete(),
                    "lastKind" to "loc",
                    "lastTs" to System.currentTimeMillis(),
                    "lastFrom" to me.uid,
                ),
                others,
            ),
        )
        batch.commit().addOnSuccessListener {
            if (others.isNotEmpty()) pushMsg(chatId, me, groupTitle, preview, key != null)
        }
    }

    /** What [sendSos] tells the caller: the id of the card in that chat, whether it was sealed, and whether the server has it already. */
    class SosSent(val msgId: String, val sealed: Boolean, val confirmed: Boolean)

    /**
     * An SOS card: a message of kind "sos" whose sealed text holds the places (see SosCodec). The chat list only gets the 🆘 sign.
     * The push carries the word "SOS" and nothing else (never the places). Returns null when the write failed; when the phone is
     * offline the write waits in Firestore's own queue and goes out when the network is back (confirmed = false then).
     */
    suspend fun sendSos(chatId: String, me: Profile, others: List<String>, payload: String): SosSent? {
        val chat = db.collection("chats").document(chatId)
        val msg = chat.collection("messages").document()
        val batch = db.batch()
        val seal = E2E.begin(chatId, msg.id, me.uid, others)
        val key = seal?.key
        val body = hashMapOf<String, Any>(
            "from" to me.uid,
            "text" to (if (key != null) E2E.sealText(key, chatId, me.uid, "t", payload) else payload),
            "kind" to "sos",
            "ts" to FieldValue.serverTimestamp(),
        )
        if (seal != null) markSealed(body, seal)
        batch.set(msg, body)
        batch.update(
            chat,
            bump(
                hashMapOf<String, Any>(
                    "lastText" to lastOf("sos", ""),
                    "lastEnc" to false,
                    "lastW" to FieldValue.delete(),
                    "lastKind" to "sos",
                    "lastTs" to System.currentTimeMillis(),
                    "lastFrom" to me.uid,
                ),
                others,
            ),
        )
        val commit = batch.commit()
        val done = try {
            withTimeoutOrNull(15_000L) { commit.await() } != null || commit.isSuccessful
        } catch (e: Exception) {
            return null
        }
        if (done && others.isNotEmpty()) am.khosq.app.push.PushClient.notify(chatId, "msg", me.name, "🆘 SOS")
        return SosSent(msg.id, key != null, done)
    }

    /** Replaces the places on an SOS card I sent earlier. [sealed] is what [sendSos] said about that card. */
    fun updateSos(chatId: String, msgId: String, payload: String, sealed: Boolean) {
        editMessage(chatId, msgId, payload, false, sealed)
    }

    /**
     * A poll: the question and the options travel as the sealed text of a message of kind "poll". The chat list only
     * gets the chart emoji. Votes are added later, one field per voter (see [vote]).
     */
    fun sendPoll(chatId: String, me: Profile, others: List<String>, poll: Poll, groupTitle: String = "") {
        val chat = db.collection("chats").document(chatId)
        val msg = chat.collection("messages").document()
        val batch = db.batch()
        val seal = E2E.begin(chatId, msg.id, me.uid, others)
        val key = seal?.key
        val json = PollCodec.encode(poll)
        val body = hashMapOf<String, Any>(
            "from" to me.uid,
            "text" to (if (key != null) E2E.sealText(key, chatId, me.uid, "t", json) else json),
            "kind" to "poll",
            "ts" to FieldValue.serverTimestamp(),
        )
        if (seal != null) markSealed(body, seal)
        batch.set(msg, body)
        val preview = lastOf("poll", "")
        batch.update(
            chat,
            bump(
                hashMapOf<String, Any>(
                    "lastText" to preview,
                    "lastEnc" to false,
                    "lastW" to FieldValue.delete(),
                    "lastKind" to "poll",
                    "lastTs" to System.currentTimeMillis(),
                    "lastFrom" to me.uid,
                ),
                others,
            ),
        )
        batch.commit().addOnSuccessListener {
            if (others.isNotEmpty()) pushMsg(chatId, me, groupTitle, preview, key != null)
        }
    }

    /** Casts my vote (option = null takes it back). The vote is sealed with the poll's own key, so only the chat can read it. */
    suspend fun vote(chatId: String, m: Message, uid: String, option: Int?) {
        val ref = db.collection("chats").document(chatId).collection("messages").document(m.id)
        if (option == null) {
            ref.update("votes.$uid", FieldValue.delete())
            return
        }
        var stored = option.toString()
        if (m.enc) {
            val key = try {
                if (E2E.isGroup(chatId, uid)) E2E.groupKey(chatId, m.from, uid, m.wraps) else E2E.keyFor(chatId, uid)
            } catch (e: Exception) {
                null
            }
            if (key == null) return
            stored = E2E.sealText(key, chatId, uid, "v", stored)
        }
        ref.update("votes.$uid", stored)
    }

    fun setTyping(chatId: String, uid: String, on: Boolean) {
        db.collection("chats").document(chatId).update("typing." + uid, if (on) System.currentTimeMillis() else 0L)
    }

    fun touchSeen(uid: String) {
        db.collection("users").document(uid).set(hashMapOf<String, Any>("lastSeen" to FieldValue.serverTimestamp()), SetOptions.merge())
    }

    /** Last seen (0 when the person hides it), the "about" line and the hide switch of one person. */
    fun userInfo(uid: String): Flow<UserInfo> = callbackFlow {
        val reg = db.collection("users").document(uid).addSnapshotListener { d, err ->
            if (err != null) {
                close(err)
                return@addSnapshotListener
            }
            val hide = d?.getBoolean("hideSeen") ?: false
            val t = d?.getTimestamp("lastSeen", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            trySend(UserInfo(if (hide) 0L else (t?.toDate()?.time ?: 0L), d?.getString("about") ?: "", hide))
        }
        awaitClose { reg.remove() }
    }

    fun react(chatId: String, msgId: String, uid: String, emoji: String?) {
        db.collection("chats").document(chatId).collection("messages").document(msgId)
            .update("reactions." + uid, emoji ?: FieldValue.delete())
    }

    fun deleteForAll(chatId: String, msgId: String) {
        db.collection("chats").document(chatId).collection("messages").document(msgId).update(
            mapOf(
                "deleted" to true,
                "text" to "",
                "data" to FieldValue.delete(),
                "reply" to FieldValue.delete(),
            ),
        )
    }

    private fun bump(base: HashMap<String, Any>, others: List<String>): HashMap<String, Any> {
        for (o in others) base["unread." + o] = FieldValue.increment(1L)
        return base
    }

    fun send(
        chatId: String,
        me: Profile,
        others: List<String>,
        text: String,
        reply: Message? = null,
        groupTitle: String = "",
    ) {
        val chat = db.collection("chats").document(chatId)
        val msg = chat.collection("messages").document()
        val batch = db.batch()
        // a chat whose other side(s) have a key is sent end-to-end; the server only gets sealed text
        val seal = E2E.begin(chatId, msg.id, me.uid, others)
        val key = seal?.key
        val body = hashMapOf<String, Any>(
            "from" to me.uid,
            "text" to (if (key != null) E2E.sealText(key, chatId, me.uid, "t", text) else text),
            "ts" to FieldValue.serverTimestamp(),
        )
        if (seal != null) markSealed(body, seal)
        if (reply != null) {
            val quote = replyOf(reply)
            body["reply"] = if (key != null) E2E.sealText(key, chatId, me.uid, "r", quote) else quote
            body["replyFrom"] = reply.from
        }
        batch.set(msg, body)
        batch.update(
            chat,
            bump(
                hashMapOf<String, Any>(
                    "lastText" to (if (key != null) E2E.sealText(key, chatId, me.uid, "l", text) else text),
                    "lastEnc" to (key != null),
                    "lastW" to (seal?.wraps ?: FieldValue.delete()),
                    "lastKind" to "text",
                    "lastTs" to System.currentTimeMillis(),
                    "lastFrom" to me.uid,
                ),
                others,
            ),
        )
        batch.commit().addOnSuccessListener {
            if (others.isNotEmpty()) pushMsg(chatId, me, groupTitle, text, key != null)
        }
    }

    /** Marks a message as end-to-end encrypted; in a group the wrapped message keys travel with it. */
    private fun markSealed(body: HashMap<String, Any>, seal: E2E.Seal) {
        body["e"] = true
        val w = seal.wraps
        if (w != null) body["w"] = w
    }

    /**
     * Changes the text of my own message. If it is the last message of the chat, the chat list changes too.
     * [enc] says whether the message was sent encrypted: an encrypted message stays encrypted.
     */
    fun editMessage(chatId: String, msgId: String, text: String, isLast: Boolean, enc: Boolean = false) {
        val me = auth.currentUser?.uid ?: return
        val key = if (enc) E2E.sentKey(chatId, msgId, me) else null
        if (enc && key == null) return
        val chat = db.collection("chats").document(chatId)
        val b = db.batch()
        val sealed = if (key != null) E2E.sealText(key, chatId, me, "t", text) else text
        b.update(chat.collection("messages").document(msgId), hashMapOf<String, Any>("text" to sealed, "edited" to true))
        if (isLast) {
            // the message key stays the same, so the wraps already in the chat document still open the new preview
            b.update(
                chat,
                hashMapOf<String, Any>(
                    "lastText" to (if (key != null) E2E.sealText(key, chatId, me, "l", text) else text),
                    "lastEnc" to (key != null),
                    "lastKind" to "text",
                ),
            )
        }
        b.commit()
    }

    /** The push carries no text for encrypted messages: Google and the relay never see them. */
    private fun pushMsg(chatId: String, me: Profile, groupTitle: String, text: String, enc: Boolean = false) {
        if (groupTitle.isEmpty()) {
            am.khosq.app.push.PushClient.notify(chatId, "msg", me.name, if (enc) "" else text)
        } else {
            am.khosq.app.push.PushClient.notify(chatId, "msg", groupTitle, if (enc) "" else me.name + ": " + text)
        }
    }

    /** Photos and voice notes are small (compressed on the phone) and live inside the message itself. */
    fun sendMedia(chatId: String, me: Profile, others: List<String>, kind: String, bytes: ByteArray, dur: Int, groupTitle: String = "", wave: String = "") {
        val preview = lastOf(kind, "")
        val chat = db.collection("chats").document(chatId)
        val msg = chat.collection("messages").document()
        val batch = db.batch()
        val seal = E2E.begin(chatId, msg.id, me.uid, others)
        val key = seal?.key
        val body = hashMapOf<String, Any>(
            "from" to me.uid,
            "text" to "",
            "kind" to kind,
            "dur" to dur.toLong(),
            "data" to Blob.fromBytes(if (key != null) E2E.sealBytes(key, chatId, me.uid, "d", bytes) else bytes),
            "ts" to FieldValue.serverTimestamp(),
        )
        if (seal != null) markSealed(body, seal)
        if (wave.isNotEmpty()) body["wave"] = wave
        batch.set(msg, body)
        batch.update(
            chat,
            bump(
                hashMapOf<String, Any>(
                    "lastText" to preview,
                    "lastEnc" to false,
                    "lastW" to FieldValue.delete(),
                    "lastKind" to kind,
                    "lastTs" to System.currentTimeMillis(),
                    "lastFrom" to me.uid,
                ),
                others,
            ),
        )
        batch.commit().addOnSuccessListener {
            if (others.isNotEmpty()) pushMsg(chatId, me, groupTitle, preview, key != null)
        }
    }

    /** Sends a copy of a message into another chat, marked as forwarded. */
    fun forward(m: Message, target: Chat, me: Profile, others: List<String>) {
        val chat = db.collection("chats").document(target.id)
        val msg = chat.collection("messages").document()
        val seal = E2E.begin(target.id, msg.id, me.uid, others)
        val key = seal?.key
        val body = hashMapOf<String, Any>(
            "from" to me.uid,
            "text" to (if (key != null && m.text.isNotEmpty()) E2E.sealText(key, target.id, me.uid, "t", m.text) else m.text),
            "kind" to m.kind,
            "dur" to m.dur.toLong(),
            "fwd" to true,
            "ts" to FieldValue.serverTimestamp(),
        )
        if (seal != null) markSealed(body, seal)
        val bytes = m.data
        if (bytes != null) body["data"] = Blob.fromBytes(if (key != null) E2E.sealBytes(key, target.id, me.uid, "d", bytes) else bytes)
        if (m.wave.isNotEmpty()) body["wave"] = m.wave
        val prev = lastOf(m.kind, m.text)
        val prevIsText = m.kind != "img" && m.kind != "voice" && m.kind != "loc" && m.kind != "poll"
        val sealedPrev = key != null && prevIsText && prev.isNotEmpty()
        val b = db.batch()
        b.set(msg, body)
        b.update(
            chat,
            bump(
                hashMapOf<String, Any>(
                    "lastText" to (if (sealedPrev) E2E.sealText(key!!, target.id, me.uid, "l", prev) else prev),
                    "lastEnc" to sealedPrev,
                    "lastW" to (if (sealedPrev) (seal?.wraps ?: FieldValue.delete()) else FieldValue.delete()),
                    "lastKind" to (if (m.kind == "img" || m.kind == "voice" || m.kind == "loc" || m.kind == "poll") m.kind else "text"),
                    "lastTs" to System.currentTimeMillis(),
                    "lastFrom" to me.uid,
                ),
                others,
            ),
        )
        b.commit().addOnSuccessListener {
            if (others.isNotEmpty()) pushMsg(target.id, me, if (target.isGroup) target.peerName else "", prev, key != null)
        }
    }

    /* ---------- groups ---------- */

    /** A line like "Ani added Gor". It stores a code, and every phone writes it in its own language. */
    private fun sysLine(from: String, code: String, arg: String = ""): Map<String, Any> =
        hashMapOf<String, Any>(
            "from" to from,
            "text" to "",
            "kind" to "sys",
            "code" to code,
            "arg" to arg,
            "ts" to FieldValue.serverTimestamp(),
        )

    /** Makes a new group chat. Members are my accepted contacts; I become its admin. */
    suspend fun createGroup(me: Profile, title: String, picks: List<Chat>): String {
        val ref = db.collection("chats").document()
        val names = hashMapOf<String, Any>(me.uid to me.name)
        val handles = hashMapOf<String, Any>(me.uid to me.handle)
        for (c in picks) {
            names[c.peerUid] = c.peerName
            handles[c.peerUid] = c.peerHandle
        }
        val data = hashMapOf<String, Any>(
            "group" to true,
            "title" to title,
            "members" to (listOf(me.uid) + picks.map { c -> c.peerUid }),
            "admins" to listOf(me.uid),
            "pv" to mapOf(me.uid to me.pv),
            "names" to names,
            "handles" to handles,
            "status" to "active",
            "lastText" to "",
            "lastTs" to System.currentTimeMillis(),
            "lastFrom" to me.uid,
        )
        ref.set(data).await()
        ref.collection("messages").add(sysLine(me.uid, "grp_created")).await()
        return ref.id
    }

    fun addMembers(chatId: String, me: Profile, picks: List<Chat>) {
        if (picks.isEmpty()) return
        val ref = db.collection("chats").document(chatId)
        val upd = hashMapOf<String, Any>(
            "members" to FieldValue.arrayUnion(*picks.map { c -> c.peerUid }.toTypedArray()),
        )
        for (c in picks) {
            upd["names." + c.peerUid] = c.peerName
            upd["handles." + c.peerUid] = c.peerHandle
        }
        val b = db.batch()
        b.update(ref, upd)
        b.set(
            ref.collection("messages").document(),
            sysLine(me.uid, "grp_added", picks.joinToString(", ") { c -> c.peerName }),
        )
        b.commit()
    }

    fun removeMember(chatId: String, me: Profile, uid: String, name: String) {
        val ref = db.collection("chats").document(chatId)
        val b = db.batch()
        b.update(ref, "members", FieldValue.arrayRemove(uid), "admins", FieldValue.arrayRemove(uid))
        b.set(ref.collection("messages").document(), sysLine(me.uid, "grp_removed", name))
        b.commit()
    }

    fun renameGroup(chatId: String, me: Profile, title: String) {
        val ref = db.collection("chats").document(chatId)
        val b = db.batch()
        b.update(ref, "title", title)
        b.set(ref.collection("messages").document(), sysLine(me.uid, "grp_renamed", title))
        b.commit()
    }

    /** The group's picture lives in photos/{chatId}; gpv in the chat says which version is the newest. */
    suspend fun setGroupPhoto(chatId: String, me: Profile, bytes: ByteArray, ver: Long) {
        db.collection("photos").document(chatId).set(hashMapOf<String, Any>("data" to Blob.fromBytes(bytes), "ts" to ver)).await()
        val ref = db.collection("chats").document(chatId)
        val b = db.batch()
        b.update(ref, "gpv", ver)
        b.set(ref.collection("messages").document(), sysLine(me.uid, "grp_photo"))
        b.commit().await()
    }

    suspend fun removeGroupPhoto(chatId: String) {
        db.collection("photos").document(chatId).delete().await()
        db.collection("chats").document(chatId).update("gpv", 0L).await()
    }

    fun setGroupDesc(chatId: String, me: Profile, text: String) {
        val ref = db.collection("chats").document(chatId)
        val b = db.batch()
        b.update(ref, "desc", text)
        b.set(ref.collection("messages").document(), sysLine(me.uid, "grp_desc"))
        b.commit()
    }

    /** Announcement mode: only the admins may write. The rules enforce it, the screen only hides the input. */
    fun setAdminOnly(chatId: String, me: Profile, on: Boolean) {
        val ref = db.collection("chats").document(chatId)
        val b = db.batch()
        b.update(ref, "sendMode", if (on) "admins" else "all")
        b.set(ref.collection("messages").document(), sysLine(me.uid, if (on) "grp_mode_admins" else "grp_mode_all"))
        b.commit()
    }

    /** Makes a member an admin, or takes the role away again. */
    fun setAdmin(chatId: String, me: Profile, uid: String, name: String, on: Boolean) {
        val ref = db.collection("chats").document(chatId)
        val b = db.batch()
        b.update(ref, "admins", if (on) FieldValue.arrayUnion(uid) else FieldValue.arrayRemove(uid))
        b.set(ref.collection("messages").document(), sysLine(me.uid, if (on) "grp_admin_on" else "grp_admin_off", name))
        b.commit()
    }

    fun leaveGroup(chat: Chat, me: Profile) {
        val ref = db.collection("chats").document(chat.id)
        val rest = chat.members.filter { m -> m != me.uid }
        val upd = hashMapOf<String, Any>("members" to FieldValue.arrayRemove(me.uid))
        if (me.uid in chat.admins) {
            upd["admins"] = chat.admins.filter { a -> a != me.uid }.ifEmpty { rest.take(1) }
        }
        val b = db.batch()
        b.set(ref.collection("messages").document(), sysLine(me.uid, "grp_left"))
        b.update(ref, upd)
        b.commit()
    }

    /* ---------- stories: 24-hour posts for the people in my chats ---------- */

    fun storiesForMe(uid: String): Flow<List<Story>> =
        storyQuery(db.collection("stories").whereArrayContains("audience", uid))

    fun myStories(uid: String): Flow<List<Story>> =
        storyQuery(db.collection("stories").whereEqualTo("owner", uid))

    private fun storyQuery(q: Query): Flow<List<Story>> = callbackFlow {
        val reg = q.addSnapshotListener { snap, err ->
            if (err != null) {
                close(err)
                return@addSnapshotListener
            }
            if (snap == null) return@addSnapshotListener
            trySend(snap.documents.mapNotNull { toStory(it) })
        }
        awaitClose { reg.remove() }
    }

    private fun toStory(d: DocumentSnapshot): Story? {
        val owner = d.getString("owner") ?: return null
        val views = (d.get("viewers") as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        return Story(
            id = d.id,
            owner = owner,
            ownerName = d.getString("ownerName") ?: "",
            kind = d.getString("kind") ?: "txt",
            text = d.getString("text") ?: "",
            bg = (d.getLong("bg") ?: 0L).toInt(),
            data = d.getBlob("data")?.toBytes(),
            ts = d.getLong("ts") ?: 0L,
            exp = d.getLong("exp") ?: 0L,
            views = views,
        )
    }

    fun postStory(me: Profile, audience: List<String>, kind: String, text: String, bg: Int, bytes: ByteArray?) {
        val now = System.currentTimeMillis()
        val data = hashMapOf<String, Any>(
            "owner" to me.uid,
            "ownerName" to me.name,
            "kind" to kind,
            "text" to text,
            "bg" to bg.toLong(),
            "ts" to now,
            "exp" to now + 24L * 3600L * 1000L,
            "audience" to audience,
            "viewers" to emptyList<String>(),
        )
        if (bytes != null) data["data"] = Blob.fromBytes(bytes)
        db.collection("stories").add(data)
    }

    fun markStoryViewed(id: String, uid: String) {
        db.collection("stories").document(id).update("viewers", FieldValue.arrayUnion(uid))
    }

    fun deleteStory(id: String) {
        db.collection("stories").document(id).delete()
    }

    fun markRead(chatId: String, uid: String) {
        db.collection("chats").document(chatId).update(
            hashMapOf<String, Any>(
                "unread." + uid to 0L,
                "read." + uid to System.currentTimeMillis(),
            ),
        )
    }
}

/**
 * Tells the sender that this phone has received the newest message of a chat (the grey double tick).
 * Both the open app and the background service call it, so it writes at most once per new message.
 */
object Delivery {
    private val done = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun mark(chatId: String, me: String, lastTs: Long, lastFrom: String, myStamp: Long) {
        if (lastFrom.isEmpty() || lastFrom == me || lastTs <= 0L || lastTs <= myStamp) return
        val prev = done[chatId] ?: 0L
        if (lastTs <= prev) return
        done[chatId] = lastTs
        FirebaseFirestore.getInstance().collection("chats").document(chatId)
            .update("dlv." + me, maxOf(System.currentTimeMillis(), lastTs))
    }
}

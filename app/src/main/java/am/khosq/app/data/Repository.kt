package am.khosq.app.data

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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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

    fun chats(uid: String): Flow<List<Chat>> = callbackFlow {
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
        )
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

    fun messages(chatId: String): Flow<List<Message>> = callbackFlow {
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
        else -> m.text.take(80)
    }

    /** Short text kept in the chat document (older app versions show it as it is). Language-neutral kind goes to lastKind. */
    private fun lastOf(kind: String, text: String): String = when (kind) {
        "img" -> "📷"
        "voice" -> "🎤"
        else -> text.take(80)
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
        val body = hashMapOf<String, Any>("from" to me.uid, "text" to text, "ts" to FieldValue.serverTimestamp())
        if (reply != null) {
            body["reply"] = replyOf(reply)
            body["replyFrom"] = reply.from
        }
        batch.set(msg, body)
        batch.update(
            chat,
            bump(
                hashMapOf<String, Any>(
                    "lastText" to text,
                    "lastKind" to "text",
                    "lastTs" to System.currentTimeMillis(),
                    "lastFrom" to me.uid,
                ),
                others,
            ),
        )
        batch.commit().addOnSuccessListener {
            pushMsg(chatId, me, groupTitle, text)
        }
    }

    /** Changes the text of my own message. If it is the last message of the chat, the chat list changes too. */
    fun editMessage(chatId: String, msgId: String, text: String, isLast: Boolean) {
        val chat = db.collection("chats").document(chatId)
        val b = db.batch()
        b.update(chat.collection("messages").document(msgId), hashMapOf<String, Any>("text" to text, "edited" to true))
        if (isLast) b.update(chat, hashMapOf<String, Any>("lastText" to text, "lastKind" to "text"))
        b.commit()
    }

    private fun pushMsg(chatId: String, me: Profile, groupTitle: String, text: String) {
        if (groupTitle.isEmpty()) am.khosq.app.push.PushClient.notify(chatId, "msg", me.name, text)
        else am.khosq.app.push.PushClient.notify(chatId, "msg", groupTitle, me.name + ": " + text)
    }

    /** Photos and voice notes are small (compressed on the phone) and live inside the message itself. */
    fun sendMedia(chatId: String, me: Profile, others: List<String>, kind: String, bytes: ByteArray, dur: Int, groupTitle: String = "") {
        val preview = lastOf(kind, "")
        val chat = db.collection("chats").document(chatId)
        val msg = chat.collection("messages").document()
        val batch = db.batch()
        batch.set(
            msg,
            hashMapOf<String, Any>(
                "from" to me.uid,
                "text" to "",
                "kind" to kind,
                "dur" to dur.toLong(),
                "data" to Blob.fromBytes(bytes),
                "ts" to FieldValue.serverTimestamp(),
            ),
        )
        batch.update(
            chat,
            bump(
                hashMapOf<String, Any>(
                    "lastText" to preview,
                    "lastKind" to kind,
                    "lastTs" to System.currentTimeMillis(),
                    "lastFrom" to me.uid,
                ),
                others,
            ),
        )
        batch.commit().addOnSuccessListener {
            pushMsg(chatId, me, groupTitle, preview)
        }
    }

    /** Sends a copy of a message into another chat, marked as forwarded. */
    fun forward(m: Message, target: Chat, me: Profile, others: List<String>) {
        val chat = db.collection("chats").document(target.id)
        val msg = chat.collection("messages").document()
        val body = hashMapOf<String, Any>(
            "from" to me.uid,
            "text" to m.text,
            "kind" to m.kind,
            "dur" to m.dur.toLong(),
            "fwd" to true,
            "ts" to FieldValue.serverTimestamp(),
        )
        val bytes = m.data
        if (bytes != null) body["data"] = Blob.fromBytes(bytes)
        val prev = lastOf(m.kind, m.text)
        val b = db.batch()
        b.set(msg, body)
        b.update(
            chat,
            bump(
                hashMapOf<String, Any>(
                    "lastText" to prev,
                    "lastKind" to (if (m.kind == "img" || m.kind == "voice") m.kind else "text"),
                    "lastTs" to System.currentTimeMillis(),
                    "lastFrom" to me.uid,
                ),
                others,
            ),
        )
        b.commit().addOnSuccessListener {
            pushMsg(target.id, me, if (target.isGroup) target.peerName else "", prev)
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

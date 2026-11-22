package am.khosq.app.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
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
        return result.user?.uid ?: throw IllegalStateException("Մուտքը չհաջողվեց")
    }

    suspend fun loadProfile(uid: String): Profile? {
        val d = db.collection("users").document(uid).get().await()
        if (!d.exists()) return null
        val handle = d.getString("handle") ?: return null
        return Profile(uid, handle, d.getString("name") ?: handle)
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
        val data = hashMapOf<String, Any>(
            "members" to listOf(me.uid, peer.uid),
            "names" to mapOf(me.uid to me.name, peer.uid to peer.name),
            "handles" to mapOf(me.uid to me.handle, peer.uid to peer.handle),
        )
        db.collection("chats").document(id).set(data, SetOptions.merge()).await()
        return id
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

    private fun toChat(d: DocumentSnapshot, me: String): Chat? {
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
                    )
                }
                trySend(list)
            }
        awaitClose { reg.remove() }
    }

    /** Works offline too: Firestore keeps the write and sends it when the network is back. */
    fun send(chatId: String, me: Profile, peerUid: String, text: String) {
        val chat = db.collection("chats").document(chatId)
        val msg = chat.collection("messages").document()
        val batch = db.batch()
        batch.set(
            msg,
            hashMapOf<String, Any>("from" to me.uid, "text" to text, "ts" to FieldValue.serverTimestamp()),
        )
        batch.update(
            chat,
            hashMapOf<String, Any>(
                "lastText" to text,
                "lastTs" to System.currentTimeMillis(),
                "lastFrom" to me.uid,
                "unread." + peerUid to FieldValue.increment(1L),
            ),
        )
        batch.commit()
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

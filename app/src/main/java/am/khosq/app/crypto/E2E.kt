package am.khosq.app.crypto

import am.khosq.app.Prefs
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption for one-to-one chats, groups and the private spaces (saved messages, stars).
 *
 * Every phone makes a key pair once (P-256). The public half is published in users/{uid}/pk, the private half never
 * leaves the phone: it is stored wrapped by a key that lives in the Android Keystore. For a chat, both phones combine
 * their own private key with the other public key and get the same secret (ECDH), from which the message key is made
 * (HKDF-SHA256). Messages are sealed with AES-256-GCM, bound to the chat, the sender and the field they belong to.
 * The server only ever sees sealed text.
 *
 * Groups: every message gets its own random-looking key (made from my secret and the message id). The message key is
 * wrapped once for every member, with the key that I share with that member (ECDH again), and the wraps travel inside
 * the message. A member who joined later, or who was removed, simply has no wrap for the messages he must not read.
 */
object E2E {
    private const val WRAP_ALIAS = "khosq_e2e_wrap"
    private const val P_SK = "e2e_sk"
    private const val P_PK = "e2e_pk"
    private val random = SecureRandom()

    private class Mine(val priv: PrivateKey, val pub: PublicKey, val pubText: String)

    private val mine: Mine by lazy { loadOrCreate() }

    /** Message keys of the chats opened so far (chat id -> key). */
    private val keys = ConcurrentHashMap<String, ByteArray>()
    private val pubs = ConcurrentHashMap<String, PublicKey>()
    private val missUntil = ConcurrentHashMap<String, Long>()

    // ------------------------------------------------------------------ my own keys

    /** My public key as text, the form in which it is published. */
    fun myPublic(): String = mine.pubText

    private fun loadOrCreate(): Mine {
        val sk = Prefs.getString(P_SK)
        val pk = Prefs.getString(P_PK)
        if (sk.isNotEmpty() && pk.isNotEmpty()) {
            try {
                val priv = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(unwrap(decode(sk))))
                val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decode(pk)))
                return Mine(priv, pub, pk)
            } catch (e: Exception) {
                // the wrapping key is gone (the phone was reset): a new identity is made below
            }
        }
        val gen = KeyPairGenerator.getInstance("EC")
        gen.initialize(ECGenParameterSpec("secp256r1"))
        val pair = gen.generateKeyPair()
        val pubText = encode(pair.public.encoded)
        Prefs.putStringSync(P_SK, encode(wrap(pair.private.encoded)))
        Prefs.putStringSync(P_PK, pubText)
        return Mine(pair.private, pair.public, pubText)
    }

    private fun wrapKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        val have = store.getKey(WRAP_ALIAS, null)
        if (have is SecretKey) return have
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun wrap(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wrapKey())
        return c.iv + c.doFinal(plain)
    }

    private fun unwrap(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, wrapKey(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return c.doFinal(blob, 12, blob.size - 12)
    }

    // ------------------------------------------------------------------ keys of the chats

    /** The other member of a one-to-one chat, myself for "saved messages", null for groups (they have their own keys, below). */
    private fun peerOf(chatId: String, myUid: String): String? {
        if (chatId == "saved$myUid") return myUid
        val p = chatId.split("_")
        if (p.size != 2 || myUid !in p) return null
        return if (p[0] == myUid) p[1] else p[0]
    }

    /** The key of a one-to-one chat if it is ready right now (no waiting); null for groups and for chats whose other side has no key. */
    fun ready(chatId: String): ByteArray? = keys[chatId]

    /** The key of a chat; fetches the other person's public key the first time. Null when the chat cannot be encrypted. */
    suspend fun keyFor(chatId: String, myUid: String): ByteArray? {
        keys[chatId]?.let { return it }
        val peer = peerOf(chatId, myUid) ?: return null
        if (peer == myUid) {
            val k = selfKey()
            keys[chatId] = k
            return k
        }
        val pub = peerPublic(peer) ?: return null
        val k = derive(pub, chatId)
        keys[chatId] = k
        return k
    }

    private val selfK: ByteArray by lazy { derive(mine.pub, "self") }

    private fun selfKey(): ByteArray = selfK

    private fun derive(peerPub: PublicKey, salt: String): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(mine.priv)
        agreement.doPhase(peerPub, true)
        return hkdf(agreement.generateSecret(), salt.toByteArray(Charsets.UTF_8), "khosq-e2e-v1".toByteArray(Charsets.UTF_8))
    }

    private fun hkdf(secret: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        val prk = mac.doFinal(secret)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        mac.update(info)
        mac.update(1.toByte())
        return mac.doFinal()
    }

    // ------------------------------------------------------------------ groups

    /** Keys I share with single people (person id -> key). A group message key is wrapped with them, one wrap per member. */
    private val pairKeys = ConcurrentHashMap<String, ByteArray>()

    /** What a new message is sealed with. [wraps] is set only for groups: the message key, wrapped for every member. */
    class Seal(val key: ByteArray, val wraps: Map<String, String>?)

    /** True for a group chat: anything that is neither a one-to-one chat nor my saved messages. */
    fun isGroup(chatId: String, myUid: String): Boolean = peerOf(chatId, myUid) == null

    private suspend fun pairKey(person: String, myUid: String): ByteArray? {
        pairKeys[person]?.let { return it }
        val k = if (person == myUid) {
            derive(mine.pub, "wrap|self")
        } else {
            val pub = peerPublic(person) ?: return null
            val a = if (person < myUid) person else myUid
            val b = if (person < myUid) myUid else person
            derive(pub, "wrap|$a|$b")
        }
        pairKeys[person] = k
        return k
    }

    /** Gets the keys of all the members ready. Returns the people whose app has no key yet (empty = the group can be encrypted). */
    suspend fun prepareGroup(members: List<String>, myUid: String): List<String> {
        val missing = ArrayList<String>()
        for (m in (members + myUid).distinct()) {
            if (pairKey(m, myUid) == null) missing.add(m)
        }
        return missing
    }

    /** True when a sealed chat-list preview can be opened right now, without waiting for a key. */
    fun previewReady(chatId: String, myUid: String, from: String): Boolean =
        if (isGroup(chatId, myUid)) pairKeys.containsKey(from) else keys.containsKey(chatId)

    private fun wrapAad(chatId: String, from: String, to: String): ByteArray =
        "w|$chatId|$from|$to".toByteArray(Charsets.UTF_8)

    /** The key of a group message: made from my own secret and the message id, so I can make it again when I edit the message. */
    private fun messageKey(chatId: String, msgId: String): ByteArray =
        hkdf(selfKey(), "$chatId|$msgId".toByteArray(Charsets.UTF_8), "khosq-msg-v1".toByteArray(Charsets.UTF_8))

    /**
     * Starts sealing a new message. One-to-one chats and saved messages use the chat key. A group gets a message key
     * wrapped for every member (me included). Null when the chat cannot be encrypted right now, so the message goes plain.
     */
    fun begin(chatId: String, msgId: String, myUid: String, others: List<String>): Seal? {
        if (!isGroup(chatId, myUid)) {
            val k = keys[chatId] ?: return null
            return Seal(k, null)
        }
        val ck = messageKey(chatId, msgId)
        val wraps = HashMap<String, String>()
        for (m in (others + myUid).distinct()) {
            val pk = pairKeys[m] ?: return null
            wraps[m] = encode(seal(pk, wrapAad(chatId, myUid, m), ck))
        }
        return Seal(ck, wraps)
    }

    /** The key of a message I sent myself, to change its text later. */
    fun sentKey(chatId: String, msgId: String, myUid: String): ByteArray? =
        if (isGroup(chatId, myUid)) messageKey(chatId, msgId) else keys[chatId]

    /** The key of a group message that [from] sent, taken out of the wrap made for me. */
    suspend fun groupKey(chatId: String, from: String, myUid: String, wraps: Map<String, String>): ByteArray? {
        val myWrap = wraps[myUid] ?: return null
        val pk = pairKey(from, myUid) ?: return null
        val raw = try {
            decode(myWrap)
        } catch (e: Exception) {
            return null
        }
        return open(pk, wrapAad(chatId, from, myUid), raw)
    }

    /** Opens the chat-list preview (the last message) of any kind of chat. */
    suspend fun openLast(chatId: String, myUid: String, from: String, sealed: String, wraps: Map<String, String>): String? {
        val key = if (isGroup(chatId, myUid)) groupKey(chatId, from, myUid, wraps) else keyFor(chatId, myUid)
        return if (key == null) null else openText(key, chatId, from, "l", sealed)
    }

    // ------------------------------------------------------------------ other people's public keys

    private fun decodePublic(text: String): PublicKey? = try {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decode(text)))
    } catch (e: Exception) {
        null
    }

    private suspend fun fetchPublic(peer: String): String? = try {
        FirebaseFirestore.getInstance().collection("users").document(peer).get().await().getString("pk")
    } catch (e: Exception) {
        null
    }

    /** A person's public key: remembered on this phone the first time it is seen, so a later swap on the server shows up. */
    private suspend fun peerPublic(peer: String): PublicKey? {
        pubs[peer]?.let { return it }
        val pinned = Prefs.getString("pk.$peer")
        if (pinned.isNotEmpty()) {
            val k = decodePublic(pinned)
            if (k != null) {
                pubs[peer] = k
                return k
            }
        }
        if (System.currentTimeMillis() < (missUntil[peer] ?: 0L)) return null
        val fetched = fetchPublic(peer)
        val k = if (fetched == null) null else decodePublic(fetched)
        if (fetched == null || k == null) {
            // the person has no key yet (an old version of the app): ask again in a minute, not on every message
            missUntil[peer] = System.currentTimeMillis() + 60_000L
            return null
        }
        Prefs.putStringSync("pk.$peer", fetched)
        pubs[peer] = k
        return k
    }

    /** Looks at the server's copy of a person's key. If it differs from the one remembered, the change is flagged. */
    suspend fun refreshPeer(peer: String, myUid: String) {
        if (peer == myUid) return
        val fetched = fetchPublic(peer) ?: return
        val pinned = Prefs.getString("pk.$peer")
        if (pinned == fetched) return
        val k = decodePublic(fetched) ?: return
        Prefs.putStringSync("pk.$peer", fetched)
        pubs[peer] = k
        missUntil.remove(peer)
        for (id in keys.keys.toList()) if (id.contains(peer)) keys.remove(id)
        pairKeys.remove(peer)
        if (pinned.isNotEmpty()) Prefs.putStringSync("pkchg.$peer", "1")
    }

    /** True when the person's key changed since it was first seen and nobody has looked at the safety number yet. */
    fun keyChanged(peer: String): Boolean = Prefs.getString("pkchg.$peer") == "1"

    fun acknowledge(peer: String) {
        Prefs.putStringSync("pkchg.$peer", "")
    }

    /** A short number both phones compute from the two public keys. If it is the same on both, nobody stands between them. */
    suspend fun safetyNumber(peer: String): String? {
        if (peerPublic(peer) == null) return null
        val theirs = Prefs.getString("pk.$peer")
        val a = mine.pubText
        val first = if (a <= theirs) a else theirs
        val second = if (a <= theirs) theirs else a
        val md = MessageDigest.getInstance("SHA-256")
        md.update(first.toByteArray(Charsets.UTF_8))
        md.update(second.toByteArray(Charsets.UTF_8))
        val h = md.digest()
        val hex = StringBuilder()
        for (i in 0 until 16) hex.append(String.format("%02X", h[i].toInt() and 0xFF))
        return hex.chunked(4).joinToString(" ")
    }

    // ------------------------------------------------------------------ sealing and opening

    private fun aad(chatId: String, from: String, field: String): ByteArray =
        "$chatId|$from|$field".toByteArray(Charsets.UTF_8)

    private fun seal(key: ByteArray, aad: ByteArray, plain: ByteArray): ByteArray {
        val iv = ByteArray(12)
        random.nextBytes(iv)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return iv + c.doFinal(plain)
    }

    private fun open(key: ByteArray, aad: ByteArray, blob: ByteArray): ByteArray? {
        if (blob.size < 12 + 16) return null
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
            c.updateAAD(aad)
            c.doFinal(blob, 12, blob.size - 12)
        } catch (e: Exception) {
            null
        }
    }

    private fun encode(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)

    private fun decode(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    /** field: "t" text, "r" quoted text, "l" chat-list preview, "d" photo or voice bytes. */
    fun sealText(key: ByteArray, chatId: String, from: String, field: String, text: String): String =
        encode(seal(key, aad(chatId, from, field), text.toByteArray(Charsets.UTF_8)))

    fun openText(key: ByteArray, chatId: String, from: String, field: String, sealed: String): String? {
        val raw = try {
            decode(sealed)
        } catch (e: Exception) {
            return null
        }
        val plain = open(key, aad(chatId, from, field), raw) ?: return null
        return String(plain, Charsets.UTF_8)
    }

    fun sealBytes(key: ByteArray, chatId: String, from: String, field: String, bytes: ByteArray): ByteArray =
        seal(key, aad(chatId, from, field), bytes)

    fun openBytes(key: ByteArray, chatId: String, from: String, field: String, sealed: ByteArray): ByteArray? =
        open(key, aad(chatId, from, field), sealed)

    /** Text only I can read, for the starred list: sealed with a key made from my own key pair. */
    fun sealSelf(label: String, text: String): String =
        encode(seal(selfKey(), "self|$label".toByteArray(Charsets.UTF_8), text.toByteArray(Charsets.UTF_8)))

    fun openSelf(label: String, sealed: String): String? {
        val raw = try {
            decode(sealed)
        } catch (e: Exception) {
            return null
        }
        val plain = open(selfKey(), "self|$label".toByteArray(Charsets.UTF_8), raw) ?: return null
        return String(plain, Charsets.UTF_8)
    }
}

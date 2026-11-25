package am.khosq.app.data

import java.security.MessageDigest
import java.util.Base64

/** What a KHOSQ QR code says about a person: their ID (handle), their account id and a fingerprint of their public key. */
class QrContact(val handle: String, val uid: String, val fp: String)

/** How a scanned code stands against what the server says about the person. */
enum class QrVerdict {
    /** The key on the server is the one the person showed: all is well. */
    MATCH,

    /** The key on the server is NOT the one in the code: somebody may stand between the two people (or the code is forged). */
    MISMATCH,

    /** The person has no key on the server yet (an old version of the app, or never opened). */
    NO_KEY,

    /** It is my own code. */
    SELF,

    /** A good code, but of somebody else than the contact that was meant. */
    WRONG_PERSON,

    /** Not a KHOSQ code. */
    BAD,
}

/**
 * The QR code of a person: `KHOSQ1/<handle>/<uid>/<fingerprint>`. Nothing secret is in it (the ID and the account id are public,
 * the fingerprint is a short hash of the public key). Its worth is that it is shown face to face: when the key that the server
 * gives for this person hashes to the fingerprint in the code, the key is the one the person really has, because nobody but
 * the server stood between the two phones' keys and the person's own screen. No Android in here, so it can be checked anywhere.
 */
object QrLogic {
    const val PREFIX = "KHOSQ1"
    private const val FP_BYTES = 16
    private const val MAX_LEN = 200
    private val HANDLE = Regex("^[a-z0-9_-]{1,30}$")
    private val UID = Regex("^[A-Za-z0-9]{6,64}$")
    private val FP = Regex("^[A-Za-z0-9_-]{22}$")

    /** The first 16 bytes of SHA-256 of the public key as it is published (the text), in URL-safe base64 without padding: 22 characters. */
    fun fingerprint(pubText: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(pubText.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(d.copyOf(FP_BYTES))
    }

    fun make(handle: String, uid: String, pubText: String): String = "$PREFIX/$handle/$uid/${fingerprint(pubText)}"

    /** The code read back, or null when the text is not exactly a KHOSQ code (a bad shape is never guessed at). */
    fun parse(text: String): QrContact? {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_LEN) return null
        val parts = t.split('/')
        if (parts.size != 4 || parts[0] != PREFIX) return null
        val h = parts[1]
        val u = parts[2]
        val f = parts[3]
        if (!HANDLE.matches(h) || !UID.matches(u) || !FP.matches(f)) return null
        return QrContact(h, u, f)
    }

    /** Does this key (as published) have the fingerprint of the code? */
    fun sameKey(pubText: String, fp: String): Boolean =
        MessageDigest.isEqual(fingerprint(pubText).toByteArray(Charsets.UTF_8), fp.toByteArray(Charsets.UTF_8))

    /**
     * What to think of a scanned [code]. [myUid] is me, [expectedUid] the contact I meant to check (null when I am adding somebody
     * new), [serverPub] the key the server holds for the person in the code (null when it holds none).
     */
    fun judge(code: QrContact?, myUid: String, expectedUid: String?, serverPub: String?): QrVerdict {
        if (code == null) return QrVerdict.BAD
        if (code.uid == myUid) return QrVerdict.SELF
        if (expectedUid != null && code.uid != expectedUid) return QrVerdict.WRONG_PERSON
        if (serverPub.isNullOrEmpty()) return QrVerdict.NO_KEY
        return if (sameKey(serverPub, code.fp)) QrVerdict.MATCH else QrVerdict.MISMATCH
    }
}

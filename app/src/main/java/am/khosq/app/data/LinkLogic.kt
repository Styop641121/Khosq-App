package am.khosq.app.data

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** What the QR code of the web version says: the session id and the public half of the page's one-time key. */
class LinkRequest(val sid: String, val webPub: String)

/** The sealed packet that goes to the Worker's post box: [eph] is the phone's one-time public key, [rh] the hash of the secret inside. */
class LinkPacket(val sid: String, val eph: String, val iv: String, val ct: String, val rh: String)

/**
 * Linking a browser (the web version) by QR code. The page makes a one-time key pair and shows its public half in the QR code
 * `KHOSQ-LINK1/<session>/<public key>`. The phone makes its own one-time key pair, combines it with the page's public key (ECDH, P-256),
 * makes an AES-256 key from the result (HKDF-SHA256, salt = the session id, info = "khosq-link-v1") and seals the person's identity
 * (account id, public key, private key) and a secret `r` with it (AES-GCM, bound to the session). Only the page can open the packet:
 * the Worker that carries it, and anybody who photographed the code, see sealed bytes. The same steps are done in the page (docs/link.js).
 * No Android in here, so it can be checked anywhere.
 */
object LinkLogic {
    const val PREFIX = "KHOSQ-LINK1"
    private const val INFO = "khosq-link-v1"
    private const val MAX_LEN = 200
    private val SID = Regex("^[A-Za-z0-9_-]{22}$")
    private val PUB = Regex("^[A-Za-z0-9_-]{87}$")
    private val PLAIN = Regex("^[A-Za-z0-9+/=_-]{1,400}$")
    private val b64 = Base64.getUrlEncoder().withoutPadding()
    private val unb64 = Base64.getUrlDecoder()

    // the curve P-256: y^2 = x^3 - 3x + b (mod p)
    private val P = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
    private val B = BigInteger("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16)

    private fun params(): ECParameterSpec {
        val ap = AlgorithmParameters.getInstance("EC")
        ap.init(ECGenParameterSpec("secp256r1"))
        return ap.getParameterSpec(ECParameterSpec::class.java)
    }

    /** The code read back, or null when the text is not exactly a code of the web version (a bad shape is never guessed at). */
    fun parse(text: String): LinkRequest? {
        val t = text.trim()
        if (t.isEmpty() || t.length > MAX_LEN) return null
        val parts = t.split('/')
        if (parts.size != 3 || parts[0] != PREFIX) return null
        if (!SID.matches(parts[1]) || !PUB.matches(parts[2])) return null
        return if (publicPoint(parts[2]) != null) LinkRequest(parts[1], parts[2]) else null
    }

    /** The point of a public key (65 bytes: 04, x, y) when it really lies on the curve; null otherwise. */
    private fun publicPoint(pub: String): ECPoint? {
        try {
            val raw = unb64.decode(pub)
            if (raw.size != 65 || raw[0] != 4.toByte()) return null
            val x = BigInteger(1, raw.copyOfRange(1, 33))
            val y = BigInteger(1, raw.copyOfRange(33, 65))
            if (x >= P || y >= P) return null
            val left = y.multiply(y).mod(P)
            val right = x.pow(3).subtract(x.multiply(BigInteger.valueOf(3))).add(B).mod(P)
            return if (left == right) ECPoint(x, y) else null
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Four digits made from the session and the key. The page shows the same four digits under its code, so the person can tell that
     * the code on the screen in front of them is the one the phone is about to link.
     */
    fun code(r: LinkRequest): String {
        val d = MessageDigest.getInstance("SHA-256").digest((r.sid + "|" + r.webPub).toByteArray(Charsets.UTF_8))
        val n = ((d[0].toLong() and 0xff) shl 24) or ((d[1].toLong() and 0xff) shl 16) or ((d[2].toLong() and 0xff) shl 8) or (d[3].toLong() and 0xff)
        return (n % 10000L).toString().padStart(4, '0')
    }

    private fun fixed32(v: BigInteger): ByteArray {
        val b = v.toByteArray()
        val out = ByteArray(32)
        val n = minOf(b.size, 32)
        System.arraycopy(b, b.size - n, out, 32 - n, n)
        return out
    }

    private fun hkdf(secret: ByteArray, salt: ByteArray, info: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = m.doFinal(secret)
        m.init(SecretKeySpec(prk, "HmacSHA256"))
        m.update(info)
        m.update(1.toByte())
        return m.doFinal()
    }

    /**
     * Seals the identity for the page. [pubText] is my public key as it is published, [priv] my private key (PKCS#8). Null when anything
     * is not as expected. The key material that was made here is wiped before returning.
     */
    fun seal(req: LinkRequest, uid: String, pubText: String, priv: ByteArray, rnd: SecureRandom = SecureRandom()): LinkPacket? {
        var shared: ByteArray? = null
        var key: ByteArray? = null
        var plain: ByteArray? = null
        try {
            val point = publicPoint(req.webPub) ?: return null
            val privText = b64.encodeToString(priv)
            if (!PLAIN.matches(uid) || !PLAIN.matches(pubText) || !PLAIN.matches(privText)) return null
            val webKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params()))
            val gen = KeyPairGenerator.getInstance("EC")
            gen.initialize(ECGenParameterSpec("secp256r1"), rnd)
            val mine = gen.generateKeyPair()
            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(mine.private)
            ka.doPhase(webKey, true)
            shared = ka.generateSecret()
            key = hkdf(shared, unb64.decode(req.sid), INFO.toByteArray(Charsets.UTF_8))
            val r = ByteArray(16).also { b -> rnd.nextBytes(b) }
            val rh = MessageDigest.getInstance("SHA-256").digest(r)
            val json = "{\"v\":1,\"uid\":\"$uid\",\"pub\":\"$pubText\",\"priv\":\"$privText\",\"r\":\"${b64.encodeToString(r)}\"}"
            plain = json.toByteArray(Charsets.UTF_8)
            val iv = ByteArray(12).also { b -> rnd.nextBytes(b) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            c.updateAAD((INFO + "|" + req.sid).toByteArray(Charsets.UTF_8))
            val ct = c.doFinal(plain)
            val ephPoint = (mine.public as ECPublicKey).w
            val eph = ByteArray(65)
            eph[0] = 4
            System.arraycopy(fixed32(ephPoint.affineX), 0, eph, 1, 32)
            System.arraycopy(fixed32(ephPoint.affineY), 0, eph, 33, 32)
            r.fill(0)
            return LinkPacket(req.sid, b64.encodeToString(eph), b64.encodeToString(iv), b64.encodeToString(ct), b64.encodeToString(rh))
        } catch (e: Exception) {
            return null
        } finally {
            shared?.fill(0)
            key?.fill(0)
            plain?.fill(0)
        }
    }
}

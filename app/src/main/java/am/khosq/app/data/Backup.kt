package am.khosq.app.data

import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Why a key file could not be opened. */
enum class KeyFileFail {
    /** Not a KHOSQ key file, or it is cut or damaged. */
    FORMAT,

    /** The passphrase is wrong (or the file was changed: the two cannot be told apart). */
    WRONG,
}

/** What a key file holds once it is opened: the account it belongs to and the two halves of the identity key. */
class KeyFileContent(val uid: String, val pub: String, val priv: ByteArray)

/** Either the content or the reason. Exactly one of them is set. */
class KeyFileResult(val content: KeyFileContent?, val fail: KeyFileFail?)

/**
 * The key file: my private encryption key, sealed with a passphrase the person chooses. The person keeps the file wherever they like;
 * without the passphrase it is only scrambled bytes. It is a text file in this shape:
 *
 *     KHOSQKEY1
 *     uid=...  pub=...  salt=...  iter=...  iv=...  data=...
 *
 * The passphrase is stretched with PBKDF2-HMAC-SHA256 and the key is sealed with AES-256-GCM; the account id and the public key are
 * bound to the seal (a file cannot be moved to another account by editing the lines).
 */
object KeyBackup {
    const val MAGIC = "KHOSQKEY1"
    const val ITER = 210_000
    private const val MIN_ITER = 100_000
    private const val MAX_ITER = 2_000_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val MAX_DATA = 4096
    private val random = SecureRandom()

    private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

    private fun unb64(s: String): ByteArray? = try {
        Base64.getDecoder().decode(s)
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun stretch(passphrase: String, salt: ByteArray, iter: Int): ByteArray {
        // the same letters typed on two phones must give the same key, whatever the keyboard does with accents
        val p = Normalizer.normalize(passphrase, Normalizer.Form.NFC)
        val spec = PBEKeySpec(p.toCharArray(), salt, iter, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun aad(uid: String, pub: String): ByteArray = "$MAGIC|$uid|$pub".toByteArray(Charsets.UTF_8)

    fun seal(
        uid: String,
        pub: String,
        priv: ByteArray,
        passphrase: String,
        salt: ByteArray = ByteArray(SALT_BYTES).also { random.nextBytes(it) },
        iv: ByteArray = ByteArray(IV_BYTES).also { random.nextBytes(it) },
        iter: Int = ITER,
    ): String {
        val key = stretch(passphrase, salt, iter)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad(uid, pub))
        val data = c.doFinal(priv)
        return listOf(
            MAGIC,
            "uid=$uid",
            "pub=$pub",
            "salt=" + b64(salt),
            "iter=$iter",
            "iv=" + b64(iv),
            "data=" + b64(data),
        ).joinToString("\n") + "\n"
    }

    fun open(text: String, passphrase: String): KeyFileResult {
        val lines = text.lines().map { x -> x.trim() }.filter { x -> x.isNotEmpty() }
        if (lines.isEmpty() || lines[0] != MAGIC) return KeyFileResult(null, KeyFileFail.FORMAT)
        val kv = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val at = lines[i].indexOf('=')
            if (at <= 0) return KeyFileResult(null, KeyFileFail.FORMAT)
            val name = lines[i].substring(0, at)
            if (kv.containsKey(name)) return KeyFileResult(null, KeyFileFail.FORMAT)
            kv[name] = lines[i].substring(at + 1)
        }
        val uid = kv["uid"]
        val pub = kv["pub"]
        val iter = kv["iter"]?.toIntOrNull()
        val salt = kv["salt"]?.let { s -> unb64(s) }
        val iv = kv["iv"]?.let { s -> unb64(s) }
        val data = kv["data"]?.let { s -> unb64(s) }
        if (uid.isNullOrEmpty() || pub.isNullOrEmpty() || iter == null || salt == null || iv == null || data == null) {
            return KeyFileResult(null, KeyFileFail.FORMAT)
        }
        if (iter < MIN_ITER || iter > MAX_ITER) return KeyFileResult(null, KeyFileFail.FORMAT)
        if (salt.size != SALT_BYTES || iv.size != IV_BYTES || data.size < 17 || data.size > MAX_DATA) {
            return KeyFileResult(null, KeyFileFail.FORMAT)
        }
        return try {
            val key = stretch(passphrase, salt, iter)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            c.updateAAD(aad(uid, pub))
            KeyFileResult(KeyFileContent(uid, pub, c.doFinal(data)), null)
        } catch (e: GeneralSecurityException) {
            KeyFileResult(null, KeyFileFail.WRONG)
        }
    }

    /** A name for the file that is easy to recognise among other files. */
    fun fileName(handle: String): String {
        val h = handle.lowercase().filter { c -> c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-' }.take(30)
        return if (h.isEmpty()) "khosq-key.khkey" else "khosq-key-$h.khkey"
    }
}

/** How good a typed password or passphrase is. [OK] is good enough to accept. */
enum class PwProblem { OK, SHORT, WEAK }

/** The rules for the account's email and password and for the passphrase of the key file. */
object AccountLogic {
    const val MIN_PASSWORD = 8
    const val MIN_PASSPHRASE = 10
    const val MAX_LEN = 128

    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$")

    private val COMMON = setOf(
        "12345678", "123456789", "1234567890", "11111111", "00000000", "password", "password1", "password123", "qwertyui",
        "qwerty123", "qwertyuiop", "abcd1234", "abc12345", "iloveyou", "asdfghjk", "12341234", "87654321", "admin123",
        "khosq123", "khosqkhosq",
    )

    fun normEmail(s: String): String = s.trim().lowercase()

    fun validEmail(s: String): Boolean {
        val e = normEmail(s)
        return e.length <= 254 && EMAIL.matches(e)
    }

    private fun weak(p: String): Boolean {
        val low = p.lowercase()
        if (low in COMMON) return true
        if (p.toSet().size <= 2) return true
        return false
    }

    fun passwordProblem(p: String): PwProblem = when {
        p.length < MIN_PASSWORD || p.length > MAX_LEN -> PwProblem.SHORT
        weak(p) -> PwProblem.WEAK
        else -> PwProblem.OK
    }

    fun passphraseProblem(p: String): PwProblem = when {
        p.length < MIN_PASSPHRASE || p.length > MAX_LEN -> PwProblem.SHORT
        weak(p) -> PwProblem.WEAK
        else -> PwProblem.OK
    }
}

/** What went wrong with a sign-in, in the few ways the person can do something about. */
enum class AuthKind { OK, NETWORK, NOT_ENABLED, EMAIL_IN_USE, WEAK_PASSWORD, BAD_EMAIL, WRONG_LOGIN, TOO_MANY, DISABLED, OTHER }

object AuthErrors {
    /**
     * [code] is the error code of Firebase sign-in ("ERROR_..." or null). [network] and [tooMany] tell about errors that have no code.
     * A wrong password and an unknown email are not told apart on purpose (both are [AuthKind.WRONG_LOGIN]).
     */
    fun kind(code: String?, network: Boolean, tooMany: Boolean): AuthKind {
        if (network) return AuthKind.NETWORK
        if (tooMany) return AuthKind.TOO_MANY
        val c = (code ?: "").uppercase()
        return when {
            c.isEmpty() -> AuthKind.OTHER
            c.contains("OPERATION_NOT_ALLOWED") -> AuthKind.NOT_ENABLED
            c.contains("EMAIL_ALREADY_IN_USE") || c.contains("CREDENTIAL_ALREADY_IN_USE") || c.contains("EMAIL_EXISTS") -> AuthKind.EMAIL_IN_USE
            c.contains("WEAK_PASSWORD") -> AuthKind.WEAK_PASSWORD
            c.contains("INVALID_EMAIL") || c.contains("MALFORMED_EMAIL") -> AuthKind.BAD_EMAIL
            c.contains("WRONG_PASSWORD") || c.contains("USER_NOT_FOUND") || c.contains("INVALID_CREDENTIAL") ||
                c.contains("INVALID_LOGIN_CREDENTIALS") || c.contains("INVALID_PASSWORD") -> AuthKind.WRONG_LOGIN
            c.contains("TOO_MANY_REQUESTS") -> AuthKind.TOO_MANY
            c.contains("USER_DISABLED") -> AuthKind.DISABLED
            c.contains("NETWORK") -> AuthKind.NETWORK
            else -> AuthKind.OTHER
        }
    }
}

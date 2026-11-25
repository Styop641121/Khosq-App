package am.khosq.app.data

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/** Where a contact stands: never confirmed, confirmed (and nothing changed since), or confirmed once but the key has changed since. */
enum class VerifyState { NONE, VERIFIED, CHANGED }

/** A contact whose name looks like another one. [same] = the names are the same once spelling tricks are removed; otherwise only one letter differs. */
data class NameMatch(val id: String, val name: String, val same: Boolean)

/**
 * Verifying a contact: both phones compute the same short code from the two public keys, the two people read it to each other
 * (in a call, or side by side) and, if it is the same, each marks the other as verified. Then nobody stands between them. The
 * mark belongs to the keys it was made for: when either key changes, the contact is no longer verified. Also here: finding a
 * new contact whose name copies one that is already known. No Android in here, so it can be checked on any computer.
 */
object VerifyLogic {
    /** 64 pictures, each from one code point that every phone since Android 8 draws on its own (no skin tones, no joined pictures). */
    val EMOJIS: List<String> = listOf(
        0x1F436, 0x1F431, 0x1F42D, 0x1F439, 0x1F430, 0x1F98A, 0x1F43B, 0x1F43C, 0x1F428, 0x1F42F, 0x1F981, 0x1F42E,
        0x1F437, 0x1F438, 0x1F435, 0x1F414, 0x1F427, 0x1F422, 0x1F40D, 0x1F419, 0x1F420, 0x1F42C, 0x1F433, 0x1F98B,
        0x1F34E, 0x1F34C, 0x1F347, 0x1F353, 0x1F352, 0x1F351, 0x1F34B, 0x1F349, 0x1F355, 0x1F369, 0x1F36A, 0x1F33D,
        0x1F332, 0x1F335, 0x1F33B, 0x1F339, 0x1F344, 0x1F319, 0x2B50, 0x1F525, 0x26A1, 0x2614,
        0x1F388, 0x1F381, 0x1F3B5, 0x1F514, 0x1F511, 0x1F680, 0x1F6B2, 0x26BD, 0x1F3C0, 0x1F4F7, 0x1F4A1, 0x23F0,
        0x1F3A9, 0x1F451, 0x1F48E, 0x1F697, 0x26F5, 0x1F3B8,
    ).map { cp -> String(Character.toChars(cp)) }

    /** How many pictures make the code: 8 of 64 = 48 bits. */
    const val CODE_LENGTH = 8

    /** SHA-256 of the two public keys, the smaller text first, so both phones get the same bytes. */
    fun digest(a: String, b: String): ByteArray {
        val first = if (a <= b) a else b
        val second = if (a <= b) b else a
        val md = MessageDigest.getInstance("SHA-256")
        md.update(first.toByteArray(Charsets.UTF_8))
        md.update(second.toByteArray(Charsets.UTF_8))
        return md.digest()
    }

    fun hex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (x in bytes) sb.append(String.format(Locale.ROOT, "%02x", x.toInt() and 0xFF))
        return sb.toString()
    }

    /** The long safety number: the first 16 bytes in groups of four hex digits, like "A1B2 C3D4 ...". */
    fun numberText(d: ByteArray): String {
        val sb = StringBuilder()
        for (i in 0 until 16) sb.append(String.format(Locale.ROOT, "%02X", d[i].toInt() and 0xFF))
        return sb.chunked(4).joinToString(" ")
    }

    /**
     * The short code: the first 6 bytes (the first three groups of the safety number) cut into eight 6-bit pieces,
     * each a picture. Same pictures on both phones exactly when those three groups are the same.
     */
    fun emojis(d: ByteArray): List<String> {
        var bits = 0L
        for (i in 0 until 6) bits = (bits shl 8) or (d[i].toLong() and 0xFF)
        val out = ArrayList<String>(CODE_LENGTH)
        for (i in 0 until CODE_LENGTH) {
            val shift = 42 - 6 * i
            out.add(EMOJIS[((bits shr shift) and 63L).toInt()])
        }
        return out
    }

    /** [stored]: what was saved when the person pressed "Verify" (a digest in hex, or ""). [current]: the digest of the keys right now (null = the key is not known). */
    fun state(stored: String, current: String?): VerifyState = when {
        stored.isEmpty() || current == null -> VerifyState.NONE
        stored == current -> VerifyState.VERIFIED
        else -> VerifyState.CHANGED
    }

    // ---- the same name twice

    private val ARMENIAN = mapOf(
        'ա' to "a", 'բ' to "b", 'գ' to "g", 'դ' to "d", 'ե' to "e", 'զ' to "z", 'է' to "e", 'ը' to "e", 'թ' to "t", 'ժ' to "zh",
        'ի' to "i", 'լ' to "l", 'խ' to "kh", 'ծ' to "ts", 'կ' to "k", 'հ' to "h", 'ձ' to "dz", 'ղ' to "gh", 'ճ' to "ch",
        'մ' to "m", 'յ' to "y", 'ն' to "n", 'շ' to "sh", 'ո' to "o", 'չ' to "ch", 'պ' to "p", 'ջ' to "j", 'ռ' to "r",
        'ս' to "s", 'վ' to "v", 'տ' to "t", 'ր' to "r", 'ց' to "ts", 'ւ' to "u", 'փ' to "p", 'ք' to "k", 'օ' to "o", 'ֆ' to "f",
        'և' to "ev",
    )
    private val CYRILLIC = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "zh", 'з' to "z", 'и' to "i",
        'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
        'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "sh", 'ъ' to "", 'ы' to "i", 'ь' to "",
        'э' to "e", 'ю' to "yu", 'я' to "ya",
    )

    /** One word of a name in plain Latin letters: Armenian and Russian letters by sound, accents and case removed, everything that is not a letter dropped. */
    private fun latinWord(word: String): String {
        val lower = word.lowercase(Locale.ROOT).replace("ու", "u")
        val sb = StringBuilder()
        for (ch in lower) {
            val t = ARMENIAN[ch] ?: CYRILLIC[ch]
            if (t != null) {
                sb.append(t)
            } else {
                // Latin letters with accents lose the accent; a digit, a space, a picture and the like give nothing
                for (c in Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)) if (c in 'a'..'z') sb.append(c)
            }
        }
        return sb.toString()
    }

    /** The name reduced to a comparable key: its words in Latin letters, sorted (so "Petrosyan Armen" and "Armen Petrosyan" meet), joined. */
    fun nameKey(name: String): String =
        name.split(Regex("[\\s,._-]+")).map { w -> latinWord(w) }.filter { w -> w.isNotEmpty() }.sorted().joinToString("")

    private fun distanceAtMostOne(a: String, b: String): Boolean {
        if (a == b) return true
        val la = a.length
        val lb = b.length
        if (kotlin.math.abs(la - lb) > 1) return false
        var i = 0
        while (i < la && i < lb && a[i] == b[i]) i++
        return when {
            la == lb -> a.substring(i + 1) == b.substring(i + 1)
            la > lb -> a.substring(i + 1) == b.substring(i)
            else -> a.substring(i) == b.substring(i + 1)
        }
    }

    /** Names shorter than this are too common to say anything. */
    const val MIN_KEY = 3

    /** Names this long may differ by one letter and still be counted (a short name that differs by a letter is another name). */
    const val NEAR_KEY = 9

    /**
     * Which of [known] (id to name) look like [name]: the same name in another alphabet, case or word order, or (for long names) one letter apart.
     * The same name comes first.
     */
    fun similarNames(name: String, known: List<Pair<String, String>>): List<NameMatch> {
        val key = nameKey(name)
        if (key.length < MIN_KEY) return emptyList()
        val out = ArrayList<NameMatch>()
        for ((id, other) in known) {
            val k = nameKey(other)
            if (k.length < MIN_KEY) continue
            if (k == key) {
                out.add(NameMatch(id, other, true))
            } else if (key.length >= NEAR_KEY && k.length >= NEAR_KEY && distanceAtMostOne(k, key)) {
                out.add(NameMatch(id, other, false))
            }
        }
        return out.sortedByDescending { m -> m.same }
    }
}

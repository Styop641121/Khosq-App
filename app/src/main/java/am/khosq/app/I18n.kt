package am.khosq.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Tiny settings store (language, theme) that works in the app and in the background services. */
object Prefs {
    private var sp: SharedPreferences? = null

    fun init(ctx: Context) {
        if (sp != null) return
        val p = ctx.applicationContext.getSharedPreferences("khosq", Context.MODE_PRIVATE)
        sp = p
        // Russian is the main language of a fresh install
        Lang.code = p.getString("lang", "ru") ?: "ru"
        AppTheme.mode = p.getString("theme", "system") ?: "system"
    }

    fun putString(key: String, value: String) {
        sp?.edit()?.putString(key, value)?.apply()
    }
}

object Lang {
    /** "ru", "hy" or "en". It is Compose state, so every screen redraws at once when it changes. */
    var code by mutableStateOf("ru")

    val options = listOf("ru" to "Русский", "hy" to "Հայերեն", "en" to "English")

    fun set(c: String) {
        code = c
        Prefs.putString("lang", c)
    }
}

object AppTheme {
    /** "system", "light" or "dark". */
    var mode by mutableStateOf("system")

    fun set(m: String) {
        mode = m
        Prefs.putString("theme", m)
    }
}

/**
 * The texts in the code are written in Armenian and act as keys: for Armenian they are shown as they are,
 * for English and Russian they are looked up in [Strings.map]. A text that has no translation stays Armenian.
 */
fun tr(hy: String): String {
    val c = Lang.code
    if (c == "hy") return hy
    val t = Strings.map[hy] ?: return hy
    return if (c == "en") t[0] else t[1]
}

/** Same as [tr], then fills {0}, {1}… with the arguments. */
fun trf(hy: String, vararg args: String): String {
    var s = tr(hy)
    for (i in args.indices) s = s.replace("{" + i + "}", args[i])
    return s
}

/** "3 members" / "3 участника" / "3 անդամ" with the right plural form. */
fun membersText(n: Int): String = when (Lang.code) {
    "hy" -> "$n անդամ"
    "en" -> if (n == 1) "1 member" else "$n members"
    else -> {
        val m10 = n % 10
        val m100 = n % 100
        val w = when {
            m10 == 1 && m100 != 11 -> "участник"
            m10 in 2..4 && m100 !in 12..14 -> "участника"
            else -> "участников"
        }
        "$n $w"
    }
}

/** What the chat list and notifications show for the last message (the stored text is only used for plain text). */
fun previewOf(kind: String, text: String): String = when (kind) {
    "img" -> "📷 " + tr("Նկար")
    "voice" -> "🎤 " + tr("Ձայնային նամակ")
    "call_missed" -> "📵 " + tr("Բաց թողնված զանգ")
    // older messages stored the Armenian preview itself
    else -> if (text == "📷 Նկար" || text == "🎤 Ձայնային նամակ" || text == "📵 Բաց թողնված զանգ") tr(text) else text
}

/** Marks a reply to a photo / voice message, so each reader sees it in their own language. */
const val REPLY_IMG = "\uE000img"
const val REPLY_VOICE = "\uE000voice"

fun replyText(reply: String): String = when (reply) {
    REPLY_IMG -> previewOf("img", "")
    REPLY_VOICE -> previewOf("voice", "")
    else -> previewOf("", reply)
}

/** Text of a call-log note made by the call code (a language-neutral note or an error with a reason). */
fun callNote(note: String): String {
    val p = "Սխալ՝ "
    return if (note.startsWith(p)) trf("Սխալ՝ {0}", tr(note.removePrefix(p))) else tr(note)
}

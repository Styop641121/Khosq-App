package am.khosq.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.os.Build
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A black box for crashes. When the app stops with an error, the reason is written to one small file on the phone (nothing is sent
 * anywhere). The next time the app opens, it shows that text in a window with a "Copy" button, so the person can paste it to the
 * developer. The text holds the kind of error, the lines of the app's own code where it happened and the phone model. No chat,
 * name, key or message is ever put in it.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"
    private const val MAX = 6000

    /** Called first thing when the app starts. Keeps the system's own handler and passes the error on to it afterwards. */
    fun install(app: Context) {
        val dir = app.applicationContext.filesDir
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                File(dir, FILE).writeText(describe(app, thread, error))
            } catch (e: Throwable) {
                // writing the report must never hide the real crash
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The saved report of the last crash, or null when there is none. */
    fun pending(ctx: Context): String? = try {
        val f = File(ctx.filesDir, FILE)
        if (f.exists()) f.readText().ifBlank { null } else null
    } catch (e: Exception) {
        null
    }

    fun clear(ctx: Context) {
        try {
            File(ctx.filesDir, FILE).delete()
        } catch (e: Exception) {
            // nothing to do
        }
    }

    /** If the last run ended with a crash, shows its report once (Copy / Close). Safe to call on every start. */
    fun showIfAny(activity: Activity) {
        val text = pending(activity) ?: return
        try {
            val pad = (20 * activity.resources.displayMetrics.density).toInt()
            val body = TextView(activity).apply {
                this.text = text
                setTextIsSelectable(true)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(pad, pad / 2, pad, pad / 2)
            }
            val scroll = ScrollView(activity).apply { addView(body) }
            val dialog = AlertDialog.Builder(activity)
                .setTitle("Հավելվածը վերջին անգամ խափանվել է")
                .setView(scroll)
                .setCancelable(false)
                .setPositiveButton("Պատճենել", null)
                .setNegativeButton("Փակել") { d, _ ->
                    clear(activity)
                    d.dismiss()
                }
                .create()
            dialog.show()
            // the Copy button keeps the window open, so the text can be copied again if needed
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("KHOSQ error", text))
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE).text = "Պատճենվեց ✓"
                } catch (e: Exception) {
                    // the text can still be selected by hand
                }
            }
        } catch (e: Exception) {
            // a window that cannot be shown must not become a second crash
        }
    }

    private fun describe(ctx: Context, thread: Thread, error: Throwable): String {
        val sb = StringBuilder()
        sb.append("KHOSQ error report\n")
        sb.append("time: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
        sb.append("phone: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(", Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        try {
            val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            sb.append("app: ").append(info.versionName).append('\n')
        } catch (e: Throwable) {
            // the version is only a help
        }
        sb.append("thread: ").append(thread.name).append("\n\n")

        // the chain of causes: what happened first is what matters most
        val chain = ArrayList<Throwable>()
        var t: Throwable? = error
        while (t != null && chain.size < 6) {
            val cur: Throwable = t
            if (chain.any { it === cur }) break
            chain.add(cur)
            t = cur.cause
        }
        val last = chain.last()
        sb.append("ROOT: ").append(last.javaClass.name).append(": ").append(cut(last.message)).append('\n')
        // the lines that belong to this app, from anywhere in the chain: they say where in the code it happened
        val own = ArrayList<String>()
        for (c in chain.reversed()) {
            for (f in c.stackTrace) {
                if (f.className.startsWith("am.khosq") && own.size < 14) own.add("    at $f")
            }
        }
        if (own.isNotEmpty()) {
            sb.append("APP LINES:\n")
            own.forEach { sb.append(it).append('\n') }
        }
        sb.append("\nFULL CHAIN:\n")
        for ((i, c) in chain.withIndex()) {
            sb.append(if (i == 0) "" else "Caused by: ").append(c.javaClass.name).append(": ").append(cut(c.message)).append('\n')
            val st = c.stackTrace
            for (j in 0 until minOf(st.size, if (i == 0) 10 else 16)) sb.append("    at ").append(st[j]).append('\n')
        }
        return if (sb.length > MAX) sb.substring(0, MAX) + "\n…" else sb.toString()
    }

    private fun cut(s: String?): String = (s ?: "").replace('\n', ' ').take(300)
}

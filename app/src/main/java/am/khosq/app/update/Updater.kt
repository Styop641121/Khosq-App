package am.khosq.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class UpdateInfo(val code: Int, val name: String, val url: String, val size: Long)

sealed class UpState {
    data object Idle : UpState()
    data object Checking : UpState()
    data object UpToDate : UpState()
    data class Failed(val reason: String = "") : UpState()
    data class Available(val info: UpdateInfo) : UpState()
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpState()
    data class Ready(val info: UpdateInfo, val path: String) : UpState()
}

/**
 * Looks at the newest GitHub release, downloads its APK inside the app and hands it to the Android installer.
 * The build number of a release is the number at the end of its tag (v2.0.57 -> 57); the installed app
 * gets the same number as its versionCode when GitHub builds it.
 */
object Updater {
    private const val API = "https://api.github.com/repos/Styop641121/Khosq-App/releases/latest"
    private const val API_LIST = "https://api.github.com/repos/Styop641121/Khosq-App/releases?per_page=1"

    val state = MutableStateFlow<UpState>(UpState.Idle)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Suppress("DEPRECATION")
    fun currentCode(ctx: Context): Int {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
    }

    fun currentName(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""

    /** The downloaded file is not needed after the app has been replaced by the new version. */
    fun cleanup(ctx: Context) {
        try {
            File(ctx.cacheDir, "updates").listFiles()?.forEach { f -> f.delete() }
        } catch (e: Exception) {
            // nothing to clean
        }
    }

    /** quiet = look in the background and say something only when a newer version exists. */
    fun checkAsync(ctx: Context, quiet: Boolean) {
        val app = ctx.applicationContext
        scope.launch { check(app, quiet) }
    }

    fun startDownload(ctx: Context, info: UpdateInfo) {
        val app = ctx.applicationContext
        scope.launch { download(app, info) }
    }

    private suspend fun check(ctx: Context, quiet: Boolean) {
        val now = state.value
        if (now is UpState.Downloading || now is UpState.Ready || now is UpState.Checking) return
        if (!quiet) state.value = UpState.Checking
        val res: UpState = withContext(Dispatchers.IO) {
            try {
                // "latest" skips pre-releases and drafts; the plain list does not
                val j: JSONObject = fetch(API)
                    ?: fetchList(API_LIST)?.optJSONObject(0)
                    ?: return@withContext UpState.Failed("no release")
                val tag = j.optString("tag_name")
                val name = j.optString("name", tag).ifEmpty { tag }
                val code = Regex("(\\d+)\\D*$").find(tag)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("#(\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull()
                var url = ""
                var size = 0L
                val assets = j.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name").endsWith(".apk")) {
                            url = a.optString("browser_download_url")
                            size = a.optLong("size")
                            break
                        }
                    }
                }
                if (code == null || url.isEmpty()) UpState.Failed("no apk")
                else if (code > currentCode(ctx)) UpState.Available(UpdateInfo(code, name, url, size))
                else UpState.UpToDate
            } catch (e: Exception) {
                UpState.Failed(e.message ?: "")
            }
        }
        if (quiet && res !is UpState.Available) {
            state.value = UpState.Idle
            return
        }
        val cur = state.value
        if (cur is UpState.Downloading || cur is UpState.Ready) return
        state.value = res
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "KHOSQ")
        return c
    }

    /** null when GitHub answers anything but 200. */
    private fun fetch(url: String): JSONObject? {
        val c = open(url)
        try {
            if (c.responseCode != 200) return null
            return JSONObject(c.inputStream.bufferedReader().use { r -> r.readText() })
        } finally {
            c.disconnect()
        }
    }

    private fun fetchList(url: String): org.json.JSONArray? {
        val c = open(url)
        try {
            if (c.responseCode != 200) return null
            return org.json.JSONArray(c.inputStream.bufferedReader().use { r -> r.readText() })
        } finally {
            c.disconnect()
        }
    }

    private suspend fun download(ctx: Context, info: UpdateInfo) {
        if (state.value is UpState.Downloading) return
        state.value = UpState.Downloading(info, 0)
        val res: UpState = withContext(Dispatchers.IO) {
            try {
                val dir = File(ctx.cacheDir, "updates")
                dir.mkdirs()
                dir.listFiles()?.forEach { f -> f.delete() }
                val out = File(dir, "khosq-" + info.code + ".apk")
                val c = URL(info.url).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", "KHOSQ")
                val http = c.responseCode
                if (http != 200) {
                    c.disconnect()
                    return@withContext UpState.Failed("HTTP " + http)
                }
                val total = if (c.contentLengthLong > 0L) c.contentLengthLong else info.size
                var done = 0L
                var last = -1
                c.inputStream.use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            if (total > 0L) {
                                val pct = (done * 100L / total).toInt().coerceIn(0, 100)
                                if (pct != last) {
                                    last = pct
                                    state.value = UpState.Downloading(info, pct)
                                }
                            }
                        }
                    }
                }
                c.disconnect()
                if (total > 0L && done < total) UpState.Failed("incomplete") else UpState.Ready(info, out.absolutePath)
            } catch (e: Exception) {
                UpState.Failed(e.message ?: "")
            }
        }
        state.value = res
    }

    /** Opens the Android installer. The first time, Android asks to allow installing from this app. */
    fun install(ctx: Context, path: String) {
        val file = File(path)
        if (!file.exists()) {
            state.value = UpState.Failed("missing file")
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e: Exception) {
                // the phone has no such screen
            }
            return
        }
        try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            state.value = UpState.Failed(e.message ?: "")
        }
    }
}

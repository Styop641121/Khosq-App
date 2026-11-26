package am.khosq.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * The photos of listings. They are public pictures on list.am; the phone fetches them straight from there, so list.am sees the phone's
 * address (as it would when the page is opened in a browser) and nothing else: no login, no KHOSQ data. Only addresses on img.list.am
 * are ever fetched. Pictures are kept in memory only while the app runs.
 */
object FinderImages {
    private const val HOST = "https://img.list.am/"
    private const val MAX_BYTES = 3 * 1024 * 1024
    private const val MAX_SIDE = 1100

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** The picture at [url], or null when it cannot be had (no network, not a list.am picture, too big, not a picture). */
    suspend fun load(url: String): ImageBitmap? {
        if (!url.startsWith(HOST)) return null
        val hit = cache.get(url)
        if (hit != null) return hit.asImageBitmap()
        val fresh = withContext(Dispatchers.IO) { fetch(url) }
        if (fresh == null) return null
        cache.put(url, fresh)
        return fresh.asImageBitmap()
    }

    private fun fetch(url: String): Bitmap? {
        var c: HttpURLConnection? = null
        return try {
            c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8_000
            c.readTimeout = 12_000
            c.instanceFollowRedirects = false
            if (c.responseCode != 200) return null
            val bytes = c.inputStream.use { s -> readLimited(s) } ?: return null
            decode(bytes)
        } catch (e: Exception) {
            null
        } finally {
            c?.disconnect()
        }
    }

    private fun readLimited(s: java.io.InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) return null
        }
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > MAX_SIDE || bounds.outHeight / sample > MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}

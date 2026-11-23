package am.khosq.app.data

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Profile photos of everyone I talk to. A photo is downloaded once per version and kept on the phone,
 * so the chat list never waits for the network. Screens just read [photos].
 */
object ProfileStore {
    val photos = mutableStateMapOf<String, ImageBitmap>()

    private val have = ConcurrentHashMap<String, Long>()
    private val busy: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var dir: File? = null

    fun init(ctx: Context) {
        if (dir != null) return
        val d = File(ctx.applicationContext.cacheDir, "photos")
        d.mkdirs()
        dir = d
    }

    /** Called with the newest version number of someone's photo (0 means they have none). */
    fun want(scope: CoroutineScope, repo: Repository, uid: String, ver: Long) {
        if (uid.isEmpty()) return
        if (ver <= 0L) {
            if (have.remove(uid) != null || photos.containsKey(uid)) photos.remove(uid)
            return
        }
        if (have[uid] == ver) return
        if (!busy.add(uid)) return
        scope.launch(Dispatchers.IO) {
            try {
                val folder = dir
                val file = if (folder != null) File(folder, "$uid-$ver.jpg") else null
                var bytes: ByteArray? = if (file != null && file.exists()) file.readBytes() else null
                if (bytes == null) {
                    bytes = repo.loadPhoto(uid)
                    if (bytes != null && file != null && folder != null) {
                        folder.listFiles { f -> f.name.startsWith("$uid-") }?.forEach { f -> f.delete() }
                        file.writeBytes(bytes)
                    }
                }
                val bmp = if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() else null
                withContext(Dispatchers.Main) {
                    if (bmp != null) {
                        photos[uid] = bmp
                        have[uid] = ver
                    }
                }
            } catch (e: Exception) {
                // try again on the next update of the chat list
            } finally {
                busy.remove(uid)
            }
        }
    }

    /** My own new photo shows at once, before the upload is finished. */
    fun setLocal(uid: String, bytes: ByteArray, ver: Long) {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() ?: return
        photos[uid] = bmp
        have[uid] = ver
        val folder = dir ?: return
        try {
            folder.listFiles { f -> f.name.startsWith("$uid-") }?.forEach { f -> f.delete() }
            File(folder, "$uid-$ver.jpg").writeBytes(bytes)
        } catch (e: Exception) {
            // the cache is only an optimisation
        }
    }

    fun clearLocal(uid: String) {
        photos.remove(uid)
        have.remove(uid)
        dir?.listFiles { f -> f.name.startsWith("$uid-") }?.forEach { f -> f.delete() }
    }
}

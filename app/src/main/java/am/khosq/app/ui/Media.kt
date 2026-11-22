package am.khosq.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayOutputStream
import java.io.File

object MediaUtil {
    /** Shrinks a photo to at most ~1280 px and about 700 KB so it fits inside one message. */
    fun compressImage(ctx: Context, uri: Uri): ByteArray? {
        return try {
            val bmp: Bitmap? = if (Build.VERSION.SDK_INT >= 28) {
                val src = ImageDecoder.createSource(ctx.contentResolver, uri)
                ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                    val w = info.size.width
                    val h = info.size.height
                    val side = maxOf(w, h)
                    if (side > 1280) {
                        val k = 1280f / side
                        decoder.setTargetSize(maxOf(1, (w * k).toInt()), maxOf(1, (h * k).toInt()))
                    }
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                val bounds = BitmapFactory.Options()
                bounds.inJustDecodeBounds = true
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
                val opts = BitmapFactory.Options()
                opts.inSampleSize = sample
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            }
            if (bmp == null) return null
            var quality = 80
            var out: ByteArray
            do {
                val bos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
                out = bos.toByteArray()
                quality -= 10
            } while (out.size > 700_000 && quality >= 30)
            if (out.size > 900_000) null else out
        } catch (e: Exception) {
            null
        }
    }
}

/** Records a voice note as a small AAC file (about 4 KB per second). */
class VoiceRecorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var startMs = 0L

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()

    fun start(): Boolean {
        return try {
            val f = File(ctx.cacheDir, "voice_" + System.currentTimeMillis() + ".m4a")
            val r = newRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(32000)
            r.setAudioSamplingRate(16000)
            r.setAudioChannels(1)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
            rec = r
            file = f
            startMs = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            rec?.release()
            rec = null
            false
        }
    }

    fun elapsedSec(): Int = ((System.currentTimeMillis() - startMs) / 1000).toInt()

    /** Stops the recording. Returns the audio and its length when it should be sent, otherwise null. */
    fun stop(send: Boolean): Pair<ByteArray, Int>? {
        val r = rec ?: return null
        val f = file
        val sec = elapsedSec()
        try {
            r.stop()
        } catch (e: Exception) {
            // stopped right after starting: nothing was recorded
        }
        r.release()
        rec = null
        file = null
        if (!send || f == null || sec < 1) {
            f?.delete()
            return null
        }
        val bytes = try {
            f.readBytes()
        } catch (e: Exception) {
            null
        }
        f.delete()
        return if (bytes == null || bytes.size > 900_000) null else Pair(bytes, sec)
    }
}

/** One voice note plays at a time; the screen reads [playingId] to draw play or pause. */
object VoicePlayer {
    private var player: MediaPlayer? = null
    var playingId: String? by mutableStateOf(null)

    fun toggle(ctx: Context, id: String, bytes: ByteArray) {
        if (playingId == id) {
            stop()
            return
        }
        stop()
        try {
            val f = File(ctx.cacheDir, "play_" + id + ".m4a")
            f.writeBytes(bytes)
            val p = MediaPlayer()
            p.setDataSource(f.absolutePath)
            p.setOnCompletionListener { stop() }
            p.prepare()
            p.start()
            player = p
            playingId = id
        } catch (e: Exception) {
            stop()
        }
    }

    fun stop() {
        try {
            player?.stop()
        } catch (e: Exception) {
            // already stopped
        }
        player?.release()
        player = null
        playingId = null
    }
}

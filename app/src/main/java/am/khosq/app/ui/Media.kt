package am.khosq.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.media.PlaybackParams
import am.khosq.app.Prefs
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.ByteArrayOutputStream
import java.io.File

object MediaUtil {
    private fun decode(ctx: Context, uri: Uri, limit: Int): Bitmap? =
        if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(ctx.contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val side = maxOf(w, h)
                if (side > limit) {
                    val k = limit.toFloat() / side
                    decoder.setTargetSize(maxOf(1, (w * k).toInt()), maxOf(1, (h * k).toInt()))
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > limit * 5 / 4) sample *= 2
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }

    /** Shrinks a photo to at most ~1280 px and about 700 KB so it fits inside one message. */
    fun compressImage(ctx: Context, uri: Uri): ByteArray? {
        return try {
            val bmp = decode(ctx, uri, 1280) ?: return null
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

    /** A profile photo: the middle square of the picture, 256 x 256 px, about 15-30 KB. */
    fun avatar(ctx: Context, uri: Uri): ByteArray? {
        return try {
            val bmp = decode(ctx, uri, 1024) ?: return null
            val side = minOf(bmp.width, bmp.height)
            val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
            val small = Bitmap.createScaledBitmap(square, 256, 256, true)
            var quality = 85
            var out: ByteArray
            do {
                val bos = ByteArrayOutputStream()
                small.compress(Bitmap.CompressFormat.JPEG, quality, bos)
                out = bos.toByteArray()
                quality -= 10
            } while (out.size > 60_000 && quality >= 40)
            out
        } catch (e: Exception) {
            null
        }
    }
}

/** A finished recording: the audio, its length in seconds and the loudness bars drawn in the chat (digits 1..9). */
class VoiceNote(val bytes: ByteArray, val sec: Int, val wave: String)

/** Records a voice note as a small AAC file (about 4 KB per second). */
class VoiceRecorder(private val ctx: Context) {
    private var rec: MediaRecorder? = null
    private var file: File? = null
    private var startMs = 0L
    private val levels = ArrayList<Int>()

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
            levels.clear()
            startMs = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            rec?.release()
            rec = null
            false
        }
    }

    fun elapsedSec(): Int = ((System.currentTimeMillis() - startMs) / 1000).toInt()

    /** Called a few times a second while recording: remembers how loud it is right now. */
    fun sample() {
        try {
            levels.add(rec?.maxAmplitude ?: 0)
        } catch (e: Exception) {
            // not recording any more
        }
    }

    /** About 40 bars from the loudness samples, each one a digit 1..9 (quiet to loud). */
    private fun wave(): String {
        if (levels.isEmpty()) return ""
        val n = 40
        val peaks = IntArray(n)
        for (i in 0 until n) {
            val from = i * levels.size / n
            val to = maxOf(from + 1, (i + 1) * levels.size / n).coerceAtMost(levels.size)
            var mx = 0
            for (k in from until to) mx = maxOf(mx, levels[k])
            peaks[i] = mx
        }
        val top = maxOf(1, peaks.max())
        val sb = StringBuilder()
        for (v in peaks) {
            // the square root lifts quiet speech so the bars are not all tiny
            val lvl = 1 + Math.round(Math.sqrt(v.toDouble() / top) * 8.0).toInt()
            sb.append(lvl.coerceIn(1, 9))
        }
        return sb.toString()
    }

    /** Stops the recording. Returns the note when it should be sent, otherwise null. */
    fun stop(send: Boolean): VoiceNote? {
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
        return if (bytes == null || bytes.size > 900_000) null else VoiceNote(bytes, sec, wave())
    }
}

/**
 * One voice note plays at a time. A note that was paused (or left by playing another one) remembers where it
 * stopped; the speed (1x, 1.5x, 2x) is shared by all notes and kept between starts.
 */
object VoicePlayer {
    private var player: MediaPlayer? = null

    /** The note that is loaded (playing or paused), and whether it is playing right now. */
    var activeId: String? by mutableStateOf(null)
    var playing: Boolean by mutableStateOf(false)
    var posMs: Int by mutableIntStateOf(0)
    var durMs: Int by mutableIntStateOf(0)
    var speed: Float by mutableFloatStateOf(Prefs.getString("vspeed", "1").toFloatOrNull() ?: 1f)

    private val resume = mutableStateMapOf<String, Int>()

    /** Where a note would continue from, in milliseconds. */
    fun positionOf(id: String): Int = if (activeId == id) posMs else (resume[id] ?: 0)

    fun isPlaying(id: String): Boolean = activeId == id && playing

    fun toggle(ctx: Context, id: String, bytes: ByteArray) {
        val p = player
        if (activeId == id && p != null) {
            if (playing) pause() else play(p)
            return
        }
        leave()
        try {
            val f = File(ctx.cacheDir, "play_" + id + ".m4a")
            f.writeBytes(bytes)
            val np = MediaPlayer()
            np.setDataSource(f.absolutePath)
            np.setOnCompletionListener { finish() }
            np.prepare()
            val from = resume[id] ?: 0
            if (from in 1 until np.duration - 300) np.seekTo(from)
            player = np
            durMs = np.duration
            activeId = id
            posMs = if (from in 1 until np.duration - 300) from else 0
            play(np)
        } catch (e: Exception) {
            release()
        }
    }

    private fun play(p: MediaPlayer) {
        try {
            p.start()
            applySpeed(p)
            playing = true
        } catch (e: Exception) {
            release()
        }
    }

    private fun applySpeed(p: MediaPlayer) {
        try {
            p.playbackParams = PlaybackParams().setSpeed(speed)
        } catch (e: Exception) {
            // this phone cannot change the speed: it plays at normal speed
        }
    }

    fun pause() {
        val p = player ?: return
        try {
            p.pause()
            posMs = p.currentPosition
        } catch (e: Exception) {
            // already stopped
        }
        playing = false
        val id = activeId
        if (id != null) resume[id] = posMs
    }

    /** 1x -> 1.5x -> 2x -> 1x. */
    fun cycleSpeed() {
        speed = when {
            speed < 1.2f -> 1.5f
            speed < 1.8f -> 2f
            else -> 1f
        }
        Prefs.putString("vspeed", speed.toString())
        val p = player
        if (p != null && playing) applySpeed(p)
    }

    /** Reads the real position while a note plays (the screen calls this several times a second). */
    fun tick() {
        val p = player ?: return
        try {
            if (playing) posMs = p.currentPosition
        } catch (e: Exception) {
            // released in the meantime
        }
    }

    /** Jumps to a place in the note (fraction 0..1). Works while playing, paused or not started. */
    fun seek(id: String, fraction: Float, totalMs: Int) {
        val f = fraction.coerceIn(0f, 1f)
        val p = player
        if (activeId == id && p != null) {
            try {
                val ms = (f * p.duration).toInt()
                p.seekTo(ms)
                posMs = ms
                resume[id] = ms
            } catch (e: Exception) {
                // released in the meantime
            }
        } else {
            resume[id] = (f * totalMs).toInt()
        }
    }

    private fun finish() {
        val id = activeId
        if (id != null) resume.remove(id)
        release()
    }

    /** Leaves the current note (remembering where it was) before another one starts. */
    private fun leave() {
        val p = player
        val id = activeId
        if (p != null && id != null) {
            try {
                resume[id] = p.currentPosition
            } catch (e: Exception) {
                // released in the meantime
            }
        }
        release()
    }

    private fun release() {
        try {
            player?.stop()
        } catch (e: Exception) {
            // already stopped
        }
        player?.release()
        player = null
        activeId = null
        playing = false
        posMs = 0
        durMs = 0
    }

    /** The chat was closed: stop, but keep where each note was. */
    fun stop() {
        leave()
    }
}

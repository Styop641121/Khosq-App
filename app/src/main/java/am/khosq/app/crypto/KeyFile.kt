package am.khosq.app.crypto

import am.khosq.app.data.KeyBackup
import am.khosq.app.data.KeyFileFail
import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream

/** How putting a key file back went. */
enum class RestoreResult { OK, WRONG_PASSPHRASE, NOT_A_KEY_FILE, OTHER_ACCOUNT, FAILED }

/**
 * Saving and putting back the key file (see [KeyBackup]). Both calls take a while (the passphrase is stretched on purpose) and touch
 * files, so they must be called from a background thread.
 */
object KeyFile {
    /** A key file is a few hundred bytes; anything much bigger is not one. */
    private const val MAX_BYTES = 32 * 1024

    /** Writes the key file to [uri] (a place the person chose) and reads it back to be sure it can be opened. */
    fun create(ctx: Context, uri: Uri, uid: String, passphrase: String): Boolean {
        return try {
            val (priv, pub) = E2E.exportIdentity()
            val text = KeyBackup.seal(uid, pub, priv, passphrase)
            val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: return false
            out.use { s -> s.write(text.toByteArray(Charsets.UTF_8)) }
            val back = readText(ctx, uri) ?: return false
            val opened = KeyBackup.open(back, passphrase).content ?: return false
            opened.uid == uid && opened.priv.contentEquals(priv)
        } catch (e: Exception) {
            false
        }
    }

    /** Opens the key file at [uri] with the passphrase and, if it belongs to account [uid], makes it the identity of this phone. */
    fun restore(ctx: Context, uri: Uri, passphrase: String, uid: String): RestoreResult {
        return try {
            val text = readText(ctx, uri) ?: return RestoreResult.NOT_A_KEY_FILE
            val r = KeyBackup.open(text, passphrase)
            val c = r.content
            if (c == null) {
                return if (r.fail == KeyFileFail.WRONG) RestoreResult.WRONG_PASSPHRASE else RestoreResult.NOT_A_KEY_FILE
            }
            if (c.uid != uid) return RestoreResult.OTHER_ACCOUNT
            if (E2E.importIdentity(c.priv, c.pub)) RestoreResult.OK else RestoreResult.FAILED
        } catch (e: Exception) {
            RestoreResult.FAILED
        }
    }

    private fun readText(ctx: Context, uri: Uri): String? {
        val input = ctx.contentResolver.openInputStream(uri) ?: return null
        input.use { s ->
            val buf = ByteArray(4096)
            val all = ByteArrayOutputStream()
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                all.write(buf, 0, n)
                if (all.size() > MAX_BYTES) return null
            }
            return String(all.toByteArray(), Charsets.UTF_8)
        }
    }
}

package am.khosq.app.ui

import am.khosq.app.data.Message
import am.khosq.app.tr
import am.khosq.app.trf
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** A small picture for the grid: the photo is read at a lower size, so many of them fit in memory. */
private fun thumbOf(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var s = 1
    while (bounds.outWidth / (s * 2) >= 360 && bounds.outHeight / (s * 2) >= 360) s *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = s }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
}

/**
 * All the photos of the open chat in one grid, newest first. They come from the messages the chat already has
 * (opened on this phone), so nothing more is downloaded. Tap a picture to see it large.
 */
@Composable
fun MediaGallery(messages: List<Message>, onClose: () -> Unit) {
    val k = KTheme.colors
    val photos = remember(messages) { messages.filter { m -> m.kind == "img" && !m.deleted && m.data != null }.asReversed() }
    var open by remember { mutableStateOf<Message?>(null) }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(k.bg).statusBarsPadding().navigationBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().height(Dim.bar).background(k.bar).padding(horizontal = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                KIconButton(icon = KIcons.Back, onClick = onClose, description = tr("Հետ"))
                Text(
                    text = tr("Մեդիա"),
                    color = k.text,
                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f).padding(start = Space.s),
                )
                Text(
                    text = trf("Նկարներ՝ {0}", photos.size.toString()),
                    color = k.textSecondary,
                    style = TextStyle(fontSize = 14.sp),
                    modifier = Modifier.padding(end = Space.l),
                )
            }
            KDivider()
            if (photos.isEmpty()) {
                EmptyState(icon = KIcons.Image, title = tr("Նկարներ դեռ չկան"), modifier = Modifier.padding(top = Space.huge))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(photos, key = { m -> m.id }) { m ->
                        val thumb = remember(m.id) { m.data?.let { b -> thumbOf(b) } }
                        Box(
                            modifier = Modifier.aspectRatio(1f).background(k.surfaceHigh).clickable { open = m },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (thumb != null) {
                                Image(
                                    bitmap = thumb,
                                    contentDescription = tr("Նկար"),
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val big = open
    if (big != null) {
        val full = remember(big.id) {
            val d = big.data
            if (d == null) null else BitmapFactory.decodeByteArray(d, 0, d.size)?.asImageBitmap()
        }
        ImageViewer(bitmap = full, title = dayLabel(big.ts) + "  " + formatTime(big.ts), onClose = { open = null })
    }
}

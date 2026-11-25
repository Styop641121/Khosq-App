package am.khosq.app.ui

import am.khosq.app.data.ProfileStore
import am.khosq.app.data.ZoomMath
import am.khosq.app.tr
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A picture over the whole screen. Two fingers zoom it, one finger moves it when it is zoomed, a double tap zooms in and back,
 * one tap shows or hides the top line, the cross (or the back button of the phone) closes it. [bitmap] = null says the picture
 * could not be opened. The window inherits the screen-capture protection of the app.
 */
@Composable
fun ImageViewer(bitmap: ImageBitmap?, title: String, onClose: () -> Unit) {
    var bars by remember { mutableStateOf(true) }
    var scale by remember { mutableFloatStateOf(ZoomMath.MIN_SCALE) }
    var ox by remember { mutableFloatStateOf(0f) }
    var oy by remember { mutableFloatStateOf(0f) }
    var box by remember { mutableStateOf(IntSize.Zero) }

    // a turned screen is a different box: start again from the whole picture
    LaunchedEffect(box) {
        scale = ZoomMath.MIN_SCALE
        ox = 0f
        oy = 0f
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .onSizeChanged { s -> box = s },
        ) {
            if (bitmap != null) {
                val bw = box.width.toFloat()
                val bh = box.height.toFloat()
                val fit = ZoomMath.fitSize(bitmap.width.toFloat(), bitmap.height.toFloat(), bw, bh)
                Image(
                    bitmap = bitmap,
                    contentDescription = tr("Նկար"),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        // the gestures are measured on the plain box, so they sit outside the scaling layer
                        .pointerInput(bitmap, box) {
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                val st = ZoomMath.step(
                                    s = ZoomMath.State(scale, ox, oy),
                                    zoom = zoom,
                                    cx = centroid.x - bw / 2f,
                                    cy = centroid.y - bh / 2f,
                                    panX = pan.x,
                                    panY = pan.y,
                                    fitW = fit.first,
                                    fitH = fit.second,
                                    boxW = bw,
                                    boxH = bh,
                                )
                                scale = st.scale
                                ox = st.x
                                oy = st.y
                            }
                        }
                        .pointerInput(bitmap, box) {
                            detectTapGestures(
                                onTap = { bars = !bars },
                                onDoubleTap = { p ->
                                    val st = ZoomMath.doubleTap(
                                        s = ZoomMath.State(scale, ox, oy),
                                        cx = p.x - bw / 2f,
                                        cy = p.y - bh / 2f,
                                        fitW = fit.first,
                                        fitH = fit.second,
                                        boxW = bw,
                                        boxH = bh,
                                    )
                                    scale = st.scale
                                    ox = st.x
                                    oy = st.y
                                },
                            )
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = ox
                            translationY = oy
                        },
                )
            } else {
                Text(
                    text = tr("Այս նկարը չհաջողվեց բացել"),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            if (bars || bitmap == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .statusBarsPadding()
                        .padding(horizontal = Space.xs, vertical = Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KIconButton(icon = KIcons.Close, onClick = onClose, tint = Color.White, description = tr("Փակել"))
                    Text(
                        text = title,
                        color = Color.White,
                        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = Space.s, end = Space.l),
                    )
                }
            }
        }
    }
}

/**
 * A profile photo (a person's or a group's) shown large. The small copy that the lists use shows at once; the full one, as it
 * was saved, replaces it as soon as it is read from the phone.
 */
@Composable
fun AvatarViewer(uid: String, name: String, onClose: () -> Unit) {
    val small = ProfileStore.photos[uid]
    val full by produceState<ImageBitmap?>(null, uid) {
        value = withContext(Dispatchers.IO) { ProfileStore.full(uid) }
    }
    ImageViewer(bitmap = full ?: small, title = name, onClose = onClose)
}

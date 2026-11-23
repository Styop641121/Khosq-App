package am.khosq.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Small vector icon set drawn from path data, so the app does not need the big icon library. */
object KIcons {
    private fun vec(name: String, d: String, slash: Boolean = false): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        if (slash) {
            // keep the icon everywhere except a diagonal band, then draw the slash inside that band
            b.addGroup(clipPathData = addPathNodes("M2.4,0L24,0L24,21.6Z M0,2.4L0,24L21.6,24Z"))
            b.addPath(addPathNodes(d), fill = SolidColor(Color.Black))
            b.clearGroup()
            b.addPath(
                addPathNodes("M3.6,3.6L20.4,20.4"),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
            )
        } else {
            b.addPath(addPathNodes(d), fill = SolidColor(Color.Black))
        }
        return b.build()
    }

    private const val CALL =
        "M6.62,10.79c1.44,2.83 3.76,5.14 6.59,6.59l2.2,-2.2c0.27,-0.27 0.67,-0.36 1.02,-0.24 1.12,0.37 2.33,0.57 3.57,0.57 0.55,0 1,0.45 1,1V20c0,0.55 -0.45,1 -1,1 -9.39,0 -17,-7.61 -17,-17 0,-0.55 0.45,-1 1,-1h3.5c0.55,0 1,0.45 1,1 0,1.25 0.2,2.45 0.57,3.57 0.11,0.35 0.03,0.74 -0.25,1.02l-2.2,2.2z"
    private const val VIDEO =
        "M17,10.5V7c0,-0.55 -0.45,-1 -1,-1H4c-0.55,0 -1,0.45 -1,1v10c0,0.55 0.45,1 1,1h12c0.55,0 1,-0.45 1,-1v-3.5l4,4v-11l-4,4z"
    private const val END =
        "M12,9c-1.6,0 -3.15,0.25 -4.6,0.72v3.1c0,0.39 -0.23,0.74 -0.56,0.9 -0.98,0.49 -1.87,1.12 -2.66,1.85 -0.18,0.18 -0.43,0.28 -0.7,0.28 -0.28,0 -0.53,-0.11 -0.71,-0.29L0.29,13.08c-0.18,-0.17 -0.29,-0.42 -0.29,-0.7 0,-0.28 0.11,-0.53 0.29,-0.71C3.34,8.78 7.46,7 12,7s8.66,1.78 11.71,4.67c0.18,0.18 0.29,0.43 0.29,0.71 0,0.28 -0.11,0.53 -0.29,0.71l-2.48,2.48c-0.18,0.18 -0.43,0.29 -0.71,0.29 -0.27,0 -0.52,-0.11 -0.7,-0.28 -0.79,-0.74 -1.69,-1.36 -2.67,-1.85 -0.33,-0.16 -0.56,-0.5 -0.56,-0.9v-3.1C15.15,9.25 13.6,9 12,9z"
    private const val MIC =
        "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3zM17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28c3.28,-0.48 6,-3.3 6,-6.72h-1.7z"
    private const val VOL =
        "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06c4.01,-0.91 7,-4.49 7,-8.77s-2.99,-7.86 -7,-8.77z"
    private const val VOL0 = "M7,9v6h4l5,5V4l-5,5H7z"
    private const val FLIP =
        "M12,6v3l4,-4 -4,-4v3c-4.42,0 -8,3.58 -8,8 0,1.57 0.46,3.03 1.24,4.26L6.7,14.8c-0.45,-0.83 -0.7,-1.79 -0.7,-2.8 0,-3.31 2.69,-6 6,-6zM18.76,7.74L17.3,9.2c0.44,0.84 0.7,1.79 0.7,2.8 0,3.31 -2.69,6 -6,6v-3l-4,4 4,4v-3c4.42,0 8,-3.58 8,-8 0,-1.57 -0.46,-3.03 -1.24,-4.26z"
    private const val ARROW_IN = "M20,5.41L18.59,4 7,15.59V9H5v10h10v-2H8.41z"
    private const val ARROW_OUT = "M9,5v2h6.59L4,18.59 5.41,20 17,8.41V15h2V5z"
    private const val MORE =
        "M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z"
    private const val GROUP =
        "M16,11c1.66,0 2.99,-1.34 2.99,-3S17.66,5 16,5c-1.66,0 -3,1.34 -3,3s1.34,3 3,3zM8,11c1.66,0 2.99,-1.34 2.99,-3S9.66,5 8,5C6.34,5 5,6.34 5,8s1.34,3 3,3zM8,13c-2.33,0 -7,1.17 -7,3.5V19h14v-2.5c0,-2.33 -4.67,-3.5 -7,-3.5zM16,13c-0.29,0 -0.62,0.02 -0.97,0.05 1.16,0.84 1.97,1.97 1.97,3.45V19h6v-2.5c0,-2.33 -4.67,-3.5 -7,-3.5z"
    private const val CHECK = "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z"
    private const val SEARCH =
        "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5zM9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z"
    private const val FORWARD = "M12,8V4l8,8 -8,8v-4H4V8z"
    private const val CLOSE =
        "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z"
    private const val PERSON =
        "M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zM12,14c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z"
    private const val EDIT =
        "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75L3,17.25zM20.71,7.04c0.39,-0.39 0.39,-1.02 0,-1.41l-2.34,-2.34c-0.39,-0.39 -1.02,-0.39 -1.41,0l-1.83,1.83 3.75,3.75 1.83,-1.83z"
    private const val CAMERA =
        "M9,2L7.17,4H4c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2h-3.17L15,2H9zM12,17c-2.76,0 -5,-2.24 -5,-5s2.24,-5 5,-5 5,2.24 5,5 -2.24,5 -5,5zM12,9.2c-1.54,0 -2.8,1.26 -2.8,2.8s1.26,2.8 2.8,2.8 2.8,-1.26 2.8,-2.8 -1.26,-2.8 -2.8,-2.8z"
    private const val CHAT = "M20,2H4c-1.1,0 -2,0.9 -2,2v18l4,-4h14c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2z"

    val Call: ImageVector by lazy { vec("call", CALL) }
    val Video: ImageVector by lazy { vec("video", VIDEO) }
    val VideoOff: ImageVector by lazy { vec("videoOff", VIDEO, slash = true) }
    val End: ImageVector by lazy { vec("end", END) }
    val Mic: ImageVector by lazy { vec("mic", MIC) }
    val MicOff: ImageVector by lazy { vec("micOff", MIC, slash = true) }
    val Vol: ImageVector by lazy { vec("vol", VOL) }
    val Vol0: ImageVector by lazy { vec("vol0", VOL0) }
    val Flip: ImageVector by lazy { vec("flip", FLIP) }
    val ArrowIn: ImageVector by lazy { vec("arrowIn", ARROW_IN) }
    val ArrowOut: ImageVector by lazy { vec("arrowOut", ARROW_OUT) }
    val More: ImageVector by lazy { vec("more", MORE) }
    val Chat: ImageVector by lazy { vec("chat", CHAT) }
    val Group: ImageVector by lazy { vec("group", GROUP) }
    val Check: ImageVector by lazy { vec("check", CHECK) }
    val Search: ImageVector by lazy { vec("search", SEARCH) }
    val Forward: ImageVector by lazy { vec("forward", FORWARD) }
    val Close: ImageVector by lazy { vec("close", CLOSE) }
    val Person: ImageVector by lazy { vec("person", PERSON) }
    val Edit: ImageVector by lazy { vec("edit", EDIT) }
    val Camera: ImageVector by lazy { vec("camera", CAMERA) }
}

/** A round button with a vector icon inside. */
@Composable
fun IconCircle(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    bg: Color,
    tint: Color,
    description: String? = null,
) {
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = description, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

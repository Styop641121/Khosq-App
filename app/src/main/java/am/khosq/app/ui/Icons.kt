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
import androidx.compose.ui.graphics.StrokeJoin
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
    private const val BOOKMARK = "M17,3H7c-1.1,0 -1.99,0.9 -1.99,2L5,21l7,-3 7,3V5c0,-1.1 -0.9,-2 -2,-2z"
    private const val DOWN = "M7.41,8.59L12,13.17l4.59,-4.58L18,10l-6,6 -6,-6 1.41,-1.41z"
    private const val REPLY = "M10,9V5l-7,7 7,7v-4.1c5,0 8.5,1.6 11,5.1 -1,-5 -4,-10 -11,-11z"
    private const val STAR = "M12,17.27L18.18,21l-1.64,-7.03L22,9.24l-7.19,-0.61L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21z"
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

    /** Outline icons: one visual weight (1.8 stroke, round ends) for everything in the chrome of the app. */
    private fun line(name: String, strokes: List<String>, fill: String? = null): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        if (fill != null) {
            // a faint fill under the outline: the "selected" look of the navigation icons
            b.addPath(addPathNodes(fill), fill = SolidColor(Color.Black), fillAlpha = 0.22f)
        }
        for (d in strokes) {
            b.addPath(
                addPathNodes(d),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    private const val C_CHAT =
        "M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"
    private const val C_PHONE =
        "M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07 19.5 19.5 0 0 1-6-6 19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 4.11 2h3a2 2 0 0 1 2 1.72 12.84 12.84 0 0 0 .7 2.81 2 2 0 0 1-.45 2.11L8.09 9.91a16 16 0 0 0 6 6l1.27-1.27a2 2 0 0 1 2.11-.45 12.84 12.84 0 0 0 2.81.7A2 2 0 0 1 22 16.92z"
    private const val C_GEAR =
        "M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"
    private const val C_CIRCLE = "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z"
    private const val C_BELL = "M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"
    private const val C_STAR = "M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z"
    private const val C_BOOKMARK = "M19 21l-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"
    private const val C_USER_BODY = "M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"
    private const val C_USER_HEAD = "M12 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8z"

    // navigation: a plain outline, and the same shape with the faint fill when selected
    val ChatLine: ImageVector by lazy { line("chatLine", listOf(C_CHAT)) }
    val ChatFill: ImageVector by lazy { line("chatFill", listOf(C_CHAT), C_CHAT) }
    val PhoneLine: ImageVector by lazy { line("phoneLine", listOf(C_PHONE)) }
    val PhoneFill: ImageVector by lazy { line("phoneFill", listOf(C_PHONE), C_PHONE) }
    val CircleLine: ImageVector by lazy { line("circleLine", listOf(C_CIRCLE, "M12 8v8", "M8 12h8")) }
    val CircleFill: ImageVector by lazy { line("circleFill", listOf(C_CIRCLE, "M12 8v8", "M8 12h8"), C_CIRCLE) }
    val GearLine: ImageVector by lazy { line("gearLine", listOf(C_GEAR, "M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z")) }
    val GearFill: ImageVector by lazy { line("gearFill", listOf(C_GEAR, "M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z"), C_GEAR) }

    // actions
    val Chat: ImageVector by lazy { line("chat", listOf(C_CHAT)) }
    val Back: ImageVector by lazy { line("back", listOf("M19 12H5", "M12 19l-7-7 7-7")) }
    val ChevronRight: ImageVector by lazy { line("chevronRight", listOf("M9 18l6-6-6-6")) }
    val Down: ImageVector by lazy { line("down", listOf("M6 9l6 6 6-6")) }
    val Plus: ImageVector by lazy { line("plus", listOf("M12 5v14", "M5 12h14")) }
    val Close: ImageVector by lazy { line("close", listOf("M18 6L6 18", "M6 6l12 12")) }
    val Check: ImageVector by lazy { line("check", listOf("M20 6L9 17l-5-5")) }
    val DoubleCheck: ImageVector by lazy { line("doubleCheck", listOf("M18 6L7 17l-5-5", "M22 10l-7.5 7.5L13 16")) }
    val Clock: ImageVector by lazy { line("clock", listOf(C_CIRCLE, "M12 7v5l3 2")) }
    val Search: ImageVector by lazy { line("search", listOf("M11 3a8 8 0 1 0 0 16 8 8 0 0 0 0-16z", "M21 21l-4.35-4.35")) }
    val Send: ImageVector by lazy { line("send", listOf("M22 2L11 13", "M22 2l-7 20-4-9-9-4 20-7z")) }
    val Attach: ImageVector by lazy {
        line(
            "attach",
            listOf("M21.44 11.05l-9.19 9.19a6 6 0 0 1-8.49-8.49l9.19-9.19a4 4 0 0 1 5.66 5.66l-9.2 9.19a2 2 0 0 1-2.83-2.83l8.49-8.48"),
        )
    }
    val MicLine: ImageVector by lazy {
        line("micLine", listOf("M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3z", "M19 10v2a7 7 0 0 1-14 0v-2", "M12 19v3"))
    }
    val Forward: ImageVector by lazy { line("forward", listOf("M15 14l5-5-5-5", "M4 20v-7a4 4 0 0 1 4-4h12")) }
    val Reply: ImageVector by lazy { line("reply", listOf("M9 14L4 9l5-5", "M20 20v-7a4 4 0 0 0-4-4H4")) }
    val Edit: ImageVector by lazy { line("edit", listOf("M17 3a2.83 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5L17 3z")) }
    val Trash: ImageVector by lazy {
        line("trash", listOf("M3 6h18", "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6", "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"))
    }
    val Copy: ImageVector by lazy {
        line("copy", listOf("M11 9h9a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-9a2 2 0 0 1-2-2v-9a2 2 0 0 1 2-2z", "M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"))
    }
    val Pin: ImageVector by lazy {
        line(
            "pin",
            listOf(
                "M12 17v5",
                "M5 17h14v-1.76a2 2 0 0 0-1.11-1.79l-1.78-.9A2 2 0 0 1 15 10.76V6h1a2 2 0 0 0 0-4H8a2 2 0 0 0 0 4h1v4.76a2 2 0 0 1-1.11 1.79l-1.78.9A2 2 0 0 0 5 15.24z",
            ),
        )
    }
    val Image: ImageVector by lazy {
        line("image", listOf("M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M8.5 7a1.5 1.5 0 1 0 0 3 1.5 1.5 0 0 0 0-3z", "M21 15l-5-5L5 21"))
    }
    val Camera: ImageVector by lazy {
        line("camera", listOf("M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z", "M12 9a4 4 0 1 0 0 8 4 4 0 0 0 0-8z"))
    }
    val Download: ImageVector by lazy { line("download", listOf("M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", "M7 10l5 5 5-5", "M12 15V3")) }
    val Archive: ImageVector by lazy { line("archive", listOf("M21 8v13H3V8", "M1 3h22v5H1z", "M10 12h4")) }
    val Link: ImageVector by lazy {
        line("link", listOf("M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71", "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"))
    }
    val Assistant: ImageVector by lazy {
        line(
            "assistant",
            listOf(
                "M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z",
                "M19 15l.8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8z",
            ),
        )
    }
    val Tasks: ImageVector by lazy { line("tasks", listOf("M9 11l3 3L22 4", "M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11")) }
    val Memory: ImageVector by lazy {
        line(
            "memory",
            listOf(
                "M12 8c4.97 0 9-1.34 9-3s-4.03-3-9-3-9 1.34-9 3 4.03 3 9 3z",
                "M21 12c0 1.66-4 3-9 3s-9-1.34-9-3",
                "M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5",
            ),
        )
    }
    val Poll: ImageVector by lazy { line("poll", listOf("M18 20V10", "M12 20V4", "M6 20v-6")) }
    val Location: ImageVector by lazy {
        line("location", listOf("M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z", "M12 7a3 3 0 1 0 0 6 3 3 0 0 0 0-6z"))
    }

    val VideoLine: ImageVector by lazy {
        line("videoLine", listOf("M23 7l-7 5 7 5V7z", "M3 5h11a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z"))
    }
    val Eye: ImageVector by lazy {
        line("eye", listOf("M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z", "M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z"))
    }
    val Play: ImageVector by lazy { vec("play", "M8,5v14l11,-7z") }
    val Pause: ImageVector by lazy { vec("pause", "M6,5h4v14H6z M14,5h4v14h-4z") }

    // people
    val Person: ImageVector by lazy { line("person", listOf(C_USER_BODY, C_USER_HEAD)) }
    val PersonAdd: ImageVector by lazy {
        line("personAdd", listOf("M16 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2", "M8.5 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8z", "M20 8v6", "M23 11h-6"))
    }
    val Group: ImageVector by lazy {
        line("group", listOf("M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2", "M9 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8z", "M23 21v-2a4 4 0 0 0-3-3.87", "M16 3.13a4 4 0 0 1 0 7.75"))
    }
    val Bookmark: ImageVector by lazy { line("bookmark", listOf(C_BOOKMARK)) }
    val BookmarkFill: ImageVector by lazy { line("bookmarkFill", listOf(C_BOOKMARK), C_BOOKMARK) }
    val Star: ImageVector by lazy { line("star", listOf(C_STAR)) }
    val StarFill: ImageVector by lazy { line("starFill", listOf(C_STAR), C_STAR) }

    // settings
    val Bell: ImageVector by lazy { line("bell", listOf(C_BELL, "M13.73 21a2 2 0 0 1-3.46 0")) }
    val BellOff: ImageVector by lazy {
        line(
            "bellOff",
            listOf("M13.73 21a2 2 0 0 1-3.46 0", "M18.63 13A17.89 17.89 0 0 1 18 8", "M6.26 6.26A5.86 5.86 0 0 0 6 8c0 7-3 9-3 9h14", "M18 8a6 6 0 0 0-9.33-5", "M2 2l20 20"),
        )
    }
    val Lock: ImageVector by lazy { line("lock", listOf("M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z", "M7 11V7a5 5 0 0 1 10 0v4")) }
    val Globe: ImageVector by lazy {
        line("globe", listOf("M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20z", "M2 12h20", "M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"))
    }
    val Moon: ImageVector by lazy { line("moon", listOf("M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z")) }
    val Info: ImageVector by lazy { line("info", listOf(C_CIRCLE, "M12 16v-4", "M12 8h.01")) }
    val EyeOff: ImageVector by lazy {
        line(
            "eyeOff",
            listOf("M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94", "M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19", "M14.12 14.12a3 3 0 1 1-4.24-4.24", "M2 2l20 20"),
        )
    }
    val Shield: ImageVector by lazy { line("shield", listOf("M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z")) }
    val Translate: ImageVector by lazy {
        line("translate", listOf("m5 8 6 6", "m4 14 6-6 2-3", "M2 5h12", "M7 2h1", "m22 22-5-10-5 10", "M14 18h6"))
    }
    val Sparkle: ImageVector by lazy { line("sparkle", listOf("M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9L12 3z", "M19 16l.7 1.8 1.8.7-1.8.7L19 21l-.7-1.8-1.8-.7 1.8-.7L19 16z")) }

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

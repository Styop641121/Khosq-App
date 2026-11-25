package am.khosq.app.ui

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Glass, glow and the special badges of the obsidian look.
 *
 * Android cannot blur what is behind a layer on every phone, so "glass" here is a translucent tinted fill, a hairline of light on
 * the edge and a faint sheen along the top. It reads as glass on the dark base and costs nothing to draw.
 */

/** A soft coloured light around a shape. Phones older than Android 9 cannot colour a shadow, so there it is left out (a black halo would look dirty). */
@Composable
fun Modifier.glow(color: Color, radius: Dp = 16.dp, shape: Shape = CircleShape, strength: Float = 0.5f): Modifier {
    val k = KTheme.colors
    val a = (if (k.dark) strength else strength * 0.55f).coerceIn(0f, 1f)
    // one way out only (no early return in a composable): the shadow, or nothing on old phones
    return if (Build.VERSION.SDK_INT >= 28 && radius.value > 0f) {
        this.shadow(elevation = radius, shape = shape, clip = false, ambientColor = color.copy(alpha = a), spotColor = color.copy(alpha = a))
    } else {
        this
    }
}

/** A soft shadow under a floating bar in the light theme. On the dark base a shadow cannot be seen, so there it is left out. */
@Composable
fun Modifier.floatShadow(shape: Shape): Modifier {
    val dark = KTheme.colors.dark
    return if (dark) this else this.shadow(elevation = 14.dp, shape = shape, clip = false, ambientColor = Color(0x33101426), spotColor = Color(0x33101426))
}

/** The look of a glass surface: the translucent fill, the sheen along the top edge and the hairline. [strong] is for floating things. */
@Composable
fun Modifier.glassSurface(shape: Shape, strong: Boolean = false): Modifier {
    val k = KTheme.colors
    val fill = if (strong) k.glass else k.glass.copy(alpha = k.glass.alpha * 0.78f)
    return this
        .clip(shape)
        .background(fill)
        .background(Brush.verticalGradient(listOf(k.sheen, Color.Transparent)))
        .border(1.dp, k.glassEdge, shape)
}

/** A bar at the top of a screen: glass with a hairline under it. It fills the width. */
@Composable
fun Modifier.glassBar(): Modifier {
    val k = KTheme.colors
    val edge = k.glassEdge
    return this
        .fillMaxWidth()
        .background(k.glass)
        .background(Brush.verticalGradient(listOf(k.sheen, Color.Transparent)))
        .drawBehind {
            val h = 1.dp.toPx()
            drawRect(edge, topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
        }
}

/** A small frosted capsule: the date between two days, an event of a group, a call in the history of a chat. */
@Composable
fun FrostedPill(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val k = KTheme.colors
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(k.glass.copy(alpha = 0.74f))
            .border(1.dp, k.glassEdge, CircleShape)
            .padding(horizontal = Space.m, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * The round SOS button of the chat list: a crimson glass badge with a soft red light. A tap opens the SOS sheet.
 */
@Composable
fun SosButton(onClick: () -> Unit, description: String, modifier: Modifier = Modifier, size: Dp = 38.dp) {
    val k = KTheme.colors
    Box(modifier = modifier.size(44.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(size)
                .glow(k.crimson, radius = 10.dp, strength = 0.38f)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(k.crimson.copy(alpha = 0.30f), k.crimson.copy(alpha = 0.12f))))
                .border(1.dp, k.crimson.copy(alpha = 0.45f), CircleShape)
                .pressable(onClick = onClick, pressedScale = 0.9f),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = KIcons.Alert, contentDescription = description, tint = k.danger, modifier = Modifier.size(20.dp))
        }
    }
}

/** The small "SOS" mark inside a chat list row or a quote, instead of the emoji: crimson glass with the word. */
@Composable
fun SosTag(modifier: Modifier = Modifier) {
    val k = KTheme.colors
    Text(
        text = "SOS",
        color = k.danger,
        style = TextStyle(fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp),
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(k.crimson.copy(alpha = 0.16f))
            .border(1.dp, k.crimson.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * The button of the KHOSQ assistant: a dark glass disc with a thin indigo-to-cyan ring, a sparkle inside and a neon aura that
 * breathes slowly (still when the person turned animations off).
 */
@Composable
fun AssistantButton(onClick: () -> Unit, description: String, modifier: Modifier = Modifier, size: Dp = 38.dp) {
    val k = KTheme.colors
    val reduce = LocalReduceMotion.current
    // no animation at all when the person turned animations off; otherwise only the alpha is read while drawing
    val pulse: State<Float>? = if (reduce) {
        null
    } else {
        rememberInfiniteTransition(label = "aura")
            .animateFloat(0.35f, 0.85f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Reverse), label = "auraPulse")
    }
    Box(modifier = modifier.size(44.dp), contentAlignment = Alignment.Center) {
        // the aura: a soft cloud behind the disc, only its alpha moves, so nothing is measured again
        Box(
            modifier = Modifier
                .size(size + 10.dp)
                .graphicsLayer { alpha = pulse?.value ?: 0.6f }
                .background(Brush.radialGradient(listOf(k.aura.copy(alpha = 0.34f), k.glow.copy(alpha = 0.12f), Color.Transparent)), CircleShape),
        )
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(k.surface)
                .background(Brush.radialGradient(listOf(k.aura.copy(alpha = 0.20f), Color.Transparent)))
                .border(1.25.dp, Brush.linearGradient(listOf(BrandColorsLight[0], k.aura)), CircleShape)
                .pressable(onClick = onClick, pressedScale = 0.9f),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = KIcons.Assistant, contentDescription = description, tint = k.aura, modifier = Modifier.size(20.dp))
        }
    }
}

/** The small round mark that tells what happened to a call: a vector arrow in a tinted disc. */
@Composable
fun CallStatusBadge(icon: ImageVector, tone: Color, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(tone.copy(alpha = 0.14f))
            .border(1.dp, tone.copy(alpha = 0.28f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tone, modifier = Modifier.size(size * 0.62f))
    }
}

/** A hairline across the full width of a surface, used under glass bars. */
@Composable
fun GlassDivider(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().height(1.dp).background(KTheme.colors.glassEdge))
}

/** The vector sign in front of the preview of a chat for kinds of message that are not plain text; null when there is none (or the SOS tag is used). */
fun previewIconOf(kind: String): ImageVector? = when (kind) {
    "img" -> KIcons.Image
    "voice" -> KIcons.MicLine
    "loc" -> KIcons.Location
    "poll" -> KIcons.Poll
    "call_missed" -> KIcons.PhoneMissed
    else -> null
}

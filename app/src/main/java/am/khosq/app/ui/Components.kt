package am.khosq.app.ui

import am.khosq.app.Lang
import am.khosq.app.R
import am.khosq.app.data.Chat
import am.khosq.app.data.ProfileStore
import am.khosq.app.tr
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun formatDur(s: Int): String = (s / 60).toString() + ":" + (s % 60).toString().padStart(2, '0')

fun formatSeen(ts: Long): String {
    val cal = java.util.Calendar.getInstance()
    val today = cal.get(java.util.Calendar.DAY_OF_YEAR) to cal.get(java.util.Calendar.YEAR)
    cal.timeInMillis = ts
    val same = (cal.get(java.util.Calendar.DAY_OF_YEAR) to cal.get(java.util.Calendar.YEAR)) == today
    return if (same) formatTime(ts) else SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(ts))
}

fun formatTime(ts: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

// ---------------------------------------------------------------------------------------------
// People
// ---------------------------------------------------------------------------------------------

/** One dark-toned avatar background: a deep base and two soft clouds of colour, so no two corners look the same. */
private class Mesh(val base: Color, val cloudA: Color, val cloudB: Color)

/** Eight dark meshes. The mesh comes from the person's ID, so the same person always looks the same. */
private val AvatarMeshes = listOf(
    Mesh(Color(0xFF191B3D), Color(0xFF4F46E5), Color(0xFF8B5CF6)),
    Mesh(Color(0xFF10253A), Color(0xFF0EA5E9), Color(0xFF6366F1)),
    Mesh(Color(0xFF0F2D2A), Color(0xFF14B8A6), Color(0xFF0EA5E9)),
    Mesh(Color(0xFF2D1C2F), Color(0xFFDB2777), Color(0xFF7C3AED)),
    Mesh(Color(0xFF2E2215), Color(0xFFF59E0B), Color(0xFFDC2626)),
    Mesh(Color(0xFF13291F), Color(0xFF10B981), Color(0xFF06B6D4)),
    Mesh(Color(0xFF251A3E), Color(0xFF8B5CF6), Color(0xFFEC4899)),
    Mesh(Color(0xFF14223A), Color(0xFF3B82F6), Color(0xFF22D3EE)),
)

/**
 * The person's photo, or the first letter on a dark mesh gradient picked from the ID.
 * [online] adds the small green dot in the corner. ([circle] is kept for older call sites: every avatar is round now.)
 * With [zoomable] a tap on a real photo opens it large (an avatar without a photo does nothing, so the [modifier] click still works).
 */
@Composable
fun Avatar(
    name: String,
    seed: String,
    size: Dp = 52.dp,
    modifier: Modifier = Modifier,
    circle: Boolean = true,
    online: Boolean = false,
    zoomable: Boolean = false,
) {
    var h = 0
    for (c in seed) h = (h * 31 + c.code) and 0x7fffffff
    val mesh = AvatarMeshes[h % AvatarMeshes.size]
    val photo = ProfileStore.photos[seed]
    val k = KTheme.colors
    var big by remember { mutableStateOf(false) }
    val canZoom = zoomable && photo != null
    Box(modifier = modifier.size(size)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(mesh.base)
                .drawBehind {
                    // a radial gradient with no radius is an error on Android, so nothing is drawn for an empty box
                    if (this.size.minDimension > 1f) {
                        drawRect(
                            Brush.radialGradient(
                                colors = listOf(mesh.cloudA.copy(alpha = 0.78f), Color.Transparent),
                                center = Offset(this.size.width * 0.2f, this.size.height * 0.15f),
                                radius = this.size.minDimension * 0.95f,
                            ),
                        )
                        drawRect(
                            Brush.radialGradient(
                                colors = listOf(mesh.cloudB.copy(alpha = 0.62f), Color.Transparent),
                                center = Offset(this.size.width * 0.88f, this.size.height * 0.92f),
                                radius = this.size.minDimension * 0.9f,
                            ),
                        )
                    }
                }
                .border(1.dp, Color(0x1FFFFFFF), CircleShape)
                .then(if (canZoom) Modifier.clickable { big = true } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (photo != null) {
                Image(
                    bitmap = photo,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = name.trim().take(1).uppercase(),
                    color = Color(0xF2FFFFFF),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (size.value * 0.40f).sp,
                    letterSpacing = 0.5.sp,
                )
            }
        }
        if (big && canZoom) AvatarViewer(uid = seed, name = name, onClose = { big = false })
        if (online) {
            val dot = (size.value * 0.27f).coerceAtLeast(11f).dp
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(dot)
                    .clip(CircleShape)
                    .background(k.bg)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(k.success),
            )
        }
    }
}

/** "Saved messages" has no name of its own in the data, so each phone writes it in its own language. */
fun Chat.label(): String = if (saved) tr("Պահպանված նամակներ") else peerName

@Composable
fun SavedAvatar(size: Dp = 52.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(BrandColors))
            .border(1.dp, Color(0x33FFFFFF), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = KIcons.BookmarkFill, contentDescription = null, tint = InkOnBrand, modifier = Modifier.size(size * 0.5f))
    }
}

// ---------------------------------------------------------------------------------------------
// Buttons
// ---------------------------------------------------------------------------------------------

/** The main button of a screen: accent gradient, white text. */
@Composable
fun GradientButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val k = KTheme.colors
    val fill = if (enabled) Brush.horizontalGradient(BrandColors) else Brush.horizontalGradient(listOf(k.surfaceHighest, k.surfaceHighest))
    val shape = RoundedCornerShape(Corner.m)
    Box(
        modifier = modifier
            .height(Dim.button)
            .then(if (enabled) Modifier.glow(k.glow, radius = 12.dp, shape = shape, strength = 0.32f) else Modifier)
            .clip(shape)
            .background(fill)
            // a thin light along the top edge: the button looks lit from above
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = if (enabled) 0.30f else 0.06f), Color.White.copy(alpha = 0.04f))), shape)
            .pressable(onClick = onClick, enabled = enabled, pressedScale = 0.97f),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (enabled) InkOnBrand else k.textTertiary,
            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
            modifier = Modifier.padding(horizontal = Space.l),
        )
    }
}

/** A calm secondary button: a tinted surface with the text in the accent colour. */
@Composable
fun SoftButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    val k = KTheme.colors
    val shape = RoundedCornerShape(Corner.m)
    Box(
        modifier = modifier
            .height(Dim.button)
            .clip(shape)
            .background(if (danger) k.danger.copy(alpha = 0.12f) else k.surfaceHigh)
            .border(1.dp, if (danger) k.danger.copy(alpha = 0.22f) else k.line, shape)
            .pressable(onClick = onClick, enabled = enabled, pressedScale = 0.97f),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (!enabled) k.textTertiary else if (danger) k.danger else k.text,
            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            modifier = Modifier.padding(horizontal = Space.l),
        )
    }
}

/** An icon button for bars and rows: a 44dp touch target around a 24dp outline icon. */
@Composable
fun KIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    tint: Color = KTheme.colors.text,
    size: Dp = 44.dp,
    iconSize: Dp = 24.dp,
    background: Color = Color.Transparent,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .pressable(onClick = onClick, pressedScale = 0.9f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = description, tint = tint, modifier = Modifier.size(iconSize))
    }
}

// ---------------------------------------------------------------------------------------------
// Brand
// ---------------------------------------------------------------------------------------------

@Composable
fun BrandTitle(size: TextUnit = 22.sp) {
    Text(
        text = "KHOSQ",
        style = TextStyle(
            brush = Brush.linearGradient(BrandColorsLight),
            fontSize = size,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 3.sp,
        ),
    )
}

/** The small rounded mark with the Armenian letter Խ, for bars. */
@Composable
fun BrandMark(size: Dp) {
    val shape = RoundedCornerShape(size * 0.3f)
    Box(
        modifier = Modifier
            .size(size)
            .glow(KTheme.colors.glow, radius = 8.dp, shape = shape, strength = 0.35f)
            .clip(shape)
            .background(Brush.linearGradient(BrandColors))
            .border(1.dp, Color(0x38FFFFFF), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "Խ", color = InkOnBrand, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.5f).sp)
    }
}

/** The real KHOSQ logo on the accent gradient, for the splash and welcome screens. */
@Composable
fun BrandLogo(size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(Brush.linearGradient(listOf(Color(0xFF3F4FE6), Color(0xFF7A4FEA)))),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_khosq_fg),
            contentDescription = "KHOSQ",
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size * 1.38f),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Small building blocks
// ---------------------------------------------------------------------------------------------

/** A hairline between rows. [inset] starts it after the avatar, the way messengers do. */
@Composable
fun KDivider(modifier: Modifier = Modifier, inset: Dp = 0.dp) {
    Box(modifier = modifier.fillMaxWidth().padding(start = inset).height(Dp.Hairline).background(KTheme.colors.line))
}

/** The title above a group of settings rows or a list section. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = KTheme.colors.textSecondary,
        style = TextStyle(fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp),
        modifier = modifier.padding(start = Space.l, end = Space.l, top = Space.l, bottom = Space.s),
    )
}

/** The counter on a chat or a tab. Grey while the chat is muted. */
@Composable
fun CountBadge(count: Int, muted: Boolean = false, modifier: Modifier = Modifier) {
    val k = KTheme.colors
    val bg = if (muted) Modifier.background(k.textTertiary.copy(alpha = 0.55f), CircleShape) else Modifier.background(Brush.horizontalGradient(BrandColors), CircleShape)
    Box(
        modifier = modifier.widthIn(min = 22.dp).height(22.dp).then(bg).padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 99) "99+" else count.toString(),
            color = Color.White,
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold, lineHeight = 14.sp),
        )
    }
}

/** A friendly empty state: a soft round icon, a title and one line of help. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    text: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val k = KTheme.colors
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = Space.xxl, vertical = Space.huge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .glow(k.glow, radius = 18.dp, strength = 0.22f)
                .clip(CircleShape)
                .background(k.surface)
                .background(Brush.radialGradient(listOf(k.accent.copy(alpha = 0.22f), Color.Transparent)))
                .border(1.dp, k.glassEdge, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = k.accent, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(Space.xs))
        Text(text = title, color = k.text, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold), textAlign = TextAlign.Center)
        if (text != null) {
            Text(text = text, color = k.textSecondary, style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp), textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(Space.s))
            action()
        }
    }
}

/** A grey bar that gently pulses while something loads. */
@Composable
fun SkeletonBar(modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(Corner.xs)) {
    val reduce = LocalReduceMotion.current
    val a = if (reduce) {
        0.6f
    } else {
        val t = rememberInfiniteTransition(label = "skeleton")
        val v by t.animateFloat(0.35f, 0.75f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
        v
    }
    Box(modifier = modifier.alpha(a).clip(shape).background(KTheme.colors.surfaceHighest))
}

/** A fake chat row for the first moment, before the list arrives. */
@Composable
fun SkeletonRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SkeletonBar(Modifier.size(Dim.avatarRow), CircleShape)
        Column(Modifier.padding(start = Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            SkeletonBar(Modifier.height(14.dp).fillMaxWidth(0.45f))
            SkeletonBar(Modifier.height(12.dp).fillMaxWidth(0.75f))
        }
    }
}

fun dayKey(ts: Long): Int {
    val c = Calendar.getInstance()
    c.timeInMillis = ts
    return c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR)
}

/** "Today", "Yesterday" or "12 November" for the date lines inside a chat. */
fun dayLabel(ts: Long): String {
    val now = System.currentTimeMillis()
    val d = dayKey(ts)
    if (d == dayKey(now)) return tr("Այսօր")
    if (d == dayKey(now - 24L * 3600L * 1000L)) return tr("Երեկ")
    val sameYear = d / 400 == dayKey(now) / 400
    return SimpleDateFormat(if (sameYear) "d MMMM" else "d MMMM yyyy", Locale(Lang.code)).format(Date(ts))
}

// ---------------------------------------------------------------------------------------------
// Inputs
// ---------------------------------------------------------------------------------------------

/** Colours for every outlined text field in the app, so they all look the same. */
@Composable
fun kFieldColors(): TextFieldColors {
    val k = KTheme.colors
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = k.text,
        unfocusedTextColor = k.text,
        focusedContainerColor = k.surface,
        unfocusedContainerColor = k.surface,
        cursorColor = k.accent,
        focusedBorderColor = k.accent,
        unfocusedBorderColor = k.lineStrong,
        focusedLabelColor = k.accent,
        unfocusedLabelColor = k.textSecondary,
        focusedPlaceholderColor = k.textTertiary,
        unfocusedPlaceholderColor = k.textTertiary,
    )
}

/** The rounded search box used on the chat list and inside a chat. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val k = KTheme.colors
    Row(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(k.surfaceHigh)
            .border(1.dp, k.line, RoundedCornerShape(23.dp))
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = KIcons.Search, contentDescription = null, tint = k.textTertiary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(text = placeholder, color = k.textTertiary, style = TextStyle(fontSize = 16.sp), maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = k.text, fontSize = 16.sp),
                cursorBrush = SolidColor(k.accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            )
        }
        if (value.isNotEmpty()) {
            KIconButton(
                icon = KIcons.Close,
                onClick = { onValueChange("") },
                size = 32.dp,
                iconSize = 18.dp,
                tint = k.textTertiary,
            )
        }
    }
}

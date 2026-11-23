package am.khosq.app.ui

import am.khosq.app.Lang
import am.khosq.app.data.Chat
import am.khosq.app.tr
import androidx.compose.material3.Icon
import java.util.Calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import am.khosq.app.data.ProfileStore
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
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

private fun avatarShape(k: Int): Shape = when (k) {
    0 -> CircleShape
    1 -> RoundedCornerShape(17.dp)
    2 -> RoundedCornerShape(topStartPercent = 50, topEndPercent = 50, bottomEndPercent = 50, bottomStartPercent = 10)
    3 -> RoundedCornerShape(topStartPercent = 10, topEndPercent = 50, bottomEndPercent = 50, bottomStartPercent = 50)
    4 -> RoundedCornerShape(topStartPercent = 50, topEndPercent = 10, bottomEndPercent = 50, bottomStartPercent = 10)
    else -> RoundedCornerShape(30)
}

/** The person's photo, or the first letter on one of six shapes picked from the ID, so the same person always looks the same. */
@Composable
fun Avatar(name: String, seed: String, size: Dp = 52.dp, modifier: Modifier = Modifier, circle: Boolean = false) {
    var h = 0
    for (c in seed) h = (h * 31 + c.code) and 0x7fffffff
    val k = h % 6
    val brush: Brush
    val ink: Color
    when (k) {
        0 -> { brush = Brush.linearGradient(listOf(Color(0xFF3042C4), Color(0xFF1A2270))); ink = Color(0xFFFFCB52) }
        1 -> { brush = Brush.linearGradient(BrandColors); ink = InkOnBrand }
        2 -> { brush = Brush.linearGradient(listOf(Color(0xFF6D8BFF), Color(0xFF3D5AFE))); ink = Color.White }
        3 -> { brush = Brush.linearGradient(listOf(Color(0xFFFFE3A8), Color(0xFFFFC24D))); ink = InkOnBrand }
        4 -> { brush = Brush.linearGradient(listOf(Color(0xFF1E2878), Color(0xFF0B1237))); ink = Color(0xFFFFCB52) }
        else -> { brush = Brush.linearGradient(listOf(Color(0xFF9DB0FF), Color(0xFF6A7CFF))); ink = Color(0xFF0B1230) }
    }
    val photo = ProfileStore.photos[seed]
    Box(
        modifier = modifier.size(size).clip(if (circle) CircleShape else avatarShape(k)).background(brush),
        contentAlignment = Alignment.Center,
    ) {
        if (photo != null) {
            Image(
                bitmap = photo,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        } else {
            Text(
                text = name.trim().take(1).uppercase(),
                color = ink,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.42f).sp,
            )
        }
    }
}

@Composable
fun GradientButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val fill = if (enabled) Brush.linearGradient(BrandColors) else Brush.linearGradient(listOf(Color(0x66888888), Color(0x66888888)))
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(fill)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = InkOnBrand, fontWeight = FontWeight.ExtraBold)
    }
}

@Composable
fun BrandTitle(size: TextUnit = 22.sp) {
    val colors = if (isDark()) BrandColors else listOf(Color(0xFF2F33C4), Color(0xFFB4308A), Color(0xFFE5483F))
    Text(
        text = "KHOSQ",
        style = TextStyle(
            brush = Brush.linearGradient(colors),
            fontSize = size,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 4.sp,
        ),
    )
}

@Composable
fun BrandMark(size: Dp) {
    Box(
        modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(Brush.linearGradient(BrandColors)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "Խ", color = InkOnBrand, fontWeight = FontWeight.ExtraBold, fontSize = (size.value * 0.5f).sp)
    }
}

/** "Saved messages" has no name of its own in the data, so each phone writes it in its own language. */
fun Chat.label(): String = if (saved) tr("Պահպանված նամակներ") else peerName

@Composable
fun SavedAvatar(size: Dp = 52.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(Brush.linearGradient(BrandColors)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = KIcons.Bookmark, contentDescription = null, tint = InkOnBrand, modifier = Modifier.size(size * 0.5f))
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

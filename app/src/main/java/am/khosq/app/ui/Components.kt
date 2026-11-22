package am.khosq.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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

fun formatTime(ts: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

private fun avatarShape(k: Int): Shape = when (k) {
    0 -> CircleShape
    1 -> RoundedCornerShape(17.dp)
    2 -> RoundedCornerShape(topStartPercent = 50, topEndPercent = 50, bottomEndPercent = 50, bottomStartPercent = 10)
    3 -> RoundedCornerShape(topStartPercent = 10, topEndPercent = 50, bottomEndPercent = 50, bottomStartPercent = 50)
    4 -> RoundedCornerShape(topStartPercent = 50, topEndPercent = 10, bottomEndPercent = 50, bottomStartPercent = 10)
    else -> RoundedCornerShape(30)
}

/** The first letter on one of six shapes, picked from the ID, so the same person always looks the same. */
@Composable
fun Avatar(name: String, seed: String, size: Dp = 52.dp, modifier: Modifier = Modifier) {
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
    Box(
        modifier = modifier.size(size).clip(avatarShape(k)).background(brush),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().take(1).uppercase(),
            color = ink,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.42f).sp,
        )
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
    val colors = if (isSystemInDarkTheme()) BrandColors else listOf(Color(0xFF2F33C4), Color(0xFFB4308A), Color(0xFFE5483F))
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

package am.khosq.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** "Ararat dusk": night indigo, apricot to pomegranate light. */
val BrandColors = listOf(Color(0xFFFFCB52), Color(0xFFFF9A3D), Color(0xFFFF6B57))
val InkOnBrand = Color(0xFF1B1000)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB23F),
    onPrimary = Color(0xFF1B1000),
    background = Color(0xFF090C22),
    onBackground = Color(0xFFF5F6FF),
    surface = Color(0xFF141A47),
    onSurface = Color(0xFFF5F6FF),
    surfaceVariant = Color(0xFF1E2558),
    onSurfaceVariant = Color(0xFFAAB2D8),
    outline = Color(0x33FFFFFF),
    error = Color(0xFFFF7B83),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3C3FD6),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFF6F1E9),
    onBackground = Color(0xFF121838),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF121838),
    surfaceVariant = Color(0xFFEDE7DC),
    onSurfaceVariant = Color(0xFF586088),
    outline = Color(0x1F121838),
    error = Color(0xFFC9283A),
)

private val KhosqTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp),
)

@Composable
fun KhosqTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = if (dark) DarkColors else LightColors
    MaterialTheme(colorScheme = scheme, typography = KhosqTypography) {
        // Text outside a Material surface would otherwise default to black.
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground) {
            content()
        }
    }
}

@Composable
fun backgroundBrush(): Brush =
    if (isSystemInDarkTheme()) {
        Brush.verticalGradient(listOf(Color(0xFF16205F), Color(0xFF090C22), Color(0xFF1E1040)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFFE4E8FF), Color(0xFFF6F1E9), Color(0xFFFFE9CF)))
    }

package am.khosq.app.ui

import am.khosq.app.AppTheme
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/*
 * KHOSQ design system.
 *
 * One place for every colour, size and timing in the app. Screens never invent their own values:
 *   colours      KTheme.colors   (two hand-tuned palettes: midnight and porcelain)
 *   type         MaterialTheme.typography (the scale below)
 *   spacing      Space.*
 *   radii        Corner.*
 *   motion       Motion.*
 * The accent is a blue-violet gradient. It is the same in both themes, so the brand never changes colour.
 */

/** Accent gradient: outgoing bubbles, primary buttons, the logo. */
val BrandColors = listOf(Color(0xFF5B6CFF), Color(0xFF8B5CF6))

/** Text and icons that sit on the accent gradient. */
val InkOnBrand = Color.White

@Immutable
class KColors(
    val dark: Boolean,
    /** the app background */
    val bg: Color,
    /** a soft glow at the very top of the background */
    val bgGlow: Color,
    /** top bars and the bottom navigation */
    val bar: Color,
    /** cards, grouped lists */
    val surface: Color,
    /** inputs, chips, sheets, menus */
    val surfaceHigh: Color,
    /** pressed or selected surfaces */
    val surfaceHighest: Color,
    /** hairline separators */
    val line: Color,
    /** borders of inputs and outlined buttons */
    val lineStrong: Color,
    val text: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    /** the accent at about fourteen percent, for selected backgrounds */
    val accentSoft: Color,
    val onAccent: Color,
    val bubbleIn: Color,
    val onBubbleIn: Color,
    val bubbleInLine: Color,
    val bubbleOutA: Color,
    val bubbleOutB: Color,
    val onBubbleOut: Color,
    /** time and ticks inside an outgoing bubble */
    val onBubbleOutSoft: Color,
    /** the second tick once the message was read */
    val tickRead: Color,
    val success: Color,
    val danger: Color,
    val warning: Color,
    val scrim: Color,
)

private val MidnightColors = KColors(
    dark = true,
    bg = Color(0xFF0A0C14),
    bgGlow = Color(0xFF12162B),
    bar = Color(0xFF0E101A),
    surface = Color(0xFF141824),
    surfaceHigh = Color(0xFF1B2030),
    surfaceHighest = Color(0xFF252B3F),
    line = Color(0x14FFFFFF),
    lineStrong = Color(0x2EFFFFFF),
    text = Color(0xFFEDEFF7),
    textSecondary = Color(0xFF9BA3B8),
    textTertiary = Color(0xFF6A7189),
    accent = Color(0xFF8091FF),
    accentSoft = Color(0x268091FF),
    onAccent = Color.White,
    bubbleIn = Color(0xFF1B2030),
    onBubbleIn = Color(0xFFEDEFF7),
    bubbleInLine = Color(0x00000000),
    bubbleOutA = Color(0xFF5566F0),
    bubbleOutB = Color(0xFF7C5CF2),
    onBubbleOut = Color.White,
    onBubbleOutSoft = Color(0xB3FFFFFF),
    tickRead = Color(0xFF8FE9FF),
    success = Color(0xFF3DD68C),
    danger = Color(0xFFFF6B7A),
    warning = Color(0xFFF5B94A),
    scrim = Color(0x99000000),
)

private val PorcelainColors = KColors(
    dark = false,
    bg = Color(0xFFF5F6FB),
    bgGlow = Color(0xFFFFFFFF),
    bar = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFEEF0F8),
    surfaceHighest = Color(0xFFE3E6F2),
    line = Color(0x14101426),
    lineStrong = Color(0x29101426),
    text = Color(0xFF11131F),
    textSecondary = Color(0xFF5B6278),
    textTertiary = Color(0xFF8A90A5),
    accent = Color(0xFF5560EE),
    accentSoft = Color(0x1F5560EE),
    onAccent = Color.White,
    bubbleIn = Color(0xFFFFFFFF),
    onBubbleIn = Color(0xFF11131F),
    bubbleInLine = Color(0x14101426),
    bubbleOutA = Color(0xFF4F63F2),
    bubbleOutB = Color(0xFF7A52F0),
    onBubbleOut = Color.White,
    onBubbleOutSoft = Color(0xBFFFFFFF),
    tickRead = Color(0xFF9DEBFF),
    success = Color(0xFF16A864),
    danger = Color(0xFFD93A4C),
    warning = Color(0xFFC77D0A),
    scrim = Color(0x66000000),
)

val LocalKColors = compositionLocalOf { MidnightColors }

/** True when the person turned animations off in the phone's developer or accessibility settings. */
val LocalReduceMotion = compositionLocalOf { false }

object KTheme {
    val colors: KColors
        @Composable @ReadOnlyComposable get() = LocalKColors.current
}

/** Spacing scale. Everything is a multiple of 4. */
object Space {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val huge = 32.dp

    /** the side gutter of every screen */
    val gutter = 16.dp
}

/** Corner radii. */
object Corner {
    val xs = 6.dp
    val s = 10.dp
    val m = 14.dp
    val l = 20.dp
    val xl = 28.dp
    val bubble = 20.dp
    val bubbleTail = 6.dp
}

/** Sizes that repeat across the app. */
object Dim {
    val avatarRow = 56.dp
    val avatarHeader = 40.dp
    val avatarSmall = 40.dp
    val touch = 48.dp
    val bar = 56.dp
    val button = 52.dp
    val composerField = 44.dp
}

/** Animation timing. */
object Motion {
    const val Fast = 120
    const val Normal = 220
    const val Slow = 360

    /** quick start, long gentle stop */
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

private val KhosqTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)

private val KhosqShapes = Shapes(
    extraSmall = RoundedCornerShape(Corner.xs),
    small = RoundedCornerShape(Corner.s),
    medium = RoundedCornerShape(Corner.m),
    large = RoundedCornerShape(Corner.l),
    extraLarge = RoundedCornerShape(Corner.xl),
)

private fun materialScheme(k: KColors): ColorScheme {
    val base = if (k.dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = k.accent,
        onPrimary = k.onAccent,
        primaryContainer = k.accentSoft,
        onPrimaryContainer = k.accent,
        secondary = k.accent,
        onSecondary = k.onAccent,
        secondaryContainer = k.accentSoft,
        onSecondaryContainer = k.text,
        tertiary = k.accent,
        onTertiary = k.onAccent,
        background = k.bg,
        onBackground = k.text,
        surface = k.surface,
        onSurface = k.text,
        surfaceVariant = k.surfaceHigh,
        onSurfaceVariant = k.textSecondary,
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = k.bg,
        surfaceContainerLow = k.surface,
        surfaceContainer = k.surface,
        surfaceContainerHigh = k.surfaceHigh,
        surfaceContainerHighest = k.surfaceHighest,
        outline = k.lineStrong,
        outlineVariant = k.line,
        error = k.danger,
        onError = Color.White,
        scrim = k.scrim,
    )
}

/** The colour mode the person chose in the settings: light, dark, or the same as the phone. */
@Composable
fun isDark(): Boolean = when (AppTheme.mode) {
    "dark" -> true
    "light" -> false
    else -> isSystemInDarkTheme()
}

private fun findActivity(c: Context): Activity? {
    var x: Context? = c
    while (x is ContextWrapper) {
        if (x is Activity) return x
        x = x.baseContext
    }
    return null
}

/** Every colour glides to its new value when the theme changes, instead of jumping. */
@Composable
private fun animatedColors(t: KColors): KColors {
    val spec = tween<Color>(Motion.Slow)
    @Composable
    fun a(c: Color): Color = animateColorAsState(c, spec, label = "theme").value
    return KColors(
        dark = t.dark,
        bg = a(t.bg),
        bgGlow = a(t.bgGlow),
        bar = a(t.bar),
        surface = a(t.surface),
        surfaceHigh = a(t.surfaceHigh),
        surfaceHighest = a(t.surfaceHighest),
        line = a(t.line),
        lineStrong = a(t.lineStrong),
        text = a(t.text),
        textSecondary = a(t.textSecondary),
        textTertiary = a(t.textTertiary),
        accent = a(t.accent),
        accentSoft = a(t.accentSoft),
        onAccent = a(t.onAccent),
        bubbleIn = a(t.bubbleIn),
        onBubbleIn = a(t.onBubbleIn),
        bubbleInLine = a(t.bubbleInLine),
        bubbleOutA = a(t.bubbleOutA),
        bubbleOutB = a(t.bubbleOutB),
        onBubbleOut = a(t.onBubbleOut),
        onBubbleOutSoft = a(t.onBubbleOutSoft),
        tickRead = a(t.tickRead),
        success = a(t.success),
        danger = a(t.danger),
        warning = a(t.warning),
        scrim = a(t.scrim),
    )
}

@Composable
fun KhosqTheme(dark: Boolean = isDark(), content: @Composable () -> Unit) {
    val target = if (dark) MidnightColors else PorcelainColors
    val k = animatedColors(target)
    val scheme = remember(k) { materialScheme(k) }
    val view = LocalView.current
    val ctx = LocalContext.current
    val reduce = remember {
        try {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (e: Exception) {
            false
        }
    }
    if (!view.isInEditMode) {
        // the clock and battery icons must stay readable when the chosen theme differs from the phone's
        SideEffect {
            val w = findActivity(view.context)?.window
            if (w != null) {
                val c = WindowCompat.getInsetsController(w, view)
                c.isAppearanceLightStatusBars = !dark
                c.isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalKColors provides k, LocalReduceMotion provides reduce) {
        MaterialTheme(colorScheme = scheme, typography = KhosqTypography, shapes = KhosqShapes) {
            // Text outside a Material surface would otherwise default to black.
            CompositionLocalProvider(
                LocalContentColor provides k.text,
                LocalTextSelectionColors provides TextSelectionColors(
                    handleColor = k.accent,
                    backgroundColor = k.accent.copy(alpha = 0.3f),
                ),
            ) {
                content()
            }
        }
    }
}

/** The app background: the flat base colour with a soft glow at the top. */
@Composable
fun backgroundBrush(): Brush {
    val k = KTheme.colors
    return Brush.verticalGradient(0f to k.bgGlow, 0.45f to k.bg, 1f to k.bg)
}

/** A button that shrinks a little while it is pressed. */
fun Modifier.pressable(
    onClick: () -> Unit,
    enabled: Boolean = true,
    pressedScale: Float = 0.95f,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) pressedScale else 1f, tween(Motion.Fast), label = "press")
    this
        .scale(s)
        .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
}

/** Horizontal padding every list row and section uses. */
val GutterPadding = PaddingValues(horizontal = Space.gutter)

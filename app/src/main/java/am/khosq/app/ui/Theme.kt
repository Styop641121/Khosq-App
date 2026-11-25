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
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
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
 * The accent is an electric indigo to royal violet gradient. It is the same in both themes, so the brand never changes colour.
 *
 * Look of the app ("obsidian"): a near-black base, one step lighter cards with a 1dp hairline of light on their edge, glass bars
 * (a translucent tinted layer with a hairline and a faint sheen; Android has no blur behind a layer that works on every phone,
 * so the glass is made of colour, not of blur), a soft glow under the accent and 20dp round message bubbles.
 */

/** Accent gradient: outgoing bubbles, primary buttons, the logo. */
val BrandColors = listOf(Color(0xFF6366F1), Color(0xFF8B5CF6))

/** The same two colours, a step lighter: text and thin lines that sit on the dark base. */
val BrandColorsLight = listOf(Color(0xFF818CF8), Color(0xFFA78BFA))

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
    /** the translucent fill of the top bars and of the floating navigation */
    val glass: Color,
    /** the hairline around a glass surface */
    val glassEdge: Color,
    /** a faint light along the top edge of a glass surface */
    val sheen: Color,
    /** the colour of the soft light under the accent (the new-chat button, the active tab) */
    val glow: Color,
    /** the thin light line along the inside of an outgoing bubble */
    val bubbleOutEdge: Color,
    /** SOS: the crimson of the alert badge */
    val crimson: Color,
    /** the neon of the assistant */
    val aura: Color,
)

private val MidnightColors = KColors(
    dark = true,
    bg = Color(0xFF090A0F),
    bgGlow = Color(0xFF10112A),
    bar = Color(0xFF0C0D14),
    surface = Color(0xFF12141D),
    surfaceHigh = Color(0xFF1A1D29),
    surfaceHighest = Color(0xFF252938),
    line = Color(0x12FFFFFF),
    lineStrong = Color(0x26FFFFFF),
    text = Color(0xFFF1F2F8),
    textSecondary = Color(0xFF9EA3B6),
    textTertiary = Color(0xFF7B8097),
    accent = Color(0xFF8187F8),
    accentSoft = Color(0x296366F1),
    onAccent = Color.White,
    bubbleIn = Color(0xFF161924),
    onBubbleIn = Color(0xFFECEEF6),
    bubbleInLine = Color(0x14FFFFFF),
    bubbleOutA = Color(0xFF6366F1),
    bubbleOutB = Color(0xFF8B5CF6),
    onBubbleOut = Color.White,
    onBubbleOutSoft = Color(0xBFFFFFFF),
    tickRead = Color(0xFFA5F3FC),
    success = Color(0xFF34D399),
    danger = Color(0xFFFB6A80),
    warning = Color(0xFFFBBF24),
    scrim = Color(0xB3000000),
    glass = Color(0xE0101119),
    glassEdge = Color(0x17FFFFFF),
    sheen = Color(0x12FFFFFF),
    glow = Color(0xFF6366F1),
    bubbleOutEdge = Color(0x2EFFFFFF),
    crimson = Color(0xFFF0455A),
    aura = Color(0xFF7DD3FC),
)

private val PorcelainColors = KColors(
    dark = false,
    bg = Color(0xFFF4F5FA),
    bgGlow = Color(0xFFFFFFFF),
    bar = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFEDEFF7),
    surfaceHighest = Color(0xFFE1E4F0),
    line = Color(0x14101426),
    lineStrong = Color(0x29101426),
    text = Color(0xFF0F1220),
    textSecondary = Color(0xFF555B72),
    textTertiary = Color(0xFF6F768E),
    accent = Color(0xFF5558E8),
    accentSoft = Color(0x1F6366F1),
    onAccent = Color.White,
    bubbleIn = Color(0xFFFFFFFF),
    onBubbleIn = Color(0xFF0F1220),
    bubbleInLine = Color(0x14101426),
    bubbleOutA = Color(0xFF6366F1),
    bubbleOutB = Color(0xFF8B5CF6),
    onBubbleOut = Color.White,
    onBubbleOutSoft = Color(0xCCFFFFFF),
    tickRead = Color(0xFFB4F0FF),
    success = Color(0xFF12A36A),
    danger = Color(0xFFD92D45),
    warning = Color(0xFFC77D0A),
    scrim = Color(0x66000000),
    glass = Color(0xE8FFFFFF),
    glassEdge = Color(0x1A101426),
    sheen = Color(0x80FFFFFF),
    glow = Color(0xFF6366F1),
    bubbleOutEdge = Color(0x33FFFFFF),
    crimson = Color(0xFFD92D45),
    aura = Color(0xFF0EA5E9),
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

    /** the floating bar of the navigation and the composer: a capsule */
    val capsule = 30.dp
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

    /** the height of the floating navigation */
    val nav = 64.dp
}

/** Animation timing. */
object Motion {
    const val Fast = 120
    const val Normal = 220
    const val Slow = 360

    /** quick start, long gentle stop */
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

/**
 * Type scale. The system font is used on purpose: it is the only one that has the Armenian letters drawn well on every phone.
 * Big sizes are set a little tighter, small ones a little looser, and every line is about 1.4 times the size.
 */
private val KhosqTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 23.sp, letterSpacing = (-0.1).sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 21.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.1.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.2.sp),
)

/** The named text styles that new screens use, so a size is never typed twice. */
object KType {
    val Display = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
    val Title = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
    val RowTitle = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp)
    val Body = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
    val Caption = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)

    /** the time and the ticks inside a bubble */
    val Micro = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp)
    val Pill = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp)
}

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
        glass = a(t.glass),
        glassEdge = a(t.glassEdge),
        sheen = a(t.sheen),
        glow = a(t.glow),
        bubbleOutEdge = a(t.bubbleOutEdge),
        crimson = a(t.crimson),
        aura = a(t.aura),
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
    return Brush.verticalGradient(0f to k.bgGlow, 0.4f to k.bg, 1f to k.bg)
}

/**
 * The background of the whole app: the base colour, and two very faint clouds of the brand colours in the top corners (the ambient
 * light of the dark theme). In the light theme the clouds are almost white, so it is the plain base.
 */
@Composable
fun Modifier.appBackground(): Modifier {
    val k = KTheme.colors
    val base = k.bg
    val top = k.bgGlow
    val a = if (k.dark) 0.16f else 0.05f
    return this
        .background(Brush.verticalGradient(0f to top, 0.4f to base, 1f to base))
        .drawBehind {
            drawRect(
                Brush.radialGradient(
                    colors = listOf(BrandColors[0].copy(alpha = a), Color.Transparent),
                    center = Offset(size.width * 0.12f, 0f),
                    radius = size.width * 0.9f,
                ),
            )
            drawRect(
                Brush.radialGradient(
                    colors = listOf(BrandColors[1].copy(alpha = a * 0.8f), Color.Transparent),
                    center = Offset(size.width * 0.95f, size.height * 0.08f),
                    radius = size.width * 0.8f,
                ),
            )
        }
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

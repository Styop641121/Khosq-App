package am.khosq.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * The first thing on screen while the app signs in: the logo eases in, and a thin loader appears
 * only if the start takes longer than a moment, so a fast start never flashes a spinner.
 */
@Composable
fun SplashScreen(modifier: Modifier = Modifier) {
    val k = KTheme.colors
    val reduce = LocalReduceMotion.current
    var shown by remember { mutableStateOf(false) }
    var slow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        shown = true
        delay(900)
        slow = true
    }
    val grow by animateFloatAsState(if (shown || reduce) 1f else 0.82f, tween(700, easing = Motion.Emphasized), label = "splashScale")
    val fade by animateFloatAsState(if (shown || reduce) 1f else 0f, tween(500), label = "splashFade")
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                scaleX = grow
                scaleY = grow
                alpha = fade
            },
        ) {
            BrandLogo(108.dp)
            Spacer(Modifier.height(Space.l))
            BrandTitle(28.sp)
        }
        AnimatedVisibility(
            visible = slow,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 72.dp),
            enter = fadeIn(tween(Motion.Slow)),
        ) {
            LinearProgressIndicator(
                color = k.accent,
                trackColor = k.surfaceHighest,
                modifier = Modifier.width(96.dp).clip(CircleShape),
            )
        }
    }
}

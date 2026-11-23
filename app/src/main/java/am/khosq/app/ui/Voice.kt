package am.khosq.app.ui

import am.khosq.app.data.Message
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private const val BARS = 40

/** The 40 loudness digits stored in the message. Older notes have none, so they get a steady pattern made from their id. */
private fun parseWave(wave: String, id: String): IntArray {
    val out = IntArray(BARS)
    if (wave.length >= BARS && wave.all { c -> c in '1'..'9' }) {
        for (i in 0 until BARS) out[i] = wave[i] - '0'
        return out
    }
    var seed = 17
    for (c in id) seed = (seed * 31 + c.code) and 0x7fffffff
    var prev = 5
    for (i in 0 until BARS) {
        seed = (seed * 1103515245 + 12345) and 0x7fffffff
        val step = (seed shr 8) % 5 - 2
        prev = (prev + step).coerceIn(2, 9)
        out[i] = prev
    }
    return out
}

@Composable
private fun Waveform(bars: IntArray, frac: Float, played: Color, rest: Color, modifier: Modifier) {
    Canvas(modifier) {
        val n = bars.size
        val gap = 2.dp.toPx()
        val bw = (size.width - gap * (n - 1)) / n
        val h = size.height
        for (i in 0 until n) {
            val bh = maxOf(3.dp.toPx(), h * bars[i] / 9f)
            val on = (i + 0.5f) / n <= frac
            drawRoundRect(
                color = if (on) played else rest,
                topLeft = Offset(i * (bw + gap), (h - bh) / 2f),
                size = Size(bw, bh),
                cornerRadius = CornerRadius(bw / 2f, bw / 2f),
            )
        }
    }
}

/** A voice note in a bubble: play / pause, loudness bars you can tap to jump, the time and the speed. */
@Composable
fun VoiceBody(m: Message, mine: Boolean, onLong: () -> Unit) {
    val ctx = LocalContext.current
    val id = m.id
    val active = VoicePlayer.activeId == id
    val playing = active && VoicePlayer.playing
    val totalMs = if (active && VoicePlayer.durMs > 0) VoicePlayer.durMs else maxOf(1000, m.dur * 1000)
    val pos = VoicePlayer.positionOf(id)
    val frac = (pos.toFloat() / totalMs).coerceIn(0f, 1f)
    val bars = remember(m.wave, m.id) { parseWave(m.wave, m.id) }

    LaunchedEffect(playing) {
        while (playing) {
            VoicePlayer.tick()
            delay(70)
        }
    }

    val ink = if (mine) InkOnBrand else MaterialTheme.colorScheme.onSurface
    val played = if (mine) InkOnBrand else MaterialTheme.colorScheme.primary
    val rest = if (mine) InkOnBrand.copy(alpha = 0.28f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (mine) Color(0xFF1B1000) else MaterialTheme.colorScheme.primary)
                .pointerInput(id) {
                    detectTapGestures(
                        onLongPress = { onLong() },
                        onTap = {
                            val d = m.data
                            if (d != null) VoicePlayer.toggle(ctx, id, d)
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (playing) "⏸" else "▶",
                color = if (mine) Color(0xFFFFCB52) else MaterialTheme.colorScheme.onPrimary,
                fontSize = 16.sp,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.width(168.dp)) {
            Waveform(
                bars = bars,
                frac = frac,
                played = played,
                rest = rest,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .pointerInput(id, totalMs) {
                        detectTapGestures(
                            onLongPress = { onLong() },
                            onTap = { off -> VoicePlayer.seek(id, off.x / size.width, totalMs) },
                        )
                    },
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatDur(if (active || pos > 0) pos / 1000 else m.dur),
                    color = ink,
                    fontSize = 12.sp,
                )
                if (active) {
                    Text(
                        text = VoicePlayer.speed.toString().removeSuffix(".0") + "×",
                        color = ink,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(ink.copy(alpha = 0.14f))
                            .clickable { VoicePlayer.cycleSpeed() }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

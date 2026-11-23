package am.khosq.app.ui

import am.khosq.app.Prefs
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background

/** The picture behind the messages. It is only on this phone, each person picks their own. */
object Wallpaper {
    var index: Int by mutableIntStateOf(Prefs.getString("wall", "0").toIntOrNull() ?: 0)

    fun set(i: Int) {
        index = i
        Prefs.putString("wall", i.toString())
    }
}

/** pattern: 0 none, 1 dots, 2 diagonal lines. */
private class Wall(val light: List<Color>, val dark: List<Color>, val pattern: Int)

private val WALLS: List<Wall?> = listOf(
    null,
    Wall(listOf(Color(0xFFE3F2FD), Color(0xFFBBDEFB)), listOf(Color(0xFF0B1B33), Color(0xFF12305A)), 0),
    Wall(listOf(Color(0xFFFFE9D6), Color(0xFFFFD1DC)), listOf(Color(0xFF2A1530), Color(0xFF3A1B2A)), 0),
    Wall(listOf(Color(0xFFE0F5EC), Color(0xFFC8EBDD)), listOf(Color(0xFF0E2420), Color(0xFF12332B)), 0),
    Wall(listOf(Color(0xFFF7F1E3), Color(0xFFF2E8D2)), listOf(Color(0xFF1C1A16), Color(0xFF26221A)), 0),
    Wall(listOf(Color(0xFFD9DCE6), Color(0xFFC5C9D8)), listOf(Color(0xFF000000), Color(0xFF05060C)), 0),
    Wall(listOf(Color(0xFFEDE7FF), Color(0xFFDCD3FA)), listOf(Color(0xFF14122A), Color(0xFF1E1A44)), 1),
    Wall(listOf(Color(0xFFFFF3D6), Color(0xFFFFE3A8)), listOf(Color(0xFF1B1508), Color(0xFF2B2010)), 2),
)

val WALLPAPER_COUNT: Int = WALLS.size

/** The background for wallpaper number [i]; number 0 is the normal app background (nothing is added). */
@Composable
fun Modifier.wallpaper(i: Int): Modifier {
    val w = WALLS.getOrNull(i) ?: return this
    val dark = isDark()
    val ink = if (dark) Color(0x16FFFFFF) else Color(0x14000000)
    val base = this.background(Brush.verticalGradient(if (dark) w.dark else w.light))
    return when (w.pattern) {
        1 -> base.drawBehind {
            val step = 26.dp.toPx()
            val r = 1.6.dp.toPx()
            var y = step / 2f
            while (y < size.height) {
                var x = step / 2f
                while (x < size.width) {
                    drawCircle(color = ink, radius = r, center = Offset(x, y))
                    x += step
                }
                y += step
            }
        }
        2 -> base.drawBehind {
            val step = 22.dp.toPx()
            val sw = 1.dp.toPx()
            var k = -size.height
            while (k < size.width) {
                drawLine(color = ink, start = Offset(k, 0f), end = Offset(k + size.height, size.height), strokeWidth = sw)
                k += step
            }
        }
        else -> base
    }
}

/** The wallpaper the person chose, for the area behind the messages of a chat. */
@Composable
fun Modifier.chatWallpaper(): Modifier = wallpaper(Wallpaper.index)

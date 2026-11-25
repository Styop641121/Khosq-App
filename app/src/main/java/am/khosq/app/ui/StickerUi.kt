package am.khosq.app.ui

import am.khosq.app.Prefs
import am.khosq.app.data.Message
import am.khosq.app.data.Sticker
import am.khosq.app.data.StickerLogic
import am.khosq.app.data.StickerPack
import am.khosq.app.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One sticker drawn by the app itself: a soft card in the colour of its pack, a big emoji and the Armenian phrase. [scale] makes the
 * emoji and the phrase smaller for the list of the sheet. The card is the same on every phone and in both themes.
 */
@Composable
internal fun StickerFace(sticker: Sticker, pack: StickerPack?, modifier: Modifier = Modifier, scale: Float = 1f) {
    val top = Color(pack?.top ?: 0xFFE4E7EFL)
    val bottom = Color(pack?.bottom ?: 0xFFC7CCDBL)
    val ink = Color(pack?.ink ?: 0xFF1D2233L)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(Corner.l))
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .padding(horizontal = 8.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = sticker.emoji,
            style = TextStyle(fontSize = (54f * scale).sp, lineHeight = (64f * scale).sp, textAlign = TextAlign.Center),
        )
        val size = StickerLogic.fontSp(sticker.text) * scale
        Text(
            text = sticker.text,
            color = ink,
            style = TextStyle(
                fontSize = size.sp,
                lineHeight = (size * 1.2f).sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

/** The body of a sticker message. A sticker that this version of the app does not know (a newer one) is said so, not guessed. */
@Composable
internal fun StickerBody(m: Message, soft: Color) {
    val st = remember(m.text) { StickerLogic.find(m.text) }
    if (st == null) {
        // an id of the right shape is a sticker of a newer app; anything else is a message that could not be opened
        val text = if (StickerLogic.valid(m.text.trim())) tr("Սթիքեր։ այն տեսնելու համար թարմացրու հավելվածը") else m.text
        Text(text = text, color = soft, style = TextStyle(fontSize = 15.sp, fontStyle = FontStyle.Italic))
        return
    }
    StickerFace(sticker = st, pack = StickerLogic.packOf(st.id), modifier = Modifier.width(STICKER_WIDTH))
}

/** The width of a sticker in the chat. */
internal val STICKER_WIDTH: Dp = 168.dp

/**
 * The sheet with the stickers: the last ones I sent and a tab for every pack. One tap on a sticker sends it and closes the sheet
 * ([onPick] gets its id).
 */
@Composable
internal fun StickerSheet(onClose: () -> Unit, onPick: (String) -> Unit) {
    val k = KTheme.colors
    val recent = remember { StickerLogic.recentList(Prefs.getString(StickerLogic.RECENT_KEY)) }
    // tab 0 is "recent" (only when there is something), the others are the packs
    val offset = if (recent.isEmpty()) 0 else 1
    var tab by remember { mutableIntStateOf(0) }
    val packs = StickerLogic.packs
    val packIndex = (tab - offset).coerceIn(0, packs.size - 1)
    val showingRecent = offset == 1 && tab == 0
    val items = if (showingRecent) recent else packs[packIndex].items
    KSheet(onDismiss = onClose) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(
                text = tr("Սթիքերներ"),
                color = k.text,
                style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.padding(horizontal = Space.xs),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                if (offset == 1) TabChip(label = "🕑 " + tr("Վերջինները"), selected = tab == 0, onClick = { tab = 0 })
                for (i in packs.indices) {
                    TabChip(label = packs[i].icon + " " + tr(packs[i].nameKey), selected = tab == i + offset, onClick = { tab = i + offset })
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                for (row in items.chunked(2)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        for (st in row) {
                            StickerFace(
                                sticker = st,
                                pack = StickerLogic.packOf(st.id),
                                scale = 0.9f,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(Corner.l))
                                    .clickable {
                                        onPick(st.id)
                                        onClose()
                                    },
                            )
                        }
                        // a lone sticker in the last row keeps the size of the others
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                Spacer(Modifier.height(Space.s))
            }
        }
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val k = KTheme.colors
    Text(
        text = label,
        color = if (selected) k.accent else k.textSecondary,
        style = TextStyle(fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(Corner.m))
            .background(if (selected) k.accentSoft else k.surfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.m, vertical = 8.dp),
    )
}

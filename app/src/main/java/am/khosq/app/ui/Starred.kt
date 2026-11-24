package am.khosq.app.ui

import am.khosq.app.AppState
import am.khosq.app.data.Star
import am.khosq.app.previewOf
import am.khosq.app.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Every message I put a star on, newest first. Tap one to open its chat; the cross takes the star off. */
@Composable
fun StarredScreen(vm: AppViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val k = KTheme.colors
    val stars by vm.stars.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(k.bar)
                .statusBarsPadding()
                .height(Dim.bar)
                .padding(horizontal = Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KIconButton(icon = KIcons.Back, onClick = onBack, description = tr("Հետ"))
            Text(
                text = tr("Աստղանշված նամակներ"),
                color = k.text,
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = Space.xs),
            )
        }
        KDivider()
        if (stars.isEmpty()) {
            EmptyState(
                icon = KIcons.Star,
                title = tr("Աստղանշված նամակներ"),
                text = tr("Դեռ աստղանշված նամակ չկա։ Նամակի վրա երկար սեղմիր և ընտրիր «Աստղանշել»։"),
                modifier = Modifier.fillMaxSize().padding(top = Space.huge),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = Space.xxl),
            ) {
                items(stars, key = { s -> s.key }) { s ->
                    Column(Modifier.animateItem()) {
                        StarRow(
                            s,
                            onClick = {
                                AppState.jumpTo(s.chatId, s.key.substringAfter("~"))
                                onOpen(s.chatId)
                            },
                            onRemove = { vm.removeStar(s.key) },
                        )
                        KDivider(inset = Space.l)
                    }
                }
            }
        }
    }
}

@Composable
private fun StarRow(s: Star, onClick: () -> Unit, onRemove: () -> Unit) {
    val k = KTheme.colors
    val where = if (s.chatId.startsWith("saved")) tr("Պահպանված նամակներ") else s.chatName
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = Space.l, top = Space.m, bottom = Space.m, end = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = (if (s.mine) tr("Դու") else s.fromName) + (if (where.isEmpty()) "" else "  ›  " + where),
                color = k.accent,
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = previewOf(s.kind, s.text),
                color = k.text,
                style = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = dayLabel(s.ts) + " " + formatTime(s.ts),
                color = k.textSecondary,
                style = TextStyle(fontSize = 12.sp),
            )
        }
        KIconButton(
            icon = KIcons.StarFill,
            onClick = onRemove,
            description = tr("Հանել աստղը"),
            tint = k.warning,
            size = 44.dp,
            iconSize = 22.dp,
        )
    }
}

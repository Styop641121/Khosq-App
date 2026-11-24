package am.khosq.app.ui

import am.khosq.app.data.Message
import am.khosq.app.data.PollCodec
import am.khosq.app.tr
import am.khosq.app.trf
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The body of a poll message: the question, the options with a bar showing how many chose each, and my own choice.
 * Tapping an option votes; tapping my option again takes the vote back.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PollBody(
    m: Message,
    mine: Boolean,
    myUid: String,
    ink: Color,
    soft: Color,
    onVote: (Int) -> Unit,
    onLong: () -> Unit,
) {
    val k = KTheme.colors
    val poll = remember(m.text) { PollCodec.decode(m.text) }
    if (poll == null) {
        Text(text = tr("Հարցումը չհաջողվեց բացել"), color = soft, style = TextStyle(fontSize = 15.sp))
        return
    }
    val tally = remember(m.votes, poll) { PollCodec.tally(m.votes, poll.options.size) }
    val counts = IntArray(poll.options.size)
    for (choice in tally.values) counts[choice] = counts[choice] + 1
    val total = tally.size
    val myChoice = tally[myUid]
    val track = if (mine) Color(0x26FFFFFF) else k.accentSoft
    val fill = if (mine) Color(0x40FFFFFF) else k.accent.copy(alpha = 0.28f)

    Column(modifier = Modifier.widthIn(min = 230.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = KIcons.Poll,
                contentDescription = null,
                tint = ink,
                modifier = Modifier.padding(top = 2.dp).size(20.dp),
            )
            Spacer(Modifier.width(Space.s))
            Text(text = poll.question, color = ink, style = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold))
        }
        Text(text = tr("Ընտրիր մեկ տարբերակ"), color = soft, style = TextStyle(fontSize = 12.sp))
        for (i in poll.options.indices) {
            val share = if (total == 0) 0f else counts[i].toFloat() / total
            val chosen = myChoice == i
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Corner.s))
                    .background(track)
                    .combinedClickable(onClick = { onVote(i) }, onLongClick = onLong),
            ) {
                Box(Modifier.matchParentSize()) {
                    if (share > 0f) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(share).background(fill))
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .border(1.5.dp, ink.copy(alpha = 0.7f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (chosen) Box(Modifier.size(10.dp).clip(CircleShape).background(ink))
                    }
                    Spacer(Modifier.width(Space.s))
                    Text(
                        text = poll.options[i],
                        color = ink,
                        style = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal),
                        modifier = Modifier.weight(1f),
                    )
                    if (total > 0) {
                        Spacer(Modifier.width(Space.s))
                        Text(text = counts[i].toString(), color = soft, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
                    }
                }
            }
        }
        Text(
            text = if (total == 0) tr("Ձայներ դեռ չկան") else trf("Ձայներ՝ {0}", total.toString()),
            color = soft,
            style = TextStyle(fontSize = 12.sp),
        )
    }
}

/** A sheet to write a poll: one question and two to six options. [onCreate] returns true when the poll went out. */
@Composable
internal fun CreatePollSheet(onClose: () -> Unit, onCreate: (String, List<String>) -> Boolean) {
    val k = KTheme.colors
    var question by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    val valid = PollCodec.clean(question, options.toList()) != null
    KSheet(onDismiss = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Text(text = tr("Նոր հարցում"), color = k.text, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold))
            OutlinedTextField(
                value = question,
                onValueChange = { v -> if (v.length <= PollCodec.MAX_QUESTION) question = v },
                label = { Text(tr("Հարցը")) },
                shape = RoundedCornerShape(Corner.m),
                colors = kFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            for (i in options.indices) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = options[i],
                        onValueChange = { v -> if (v.length <= PollCodec.MAX_OPTION) options[i] = v },
                        label = { Text(trf("Տարբերակ {0}", (i + 1).toString()), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        shape = RoundedCornerShape(Corner.m),
                        colors = kFieldColors(),
                        modifier = Modifier.weight(1f),
                    )
                    if (options.size > PollCodec.MIN_OPTIONS) {
                        KIconButton(
                            icon = KIcons.Close,
                            onClick = { options.removeAt(i) },
                            tint = k.danger,
                            size = 40.dp,
                            iconSize = 20.dp,
                        )
                    }
                }
            }
            if (options.size < PollCodec.MAX_OPTIONS) {
                SheetRow(icon = KIcons.Plus, label = tr("Ավելացնել տարբերակ"), onClick = { options.add("") })
            }
            Row(Modifier.fillMaxWidth().padding(top = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                SoftButton(text = tr("Չեղարկել"), onClick = onClose, modifier = Modifier.weight(1f))
                GradientButton(
                    text = tr("Ուղարկել"),
                    enabled = valid,
                    onClick = { if (onCreate(question, options.toList())) onClose() },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

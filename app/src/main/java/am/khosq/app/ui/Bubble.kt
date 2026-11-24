package am.khosq.app.ui

import am.khosq.app.data.Message
import am.khosq.app.replyText
import am.khosq.app.tr
import am.khosq.app.trf
import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** True when two messages belong to the same visual run: same sender, close in time, same day. */
internal fun sameRun(a: Message?, b: Message?): Boolean {
    if (a == null || b == null) return false
    if (a.kind == "call" || a.kind == "sys" || b.kind == "call" || b.kind == "sys") return false
    return a.from == b.from && kotlin.math.abs(a.ts - b.ts) < 3L * 60_000L && dayKey(a.ts) == dayKey(b.ts)
}

/** The grey pill with the day, between two days of messages. */
@Composable
internal fun DateChip(text: String) {
    val k = KTheme.colors
    Box(Modifier.fillMaxWidth().padding(vertical = Space.s), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = k.textSecondary,
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
            modifier = Modifier
                .clip(RoundedCornerShape(Corner.m))
                .background(k.surfaceHigh.copy(alpha = 0.88f))
                .padding(horizontal = Space.m, vertical = 5.dp),
        )
    }
}

/**
 * The line at the top of a chat that says whether its messages are protected end-to-end. In a one-to-one chat tap it
 * for the safety number. [missing] names the group members whose app cannot encrypt yet.
 */
@Composable
internal fun SecureChip(
    secure: Boolean,
    saved: Boolean,
    group: Boolean = false,
    missing: String = "",
    onClick: (() -> Unit)? = null,
) {
    val k = KTheme.colors
    val text = when {
        saved -> tr("Պահված նամակները գաղտնագրված են և կարդացվում են միայն քո հեռախոսում")
        group && secure -> tr("Խմբի նամակները պաշտպանված են end-to-end գաղտնագրմամբ. դրանք կարող են կարդալ միայն խմբի անդամները")
        group -> trf("Այս խումբը դեռ գաղտնագրված չէ. հավելվածը դեռ չեն թարմացրել՝ {0}", missing)
        secure -> tr("Նամակները պաշտպանված են end-to-end գաղտնագրմամբ. դրանք կարող են կարդալ միայն դու և զրուցակիցդ")
        else -> tr("Այս զրույցը դեռ գաղտնագրված չէ. զրուցակցի հավելվածը դեռ չի թարմացվել")
    }
    val tone = if (secure || saved) k.textSecondary else k.warning
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.l, vertical = Space.m)
            .clip(RoundedCornerShape(Corner.l))
            .background(k.surfaceHigh)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = KIcons.Lock, contentDescription = null, tint = tone, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Space.s))
        Text(text = text, color = tone, style = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp))
    }
}

/** A card above the composer for the "accept or block" and "unblock" moments. */
@Composable
internal fun NoticeBar(
    text: String,
    primary: String,
    onPrimary: () -> Unit,
    secondary: String? = null,
    onSecondary: () -> Unit = {},
) {
    val k = KTheme.colors
    val shape = RoundedCornerShape(Corner.l)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Space.m)
            .clip(shape)
            .background(k.surface)
            .border(1.dp, k.line, shape)
            .padding(Space.l),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = text, color = k.text, style = TextStyle(fontSize = 15.sp, lineHeight = 21.sp))
        Spacer(Modifier.height(Space.m))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            GradientButton(text = primary, onClick = onPrimary, modifier = Modifier.weight(1f))
            if (secondary != null) {
                SoftButton(text = secondary, onClick = onSecondary, danger = true, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ImageBody(m: Message) {
    val k = KTheme.colors
    val bmp = remember(m.id) {
        val d = m.data
        if (d == null) null else BitmapFactory.decodeByteArray(d, 0, d.size)?.asImageBitmap()
    }
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = tr("Նկար"),
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.width(240.dp).clip(RoundedCornerShape(Corner.m)),
        )
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = KIcons.Image, contentDescription = null, tint = k.textSecondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Space.s))
            Text(text = tr("Նկար"), style = TextStyle(fontSize = 16.sp))
        }
    }
}

/** A steady colour per person, so the same sender always looks the same in a group. */
@Composable
private fun senderColor(key: String): Color {
    var h = 0
    for (c in key) h = (h * 31 + c.code) and 0x7fffffff
    val palette = if (KTheme.colors.dark) {
        listOf(Color(0xFF8FA0FF), Color(0xFF5CD0C0), Color(0xFFFFB066), Color(0xFFFF8FB5), Color(0xFF7FD99A), Color(0xFFB79BFF), Color(0xFF6FC8FF))
    } else {
        listOf(Color(0xFF4F5BD9), Color(0xFF138B7A), Color(0xFFC26A12), Color(0xFFC23A72), Color(0xFF1F9250), Color(0xFF7A4BD6), Color(0xFF1F78B4))
    }
    return palette[h % palette.size]
}

private fun systemLine(m: Message, actor: String): String {
    if (m.kind == "call") {
        return when (m.code) {
            "call_done" -> (if (m.vid) tr("Տեսազանգ") else tr("Զանգ")) + " · " + formatDur(m.dur)
            "call_missed" -> tr("Բաց թողնված զանգ")
            "call_declined" -> tr("Մերժված զանգ")
            "call_cancelled" -> tr("Չեղարկված զանգ")
            else -> m.text
        }
    }
    return when (m.code) {
        "grp_created" -> trf("{0} ստեղծեց խումբը", actor)
        "grp_added" -> trf("{0} ավելացրեց՝ {1}", actor, m.arg)
        "grp_removed" -> trf("{0} հեռացրեց՝ {1}", actor, m.arg)
        "grp_renamed" -> trf("{0} փոխեց խմբի անունը՝ «{1}»", actor, m.arg)
        "grp_left" -> trf("{0} լքեց խումբը", actor)
        "grp_photo" -> trf("{0} փոխեց խմբի նկարը", actor)
        "grp_desc" -> trf("{0} փոխեց խմբի նկարագրությունը", actor)
        "grp_mode_admins" -> trf("{0} միացրեց «միայն ադմինները գրեն» ռեժիմը", actor)
        "grp_mode_all" -> trf("{0} անջատեց «միայն ադմինները գրեն» ռեժիմը", actor)
        "grp_admin_on" -> trf("{0} ադմին նշանակեց՝ {1}", actor, m.arg)
        "grp_admin_off" -> trf("{0} հանեց ադմինությունը՝ {1}", actor, m.arg)
        else -> m.text
    }
}

/** Three soft dots in a small bubble while the other person is typing. */
@Composable
internal fun TypingBubble() {
    val k = KTheme.colors
    val reduce = LocalReduceMotion.current
    val t = rememberInfiniteTransition(label = "typing")
    val shape = RoundedCornerShape(Corner.bubble, Corner.bubble, Corner.bubble, Corner.bubbleTail)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(k.bubbleIn)
            .border(1.dp, k.bubbleInLine, shape)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        for (i in 0..2) {
            val a = if (reduce) {
                0.6f
            } else {
                val v by t.animateFloat(
                    initialValue = 0.28f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse, StartOffset(i * 140)),
                    label = "dot$i",
                )
                v
            }
            Box(Modifier.size(7.dp).alpha(a).clip(CircleShape).background(k.textSecondary))
        }
    }
}

@Composable
private fun StatusTicks(pending: Boolean, delivered: Boolean, read: Boolean, soft: Color) {
    val k = KTheme.colors
    val icon: ImageVector = when {
        pending -> KIcons.Clock
        delivered -> KIcons.DoubleCheck
        else -> KIcons.Check
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = if (read) k.tickRead else soft,
        modifier = Modifier.size(if (pending) 12.dp else 16.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Bubble(
    m: Message,
    mine: Boolean,
    peerRead: Long,
    peerDelivered: Long,
    showTicks: Boolean,
    sender: String?,
    senderKey: String,
    myUid: String,
    names: Map<String, String>,
    peerName: String,
    starred: Boolean,
    joinedOlder: Boolean,
    onSwipe: () -> Unit,
    onLong: () -> Unit,
    onVote: (Int) -> Unit = {},
) {
    val k = KTheme.colors
    if (m.kind == "call" || m.kind == "sys") {
        val isCall = m.kind == "call"
        val bad = isCall && m.code != "call_done"
        Box(Modifier.fillMaxWidth().padding(vertical = Space.xs), contentAlignment = Alignment.Center) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(Corner.m))
                    .background(k.surfaceHigh.copy(alpha = 0.88f))
                    .padding(horizontal = Space.m, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isCall) {
                    Icon(
                        imageVector = if (m.vid) KIcons.VideoLine else KIcons.PhoneLine,
                        contentDescription = null,
                        tint = if (bad) k.danger else k.success,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = systemLine(m, names[m.from] ?: peerName) + "  " + formatTime(m.ts),
                    color = k.textSecondary,
                    style = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
                )
            }
        }
        return
    }
    val big = Corner.bubble
    val small = Corner.bubbleTail
    val shape = if (mine) {
        RoundedCornerShape(topStart = big, topEnd = if (joinedOlder) small else big, bottomEnd = small, bottomStart = big)
    } else {
        RoundedCornerShape(topStart = if (joinedOlder) small else big, topEnd = big, bottomEnd = big, bottomStart = small)
    }
    val fill = if (mine) {
        Modifier.background(Brush.linearGradient(listOf(k.bubbleOutA, k.bubbleOutB)))
    } else {
        Modifier.background(k.bubbleIn).border(1.dp, k.bubbleInLine, shape)
    }
    val ink = if (mine) k.onBubbleOut else k.onBubbleIn
    val soft = if (mine) k.onBubbleOutSoft else k.textSecondary
    val read = mine && !m.pending && peerRead >= m.ts
    val delivered = read || (mine && !m.pending && peerDelivered >= m.ts)
    val swipe by rememberUpdatedState(onSwipe)
    val canSwipe = !m.deleted && !m.pending
    val slide = remember { Animatable(0f) }
    val slideScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val trigger = with(LocalDensity.current) { 64.dp.toPx() }
    val maxBubble = (LocalConfiguration.current.screenWidthDp * 0.78f).coerceIn(240f, 420f).dp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (!canSwipe) {
                    Modifier
                } else {
                    Modifier.pointerInput(m.id) {
                        var crossed = false
                        detectHorizontalDragGestures(
                            onDragStart = { crossed = false },
                            onDragEnd = {
                                val fire = slide.value >= trigger
                                slideScope.launch { slide.animateTo(0f) }
                                if (fire) swipe()
                            },
                            onDragCancel = { slideScope.launch { slide.animateTo(0f) } },
                            onHorizontalDrag = { change, dx ->
                                change.consume()
                                val nx = (slide.value + dx * 0.6f).coerceIn(0f, trigger * 1.5f)
                                if (!crossed && nx >= trigger) {
                                    crossed = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                slideScope.launch { slide.snapTo(nx) }
                            },
                        )
                    }
                },
            ),
    ) {
        if (slide.value > 8f) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = Space.s)
                    .size(34.dp)
                    .graphicsLayer { alpha = (slide.value / trigger).coerceIn(0f, 1f) }
                    .clip(CircleShape)
                    .background(k.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = KIcons.Reply, contentDescription = null, tint = k.accent, modifier = Modifier.size(20.dp))
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(slide.value.roundToInt(), 0) },
            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = maxBubble)
                    .clip(shape)
                    .then(fill)
                    .combinedClickable(onClick = {}, onLongClick = onLong)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                if (sender != null) {
                    Text(
                        text = sender,
                        color = senderColor(senderKey),
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                if (m.fwd && !m.deleted) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 3.dp)) {
                        Icon(imageVector = KIcons.Forward, contentDescription = null, tint = soft, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = tr("Փոխանցված"),
                            color = soft,
                            style = TextStyle(fontSize = 12.sp, fontStyle = FontStyle.Italic),
                        )
                    }
                }
                if (m.deleted) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = KIcons.Close, contentDescription = null, tint = soft, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(text = tr("Նամակը ջնջված է"), color = soft, style = TextStyle(fontSize = 15.sp, fontStyle = FontStyle.Italic))
                    }
                } else {
                    if (m.reply.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .padding(bottom = 6.dp)
                                .clip(RoundedCornerShape(Corner.s))
                                .background(if (mine) Color(0x26FFFFFF) else k.accentSoft)
                                .height(IntrinsicSize.Min),
                        ) {
                            Box(
                                Modifier
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .background(if (mine) Color.White.copy(alpha = 0.85f) else k.accent),
                            )
                            Column(Modifier.padding(horizontal = Space.s, vertical = 5.dp)) {
                                Text(
                                    text = when {
                                        m.replyFrom.isEmpty() -> m.replyName
                                        m.replyFrom == myUid -> tr("Դու")
                                        else -> names[m.replyFrom] ?: peerName
                                    },
                                    color = if (mine) Color.White else k.accent,
                                    style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = replyText(m.reply),
                                    color = soft,
                                    style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    when (m.kind) {
                        "img" -> ImageBody(m)
                        "voice" -> VoiceBody(m, mine, onLong)
                        "loc" -> LocationBody(m, mine, ink, soft, onLong)
                        "poll" -> PollBody(m, mine, myUid, ink, soft, onVote, onLong)
                        else -> {
                            val linkColor = if (mine) Color(0xFFE3E8FF) else k.accent
                            val rich = remember(m.text, linkColor) { richText(m.text, linkColor) }
                            Text(text = rich, color = ink, style = TextStyle(fontSize = 16.sp, lineHeight = 22.sp))
                        }
                    }
                    if (m.reactions.isNotEmpty()) {
                        Row(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            for ((e, n) in m.reactions.values.groupingBy { it }.eachCount()) {
                                Text(
                                    text = if (n > 1) "$e $n" else e,
                                    color = ink,
                                    style = TextStyle(fontSize = 13.sp),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(Corner.m))
                                        .background(if (mine) Color(0x2EFFFFFF) else k.surfaceHighest)
                                        .padding(horizontal = 7.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.align(Alignment.End).padding(top = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (m.edited && !m.deleted) {
                        Text(text = tr("փոխված"), color = soft, style = TextStyle(fontSize = 11.sp, fontStyle = FontStyle.Italic))
                        Spacer(Modifier.width(5.dp))
                    }
                    if (starred) {
                        Icon(imageVector = KIcons.StarFill, contentDescription = null, tint = soft, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(text = formatTime(m.ts), color = soft, style = TextStyle(fontSize = 11.sp))
                    if (mine && showTicks) {
                        Spacer(Modifier.width(4.dp))
                        StatusTicks(pending = m.pending, delivered = delivered, read = read, soft = soft)
                    }
                }
            }
        }
    }
}

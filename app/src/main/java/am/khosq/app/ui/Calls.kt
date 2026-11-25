package am.khosq.app.ui

import am.khosq.app.tr
import am.khosq.app.trf

import am.khosq.app.call.CallManager
import am.khosq.app.call.CallPermissions
import am.khosq.app.data.CallRecord
import am.khosq.app.data.Chat
import am.khosq.app.data.Profile
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** "Այսօր 14:32", "Երեկ 09:10" or "05.11 18:00". */
private fun callTime(ts: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance()
    then.timeInMillis = ts
    val days = (now.get(Calendar.YEAR) * 366 + now.get(Calendar.DAY_OF_YEAR)) -
        (then.get(Calendar.YEAR) * 366 + then.get(Calendar.DAY_OF_YEAR))
    return when (days) {
        0 -> trf("Այսօր {0}", formatTime(ts))
        1 -> trf("Երեկ {0}", formatTime(ts))
        else -> SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(ts))
    }
}

private fun callLabel(r: CallRecord): String = when {
    r.answered -> if (r.dur >= 0) formatDur(r.dur) else tr("Պատասխանվել է")
    r.outgoing && r.state == "declined" -> tr("Մերժվեց")
    r.outgoing && r.state == "missed" -> tr("Չպատասխանեց")
    r.outgoing -> tr("Չեղարկված")
    r.state == "declined" -> tr("Մերժված")
    else -> tr("Բաց թողնված")
}

/** The "Զանգեր" tab: every call, newest first. Tap a row to open the chat, tap the round icon to call back. */
@Composable
fun CallsTab(me: Profile, calls: List<CallRecord>, chats: List<Chat>, onOpenChat: (String) -> Unit) {
    val ctx = LocalContext.current
    val byPeer = remember(chats) { chats.associateBy { c -> c.peerUid } }
    var target by remember { mutableStateOf<Chat?>(null) }
    var wantVideo by remember { mutableStateOf(false) }

    fun place() {
        val c = target ?: return
        CallManager.startOutgoing(me.uid, me.name, c.peerUid, c.peerName, wantVideo)
    }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (CallPermissions.has(ctx, wantVideo)) place()
    }

    fun callBack(chat: Chat, video: Boolean) {
        target = chat
        wantVideo = video
        if (CallPermissions.has(ctx, video)) place() else perms.launch(CallPermissions.needed(video))
    }

    if (calls.isEmpty()) {
        EmptyState(
            icon = KIcons.PhoneLine,
            title = tr("Զանգեր դեռ չկան"),
            text = tr("Զանգիր ընկերոջդ զրույցի վերևի կոճակով, և պատմությունը կերևա այստեղ։"),
            modifier = Modifier.fillMaxSize().padding(top = Space.huge),
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Space.xxl),
    ) {
        items(calls, key = { r -> r.id }) { rec ->
            val chat = byPeer[rec.peerUid]
            val name = chat?.peerName ?: rec.peerName.ifEmpty { tr("Անհայտ") }
            val canCall = chat != null && chat.status == "active" && chat.blockedBy.isEmpty()
            Column(Modifier.animateItem()) {
                CallRow(
                    rec = rec,
                    name = name,
                    canCall = canCall,
                    onOpen = { if (chat != null) onOpenChat(chat.id) },
                    onCall = { if (chat != null) callBack(chat, rec.video) },
                )
                KDivider(inset = Space.l + 52.dp + Space.m)
            }
        }
    }
}

@Composable
private fun CallRow(rec: CallRecord, name: String, canCall: Boolean, onOpen: () -> Unit, onCall: () -> Unit) {
    val k = KTheme.colors
    val tone = when {
        rec.missed -> k.danger
        rec.answered -> k.success
        else -> k.textSecondary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = Space.l, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name = name, seed = rec.peerUid, size = 52.dp)
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = name,
                style = KType.RowTitle,
                color = if (rec.missed) k.danger else k.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                // a small round badge says what happened: a missed, an outgoing or an incoming call
                CallStatusBadge(
                    icon = when {
                        rec.missed -> KIcons.PhoneMissed
                        rec.outgoing -> KIcons.PhoneOut
                        else -> KIcons.PhoneIn
                    },
                    tone = tone,
                    size = 22.dp,
                )
                Spacer(Modifier.width(Space.s))
                Text(
                    text = (if (rec.video) tr("Տեսազանգ") + " · " else "") + callLabel(rec) + " · " + callTime(rec.ts),
                    color = k.textSecondary,
                    style = TextStyle(fontSize = 13.5.sp, lineHeight = 18.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (canCall) {
            KIconButton(
                icon = if (rec.video) KIcons.VideoLine else KIcons.PhoneLine,
                onClick = onCall,
                tint = k.accent,
                size = 42.dp,
                iconSize = 21.dp,
                background = k.accentSoft,
                description = if (rec.video) tr("Տեսազանգ") else tr("Զանգ"),
            )
        }
    }
}

package am.khosq.app.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val Answered = Color(0xFF2FB36B)

/** "Այսօր 14:32", "Երեկ 09:10" or "05.11 18:00". */
private fun callTime(ts: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance()
    then.timeInMillis = ts
    val days = (now.get(Calendar.YEAR) * 366 + now.get(Calendar.DAY_OF_YEAR)) -
        (then.get(Calendar.YEAR) * 366 + then.get(Calendar.DAY_OF_YEAR))
    return when (days) {
        0 -> "Այսօր " + formatTime(ts)
        1 -> "Երեկ " + formatTime(ts)
        else -> SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(ts))
    }
}

private fun callLabel(r: CallRecord): String = when {
    r.answered -> if (r.dur >= 0) formatDur(r.dur) else "Պատասխանվել է"
    r.outgoing && r.state == "declined" -> "Մերժվեց"
    r.outgoing && r.state == "missed" -> "Չպատասխանեց"
    r.outgoing -> "Չեղարկված"
    r.state == "declined" -> "Մերժված"
    else -> "Բաց թողնված"
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
        Column(
            modifier = Modifier.fillMaxSize().padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            IconCircle(
                icon = KIcons.Call,
                onClick = { },
                size = 72.dp,
                bg = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(16.dp))
            Text(text = "Զանգեր դեռ չկան", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(6.dp))
            Text(
                text = "Զանգիր ընկերոջդ զրույցի վերևի կոճակով, և պատմությունը կերևա այստեղ։",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(calls, key = { r -> r.id }) { rec ->
            val chat = byPeer[rec.peerUid]
            val name = chat?.peerName ?: rec.peerName.ifEmpty { "Անհայտ" }
            val canCall = chat != null && chat.status == "active" && chat.blockedBy.isEmpty()
            CallRow(
                rec = rec,
                name = name,
                canCall = canCall,
                onOpen = { if (chat != null) onOpenChat(chat.id) },
                onCall = { if (chat != null) callBack(chat, rec.video) },
            )
        }
    }
}

@Composable
private fun CallRow(rec: CallRecord, name: String, canCall: Boolean, onOpen: () -> Unit, onCall: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    val tone = when {
        rec.missed -> MaterialTheme.colorScheme.error
        rec.answered -> Answered
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(onClick = onOpen)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name = name, seed = rec.peerUid, size = 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = if (rec.missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (rec.outgoing) KIcons.ArrowOut else KIcons.ArrowIn,
                    contentDescription = null,
                    tint = tone,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = (if (rec.video) "Տեսազանգ · " else "") + callLabel(rec) + " · " + callTime(rec.ts),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (canCall) {
            IconCircle(
                icon = if (rec.video) KIcons.Video else KIcons.Call,
                onClick = onCall,
                size = 42.dp,
                bg = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                tint = MaterialTheme.colorScheme.primary,
                description = if (rec.video) "Տեսազանգ" else "Զանգ",
            )
        }
    }
}

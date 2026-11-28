package am.khosq.app.ui

import am.khosq.app.call.CallPermissions
import am.khosq.app.call.GBanner
import am.khosq.app.call.GCallUi
import am.khosq.app.call.GPerson
import am.khosq.app.call.GPhase
import am.khosq.app.call.GroupCallManager
import am.khosq.app.tr
import am.khosq.app.trf
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.webrtc.VideoTrack

/** True while I am in a group call (joining, or in it): the call screen can be made small, and the app shows the green bar. */
fun groupIsOn(p: GPhase): Boolean = p == GPhase.JOINING || p == GPhase.ACTIVE

/** The words for why a group call ended (or did not start). */
private fun groupNote(note: String): String = when (note) {
    "ended" -> tr("Զանգն արդեն ավարտվել է")
    "full" -> tr("Զանգը լիքն է")
    "denied" -> tr("Թույլատրիր միկրոֆոնը (տեսազանգի դեպքում՝ նաև տեսախցիկը)")
    "noMedia" -> tr("Միկրոֆոնը կամ տեսախցիկը չհաջողվեց միացնել")
    "noanswer" -> tr("Չպատասխանեց")
    "elsewhere" -> tr("Պատասխանվեց մեկ այլ սարքից")
    else -> tr("Զանգը չհաջողվեց")
}

/**
 * Asks for the microphone (and the camera for a video call) and then does [go]. A video call goes on without the camera when only the
 * camera is refused (the call code joins with the microphone alone), so only the microphone is needed to go on.
 */
@Composable
fun rememberCallGate(): (Boolean, () -> Unit) -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val go = pending
        pending = null
        if (go != null && CallPermissions.has(ctx, false)) go()
    }
    return remember(ctx) {
        { video: Boolean, go: () -> Unit ->
            if (CallPermissions.has(ctx, video)) {
                go()
            } else {
                pending = go
                launcher.launch(CallPermissions.needed(video))
            }
        }
    }
}

private class TileData(
    val key: String,
    val uid: String,
    val name: String,
    val state: String,
    val muted: Boolean,
    val cam: Boolean,
    val video: VideoTrack?,
    val you: Boolean,
)

@Composable
private fun Tile(t: TileData, videoCall: Boolean, modifier: Modifier) {
    val picture = videoCall && t.cam && t.video != null && t.state != "lost"
    Box(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0x22FFFFFF)),
    ) {
        if (picture) {
            VideoView(t.video, mirror = t.you, overlay = false, modifier = Modifier.fillMaxSize())
        } else {
            Avatar(name = t.name.ifEmpty { "?" }, seed = t.uid, size = 76.dp, circle = true, modifier = Modifier.align(Alignment.Center))
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Color(0x66000000))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (t.muted) {
                Icon(
                    imageVector = KIcons.MicOff,
                    contentDescription = tr("Միկրոֆոնն անջատված է"),
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text = if (t.you) tr("Դու") else t.name,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!t.you && t.state != "connected") {
            Text(
                text = if (t.state == "lost") tr("Կապը խզվեց") else tr("Միանում է…"),
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x88000000))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/** Me and the others as tiles: one under the other for one or two, in two columns for more (the last one alone takes the whole row). */
@Composable
private fun GroupGrid(s: GCallUi, modifier: Modifier) {
    val list = ArrayList<TileData>()
    list.add(
        TileData(
            key = "me",
            uid = s.meUid,
            name = s.meName,
            state = "connected",
            muted = s.muted,
            cam = s.cameraOn && !s.noCamera,
            video = s.localVideo,
            you = true,
        ),
    )
    for (p: GPerson in s.people) {
        list.add(TileData(p.uid, p.uid, p.name, p.state, p.muted, p.cam, p.video, false))
    }
    val cols = if (list.size <= 2) 1 else 2
    val rows = list.chunked(cols)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in rows) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (t in row) {
                    key(t.key) { Tile(t, s.video, Modifier.weight(1f).fillMaxHeight()) }
                }
            }
        }
    }
}

private fun statusOf(s: GCallUi, now: Long): String = when (s.phase) {
    GPhase.RINGING -> if (s.video) tr("Խմբային տեսազանգ") else tr("Խմբային ձայնային զանգ")
    GPhase.JOINING -> tr("Միանում է…")
    GPhase.ACTIVE ->
        if (s.startedAt > 0L) {
            formatDur(((now - s.startedAt) / 1000).toInt().coerceAtLeast(0)) + " · " + trf("{0} մարդ", s.count.toString())
        } else if (s.people.isNotEmpty()) {
            tr("Միանում է…")
        } else if (s.outgoing) {
            tr("Զանգում է…")
        } else {
            tr("Սպասում ենք մասնակիցներին…")
        }
    GPhase.ENDED -> if (s.note.isEmpty()) "" else groupNote(s.note)
    GPhase.IDLE -> ""
}

/** The full-screen window of a group call: the ring for a call that comes in, the tiles for a call that goes on, and a note when it ended. */
@Composable
fun GroupCallOverlay() {
    val s by GroupCallManager.ui.collectAsStateWithLifecycle()
    val log by GroupCallManager.dbg.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.phase) {
        // a new or finished call always starts on the full screen
        if (s.phase == GPhase.IDLE || s.phase == GPhase.RINGING || s.phase == GPhase.ENDED) CallUiState.minimized = false
    }
    LaunchedEffect(s.phase, s.startedAt) {
        while (s.phase == GPhase.ACTIVE && s.startedAt > 0L) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (CallPermissions.has(ctx, false)) GroupCallManager.join() else GroupCallManager.decline()
    }
    if (s.phase == GPhase.IDLE) return
    if (s.phase == GPhase.ENDED && s.note.isEmpty()) return
    val inCall = groupIsOn(s.phase)
    if (CallUiState.minimized && inCall) return
    BackHandler(enabled = inCall) { CallUiState.minimized = true }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1A2160), Color(0xFF0A0C14), Color(0xFF241050))))
            .clickable(enabled = false) { },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (s.video) tr("ՏԵՍԱԶԱՆԳ") else tr("ԶԱՆԳ"),
                color = Color(0xFFB9C2FF),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x22FFFFFF))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = s.title.ifEmpty { tr("Խումբ") },
                color = Color.White,
                fontSize = if (s.phase == GPhase.RINGING) 28.sp else 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 36.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(text = statusOf(s, now), color = Color(0xFFD8DCFF), fontSize = 15.sp, textAlign = TextAlign.Center)

            if (s.phase == GPhase.RINGING) {
                Spacer(Modifier.height(18.dp))
                Pulse(active = true) {
                    Avatar(name = s.title.ifEmpty { "?" }, seed = s.chatId, size = 128.dp, circle = true)
                }
                Text(
                    text = trf("Զանգում է՝ {0}", s.hostName.ifEmpty { "?" }),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
                if (s.ringIn.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = trf("Արդեն զանգի մեջ են՝ {0}", s.ringIn.joinToString(", ")),
                        color = Color(0xCCFFFFFF),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 18.dp),
                    )
                }
                Box(Modifier.weight(1f))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 28.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    CallBtn(KIcons.End, tr("Մերժել"), Danger, Color.White, 76.dp) { GroupCallManager.decline() }
                    CallBtn(if (s.video) KIcons.Video else KIcons.Call, tr("Միանալ"), Good, Color.White, 76.dp) {
                        if (CallPermissions.has(ctx, s.video)) GroupCallManager.join() else perms.launch(CallPermissions.needed(s.video))
                    }
                }
            } else if (inCall) {
                GroupGrid(s, Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(34.dp))
                            .background(Color(0x22FFFFFF))
                            .padding(vertical = 12.dp, horizontal = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        CallBtn(
                            if (s.muted) KIcons.MicOff else KIcons.Mic, tr("Ձայն"),
                            if (s.muted) Color.White else Glass, if (s.muted) InkOnBrand else Color.White, 54.dp,
                        ) { GroupCallManager.toggleMute() }
                        CallBtn(
                            if (s.speaker) KIcons.Vol else KIcons.Vol0, tr("Բարձրախոս"),
                            if (s.speaker) Color.White else Glass, if (s.speaker) InkOnBrand else Color.White, 54.dp,
                        ) { GroupCallManager.toggleSpeaker() }
                        if (s.video && !s.noCamera) {
                            CallBtn(
                                if (s.cameraOn) KIcons.Video else KIcons.VideoOff, tr("Տեսախցիկ"),
                                if (s.cameraOn) Glass else Color.White, if (s.cameraOn) Color.White else InkOnBrand, 54.dp,
                            ) { GroupCallManager.toggleCamera() }
                            CallBtn(KIcons.Flip, tr("Շրջել"), Glass, Color.White, 54.dp) { GroupCallManager.switchCamera() }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    CallBtn(KIcons.End, tr("Լքել զանգը"), Danger, Color.White, 68.dp) { GroupCallManager.leave() }
                    Spacer(Modifier.height(6.dp))
                }
            } else {
                Box(Modifier.weight(1f))
            }
        }
        if (inCall) {
            IconCircle(
                icon = KIcons.Down,
                onClick = { CallUiState.minimized = true },
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 14.dp, top = 12.dp),
                size = 40.dp,
                bg = Glass,
                tint = Color.White,
                description = tr("Փոքրացնել"),
            )
        }
        if (log.isNotEmpty() && s.phase != GPhase.RINGING) {
            // a test aid: the last things the call did, so a call that does not connect can be read from a screenshot
            Text(
                text = log.joinToString("\n"),
                color = Color(0xFFB8FFB8),
                fontSize = 9.sp,
                lineHeight = 11.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 8.dp, end = 8.dp, top = 64.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xAA000000))
                    .padding(4.dp),
            )
        }
    }
}

/** The green strip above the app while a group call is made small. Tap it to get the call screen back. */
@Composable
fun GroupCallBar() {
    val s by GroupCallManager.ui.collectAsStateWithLifecycle()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.phase, s.startedAt) {
        while (s.phase == GPhase.ACTIVE && s.startedAt > 0L) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Good)
            .clickable { CallUiState.minimized = false }
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (s.video) KIcons.Video else KIcons.Call,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = s.title.ifEmpty { tr("Խումբ") },
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = tr("Սեղմիր՝ զանգին վերադառնալու համար"), color = Color(0xCCFFFFFF), fontSize = 11.sp)
        }
        Text(text = statusOf(s, now), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

/** The bar under the header of a group chat whose group has a call going on: who is in it and a button to join. */
@Composable
fun GroupCallBanner(b: GBanner, onJoin: () -> Unit) {
    val k = KTheme.colors
    val names = b.people.joinToString(", ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(k.accent.copy(alpha = 0.14f))
            .padding(start = Space.l, end = Space.xs, top = Space.xs, bottom = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (b.video) KIcons.Video else KIcons.Call,
            contentDescription = null,
            tint = k.accent,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (b.video) tr("Խմբային տեսազանգ է ընթանում") else tr("Խմբային ձայնային զանգ է ընթանում"),
                color = k.text,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = if (b.full) (if (names.isEmpty()) "" else "$names · ") + tr("լիքն է") else names
            if (sub.isNotEmpty()) {
                Text(
                    text = sub,
                    color = k.textSecondary,
                    style = TextStyle(fontSize = 12.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TextButton(onClick = onJoin, enabled = !b.full) { Text(tr("Միանալ")) }
    }
}

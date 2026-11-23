package am.khosq.app.ui

import am.khosq.app.tr
import am.khosq.app.callNote

import am.khosq.app.call.CallManager
import am.khosq.app.call.CallPermissions
import am.khosq.app.call.Phase
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

private val Glass = Color(0x33FFFFFF)
private val Danger = Color(0xFFFF5C6C)
private val Good = Color(0xFF1FAE6B)

@Composable
private fun VideoView(track: VideoTrack?, mirror: Boolean, overlay: Boolean, modifier: Modifier) {
    var renderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    AndroidView(
        factory = { c ->
            SurfaceViewRenderer(c).apply {
                init(CallManager.eglContext(), null)
                setEnableHardwareScaler(true)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setMirror(mirror)
                setZOrderMediaOverlay(overlay)
                renderer = this
            }
        },
        modifier = modifier,
    )
    DisposableEffect(track, renderer) {
        val r = renderer
        if (r != null && track != null) {
            try { track.addSink(r) } catch (e: Exception) { }
        }
        onDispose {
            if (r != null && track != null) {
                try { track.removeSink(r) } catch (e: Exception) { }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            try { renderer?.release() } catch (e: Exception) { }
        }
    }
}

@Composable
private fun CallBtn(
    icon: ImageVector,
    label: String,
    bg: Color,
    tint: Color,
    size: Dp = 60.dp,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(size).clip(CircleShape).background(bg).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = tint, modifier = Modifier.size(size * 0.44f))
        }
        if (label.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(text = label, color = Color(0xCCFFFFFF), fontSize = 12.sp)
        }
    }
}

/** Two soft rings that grow around the avatar while the phone is ringing. */
@Composable
private fun Pulse(active: Boolean, content: @Composable () -> Unit) {
    val t = rememberInfiniteTransition(label = "pulse")
    val p by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing)),
        label = "p",
    )
    Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
        if (active) {
            for (k in 0..1) {
                val q = (p + k * 0.5f) % 1f
                Box(
                    Modifier
                        .size(130.dp + 106.dp * q)
                        .graphicsLayer { alpha = (1f - q) * 0.6f }
                        .border(2.dp, Color(0xFF8091FF), CircleShape),
                )
            }
        }
        content()
    }
}

/** True while the call is shrunk to the green bar at the top, so the rest of the app can be used. */
object CallUiState {
    var minimized by mutableStateOf(false)
}

/** A call that is ringing out, connecting or running (not ringing in, not finished). */
fun callIsOn(p: Phase): Boolean = p == Phase.OUTGOING || p == Phase.CONNECTING || p == Phase.CONNECTED

/** The green strip shown above the app while a call is minimized. Tap it to get the call screen back. */
@Composable
fun CallBar() {
    val s by CallManager.ui.collectAsStateWithLifecycle()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.phase) {
        while (s.phase == Phase.CONNECTED) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val status = when (s.phase) {
        Phase.CONNECTED -> formatDur(((now - s.startedAt) / 1000).toInt().coerceAtLeast(0))
        Phase.CONNECTING -> tr("Միանում է…")
        else -> tr("Զանգում է…")
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
                text = s.peerName,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = tr("Սեղմիր՝ զանգին վերադառնալու համար"), color = Color(0xCCFFFFFF), fontSize = 11.sp)
        }
        Text(text = status, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

/** What the small floating window (picture-in-picture) shows: the other person's picture, or their avatar and the time. */
@Composable
fun CallPip() {
    val s by CallManager.ui.collectAsStateWithLifecycle()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.phase) {
        while (s.phase == Phase.CONNECTED) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val time = when (s.phase) {
        Phase.CONNECTED -> formatDur(((now - s.startedAt) / 1000).toInt().coerceAtLeast(0))
        Phase.CONNECTING -> tr("Միանում է…")
        else -> tr("Զանգում է…")
    }
    val remoteShown = s.video && s.remoteVideo != null && s.phase == Phase.CONNECTED
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1A2160), Color(0xFF0A0C14), Color(0xFF241050)))),
        contentAlignment = Alignment.Center,
    ) {
        if (remoteShown) {
            VideoView(s.remoteVideo, mirror = false, overlay = false, modifier = Modifier.fillMaxSize())
            Text(
                text = time,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x88000000))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(name = s.peerName.ifEmpty { "?" }, seed = s.peerUid, size = 56.dp, circle = true)
                Spacer(Modifier.height(6.dp))
                Text(text = s.peerName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(text = time, color = Color(0xFFD8DCFF), fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun CallOverlay() {
    val s by CallManager.ui.collectAsStateWithLifecycle()
    LaunchedEffect(s.phase) {
        // a new or finished call always starts on the full call screen
        if (s.phase == Phase.IDLE || s.phase == Phase.INCOMING || s.phase == Phase.ENDED) CallUiState.minimized = false
    }
    if (s.phase == Phase.IDLE) return
    if (CallUiState.minimized && callIsOn(s.phase)) return
    BackHandler(enabled = callIsOn(s.phase)) { CallUiState.minimized = true }
    val ctx = LocalContext.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.phase) {
        while (s.phase == Phase.CONNECTED) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (CallPermissions.has(ctx, s.video) || CallPermissions.has(ctx, false)) {
            CallManager.accept()
        } else {
            CallManager.decline()
        }
    }

    val status = when (s.phase) {
        Phase.OUTGOING -> tr("Զանգում է…")
        Phase.INCOMING -> if (s.video) tr("Մուտքային տեսազանգ") else tr("Մուտքային զանգ")
        Phase.CONNECTING -> tr("Միանում է…")
        Phase.CONNECTED -> formatDur(((now - s.startedAt) / 1000).toInt().coerceAtLeast(0))
        Phase.ENDED -> if (s.note.isEmpty()) tr("Զանգն ավարտվեց") else callNote(s.note)
        Phase.IDLE -> ""
    }
    val remoteShown = s.video && s.remoteVideo != null && s.phase == Phase.CONNECTED
    val ringing = s.phase == Phase.OUTGOING || s.phase == Phase.INCOMING || s.phase == Phase.CONNECTING

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF1A2160), Color(0xFF0A0C14), Color(0xFF241050))))
            .clickable(enabled = false) { },
    ) {
        if (remoteShown) {
            VideoView(s.remoteVideo, mirror = false, overlay = false, modifier = Modifier.fillMaxSize())
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent))),
            )
        }

        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 16.dp),
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
            if (!remoteShown) {
                Spacer(Modifier.height(18.dp))
                Pulse(active = ringing) {
                    Avatar(name = s.peerName.ifEmpty { "?" }, seed = s.peerUid, size = 128.dp, circle = true)
                }
            } else {
                Spacer(Modifier.height(12.dp))
            }
            Text(text = s.peerName, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(text = status, color = Color(0xFFD8DCFF), fontSize = 16.sp)

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (s.video && s.localVideo != null && s.phase != Phase.INCOMING) {
                    VideoView(
                        s.localVideo,
                        mirror = true,
                        overlay = true,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 12.dp)
                            .size(width = 104.dp, height = 148.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .border(1.5.dp, Color(0x66FFFFFF), RoundedCornerShape(18.dp)),
                    )
                }
            }

            when (s.phase) {
                Phase.INCOMING -> Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 28.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    CallBtn(KIcons.End, tr("Մերժել"), Danger, Color.White, 76.dp) { CallManager.decline() }
                    CallBtn(if (s.video) KIcons.Video else KIcons.Call, tr("Պատասխանել"), Good, Color.White, 76.dp) {
                        if (CallPermissions.has(ctx, s.video)) CallManager.accept()
                        else perms.launch(CallPermissions.needed(s.video))
                    }
                }
                Phase.ENDED -> Unit
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(34.dp))
                            .background(Color(0x22FFFFFF))
                            .padding(vertical = 14.dp, horizontal = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        CallBtn(
                            if (s.muted) KIcons.MicOff else KIcons.Mic, tr("Ձայն"),
                            if (s.muted) Color.White else Glass, if (s.muted) InkOnBrand else Color.White, 56.dp,
                        ) { CallManager.toggleMute() }
                        CallBtn(
                            if (s.speaker) KIcons.Vol else KIcons.Vol0, tr("Բարձրախոս"),
                            if (s.speaker) Color.White else Glass, if (s.speaker) InkOnBrand else Color.White, 56.dp,
                        ) { CallManager.toggleSpeaker() }
                        if (s.video) {
                            CallBtn(
                                if (s.cameraOn) KIcons.Video else KIcons.VideoOff, tr("Տեսախցիկ"),
                                if (s.cameraOn) Glass else Color.White, if (s.cameraOn) Color.White else InkOnBrand, 56.dp,
                            ) { CallManager.toggleCamera() }
                            CallBtn(KIcons.Flip, tr("Շրջել"), Glass, Color.White, 56.dp) { CallManager.switchCamera() }
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                    CallBtn(KIcons.End, "", Danger, Color.White, 72.dp) { CallManager.hangup() }
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
        if (callIsOn(s.phase)) {
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
    }
}

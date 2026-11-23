package am.khosq.app.ui

import am.khosq.app.call.CallManager
import am.khosq.app.call.CallPermissions
import am.khosq.app.call.Phase
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

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
private fun RoundBtn(label: String, color: Color, active: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(66.dp)
            .clip(CircleShape)
            .background(if (active) color else color.copy(alpha = 0.35f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, fontSize = 26.sp)
    }
}

@Composable
fun CallOverlay() {
    val s by CallManager.ui.collectAsStateWithLifecycle()
    if (s.phase == Phase.IDLE) return
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
        Phase.OUTGOING -> "Զանգում է…"
        Phase.INCOMING -> if (s.video) "Մուտքային տեսազանգ" else "Մուտքային զանգ"
        Phase.CONNECTING -> "Միանում է…"
        Phase.CONNECTED -> formatDur(((now - s.startedAt) / 1000).toInt().coerceAtLeast(0))
        Phase.ENDED -> if (s.note.isEmpty()) "Զանգն ավարտվեց" else s.note
        Phase.IDLE -> ""
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF16205F), Color(0xFF090C22), Color(0xFF1E1040))))
            .clickable(enabled = false) { },
    ) {
        if (s.video && s.remoteVideo != null && s.phase == Phase.CONNECTED) {
            VideoView(s.remoteVideo, mirror = false, overlay = false, modifier = Modifier.fillMaxSize())
        }

        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(28.dp))
            val showFace = !(s.video && s.remoteVideo != null && s.phase == Phase.CONNECTED)
            if (showFace) {
                Avatar(name = s.peerName.ifEmpty { "?" }, seed = s.peerUid, size = 120.dp)
                Spacer(Modifier.height(18.dp))
            }
            Text(
                text = s.peerName,
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(text = status, color = Color(0xFFE0E4FF), fontSize = 16.sp)

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
                            .clip(RoundedCornerShape(16.dp)),
                    )
                }
            }

            when (s.phase) {
                Phase.INCOMING -> Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    RoundBtn("✖", Color(0xFFE5484D)) { CallManager.decline() }
                    RoundBtn("✆", Color(0xFF2FB36B)) {
                        if (CallPermissions.has(ctx, s.video)) CallManager.accept()
                        else perms.launch(CallPermissions.needed(s.video))
                    }
                }
                Phase.ENDED -> Unit
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 22.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        RoundBtn(if (s.muted) "🔇" else "🎙", Color(0xFF3A4180), active = true) { CallManager.toggleMute() }
                        RoundBtn(if (s.speaker) "🔊" else "🔈", Color(0xFF3A4180)) { CallManager.toggleSpeaker() }
                        if (s.video) {
                            RoundBtn(if (s.cameraOn) "📹" else "🚫", Color(0xFF3A4180)) { CallManager.toggleCamera() }
                            RoundBtn("🔄", Color(0xFF3A4180)) { CallManager.switchCamera() }
                        }
                    }
                    RoundBtn("✖", Color(0xFFE5484D)) { CallManager.hangup() }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

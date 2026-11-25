package am.khosq.app

import am.khosq.app.call.CallManager
import am.khosq.app.call.CallPermissions
import am.khosq.app.call.Phase
import am.khosq.app.ui.CallOverlay
import am.khosq.app.ui.CallPip
import am.khosq.app.ui.CallUiState
import am.khosq.app.ui.KhosqTheme
import am.khosq.app.ui.backgroundBrush
import am.khosq.app.ui.callIsOn
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The call screen that opens by itself when someone calls and the phone is locked or asleep.
 * It is allowed to show above the lock screen, so the call can be answered without unlocking,
 * and it shows nothing but the call: the chats stay behind the lock.
 */
class CallActivity : ComponentActivity() {
    /** True while this screen is the small floating window (picture-in-picture). */
    private var pip by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        showAboveLockScreen()
        // "no screenshots" covers the call screen too
        AppLock.applyScreen(window)
        CallManager.init(applicationContext)
        answerIfAsked(intent)
        setContent {
            KhosqTheme {
                CallHost(
                    pip = pip,
                    onToApp = { toApp() },
                    onDone = { finish() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showAboveLockScreen()
        answerIfAsked(intent)
    }

    @Suppress("DEPRECATION")
    private fun showAboveLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
    }

    /** The "Answer" button of the notification opens this screen and picks up the call right away. */
    private fun answerIfAsked(i: Intent?) {
        if (i == null || !i.getBooleanExtra(EXTRA_ANSWER, false)) return
        i.removeExtra(EXTRA_ANSWER)
        val s = CallManager.ui.value
        // without the microphone permission the call screen asks for it when the person taps "Answer"
        if (s.phase == Phase.INCOMING && CallPermissions.has(this, s.video)) CallManager.accept()
    }

    /** Leaves the call screen for the app; the call goes on and the app shows the green bar. */
    private fun toApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }

    override fun onStart() {
        super.onStart()
        AppState.started()
        CallManager.appVisible(true)
    }

    override fun onStop() {
        if (AppState.stopped()) CallManager.appVisible(false)
        super.onStop()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (callIsOn(CallManager.ui.value.phase)) {
            try {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16)).build(),
                )
            } catch (e: Exception) {
                // this phone does not allow it: the call goes on from the notification
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pip = isInPictureInPictureMode
    }

    companion object {
        const val EXTRA_ANSWER = "answer"
    }
}

@Composable
private fun CallHost(pip: Boolean, onToApp: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val call by CallManager.ui.collectAsStateWithLifecycle()
    val on = callIsOn(call.phase)

    // the screen closes when the call is over
    LaunchedEffect(call.phase) {
        if (call.phase == Phase.IDLE) onDone()
    }

    // the "back" button of a running call: the call moves to the green bar in the app
    LaunchedEffect(CallUiState.minimized, on) {
        if (CallUiState.minimized && on) onToApp()
    }

    // the screen stays lit for as long as a call is going on
    val active = call.phase != Phase.IDLE
    DisposableEffect(active) {
        val w = (ctx as? android.app.Activity)?.window
        if (active) w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    Box(Modifier.fillMaxSize().background(backgroundBrush())) {
        if (pip && on) CallPip() else CallOverlay()
    }
}

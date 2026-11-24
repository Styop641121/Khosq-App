package am.khosq.app

import am.khosq.app.call.CallManager
import am.khosq.app.data.Profile
import am.khosq.app.ui.CallBar
import am.khosq.app.ui.CallOverlay
import am.khosq.app.ui.CallPip
import am.khosq.app.ui.CallUiState
import am.khosq.app.ui.callIsOn
import am.khosq.app.ui.AppViewModel
import am.khosq.app.ui.Boot
import am.khosq.app.ui.ChatScreen
import am.khosq.app.ui.Corner
import am.khosq.app.ui.EmptyState
import am.khosq.app.ui.KIcons
import am.khosq.app.ui.KTheme
import am.khosq.app.ui.LocalReduceMotion
import am.khosq.app.ui.Motion
import am.khosq.app.ui.Space
import am.khosq.app.ui.SplashScreen
import am.khosq.app.ui.GradientButton
import am.khosq.app.ui.HomeScreen
import am.khosq.app.ui.InviteJoinHost
import am.khosq.app.ui.KhosqTheme
import am.khosq.app.ui.SetupScreen
import am.khosq.app.ui.StarredScreen
import am.khosq.app.ui.TasksScreen
import am.khosq.app.ui.UpdateDialog
import am.khosq.app.update.Updater
import am.khosq.app.ui.backgroundBrush
import android.Manifest
import android.app.Activity
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.util.Rational
import android.view.WindowManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

class MainActivity : ComponentActivity() {
    private val askNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // the window shows this colour until the first frame is drawn, so a light-theme start does not flash dark
        val lightNow = when (AppTheme.mode) {
            "light" -> true
            "dark" -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES
        }
        window.setBackgroundDrawable(ColorDrawable(if (lightNow) 0xFFF5F6FB.toInt() else 0xFF0A0C14.toInt()))
        CallManager.init(applicationContext)
        readIntent(intent)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            KhosqTheme {
                Root()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(i: Intent?) {
        if (i?.getBooleanExtra("call", false) == true && Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        val id = i?.getStringExtra("chat")
        if (id != null) AppState.pendingChat.value = id
        // a tap on a task reminder
        if (i?.getBooleanExtra(Reminders.EXTRA_OPEN_TASKS, false) == true) AppState.openTasks.value = true
        // a group invite link (khosq://join/TOKEN, or the https page of the Worker)
        val invite = am.khosq.app.push.InviteClient.tokenFrom(i?.data)
        if (invite != null) AppState.pendingInvite.value = invite
    }

    override fun onStart() {
        super.onStart()
        AppState.inForeground = true
        AppState.started()
        CallManager.appVisible(true)
    }

    override fun onStop() {
        AppState.inForeground = false
        // the camera pauses only when the call screen is not on show either
        if (AppState.stopped()) CallManager.appVisible(false)
        super.onStop()
    }

    /** Home or recents during a call: the call shrinks into a small floating window instead of disappearing. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val p = CallManager.ui.value.phase
        if (callIsOn(p)) {
            try {
                enterPictureInPictureMode(
                    PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16)).build(),
                )
            } catch (e: Exception) {
                // this phone does not allow it: the call goes on from the notification as before
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        AppState.inPip.value = isInPictureInPictureMode
    }
}

private fun markBatteryAsked(ctx: Context) {
    ctx.getSharedPreferences("khosq", Context.MODE_PRIVATE).edit().putBoolean("battery_asked", true).apply()
}

@Composable
private fun Root(vm: AppViewModel = viewModel()) {
    val ctx = LocalContext.current
    val boot by vm.boot.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val call by CallManager.ui.collectAsStateWithLifecycle()
    var askBattery by remember { mutableStateOf(false) }
    var askFullScreen by remember { mutableStateOf(false) }
    val pip by AppState.inPip.collectAsStateWithLifecycle()
    val callOn = callIsOn(call.phase)
    val showBar = callOn && CallUiState.minimized && !pip

    // the floating window has nothing left to show once the call is over
    LaunchedEffect(pip, callOn) {
        if (pip && !callOn) (ctx as? Activity)?.moveTaskToBack(true)
    }

    // the screen stays lit for as long as a call is going on
    DisposableEffect(call.phase != am.khosq.app.call.Phase.IDLE) {
        val w = (ctx as? Activity)?.window
        if (call.phase != am.khosq.app.call.Phase.IDLE) w?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    LaunchedEffect(boot is Boot.Ready) {
        if (boot is Boot.Ready) {
            KeepAliveService.start(ctx)
            // look for a newer build at most every three hours, and say something only if there is one
            val nowMs = System.currentTimeMillis()
            if (nowMs - Prefs.getLong("upd_at") > 3L * 3600L * 1000L) {
                Prefs.putLong("upd_at", nowMs)
                Updater.checkAsync(ctx, true)
            }
            val prefs = ctx.getSharedPreferences("khosq", Context.MODE_PRIVATE)
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!prefs.getBoolean("battery_asked", false) && !pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                askBattery = true
            }
            if (Build.VERSION.SDK_INT >= 34) {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                if (!nm.canUseFullScreenIntent() && !prefs.getBoolean("fsi_asked", false)) askFullScreen = true
            }
        }
    }

    LaunchedEffect(call.phase) {
        val act = ctx as? Activity
        if (act != null) {
            val on = call.phase != am.khosq.app.call.Phase.IDLE
            if (!on) kotlinx.coroutines.delay(2500)
            if (Build.VERSION.SDK_INT >= 27) {
                act.setShowWhenLocked(on)
                act.setTurnScreenOn(on)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(backgroundBrush())) {
        Column(Modifier.fillMaxSize()) {
            if (showBar) CallBar()
            // below the green bar the status bar is already taken care of
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(if (showBar) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier),
            ) {
                when (val b = boot) {
                    is Boot.Loading -> SplashScreen()
                    is Boot.Failed -> EmptyState(
                        icon = KIcons.Info,
                        title = b.text,
                        modifier = Modifier.align(Alignment.Center),
                        action = {
                            GradientButton(text = tr("Նորից փորձել"), onClick = { vm.start() }, modifier = Modifier.width(220.dp))
                        },
                    )
                    is Boot.NeedSetup -> SetupScreen(vm)
                    is Boot.Ready -> MainNav(vm, b.me)
                }

                val n = notice
                if (n != null) {
                    val kc = KTheme.colors
                    Text(
                        text = n,
                        color = kc.text,
                        style = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(Space.xxl)
                            .shadow(10.dp, RoundedCornerShape(Corner.l))
                            .clip(RoundedCornerShape(Corner.l))
                            .background(kc.surfaceHigh)
                            .clickable { vm.clearNotice() }
                            .padding(horizontal = Space.l, vertical = Space.m),
                    )
                }
            }
        }

        if (pip && callOn) CallPip() else CallOverlay()
        if (boot is Boot.Ready) UpdateDialog()
    }

    if (askFullScreen) {
        val done = {
            ctx.getSharedPreferences("khosq", Context.MODE_PRIVATE).edit().putBoolean("fsi_asked", true).apply()
            askFullScreen = false
        }
        AlertDialog(
            onDismissRequest = { done() },
            title = { Text(tr("Զանգի էկրան")) },
            text = { Text(tr("Որ զանգի ժամանակ էկրանը ինքնուրույն վառվի, թույլ տուր KHOSQ-ին ցույց տալ ամբողջ էկրանով ծանուցումներ։")) },
            confirmButton = {
                TextButton(onClick = {
                    done()
                    try {
                        ctx.startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + ctx.packageName)),
                        )
                    } catch (e: Exception) {
                        // the phone has no such screen
                    }
                }) { Text(tr("Թույլատրել")) }
            },
            dismissButton = { TextButton(onClick = { done() }) { Text(tr("Ոչ հիմա")) } },
        )
    }

    if (askBattery) {
        AlertDialog(
            onDismissRequest = {
                markBatteryAsked(ctx)
                askBattery = false
            },
            title = { Text(tr("Նամակներ փակ հավելվածի ժամանակ")) },
            text = {
                Text(
                    tr("Որ նամակները հասնեն նաև երբ հավելվածը փակ է, թույլ տուր KHOSQ-ին աշխատել ֆոնում (մարտկոցի խնայողությունից բացառել)։"),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    markBatteryAsked(ctx)
                    askBattery = false
                    try {
                        ctx.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)),
                        )
                    } catch (e: Exception) {
                        // the phone has no such screen
                    }
                }) { Text(tr("Թույլատրել")) }
            },
            dismissButton = {
                TextButton(onClick = {
                    markBatteryAsked(ctx)
                    askBattery = false
                }) { Text(tr("Ոչ հիմա")) }
            },
        )
    }
}

@Composable
private fun MainNav(vm: AppViewModel, me: Profile) {
    val nav = rememberNavController()
    val pending by AppState.pendingChat.collectAsStateWithLifecycle()
    val openTasks by AppState.openTasks.collectAsStateWithLifecycle()

    LaunchedEffect(openTasks) {
        if (openTasks) {
            nav.navigate("tasks") { launchSingleTop = true }
            AppState.openTasks.value = false
        }
    }

    LaunchedEffect(pending) {
        val id = pending
        if (id != null) {
            nav.navigate("chat/$id")
            AppState.pendingChat.value = null
        }
    }

    // a group invite link was opened: ask, then join
    InviteJoinHost(onOpen = { id -> nav.navigate("chat/$id") })

    // screens glide in a short way and fade; with "remove animations" on, they only fade
    val calm = LocalReduceMotion.current
    val slide = tween<IntOffset>(Motion.Normal, easing = Motion.Emphasized)
    val fade = tween<Float>(Motion.Normal)
    NavHost(
        navController = nav,
        startDestination = "home",
        enterTransition = {
            if (calm) fadeIn(fade) else slideInHorizontally(slide, initialOffsetX = { w -> w / 6 }) + fadeIn(fade)
        },
        exitTransition = { fadeOut(fade) },
        popEnterTransition = { fadeIn(fade) },
        popExitTransition = {
            if (calm) fadeOut(fade) else slideOutHorizontally(slide, targetOffsetX = { w -> w / 6 }) + fadeOut(fade)
        },
    ) {
        composable("home") {
            HomeScreen(
                vm = vm,
                me = me,
                onOpen = { id -> nav.navigate("chat/$id") },
                onStarred = { nav.navigate("starred") },
                onTasks = { nav.navigate("tasks") { launchSingleTop = true } },
            )
        }
        composable("tasks") {
            TasksScreen(vm = vm, onBack = { nav.popBackStack() }, onOpenChat = { id -> nav.navigate("chat/$id") })
        }
        composable("starred") {
            StarredScreen(vm = vm, onBack = { nav.popBackStack() }, onOpen = { id -> nav.navigate("chat/$id") })
        }
        composable(
            route = "chat/{id}",
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString("id") ?: ""
            ChatScreen(vm = vm, me = me, chatId = id, onBack = { nav.popBackStack() })
        }
    }
}

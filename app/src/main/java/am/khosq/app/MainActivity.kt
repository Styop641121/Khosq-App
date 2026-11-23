package am.khosq.app

import am.khosq.app.call.CallManager
import am.khosq.app.data.Profile
import am.khosq.app.ui.CallOverlay
import am.khosq.app.ui.AppViewModel
import am.khosq.app.ui.Boot
import am.khosq.app.ui.ChatScreen
import am.khosq.app.ui.GradientButton
import am.khosq.app.ui.HomeScreen
import am.khosq.app.ui.KhosqTheme
import am.khosq.app.ui.SetupScreen
import am.khosq.app.ui.backgroundBrush
import android.Manifest
import android.app.Activity
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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
    }

    override fun onStart() {
        super.onStart()
        AppState.inForeground = true
    }

    override fun onStop() {
        AppState.inForeground = false
        super.onStop()
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

    LaunchedEffect(boot is Boot.Ready) {
        if (boot is Boot.Ready) {
            KeepAliveService.start(ctx)
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
        when (val b = boot) {
            is Boot.Loading -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.primary,
            )
            is Boot.Failed -> Column(
                modifier = Modifier.align(Alignment.Center).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(text = b.text, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                GradientButton(text = tr("Նորից փորձել"), onClick = { vm.start() }, modifier = Modifier.fillMaxWidth())
            }
            is Boot.NeedSetup -> SetupScreen(vm)
            is Boot.Ready -> MainNav(vm, b.me)
        }

        val n = notice
        if (n != null) {
            Text(
                text = n,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(24.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { vm.clearNotice() }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            )
        }

        CallOverlay()
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

    LaunchedEffect(pending) {
        val id = pending
        if (id != null) {
            nav.navigate("chat/$id")
            AppState.pendingChat.value = null
        }
    }

    NavHost(
        navController = nav,
        startDestination = "home",
        enterTransition = { slideInHorizontally(initialOffsetX = { w -> w / 5 }) + fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { slideOutHorizontally(targetOffsetX = { w -> w / 5 }) + fadeOut() },
    ) {
        composable("home") {
            HomeScreen(vm = vm, me = me, onOpen = { id -> nav.navigate("chat/$id") })
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

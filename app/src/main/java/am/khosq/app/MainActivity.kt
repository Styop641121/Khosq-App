package am.khosq.app

import am.khosq.app.ui.AppViewModel
import am.khosq.app.ui.Boot
import am.khosq.app.ui.ChatScreen
import am.khosq.app.ui.GradientButton
import am.khosq.app.ui.HomeScreen
import am.khosq.app.ui.KhosqTheme
import am.khosq.app.ui.SetupScreen
import am.khosq.app.ui.backgroundBrush
import am.khosq.app.data.Profile
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KhosqTheme {
                Root()
            }
        }
    }
}

@Composable
private fun Root(vm: AppViewModel = viewModel()) {
    val boot by vm.boot.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()

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
                GradientButton(text = "Նորից փորձել", onClick = { vm.start() }, modifier = Modifier.fillMaxWidth())
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
    }
}

@Composable
private fun MainNav(vm: AppViewModel, me: Profile) {
    val nav = rememberNavController()
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

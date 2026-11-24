package am.khosq.app.ui

import am.khosq.app.AppState
import am.khosq.app.push.InviteClient
import am.khosq.app.tr
import am.khosq.app.trf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/** Shows the "join this group?" question when the phone was asked to open an invite link. Nothing happens before the person says yes. */
@Composable
fun InviteJoinHost(onOpen: (String) -> Unit) {
    val token by AppState.pendingInvite.collectAsStateWithLifecycle()
    val t = token
    if (t != null) InviteDialog(t, onOpen)
}

private sealed interface InviteState {
    object Looking : InviteState
    data class Ready(val info: InviteClient.Info) : InviteState
    object Joining : InviteState
    data class Failed(val text: String) : InviteState
}

@Composable
private fun InviteDialog(token: String, onOpen: (String) -> Unit) {
    var state by remember(token) { mutableStateOf<InviteState>(InviteState.Looking) }
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { AppState.pendingInvite.value = null }

    // look at the group first (this does not join)
    LaunchedEffect(token) {
        state = try {
            InviteState.Ready(InviteClient.look(token))
        } catch (e: Exception) {
            InviteState.Failed(InviteClient.explain(e, false))
        }
    }

    val st = state
    AlertDialog(
        onDismissRequest = { if (st !is InviteState.Joining) close() },
        title = {
            Text(
                when (st) {
                    is InviteState.Ready -> if (st.info.member) st.info.title else trf("Միանա՞լ «{0}» խմբին", st.info.title)
                    else -> tr("Խմբի հրավեր")
                },
            )
        },
        text = {
            Text(
                when (st) {
                    is InviteState.Looking -> tr("Ստուգում եմ հղումը…")
                    is InviteState.Joining -> tr("Միանում եմ…")
                    is InviteState.Failed -> st.text
                    is InviteState.Ready ->
                        if (st.info.member) tr("Դու արդեն այս խմբում ես։")
                        else trf("Խմբում {0} անդամ կա։ Կտեսնես միայն այն նամակները, որոնք կգրվեն միանալուց հետո։", st.info.count.toString())
                },
            )
        },
        confirmButton = {
            if (st is InviteState.Ready) {
                if (st.info.member) {
                    TextButton(onClick = {
                        close()
                        onOpen(st.info.chatId)
                    }) { Text(tr("Բացել")) }
                } else {
                    TextButton(onClick = {
                        state = InviteState.Joining
                        scope.launch {
                            try {
                                val joined = InviteClient.join(token)
                                close()
                                onOpen(joined.chatId)
                            } catch (e: Exception) {
                                state = InviteState.Failed(InviteClient.explain(e, false))
                            }
                        }
                    }) { Text(tr("Միանալ")) }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { close() }, enabled = st !is InviteState.Joining) { Text(tr("Փակել")) }
        },
    )
}

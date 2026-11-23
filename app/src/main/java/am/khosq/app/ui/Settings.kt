package am.khosq.app.ui

import am.khosq.app.AppTheme
import am.khosq.app.KeepAliveService
import am.khosq.app.Lang
import am.khosq.app.data.Chat
import am.khosq.app.data.Profile
import am.khosq.app.tr
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Opens the system screen where the person picks the sound, vibration and importance of message notifications. */
private fun openSoundSettings(ctx: Context) {
    KeepAliveService.createChannels(ctx)
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, KeepAliveService.CH_MSG)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: Exception) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e2: Exception) {
            // the phone has no such screen
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = title.uppercase(), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

/** The language buttons. They are also shown on the very first screen, so the person can switch before registering. */
@Composable
fun LanguageChips(modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((code, label) in Lang.options) {
            Chip(label = label, selected = Lang.code == code, modifier = Modifier.weight(1f)) { Lang.set(code) }
        }
    }
}

/** Third tab: my photo, name, "about", ID and the app settings. */
@Composable
fun SettingsTab(vm: AppViewModel, me: Profile) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var name by remember(me.name) { mutableStateOf(me.name) }
    var about by remember(me.about) { mutableStateOf(me.about) }
    val changed = name.trim() != me.name || about.trim() != me.about
    var copied by remember { mutableStateOf(false) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.Default) {
                val bytes = MediaUtil.avatar(ctx, uri)
                if (bytes != null) vm.setPhoto(bytes)
            }
        }
    }
    val pickPhoto = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section(tr("Իմ պրոֆիլը")) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.BottomEnd) {
                    Avatar(
                        name = me.name,
                        seed = me.uid,
                        size = 104.dp,
                        modifier = Modifier.clickable { pickPhoto() },
                    )
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                            .clickable { pickPhoto() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = KIcons.Camera,
                            contentDescription = tr("Փոխել նկարը"),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = pickPhoto) { Text(tr("Փոխել նկարը")) }
                    if (me.pv > 0L) {
                        TextButton(onClick = { vm.setPhoto(null) }) {
                            Text(tr("Ջնջել նկարը"), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { v -> name = v.take(30) },
                label = { Text(tr("Քո անունը")) },
                singleLine = true,
                shape = RoundedCornerShape(17.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = about,
                onValueChange = { v -> about = v.take(140) },
                label = { Text(tr("Իմ մասին")) },
                placeholder = { Text(tr("Մի քանի բառ քո մասին…")) },
                maxLines = 3,
                shape = RoundedCornerShape(17.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            GradientButton(
                text = tr("Պահպանել"),
                enabled = changed && name.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = { vm.saveProfile(name, about) },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(text = tr("Իմ ID-ն"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text = "@" + me.handle, fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(me.handle))
                    copied = true
                }) { Text(if (copied) tr("Պատճենվեց") else tr("Պատճենել")) }
            }
        }

        Section(tr("Լեզու")) {
            LanguageChips()
        }

        Section(tr("Թեմա")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(tr("Ինչպես հեռախոսում"), AppTheme.mode == "system", Modifier.weight(1.4f)) { AppTheme.set("system") }
                Chip(tr("Լուսավոր"), AppTheme.mode == "light", Modifier.weight(1f)) { AppTheme.set("light") }
                Chip(tr("Մութ"), AppTheme.mode == "dark", Modifier.weight(1f)) { AppTheme.set("dark") }
            }
        }

        Section(tr("Ծանուցումներ")) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { openSoundSettings(ctx) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(text = tr("Նամակների ձայնը"), fontWeight = FontWeight.SemiBold)
                    Text(
                        text = tr("Ընտրիր ձայնը և թրթռոցը"),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(text = "›", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Section(tr("Գաղտնիություն")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(text = tr("Ցույց տալ «առցանց»"), fontWeight = FontWeight.SemiBold)
                    Text(
                        text = tr("Եթե անջատես, ուրիշները չեն տեսնի՝ երբ ես առցանց, ոչ էլ վերջին այցս։"),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Switch(checked = !me.hideSeen, onCheckedChange = { on -> vm.setHideSeen(!on) })
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** What another person has told about themselves: photo, name, ID, "about" and when they were last online. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactSheet(chat: Chat, about: String, status: String, onClose: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Avatar(name = chat.peerName, seed = chat.peerUid, size = 120.dp)
            Spacer(Modifier.height(6.dp))
            Text(text = chat.peerName, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (chat.peerHandle.isNotEmpty()) {
                Text(text = "@" + chat.peerHandle, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
            }
            if (status.isNotEmpty()) {
                Text(text = status, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Text(text = tr("Նկարագրություն"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = if (about.isBlank()) tr("Դեռ ոչինչ չի գրել") else about,
                color = if (about.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

package am.khosq.app.ui

import am.khosq.app.tr
import am.khosq.app.trf
import am.khosq.app.membersText

import am.khosq.app.data.Chat
import am.khosq.app.data.Message
import am.khosq.app.data.Profile
import am.khosq.app.data.ProfileStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One contact in a picker: avatar, name and a round tick that fills with the accent when chosen. */
@Composable
private fun PickRow(chat: Chat, on: Boolean, onToggle: () -> Unit) {
    val k = KTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.m))
            .clickable(onClick = onToggle)
            .padding(horizontal = Space.xs, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (chat.saved) SavedAvatar(44.dp) else Avatar(name = chat.peerName, seed = chat.peerUid, size = 44.dp)
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(
                text = chat.label(),
                color = k.text,
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (chat.peerHandle.isNotEmpty()) {
                Text(text = "@" + chat.peerHandle, color = k.textSecondary, style = TextStyle(fontSize = 13.sp))
            }
        }
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .then(if (on) Modifier.background(Brush.linearGradient(BrandColors)) else Modifier.border(1.5.dp, k.lineStrong, CircleShape)),
            contentAlignment = Alignment.Center,
        ) {
            if (on) Icon(imageVector = KIcons.Check, contentDescription = null, tint = InkOnBrand, modifier = Modifier.size(16.dp))
        }
    }
}

/** "Cancel" and "Save" side by side, for the small inline editors. */
@Composable
private fun CancelSaveRow(saveEnabled: Boolean, onCancel: () -> Unit, onSave: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        SoftButton(text = tr("Չեղարկել"), onClick = onCancel, modifier = Modifier.weight(1f))
        GradientButton(text = tr("Պահպանել"), onClick = onSave, enabled = saveEnabled, modifier = Modifier.weight(1f))
    }
}

/** Bottom sheet: name the group, tick the people, create. */
@Composable
fun CreateGroupSheet(vm: AppViewModel, onClose: () -> Unit, onOpen: (String) -> Unit) {
    val k = KTheme.colors
    val contacts = remember { vm.contacts() }
    var title by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    KSheet(onDismiss = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Text(text = tr("Նոր խումբ"), color = k.text, style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold))
            OutlinedTextField(
                value = title,
                onValueChange = { v -> if (v.length <= 40) title = v },
                label = { Text(tr("Խմբի անունը")) },
                singleLine = true,
                shape = RoundedCornerShape(Corner.m),
                colors = kFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (contacts.isEmpty()) {
                HintText(text = tr("Խումբ ստեղծելու համար նախ պետք է ունենաս առնվազն մեկ ընդունված կոնտակտ։"))
            } else {
                Text(
                    text = trf("Ընտրիր անդամներին ({0})", picked.size.toString()),
                    color = k.textSecondary,
                    style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    for (c in contacts) {
                        PickRow(chat = c, on = c.peerUid in picked) {
                            if (c.peerUid in picked) picked.remove(c.peerUid) else picked.add(c.peerUid)
                        }
                    }
                }
            }
            val err = error
            if (err != null) Text(text = err, color = k.danger, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium))
            GradientButton(
                text = if (busy) tr("Ստեղծվում է…") else tr("Ստեղծել խումբը"),
                enabled = !busy && title.isNotBlank() && picked.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    busy = true
                    error = null
                    vm.createGroup(title, contacts.filter { c -> c.peerUid in picked }) { id, e ->
                        busy = false
                        if (id != null) {
                            onClose()
                            onOpen(id)
                        } else {
                            error = e ?: tr("Չհաջողվեց")
                        }
                    }
                },
            )
        }
    }
}

/** Members, rename, add and remove (admin only), leave. */
@Composable
fun GroupInfoSheet(vm: AppViewModel, me: Profile, chat: Chat, onClose: () -> Unit, onLeft: () -> Unit) {
    val k = KTheme.colors
    val iAmAdmin = me.uid in chat.admins
    var renaming by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf(chat.peerName) }
    var adding by remember { mutableStateOf(false) }
    val picked = remember { mutableStateListOf<String>() }
    var confirmLeave by remember { mutableStateOf(false) }
    var actionFor by remember { mutableStateOf<String?>(null) }
    var editingDesc by remember { mutableStateOf(false) }
    var newDesc by remember(chat.desc) { mutableStateOf(chat.desc) }
    val candidates = remember(chat.members) { vm.contacts().filter { c -> c.peerUid !in chat.members } }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.Default) {
                val bytes = MediaUtil.avatar(ctx, uri)
                if (bytes != null) vm.setGroupPhoto(chat.id, bytes)
            }
        }
    }
    val hasPhoto = ProfileStore.photos[chat.id] != null

    KSheet(onDismiss = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                Avatar(name = chat.peerName, seed = chat.id, size = 92.dp)
                Spacer(Modifier.height(Space.xs))
                Text(
                    text = chat.peerName,
                    color = k.text,
                    style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(text = membersText(chat.members.size), color = k.textSecondary, style = TextStyle(fontSize = 14.sp))
            }

            if (chat.desc.isNotEmpty() && !editingDesc) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = Space.l, vertical = Space.s)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Corner.l))
                        .background(k.surfaceHigh)
                        .padding(Space.l),
                    verticalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    Text(text = tr("Նկարագրություն"), color = k.textSecondary, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
                    Text(text = chat.desc, color = k.text, style = TextStyle(fontSize = 16.sp, lineHeight = 22.sp))
                }
            }

            if (iAmAdmin) {
                SheetRow(
                    icon = KIcons.Camera,
                    label = tr("Փոխել նկարը"),
                    onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                )
                if (hasPhoto) {
                    SheetRow(icon = KIcons.Trash, label = tr("Ջնջել նկարը"), danger = true, onClick = { vm.setGroupPhoto(chat.id, null) })
                }
                if (editingDesc) {
                    Column(Modifier.padding(horizontal = Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        OutlinedTextField(
                            value = newDesc,
                            onValueChange = { v -> if (v.length <= 200) newDesc = v },
                            label = { Text(tr("Նկարագրություն")) },
                            shape = RoundedCornerShape(Corner.m),
                            colors = kFieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        CancelSaveRow(
                            saveEnabled = newDesc.trim() != chat.desc,
                            onCancel = {
                                editingDesc = false
                                newDesc = chat.desc
                            },
                            onSave = {
                                vm.setGroupDesc(chat.id, newDesc)
                                editingDesc = false
                            },
                        )
                    }
                } else {
                    SheetRow(icon = KIcons.Edit, label = tr("Փոխել նկարագրությունը"), onClick = { editingDesc = true })
                }
                if (renaming) {
                    Column(Modifier.padding(horizontal = Space.l), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        OutlinedTextField(
                            value = newTitle,
                            onValueChange = { v -> if (v.length <= 40) newTitle = v },
                            label = { Text(tr("Խմբի նոր անունը")) },
                            singleLine = true,
                            shape = RoundedCornerShape(Corner.m),
                            colors = kFieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        CancelSaveRow(
                            saveEnabled = newTitle.isNotBlank() && newTitle.trim() != chat.peerName,
                            onCancel = { renaming = false },
                            onSave = {
                                vm.renameGroup(chat.id, newTitle)
                                renaming = false
                            },
                        )
                    }
                } else {
                    SheetRow(icon = KIcons.Edit, label = tr("Փոխել անունը"), onClick = { renaming = true })
                }
                SheetRow(
                    icon = KIcons.Lock,
                    label = tr("Միայն ադմինները գրեն"),
                    subtitle = if (chat.adminOnly) tr("Միացված է. մյուսները միայն կարդում են") else tr("Անջատված է. բոլորը կարող են գրել"),
                    onClick = { vm.setAdminOnly(chat.id, !chat.adminOnly) },
                )
            }

            SectionLabel(tr("Անդամներ"))
            for (uid in chat.members) {
                val name = if (uid == me.uid) tr("Դու") else (chat.memberNames[uid] ?: "?")
                val handle = chat.memberHandles[uid] ?: ""
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = iAmAdmin && uid != me.uid) { actionFor = uid }
                        .padding(horizontal = Space.l, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(name = name, seed = uid, size = 44.dp)
                    Spacer(Modifier.width(Space.m))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = name,
                            color = k.text,
                            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (handle.isNotEmpty()) {
                            Text(text = "@" + handle, color = k.textSecondary, style = TextStyle(fontSize = 13.sp))
                        }
                    }
                    if (uid in chat.admins) {
                        Text(
                            text = tr("ադմին"),
                            color = k.accent,
                            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                            modifier = Modifier
                                .clip(RoundedCornerShape(Corner.s))
                                .background(k.accentSoft)
                                .padding(horizontal = Space.s, vertical = 3.dp),
                        )
                    }
                    if (iAmAdmin && uid != me.uid) {
                        KIconButton(
                            icon = KIcons.Close,
                            onClick = { vm.removeMember(chat.id, uid, chat.memberNames[uid] ?: "") },
                            tint = k.danger,
                            size = 40.dp,
                            iconSize = 20.dp,
                        )
                    }
                }
            }

            if (iAmAdmin) {
                if (adding) {
                    Column(Modifier.padding(horizontal = Space.l), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        if (candidates.isEmpty()) {
                            HintText(text = tr("Բոլոր կոնտակտներդ արդեն խմբում են։"))
                        } else {
                            for (c in candidates) {
                                PickRow(chat = c, on = c.peerUid in picked) {
                                    if (c.peerUid in picked) picked.remove(c.peerUid) else picked.add(c.peerUid)
                                }
                            }
                        }
                        Spacer(Modifier.height(Space.xs))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            SoftButton(
                                text = tr("Չեղարկել"),
                                onClick = {
                                    adding = false
                                    picked.clear()
                                },
                                modifier = Modifier.weight(1f),
                            )
                            GradientButton(
                                text = trf("Ավելացնել ({0})", picked.size.toString()),
                                enabled = picked.isNotEmpty(),
                                onClick = {
                                    vm.addMembers(chat.id, candidates.filter { c -> c.peerUid in picked })
                                    picked.clear()
                                    adding = false
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                } else {
                    SheetRow(icon = KIcons.PersonAdd, label = tr("Ավելացնել անդամ"), onClick = { adding = true })
                }
            }

            SheetRow(icon = KIcons.Close, label = tr("Լքել խումբը"), danger = true, onClick = { confirmLeave = true })
        }
    }

    val target = actionFor
    if (target != null) {
        val targetName = chat.memberNames[target] ?: ""
        val isAdmin = target in chat.admins
        AlertDialog(
            onDismissRequest = { actionFor = null },
            title = { Text(targetName) },
            text = { Text(tr("Ադմինը կարող է փոխել խմբի տվյալները, ավելացնել և հեռացնել անդամներին։")) },
            confirmButton = {
                TextButton(onClick = {
                    vm.setAdmin(chat.id, target, targetName, !isAdmin)
                    actionFor = null
                }) { Text(if (isAdmin) tr("Հանել ադմինությունից") else tr("Նշանակել ադմին")) }
            },
            dismissButton = { TextButton(onClick = { actionFor = null }) { Text(tr("Փակել")) } },
        )
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(tr("Լքե՞լ խումբը")) },
            text = { Text(tr("Դու այլևս չես տեսնի այս խմբի նամակները։")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    vm.leaveGroup(chat)
                    onClose()
                    onLeft()
                }) { Text(tr("Լքել"), color = k.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(tr("Ոչ")) } },
        )
    }
}

/** Pick one or more chats and send a copy of the message there. */
@Composable
fun ForwardSheet(vm: AppViewModel, message: Message, onClose: () -> Unit) {
    val k = KTheme.colors
    val targets = remember { vm.shareTargets() }
    val picked = remember { mutableStateListOf<String>() }
    KSheet(onDismiss = onClose) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Text(text = tr("Փոխանցել…"), color = k.text, style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold))
            if (targets.isEmpty()) {
                HintText(text = tr("Դեռ չկա զրույց, որին կարելի է փոխանցել։"))
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    for (c in targets) {
                        PickRow(chat = c, on = c.id in picked) {
                            if (c.id in picked) picked.remove(c.id) else picked.add(c.id)
                        }
                    }
                }
            }
            GradientButton(
                text = if (picked.isEmpty()) tr("Ընտրիր զրույց") else trf("Ուղարկել ({0})", picked.size.toString()),
                enabled = picked.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    vm.forward(message, targets.filter { c -> c.id in picked })
                    onClose()
                },
            )
        }
    }
}

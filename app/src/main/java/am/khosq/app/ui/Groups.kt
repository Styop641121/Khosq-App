package am.khosq.app.ui

import am.khosq.app.data.Chat
import am.khosq.app.data.Message
import am.khosq.app.data.Profile
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One contact in a picker: avatar, name and a round tick that turns gold when chosen. */
@Composable
private fun PickRow(chat: Chat, on: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name = chat.peerName, seed = chat.peerUid, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(text = chat.peerName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (chat.peerHandle.isNotEmpty()) {
                Text(
                    text = "@" + chat.peerHandle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(if (on) Brush.linearGradient(BrandColors) else Brush.linearGradient(listOf(Color(0x00000000), Color(0x00000000))))
                .border(1.5.dp, if (on) Color.Transparent else MaterialTheme.colorScheme.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (on) Icon(imageVector = KIcons.Check, contentDescription = null, tint = InkOnBrand, modifier = Modifier.size(16.dp))
        }
    }
}

/** Bottom sheet: name the group, tick the people, create. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupSheet(vm: AppViewModel, onClose: () -> Unit, onOpen: (String) -> Unit) {
    val contacts = remember { vm.contacts() }
    var title by remember { mutableStateOf("") }
    val picked = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onClose, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "Նոր խումբ", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = title,
                onValueChange = { v -> if (v.length <= 40) title = v },
                label = { Text("Խմբի անունը") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (contacts.isEmpty()) {
                Text(
                    text = "Խումբ ստեղծելու համար նախ պետք է ունենաս առնվազն մեկ ընդունված կոնտակտ։",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "Ընտրիր անդամներին (" + picked.size + ")",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
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
            if (err != null) Text(text = err, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            GradientButton(
                text = if (busy) "Ստեղծվում է…" else "Ստեղծել խումբը",
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
                            error = e ?: "Չհաջողվեց"
                        }
                    }
                },
            )
        }
    }
}

/** Members, rename, add and remove (admin only), leave. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupInfoSheet(vm: AppViewModel, me: Profile, chat: Chat, onClose: () -> Unit, onLeft: () -> Unit) {
    val iAmAdmin = me.uid in chat.admins
    var renaming by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf(chat.peerName) }
    var adding by remember { mutableStateOf(false) }
    val picked = remember { mutableStateListOf<String>() }
    var confirmLeave by remember { mutableStateOf(false) }
    val candidates = remember(chat.members) { vm.contacts().filter { c -> c.peerUid !in chat.members } }

    ModalBottomSheet(onDismissRequest = onClose, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(name = chat.peerName, seed = chat.id, size = 56.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = chat.peerName,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = chat.members.size.toString() + " անդամ",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                    )
                }
            }

            if (iAmAdmin) {
                if (renaming) {
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { v -> if (v.length <= 40) newTitle = v },
                        label = { Text("Խմբի նոր անունը") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { renaming = false }) { Text("Չեղարկել") }
                        TextButton(
                            enabled = newTitle.isNotBlank() && newTitle.trim() != chat.peerName,
                            onClick = {
                                vm.renameGroup(chat.id, newTitle)
                                renaming = false
                            },
                        ) { Text("Պահպանել") }
                    }
                } else {
                    TextButton(onClick = { renaming = true }) { Text("✎  Փոխել անունը") }
                }
            }

            Text(text = "Անդամներ", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            for (uid in chat.members) {
                val name = if (uid == me.uid) "Դու" else (chat.memberNames[uid] ?: "?")
                val handle = chat.memberHandles[uid] ?: ""
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(name = name, seed = uid, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(text = name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (handle.isNotEmpty()) {
                            Text(text = "@" + handle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (uid in chat.admins) {
                        Text(
                            text = "ադմին",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    if (iAmAdmin && uid != me.uid) {
                        TextButton(onClick = { vm.removeMember(chat.id, uid, chat.memberNames[uid] ?: "") }) {
                            Text("✕", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            if (iAmAdmin) {
                if (adding) {
                    if (candidates.isEmpty()) {
                        Text(
                            text = "Բոլոր կոնտակտներդ արդեն խմբում են։",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                        )
                    } else {
                        for (c in candidates) {
                            PickRow(chat = c, on = c.peerUid in picked) {
                                if (c.peerUid in picked) picked.remove(c.peerUid) else picked.add(c.peerUid)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            adding = false
                            picked.clear()
                        }) { Text("Չեղարկել") }
                        TextButton(
                            enabled = picked.isNotEmpty(),
                            onClick = {
                                vm.addMembers(chat.id, candidates.filter { c -> c.peerUid in picked })
                                picked.clear()
                                adding = false
                            },
                        ) { Text("Ավելացնել (" + picked.size + ")") }
                    }
                } else {
                    TextButton(onClick = { adding = true }) { Text("+  Ավելացնել անդամ") }
                }
            }

            TextButton(onClick = { confirmLeave = true }) {
                Text("Լքել խումբը", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Լքե՞լ խումբը") },
            text = { Text("Դու այլևս չես տեսնի այս խմբի նամակները։") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    vm.leaveGroup(chat)
                    onClose()
                    onLeft()
                }) { Text("Լքել", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Ոչ") } },
        )
    }
}

/** Pick one or more chats and send a copy of the message there. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardSheet(vm: AppViewModel, message: Message, onClose: () -> Unit) {
    val targets = remember { vm.shareTargets() }
    val picked = remember { mutableStateListOf<String>() }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "Փոխանցել…", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (targets.isEmpty()) {
                Text(text = "Դեռ չկա զրույց, որին կարելի է փոխանցել։", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                text = if (picked.isEmpty()) "Ընտրիր զրույց" else "Ուղարկել (" + picked.size + ")",
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

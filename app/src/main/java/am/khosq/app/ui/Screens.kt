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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private fun cleanHandle(s: String): String =
    s.lowercase().filter { c -> c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-' }.take(30)

/* ---------------- first start: name and ID ---------------- */

@Composable
fun SetupScreen(vm: AppViewModel) {
    var name by remember { mutableStateOf("") }
    var handle by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(28.dp))
        BrandMark(88.dp)
        BrandTitle(34.sp)
        Text(
            text = "Գրիր անունդ և ընտրիր քո ID-ն։ Ընկերդ քեզ կգտնի հենց այդ ID-ով։",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(30) },
            label = { Text("Քո անունը") },
            singleLine = true,
            shape = RoundedCornerShape(17.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = handle,
            onValueChange = { handle = cleanHandle(it) },
            label = { Text("Քո ID-ն (լատինատառ, թվեր)") },
            singleLine = true,
            shape = RoundedCornerShape(17.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        val e = error
        if (e != null) {
            Text(text = e, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
        }
        GradientButton(
            text = "Սկսել",
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val n = name.trim()
                if (n.isEmpty()) {
                    error = "Գրիր քո անունը"
                } else if (handle.length < 3) {
                    error = "ID-ն պետք է լինի առնվազն 3 նշան"
                } else {
                    error = null
                    busy = true
                    vm.register(n, handle) { msg ->
                        busy = false
                        error = msg
                    }
                }
            },
        )
    }
}

/* ---------------- chat list ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel, me: Profile, onOpen: (String) -> Unit) {
    val chats by vm.chats.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var showMe by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandMark(40.dp)
                Spacer(Modifier.width(12.dp))
                BrandTitle()
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(22.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { showMe = true }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    Text(text = "@" + me.handle, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
            if (chats.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(text = "Դեռ զրույցներ չկան", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = "Սեղմիր «Նոր կոնտակտ» և գրիր ընկերոջդ ID-ն։",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(chats, key = { c -> c.id }) { chat ->
                        ChatRow(chat = chat, myUid = me.uid, onClick = { onOpen(chat.id) })
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(20.dp)
                .shadow(10.dp, RoundedCornerShape(29.dp))
                .height(58.dp)
                .clip(RoundedCornerShape(29.dp))
                .background(Brush.linearGradient(BrandColors))
                .clickable { showAdd = true }
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "+  Նոր կոնտակտ", color = InkOnBrand, fontWeight = FontWeight.ExtraBold)
        }
    }

    if (showAdd) {
        AddContactSheet(vm = vm, onClose = { showAdd = false }, onOpen = onOpen)
    }
    if (showMe) {
        AlertDialog(
            onDismissRequest = { showMe = false },
            title = { Text("Իմ ID-ն") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = me.name, fontWeight = FontWeight.Bold)
                    Text(text = me.handle, fontFamily = FontFamily.Monospace, fontSize = 20.sp)
                    Text(
                        text = "Ուղարկիր այս ID-ն ընկերոջդ, որ նա քեզ գտնի։",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(me.handle))
                    showMe = false
                }) { Text("Պատճենել") }
            },
            dismissButton = { TextButton(onClick = { showMe = false }) { Text("Փակել") } },
        )
    }
}

@Composable
private fun ChatRow(chat: Chat, myUid: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name = chat.peerName, seed = chat.peerUid)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = chat.peerName,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val prefix = if (chat.lastFrom == myUid) "Դու՝ " else ""
            Text(
                text = if (chat.lastText.isEmpty()) "@" + chat.peerHandle else prefix + chat.lastText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (chat.lastTs > 0L) {
                Text(
                    text = formatTime(chat.lastTs),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (chat.unread > 0L) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Brush.linearGradient(BrandColors))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = chat.unread.toString(), color = InkOnBrand, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddContactSheet(vm: AppViewModel, onClose: () -> Unit, onOpen: (String) -> Unit) {
    var handle by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onClose, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(text = "Նոր կոնտակտ", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = handle,
                onValueChange = { handle = cleanHandle(it) },
                label = { Text("Ընկերոջդ ID-ն") },
                singleLine = true,
                shape = RoundedCornerShape(17.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            val e = error
            if (e != null) {
                Text(text = e, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            }
            GradientButton(
                text = "Գտնել և սկսել զրույց",
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    error = null
                    busy = true
                    vm.addContact(handle) { chatId, err ->
                        busy = false
                        if (err != null) {
                            error = err
                        } else if (chatId != null) {
                            onClose()
                            onOpen(chatId)
                        }
                    }
                },
            )
        }
    }
}

/* ---------------- conversation ---------------- */

@Composable
fun ChatScreen(vm: AppViewModel, me: Profile, chatId: String, onBack: () -> Unit) {
    val chats by vm.chats.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val chat = chats.firstOrNull { c -> c.id == chatId }
    val peerUid = chat?.peerUid ?: chatId.split("_").firstOrNull { p -> p != me.uid } ?: ""
    var text by remember { mutableStateOf("") }
    val ctx = LocalContext.current

    DisposableEffect(chatId) {
        vm.openChat(chatId)
        NotificationManagerCompat.from(ctx).cancel(chatId.hashCode())
        onDispose { vm.closeChat() }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().navigationBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Հետ")
            }
            Avatar(name = chat?.peerName ?: "?", seed = peerUid, size = 42.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = chat?.peerName ?: "…",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (chat != null && chat.peerHandle.isNotEmpty()) "@" + chat.peerHandle else "",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(messages.asReversed(), key = { m -> m.id }) { m ->
                Bubble(m = m, mine = m.from == me.uid, peerRead = chat?.peerRead ?: 0L)
            }
        }

        val pill = RoundedCornerShape(30.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .clip(pill)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                .border(1.dp, MaterialTheme.colorScheme.outline, pill)
                .padding(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            TextField(
                value = text,
                onValueChange = { text = it.take(4000) },
                placeholder = { Text("Գրիր նամակ…") },
                maxLines = 5,
                modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
            )
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(BrandColors))
                    .clickable(enabled = text.isNotBlank()) {
                        vm.send(chatId, peerUid, text.trim())
                        text = ""
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Ուղարկել", tint = InkOnBrand)
            }
        }
    }
}

@Composable
private fun Bubble(m: Message, mine: Boolean, peerRead: Long) {
    val shape = if (mine) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
    val fill = if (mine) {
        Modifier.background(Brush.linearGradient(listOf(Color(0xFFFFD36A), Color(0xFFFFA24A))))
    } else {
        Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
    }
    val ink = if (mine) InkOnBrand else MaterialTheme.colorScheme.onSurface
    val soft = if (mine) Color(0xB31B1000) else MaterialTheme.colorScheme.onSurfaceVariant
    val read = mine && !m.pending && peerRead >= m.ts
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .then(fill)
                .padding(horizontal = 13.dp, vertical = 8.dp),
        ) {
            Text(text = m.text, color = ink, fontSize = 16.sp)
            Row(modifier = Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Text(text = formatTime(m.ts), fontSize = 11.sp, color = soft)
                if (mine) {
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (m.pending) "🕓" else if (read) "✓✓" else "✓",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (read) Color(0xFF1A3FD0) else soft,
                    )
                }
            }
        }
    }
}

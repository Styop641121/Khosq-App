package am.khosq.app.ui

import am.khosq.app.tr
import am.khosq.app.trf
import am.khosq.app.membersText
import am.khosq.app.previewOf
import am.khosq.app.replyText

import am.khosq.app.call.CallManager
import am.khosq.app.call.CallPermissions
import am.khosq.app.data.Chat
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import am.khosq.app.data.Message
import am.khosq.app.data.Profile
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.font.FontStyle
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
        LanguageChips()
        Text(
            text = tr("Գրիր անունդ և ընտրիր քո ID-ն։ Ընկերդ քեզ կգտնի հենց այդ ID-ով։"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(30) },
            label = { Text(tr("Քո անունը")) },
            singleLine = true,
            shape = RoundedCornerShape(17.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = handle,
            onValueChange = { handle = cleanHandle(it) },
            label = { Text(tr("Քո ID-ն (լատինատառ, թվեր)")) },
            singleLine = true,
            shape = RoundedCornerShape(17.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        val e = error
        if (e != null) {
            Text(text = e, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
        }
        GradientButton(
            text = tr("Սկսել"),
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                val n = name.trim()
                if (n.isEmpty()) {
                    error = tr("Գրիր քո անունը")
                } else if (handle.length < 3) {
                    error = tr("ID-ն պետք է լինի առնվազն 3 նշան")
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
fun HomeScreen(vm: AppViewModel, me: Profile, onOpen: (String) -> Unit, onStarred: () -> Unit) {
    val chats by vm.chats.collectAsStateWithLifecycle()
    val stories by vm.stories.collectAsStateWithLifecycle()
    var viewing by remember { mutableStateOf<String?>(null) }
    var composing by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var showGroup by remember { mutableStateOf(false) }
    var homeSearch by rememberSaveable { mutableStateOf(false) }
    var homeQuery by rememberSaveable { mutableStateOf("") }
    val homeFocus = remember { FocusRequester() }
    LaunchedEffect(homeSearch) {
        if (homeSearch) homeFocus.requestFocus()
    }
    var showMe by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val calls by vm.calls.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    var menuChat by remember { mutableStateOf<Chat?>(null) }
    BackHandler(enabled = tab != 0) { tab = 0 }
    BackHandler(enabled = homeSearch && tab == 0) {
        homeSearch = false
        homeQuery = ""
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrandMark(40.dp)
            Spacer(Modifier.width(12.dp))
            BrandTitle()
            Spacer(Modifier.weight(1f))
            if (tab == 0) {
                IconCircle(
                    icon = KIcons.Search,
                    onClick = {
                        homeSearch = !homeSearch
                        if (!homeSearch) homeQuery = ""
                    },
                    size = 40.dp,
                    bg = MaterialTheme.colorScheme.surfaceVariant,
                    tint = MaterialTheme.colorScheme.onSurface,
                    description = tr("Որոնել"),
                )
                Spacer(Modifier.width(10.dp))
            }
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

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab == 0) {
                Column(Modifier.fillMaxSize()) {
                    if (homeSearch) {
                        OutlinedTextField(
                            value = homeQuery,
                            onValueChange = { v -> homeQuery = v },
                            singleLine = true,
                            placeholder = { Text(tr("Որոնել զրույցներում…")) },
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp).focusRequester(homeFocus),
                        )
                    } else {
                        StoriesRail(
                            stories = stories,
                            me = me,
                            onOpen = { owner -> viewing = owner },
                            onAdd = { composing = true },
                        )
                    }
                    val shownChats = if (homeSearch && homeQuery.isNotBlank()) {
                        chats.filter { c ->
                            c.label().contains(homeQuery.trim(), ignoreCase = true) ||
                                c.peerHandle.contains(homeQuery.trim(), ignoreCase = true) ||
                                previewOf(c.lastKind, c.lastText).contains(homeQuery.trim(), ignoreCase = true)
                        }
                    } else {
                        chats
                    }.sortedByDescending { c -> c.id in prefs.pins }
                    if (shownChats.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(36.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (chats.isNotEmpty()) {
                                Text(text = tr("Ոչինչ չի գտնվել"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            } else {
                                Text(text = tr("Դեռ զրույցներ չկան"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    text = tr("Սեղմիր «Նոր կոնտակտ» և գրիր ընկերոջդ ID-ն։"),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 100.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(shownChats, key = { c -> c.id }) { chat ->
                                ChatRow(
                                    chat = chat,
                                    myUid = me.uid,
                                    pinned = chat.id in prefs.pins,
                                    muted = chat.id in prefs.muted,
                                    onClick = { onOpen(chat.id) },
                                    onLong = { menuChat = chat },
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .shadow(10.dp, CircleShape)
                            .size(58.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable { showGroup = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = KIcons.Group,
                            contentDescription = tr("Նոր խումբ"),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .shadow(10.dp, RoundedCornerShape(29.dp))
                            .height(58.dp)
                            .clip(RoundedCornerShape(29.dp))
                            .background(Brush.linearGradient(BrandColors))
                            .clickable { showAdd = true }
                            .padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = tr("+  Նոր կոնտակտ"), color = InkOnBrand, fontWeight = FontWeight.ExtraBold)
                    }
                }
            } else if (tab == 1) {
                CallsTab(me = me, calls = calls, chats = chats, onOpenChat = onOpen)
            } else {
                SettingsTab(vm = vm, me = me, onStarred = onStarred)
            }
        }

        HomeTabs(
            tab = tab,
            missed = calls.count { r -> r.missed && r.ts > System.currentTimeMillis() - 24L * 3600L * 1000L },
            onTab = { t -> tab = t },
        )
    }

    val mc = menuChat
    if (mc != null) {
        AlertDialog(
            onDismissRequest = { menuChat = null },
            confirmButton = { TextButton(onClick = { menuChat = null }) { Text(tr("Փակել")) } },
            title = { Text(mc.label(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    TextButton(onClick = {
                        vm.togglePin(mc.id)
                        menuChat = null
                    }) { Text(if (mc.id in prefs.pins) tr("📌 Ապաամրացնել") else tr("📌 Ամրացնել")) }
                    if (!mc.saved) {
                        TextButton(onClick = {
                            vm.toggleMute(mc.id)
                            menuChat = null
                        }) { Text(if (mc.id in prefs.muted) tr("🔔 Միացնել ձայնը") else tr("🔕 Անջատել ձայնը")) }
                    }
                }
            },
        )
    }
    if (showAdd) {
        AddContactSheet(vm = vm, onClose = { showAdd = false }, onOpen = onOpen)
    }
    if (showGroup) {
        CreateGroupSheet(vm = vm, onClose = { showGroup = false }, onOpen = onOpen)
    }
    if (composing) {
        StoryComposer(vm = vm, onClose = { composing = false })
    }
    val owner = viewing
    if (owner != null) {
        StoryViewer(ownerId = owner, me = me, vm = vm, onClose = { viewing = null })
    }
    if (showMe) {
        AlertDialog(
            onDismissRequest = { showMe = false },
            title = { Text(tr("Իմ ID-ն")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = me.name, fontWeight = FontWeight.Bold)
                    Text(text = me.handle, fontFamily = FontFamily.Monospace, fontSize = 20.sp)
                    Text(
                        text = tr("Ուղարկիր այս ID-ն ընկերոջդ, որ նա քեզ գտնի։"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(me.handle))
                    showMe = false
                }) { Text(tr("Պատճենել")) }
            },
            dismissButton = { TextButton(onClick = { showMe = false }) { Text(tr("Փակել")) } },
        )
    }
}

@Composable
private fun HomeTabs(tab: Int, missed: Int, onTab: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TabItem(Modifier.weight(1f), KIcons.Chat, tr("Զրույցներ"), tab == 0, 0) { onTab(0) }
        TabItem(Modifier.weight(1f), KIcons.Call, tr("Զանգեր"), tab == 1, missed) { onTab(1) }
        TabItem(Modifier.weight(1f), KIcons.Person, tr("Պրոֆիլ"), tab == 2, 0) { onTab(2) }
    }
}

@Composable
private fun TabItem(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    badge: Int,
    onClick: () -> Unit,
) {
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text = label, color = tint, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        if (!selected && badge > 0) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.error).size(8.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(chat: Chat, myUid: String, pinned: Boolean, muted: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (chat.saved) SavedAvatar() else Avatar(name = chat.peerName, seed = chat.peerUid)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (chat.isGroup && !chat.saved) {
                    Icon(
                        imageVector = KIcons.Group,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = chat.label(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val prefix = when {
                chat.lastFrom == myUid -> tr("Դու") + ": "
                chat.isGroup -> (chat.memberNames[chat.lastFrom] ?: "").let { n -> if (n.isEmpty()) "" else "$n: " }
                else -> ""
            }
            Text(
                text = when {
                    chat.blockedBy.isNotEmpty() -> tr("🚫 Արգելափակված")
                    chat.status == "pending" && chat.requester != myUid -> tr("🔔 Նոր հարցում")
                    chat.status == "pending" -> tr("⏳ Սպասում է հաստատման")
                    chat.lastText.isEmpty() && chat.saved -> tr("Նշումներ, հղումներ, նկարներ՝ քեզ համար")
                    chat.lastText.isEmpty() && chat.isGroup -> membersText(chat.members.size)
                    chat.lastText.isEmpty() -> "@" + chat.peerHandle
                    else -> prefix + previewOf(chat.lastKind, chat.lastText)
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (chat.lastTs > 0L) {
                Text(
                    text = (if (muted) "🔕 " else "") + formatTime(chat.lastTs),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (chat.unread > 0L) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (muted) Brush.linearGradient(listOf(Color(0xFF8A8FA8), Color(0xFF8A8FA8)))
                            else Brush.linearGradient(BrandColors),
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = chat.unread.toString(), color = InkOnBrand, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                }
            } else if (pinned) {
                Text(text = "📌", fontSize = 12.sp)
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
            Text(text = tr("Նոր կոնտակտ"), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = handle,
                onValueChange = { handle = cleanHandle(it) },
                label = { Text(tr("Ընկերոջդ ID-ն")) },
                singleLine = true,
                shape = RoundedCornerShape(17.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            val e = error
            if (e != null) {
                Text(text = e, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            }
            GradientButton(
                text = tr("Գտնել և սկսել զրույց"),
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
private fun CircleAction(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Brush.linearGradient(BrandColors))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
fun ChatScreen(vm: AppViewModel, me: Profile, chatId: String, onBack: () -> Unit) {
    val chats by vm.chats.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val replyTo by vm.replyTo.collectAsStateWithLifecycle()
    val peerInfo by vm.peerInfo.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val stars by vm.stars.collectAsStateWithLifecycle()
    val starSet = remember(stars) { stars.mapTo(HashSet<String>()) { s -> s.key } }
    val peerSeen = peerInfo.seen
    val chat = chats.firstOrNull { c -> c.id == chatId }
    val peerUid = chat?.peerUid ?: chatId.split("_").firstOrNull { p -> p != me.uid } ?: ""
    val pendingForMe = chat != null && chat.status == "pending" && chat.requester != me.uid
    val waiting = chat != null && chat.status == "pending" && chat.requester == me.uid
    val iBlocked = chat != null && me.uid in chat.blockedBy
    val canCall = chat != null && !chat.isGroup && chat.status == "active" && chat.blockedBy.isEmpty()
    val isSaved = chat?.saved == true
    val isGroup = chat != null && chat.isGroup && !isSaved
    var showInfo by remember { mutableStateOf(false) }
    var showContact by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Message?>(null) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var forwardMsg by remember { mutableStateOf<Message?>(null) }
    val searchFocus = remember { FocusRequester() }
    BackHandler(enabled = searching) {
        searching = false
        query = ""
    }
    LaunchedEffect(searching) {
        if (searching) searchFocus.requestFocus()
    }
    var moreOpen by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val recorder = remember { VoiceRecorder(ctx) }
    var recording by remember { mutableStateOf(false) }
    var recSec by remember { mutableStateOf(0) }
    var menuFor by remember { mutableStateOf<Message?>(null) }
    var typingNow by remember { mutableStateOf(false) }
    var baseTyping by remember { mutableStateOf(-1L) }
    var clock by remember { mutableStateOf(System.currentTimeMillis()) }

    LaunchedEffect(chat?.peerTyping) {
        val v = chat?.peerTyping
        if (v == null) return@LaunchedEffect
        if (baseTyping == -1L) {
            baseTyping = v
            return@LaunchedEffect
        }
        if (v > 0L && v != baseTyping) {
            typingNow = true
            delay(5000)
            typingNow = false
        } else {
            typingNow = false
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            clock = System.currentTimeMillis()
        }
    }
    val statusText = when {
        isSaved -> ""
        chat != null && chat.isGroup -> if (typingNow) tr("գրում են…") else membersText(chat.members.size)
        typingNow -> tr("գրում է…")
        peerSeen > 0L && clock - peerSeen < 100_000L -> tr("առցանց")
        peerSeen > 0L -> trf("վերջին անգամ՝ {0}", formatSeen(peerSeen))
        else -> ""
    }

    fun finishRecording(send: Boolean) {
        val result = recorder.stop(send)
        recording = false
        recSec = 0
        if (send && result != null) vm.sendVoice(chatId, peerUid, result.bytes, result.sec, result.wave)
    }

    fun beginRecording() {
        if (recorder.start()) {
            recording = true
            recSec = 0
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginRecording()
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.Default) {
                val bytes = MediaUtil.compressImage(ctx, uri)
                if (bytes != null) vm.sendImage(chatId, peerUid, bytes)
            }
        }
    }

    var wantVideo by remember { mutableStateOf(false) }
    fun placeCall() {
        CallManager.startOutgoing(me.uid, me.name, peerUid, chat?.peerName ?: "", wantVideo)
    }
    val callPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (CallPermissions.has(ctx, wantVideo)) placeCall()
    }
    fun startCall(video: Boolean) {
        if (peerUid.isEmpty()) return
        wantVideo = video
        if (CallPermissions.has(ctx, video)) placeCall() else callPerms.launch(CallPermissions.needed(video))
    }

    DisposableEffect(chatId) {
        vm.openChat(chatId)
        NotificationManagerCompat.from(ctx).cancel(chatId.hashCode())
        onDispose {
            recorder.stop(false)
            VoicePlayer.stop()
            vm.closeChat()
        }
    }

    LaunchedEffect(recording) {
        while (recording) {
            delay(100)
            recorder.sample()
            recSec = recorder.elapsedSec()
            if (recSec >= 120) finishRecording(true)
        }
    }

    val mm = menuFor
    if (mm != null) {
        val clip = LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { menuFor = null },
            confirmButton = { TextButton(onClick = { menuFor = null }) { Text(tr("Փակել")) } },
            text = {
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        for (e in listOf("👍", "❤️", "😂", "😮", "😢", "🙏")) {
                            Text(
                                text = e,
                                fontSize = 28.sp,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .clickable {
                                        vm.react(chatId, mm, e)
                                        menuFor = null
                                    }
                                    .padding(6.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        vm.setReply(mm)
                        menuFor = null
                    }) { Text(tr("↩ Պատասխանել")) }
                    TextButton(onClick = {
                        forwardMsg = mm
                        menuFor = null
                    }) { Text(tr("↪ Փոխանցել")) }
                    TextButton(onClick = {
                        vm.toggleStar(chatId, mm)
                        menuFor = null
                    }) { Text(if ((chatId + "~" + mm.id) in starSet) tr("☆ Հանել աստղը") else tr("⭐ Աստղանշել")) }
                    if (mm.kind == "text") {
                        TextButton(onClick = {
                            clip.setText(AnnotatedString(mm.text))
                            menuFor = null
                        }) { Text(tr("⧉ Պատճենել")) }
                    }
                    if (mm.from == me.uid && mm.kind == "text" && System.currentTimeMillis() - mm.ts < 15L * 60_000L) {
                        TextButton(onClick = {
                            vm.setReply(null)
                            editing = mm
                            text = mm.text
                            menuFor = null
                        }) { Text(tr("✎ Խմբագրել")) }
                    }
                    if (mm.from == me.uid) {
                        TextButton(onClick = {
                            vm.deleteMessage(chatId, mm)
                            menuFor = null
                        }) { Text(tr("🗑 Ջնջել բոլորի համար"), color = MaterialTheme.colorScheme.error) }
                    }
                }
            },
        )
    }

    val shown = if (searching && query.isNotBlank()) {
        messages.filter { m -> !m.deleted && m.kind == "text" && m.text.contains(query.trim(), ignoreCase = true) }
    } else {
        messages
    }
    val fm = forwardMsg
    if (fm != null) {
        ForwardSheet(vm = vm, message = fm, onClose = { forwardMsg = null })
    }
    if (showContact && chat != null && !chat.isGroup) {
        ContactSheet(chat = chat, about = peerInfo.about, status = statusText, onClose = { showContact = false })
    }
    if (showInfo && chat != null && chat.isGroup) {
        GroupInfoSheet(vm = vm, me = me, chat = chat, onClose = { showInfo = false }, onLeft = onBack)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().navigationBarsPadding()) {
        if (searching) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    searching = false
                    query = ""
                }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Հետ")) }
                OutlinedTextField(
                    value = query,
                    onValueChange = { v -> query = v },
                    singleLine = true,
                    placeholder = { Text(tr("Որոնել նամակներում…")) },
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f).focusRequester(searchFocus),
                )
                if (query.isNotBlank()) {
                    Text(
                        text = shown.size.toString(),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        } else Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Հետ"))
            }
            Row(
                modifier = Modifier.weight(1f).clickable(enabled = chat != null && !isSaved) {
                    if (isGroup) showInfo = true else showContact = true
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
            if (isSaved) SavedAvatar(42.dp) else Avatar(name = chat?.peerName ?: "?", seed = peerUid, size = 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = chat?.label() ?: "…",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOf(
                        if (chat != null && chat.peerHandle.isNotEmpty()) "@" + chat.peerHandle else "",
                        statusText,
                    ).filter { t -> t.isNotEmpty() }.joinToString(" · "),
                    color = if (typingNow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            }
            if (canCall) {
                IconCircle(
                    icon = KIcons.Call,
                    onClick = { startCall(false) },
                    size = 40.dp,
                    bg = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    tint = MaterialTheme.colorScheme.primary,
                    description = tr("Զանգ"),
                )
                Spacer(Modifier.width(8.dp))
                IconCircle(
                    icon = KIcons.Video,
                    onClick = { startCall(true) },
                    size = 40.dp,
                    bg = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    tint = MaterialTheme.colorScheme.primary,
                    description = tr("Տեսազանգ"),
                )
            }
            Box {
                IconButton(onClick = { moreOpen = true }) {
                    Icon(imageVector = KIcons.More, contentDescription = tr("Ավելին"))
                }
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(tr("Որոնել նամակներում")) },
                        onClick = {
                            moreOpen = false
                            searching = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (chatId in prefs.pins) tr("📌 Ապաամրացնել") else tr("📌 Ամրացնել")) },
                        onClick = {
                            moreOpen = false
                            vm.togglePin(chatId)
                        },
                    )
                    if (!isSaved) {
                        DropdownMenuItem(
                            text = { Text(if (chatId in prefs.muted) tr("🔔 Միացնել ձայնը") else tr("🔕 Անջատել ձայնը")) },
                            onClick = {
                                moreOpen = false
                                vm.toggleMute(chatId)
                            },
                        )
                    }
                    if (isGroup) {
                        DropdownMenuItem(
                            text = { Text(tr("Խմբի տվյալներ")) },
                            onClick = {
                                moreOpen = false
                                showInfo = true
                            },
                        )
                    } else if (!isSaved) {
                        DropdownMenuItem(
                            text = { Text(if (iBlocked) tr("Ապաարգելափակել") else tr("Արգելափակել")) },
                            onClick = {
                                moreOpen = false
                                vm.setBlocked(chatId, !iBlocked)
                            },
                        )
                    }
                }
            }
        }

        val listState = rememberLazyListState()
        val showDown by remember { derivedStateOf { listState.firstVisibleItemIndex > 2 } }
        val dateHeads = remember(shown) {
            val set = HashSet<String>()
            var prev = -1
            for (x in shown) {
                val k = dayKey(x.ts)
                if (k != prev) set.add(x.id)
                prev = k
            }
            set
        }
        Box(Modifier.weight(1f).fillMaxWidth().chatWallpaper()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(shown.asReversed(), key = { m -> m.id }) { m ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (m.id in dateHeads) DateChip(dayLabel(m.ts))
                        Bubble(
                            m = m,
                            mine = m.from == me.uid,
                            peerRead = chat?.peerRead ?: 0L,
                            peerDelivered = chat?.peerDelivered ?: 0L,
                            showTicks = !isSaved,
                            sender = if (chat != null && chat.isGroup && m.from != me.uid) (chat.memberNames[m.from] ?: "?") else null,
                            senderKey = m.from,
                            myUid = me.uid,
                            names = chat?.memberNames ?: emptyMap(),
                            peerName = chat?.peerName ?: "",
                            starred = (chatId + "~" + m.id) in starSet,
                            onSwipe = { vm.setReply(m) },
                            onLong = { if (!m.deleted && !m.pending && m.kind != "call" && m.kind != "sys") menuFor = m },
                        )
                    }
                }
            }
            if (showDown) {
                IconCircle(
                    icon = KIcons.Down,
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp).shadow(6.dp, CircleShape),
                    size = 44.dp,
                    bg = MaterialTheme.colorScheme.surface,
                    tint = MaterialTheme.colorScheme.primary,
                    description = tr("Ներքև"),
                )
            }
        }

        val rt = replyTo
        if (rt != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = if (rt.from == me.uid) tr("Դու") else if (chat != null && chat.isGroup) (chat.memberNames[rt.from] ?: "") else (chat?.peerName ?: ""),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = previewOf(rt.kind, rt.text),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { vm.setReply(null) }) { Text(text = "✕", fontSize = 16.sp) }
            }
        }

        val ed = editing
        if (ed != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = KIcons.Edit,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = tr("Խմբագրում"),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = ed.text,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = {
                    editing = null
                    text = ""
                }) { Text(text = "✕", fontSize = 16.sp) }
            }
        }

        if (waiting) {
            Text(
                text = trf("⏳ Սպասում ես, որ {0}-ը ընդունի հարցումդ", chat?.peerName ?: ""),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 18.dp),
            )
        }
        val pill = RoundedCornerShape(30.dp)
        if (pendingForMe) {
            NoticeBar(
                text = trf("{0}-ը ուզում է գրել քեզ", chat?.peerName ?: ""),
                primary = tr("Ընդունել"),
                onPrimary = { vm.acceptChat(chatId) },
                secondary = tr("Արգելափակել"),
                onSecondary = { vm.setBlocked(chatId, true) },
            )
        } else if (iBlocked) {
            NoticeBar(
                text = tr("Դու արգելափակել ես այս մարդուն"),
                primary = tr("Ապաարգելափակել"),
                onPrimary = { vm.setBlocked(chatId, false) },
            )
        } else Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .clip(pill)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                .border(1.dp, MaterialTheme.colorScheme.outline, pill)
                .padding(6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            if (recording) {
                Text(
                    text = "●  " + formatDur(recSec),
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(start = 14.dp, bottom = 14.dp),
                )
                TextButton(onClick = { finishRecording(false) }) { Text(tr("Չեղարկել")) }
                CircleAction(onClick = { finishRecording(true) }) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = tr("Ուղարկել"), tint = InkOnBrand)
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .clickable {
                            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "📎", fontSize = 22.sp)
                }
                TextField(
                    value = text,
                    onValueChange = {
                        text = it.take(4000)
                        if (it.isNotBlank()) vm.typing(chatId)
                    },
                    placeholder = { Text(tr("Գրիր նամակ…")) },
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
                if (text.isBlank() && editing == null) {
                    CircleAction(onClick = {
                        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        if (granted) beginRecording() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }) {
                        Text(text = "🎤", fontSize = 20.sp)
                    }
                } else {
                    CircleAction(onClick = {
                        val e = editing
                        if (e != null) {
                            vm.editMessage(chatId, e, text)
                            editing = null
                        } else {
                            vm.send(chatId, peerUid, text.trim())
                        }
                        text = ""
                    }) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = tr("Ուղարկել"), tint = InkOnBrand)
                    }
                }
            }
        }
    }
}

@Composable
private fun DateChip(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun NoticeBar(
    text: String,
    primary: String,
    onPrimary: () -> Unit,
    secondary: String? = null,
    onSecondary: () -> Unit = {},
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = text, fontSize = 15.sp)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GradientButton(text = primary, onClick = onPrimary, modifier = Modifier.weight(1f))
            if (secondary != null) {
                TextButton(onClick = onSecondary) { Text(secondary, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun ImageBody(m: Message) {
    val bmp = remember(m.id) {
        val d = m.data
        if (d == null) null else BitmapFactory.decodeByteArray(d, 0, d.size)?.asImageBitmap()
    }
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = tr("Նկար"),
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.width(240.dp).clip(RoundedCornerShape(14.dp)),
        )
    } else {
        Text(text = tr("📷 Նկար"), fontSize = 16.sp)
    }
}

/** A steady colour per person, so the same sender always looks the same in a group. */
@Composable
private fun senderColor(key: String): Color {
    var h = 0
    for (c in key) h = (h * 31 + c.code) and 0x7fffffff
    val dark = isDark()
    val palette = if (dark) {
        listOf(Color(0xFFFFB23F), Color(0xFF8FA6FF), Color(0xFFFF8A80), Color(0xFF7BE0B0), Color(0xFFD7A6FF), Color(0xFF7FD6F5))
    } else {
        listOf(Color(0xFFB45F00), Color(0xFF3C3FD6), Color(0xFFC2362B), Color(0xFF12805A), Color(0xFF8A3FC9), Color(0xFF0B7DA8))
    }
    return palette[h % palette.size]
}

private fun systemLine(m: Message, actor: String): String {
    if (m.kind == "call") {
        return when (m.code) {
            "call_done" -> (if (m.vid) "📹 " + tr("Տեսազանգ") else "📞 " + tr("Զանգ")) + " · " + formatDur(m.dur)
            "call_missed" -> "📵 " + tr("Բաց թողնված զանգ")
            "call_declined" -> "📵 " + tr("Մերժված զանգ")
            "call_cancelled" -> "📵 " + tr("Չեղարկված զանգ")
            else -> m.text
        }
    }
    return when (m.code) {
        "grp_created" -> trf("{0} ստեղծեց խումբը", actor)
        "grp_added" -> trf("{0} ավելացրեց՝ {1}", actor, m.arg)
        "grp_removed" -> trf("{0} հեռացրեց՝ {1}", actor, m.arg)
        "grp_renamed" -> trf("{0} փոխեց խմբի անունը՝ «{1}»", actor, m.arg)
        "grp_left" -> trf("{0} լքեց խումբը", actor)
        "grp_photo" -> trf("{0} փոխեց խմբի նկարը", actor)
        "grp_desc" -> trf("{0} փոխեց խմբի նկարագրությունը", actor)
        else -> m.text
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    m: Message,
    mine: Boolean,
    peerRead: Long,
    peerDelivered: Long,
    showTicks: Boolean,
    sender: String?,
    senderKey: String,
    myUid: String,
    names: Map<String, String>,
    peerName: String,
    starred: Boolean,
    onSwipe: () -> Unit,
    onLong: () -> Unit,
) {
    if (m.kind == "call" || m.kind == "sys") {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                text = systemLine(m, names[m.from] ?: peerName) + "  " + formatTime(m.ts),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
        return
    }
    val shape = if (mine) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
    val fill = if (mine) {
        Modifier.background(Brush.linearGradient(listOf(Color(0xFFFFD36A), Color(0xFFFFA24A))))
    } else {
        Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
    }
    val ink = if (mine) InkOnBrand else MaterialTheme.colorScheme.onSurface
    val soft = if (mine) Color(0xB31B1000) else MaterialTheme.colorScheme.onSurfaceVariant
    val read = mine && !m.pending && peerRead >= m.ts
    val delivered = read || (mine && !m.pending && peerDelivered >= m.ts)
    val swipe by rememberUpdatedState(onSwipe)
    val canSwipe = !m.deleted && !m.pending
    val slide = remember { Animatable(0f) }
    val slideScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val trigger = with(LocalDensity.current) { 64.dp.toPx() }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (!canSwipe) {
                    Modifier
                } else {
                    Modifier.pointerInput(m.id) {
                        var crossed = false
                        detectHorizontalDragGestures(
                            onDragStart = { crossed = false },
                            onDragEnd = {
                                val fire = slide.value >= trigger
                                slideScope.launch { slide.animateTo(0f) }
                                if (fire) swipe()
                            },
                            onDragCancel = { slideScope.launch { slide.animateTo(0f) } },
                            onHorizontalDrag = { change, dx ->
                                change.consume()
                                val nx = (slide.value + dx * 0.6f).coerceIn(0f, trigger * 1.5f)
                                if (!crossed && nx >= trigger) {
                                    crossed = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                slideScope.launch { slide.snapTo(nx) }
                            },
                        )
                    }
                },
            ),
    ) {
        if (slide.value > 8f) {
            Icon(
                imageVector = KIcons.Reply,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 10.dp)
                    .size(26.dp)
                    .graphicsLayer { alpha = (slide.value / trigger).coerceIn(0f, 1f) },
            )
        }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(slide.value.roundToInt(), 0) },
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .then(fill)
                .combinedClickable(onClick = {}, onLongClick = onLong)
                .padding(horizontal = 13.dp, vertical = 8.dp),
        ) {
            if (sender != null) {
                Text(
                    text = sender,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = senderColor(senderKey),
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            if (m.fwd && !m.deleted) {
                Text(
                    text = tr("↪ Փոխանցված"),
                    color = soft,
                    fontSize = 11.sp,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            if (m.deleted) {
                Text(text = tr("🚫 Նամակը ջնջված է"), color = soft, fontSize = 15.sp, fontStyle = FontStyle.Italic)
            } else {
                if (m.reply.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .padding(bottom = 6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x22000000))
                            .padding(8.dp),
                    ) {
                        Text(
                            text = when {
                                m.replyFrom.isEmpty() -> m.replyName
                                m.replyFrom == myUid -> tr("Դու")
                                else -> names[m.replyFrom] ?: peerName
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = ink,
                        )
                        Text(
                            text = replyText(m.reply),
                            fontSize = 13.sp,
                            color = soft,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                when (m.kind) {
                    "img" -> ImageBody(m)
                    "voice" -> VoiceBody(m, mine, onLong)
                    else -> {
                        val linkColor = if (mine) Color(0xFF0B2A9A) else MaterialTheme.colorScheme.primary
                        val rich = remember(m.text, linkColor) { richText(m.text, linkColor) }
                        Text(text = rich, color = ink, fontSize = 16.sp)
                    }
                }
                if (m.reactions.isNotEmpty()) {
                    Row(Modifier.padding(top = 4.dp)) {
                        for ((e, n) in m.reactions.values.groupingBy { it }.eachCount()) {
                            Text(
                                text = if (n > 1) "$e $n" else e,
                                fontSize = 14.sp,
                                modifier = Modifier
                                    .padding(end = 6.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0x22000000))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
            Row(modifier = Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                if (m.edited && !m.deleted) {
                    Text(text = tr("փոխված"), fontSize = 11.sp, color = soft, fontStyle = FontStyle.Italic)
                    Spacer(Modifier.width(5.dp))
                }
                if (starred) {
                    Text(text = "★", fontSize = 11.sp, color = soft)
                    Spacer(Modifier.width(4.dp))
                }
                Text(text = formatTime(m.ts), fontSize = 11.sp, color = soft)
                if (mine && showTicks) {
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = if (m.pending) "🕓" else if (delivered) "✓✓" else "✓",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (read) Color(0xFF1A3FD0) else soft,
                    )
                }
            }
        }
    }
    }
}

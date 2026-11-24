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
import android.widget.Toast
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization

@Composable
fun ChatScreen(vm: AppViewModel, me: Profile, chatId: String, onBack: () -> Unit) {
    val k = KTheme.colors
    val chats by vm.chats.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val replyTo by vm.replyTo.collectAsStateWithLifecycle()
    val peerInfo by vm.peerInfo.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val stars by vm.stars.collectAsStateWithLifecycle()
    val secure by vm.secure.collectAsStateWithLifecycle()
    val groupMissing by vm.groupMissing.collectAsStateWithLifecycle()
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
    var attachOpen by remember { mutableStateOf(false) }
    var locAsk by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var showMedia by remember { mutableStateOf(false) }
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

    val isOnline = !isSaved && !isGroup && peerSeen > 0L && clock - peerSeen < 100_000L
    var chatReady by remember { mutableStateOf(false) }
    LaunchedEffect(chatId) {
        delay(700)
        chatReady = true
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

    // one-time location: ask once for the position, then show it and send only after "Send"
    fun fetchLocation() {
        Toast.makeText(ctx, tr("Տեղադրությունը որոշվում է…"), Toast.LENGTH_SHORT).show()
        OneShotLocation.request(ctx) { loc ->
            if (loc != null) {
                locAsk = loc.latitude to loc.longitude
            } else {
                Toast.makeText(
                    ctx,
                    tr("Տեղադրությունը չհաջողվեց որոշել։ Ստուգիր, որ տեղադրությունը (GPS) միացված է հեռախոսում։"),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    val locPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (OneShotLocation.hasPermission(ctx)) {
            fetchLocation()
        } else {
            Toast.makeText(ctx, tr("Առանց թույլտվության տեղադրությունը չի ուղարկվի։"), Toast.LENGTH_LONG).show()
        }
    }
    fun askForLocation() {
        if (OneShotLocation.hasPermission(ctx)) {
            fetchLocation()
        } else {
            locPerms.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
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
        val isStarred = (chatId + "~" + mm.id) in starSet
        KSheet(onDismiss = { menuFor = null }) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.l, vertical = Space.xs)
                    .clip(RoundedCornerShape(Corner.xl))
                    .background(k.surfaceHigh)
                    .padding(horizontal = Space.s, vertical = Space.xs),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
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
                            .padding(8.dp),
                    )
                }
            }
            Spacer(Modifier.height(Space.xs))
            SheetRow(icon = KIcons.Reply, label = tr("Պատասխանել նամակին"), onClick = {
                vm.setReply(mm)
                menuFor = null
            })
            SheetRow(icon = KIcons.Forward, label = tr("Փոխանցել"), onClick = {
                forwardMsg = mm
                menuFor = null
            })
            SheetRow(
                icon = if (isStarred) KIcons.StarFill else KIcons.Star,
                label = if (isStarred) tr("Հանել աստղը") else tr("Աստղանշել"),
                onClick = {
                    vm.toggleStar(chatId, mm)
                    menuFor = null
                },
            )
            val pinnedHere = chat?.pinId == mm.id
            SheetRow(
                icon = KIcons.Pin,
                label = if (pinnedHere) tr("Ապաամրացնել նամակը") else tr("Ամրացնել նամակը"),
                onClick = {
                    vm.setPinned(chatId, if (pinnedHere) null else mm.id)
                    menuFor = null
                },
            )
            if (mm.kind == "text") {
                SheetRow(icon = KIcons.Copy, label = tr("Պատճենել"), onClick = {
                    clip.setText(AnnotatedString(mm.text))
                    menuFor = null
                })
            }
            if (mm.from == me.uid && mm.kind == "text" && System.currentTimeMillis() - mm.ts < 15L * 60_000L) {
                SheetRow(icon = KIcons.Edit, label = tr("Խմբագրել"), onClick = {
                    vm.setReply(null)
                    editing = mm
                    text = mm.text
                    menuFor = null
                })
            }
            if (mm.from == me.uid) {
                SheetRow(icon = KIcons.Trash, label = tr("Ջնջել բոլորի համար"), danger = true, onClick = {
                    vm.deleteMessage(chatId, mm)
                    menuFor = null
                })
            }
        }
    }

    val shown = if (searching && query.isNotBlank()) {
        messages.filter { m -> !m.deleted && m.kind == "text" && m.text.contains(query.trim(), ignoreCase = true) }
    } else {
        messages
    }
    // the message pinned at the top of this chat: only its id is stored, the text comes from the opened messages
    val pinnedMsg = if (chat != null && chat.pinId.isNotEmpty()) messages.firstOrNull { x -> x.id == chat.pinId && !x.deleted } else null
    val fm = forwardMsg
    if (fm != null) {
        ForwardSheet(vm = vm, message = fm, onClose = { forwardMsg = null })
    }
    if (showContact && chat != null && !chat.isGroup) {
        val common = chats.filter { g -> g.isGroup && !g.saved && chat.peerUid in g.members }.map { g -> g.peerName }
        ContactSheet(
            chat = chat,
            about = peerInfo.about,
            status = statusText,
            commonGroups = common,
            photoCount = messages.count { x -> x.kind == "img" && !x.deleted && x.data != null },
            iBlocked = iBlocked,
            onMedia = {
                showContact = false
                showMedia = true
            },
            onBlock = { vm.setBlocked(chatId, !iBlocked) },
            onClose = { showContact = false },
        )
    }
    if (showMedia) {
        MediaGallery(messages = messages, onClose = { showMedia = false })
    }
    if (attachOpen) {
        KSheet(onDismiss = { attachOpen = false }) {
            SheetRow(
                icon = KIcons.Image,
                label = tr("Նկար"),
                onClick = {
                    attachOpen = false
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            )
            SheetRow(
                icon = KIcons.Location,
                label = tr("Ուղարկել տեղադրությունը"),
                subtitle = tr("Քո ընթացիկ տեղադրությունը՝ մեկ անգամ"),
                onClick = {
                    attachOpen = false
                    askForLocation()
                },
            )
        }
    }
    val la = locAsk
    if (la != null) {
        AlertDialog(
            onDismissRequest = { locAsk = null },
            title = { Text(tr("Ուղարկե՞լ քո տեղադրությունը")) },
            text = {
                Text(
                    trf(
                        "Կուղարկվի միայն քո ներկա կետը՝ {0}։ Հետագա տեղաշարժը չի հետևվում։",
                        String.format(java.util.Locale.US, "%.5f, %.5f", la.first, la.second),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.sendLocation(chatId, peerUid, la.first, la.second)
                        locAsk = null
                    },
                ) { Text(tr("Ուղարկել")) }
            },
            dismissButton = { TextButton(onClick = { locAsk = null }) { Text(tr("Չեղարկել")) } },
        )
    }
    if (showInfo && chat != null && chat.isGroup) {
        GroupInfoSheet(vm = vm, me = me, chat = chat, onClose = { showInfo = false }, onLeft = onBack)
    }


    val listState = rememberLazyListState()
    Column(Modifier.fillMaxSize().imePadding()) {
        // ---------------- top bar ----------------
        Column(Modifier.fillMaxWidth().background(k.bar).statusBarsPadding()) {
            if (searching) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(Dim.bar).padding(horizontal = Space.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KIconButton(
                        icon = KIcons.Back,
                        onClick = {
                            searching = false
                            query = ""
                        },
                        description = tr("Հետ"),
                    )
                    SearchField(
                        value = query,
                        onValueChange = { v -> query = v },
                        placeholder = tr("Որոնել նամակներում…"),
                        focusRequester = searchFocus,
                        modifier = Modifier.weight(1f),
                    )
                    if (query.isNotBlank()) {
                        Text(
                            text = shown.size.toString(),
                            color = k.accent,
                            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(horizontal = Space.m),
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().height(Dim.bar).padding(horizontal = Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KIconButton(icon = KIcons.Back, onClick = onBack, description = tr("Հետ"))
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(Corner.m))
                            .clickable(enabled = chat != null && !isSaved) {
                                if (isGroup) showInfo = true else showContact = true
                            }
                            .padding(horizontal = Space.xs, vertical = Space.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isSaved) {
                            SavedAvatar(Dim.avatarHeader)
                        } else {
                            Avatar(name = chat?.peerName ?: "?", seed = peerUid, size = Dim.avatarHeader, online = isOnline)
                        }
                        Spacer(Modifier.width(Space.m))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = chat?.label() ?: "…",
                                color = k.text,
                                style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val sub = if (statusText.isNotEmpty()) {
                                statusText
                            } else if (chat != null && chat.peerHandle.isNotEmpty() && !isGroup) {
                                "@" + chat.peerHandle
                            } else {
                                ""
                            }
                            if (sub.isNotEmpty()) {
                                Text(
                                    text = sub,
                                    color = if (typingNow || isOnline) k.accent else k.textSecondary,
                                    style = TextStyle(fontSize = 13.sp, lineHeight = 17.sp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    if (canCall) {
                        KIconButton(icon = KIcons.PhoneLine, onClick = { startCall(false) }, tint = k.accent, description = tr("Զանգ"))
                        KIconButton(icon = KIcons.VideoLine, onClick = { startCall(true) }, tint = k.accent, description = tr("Տեսազանգ"))
                    }
                    Box {
                        KIconButton(icon = KIcons.More, onClick = { moreOpen = true }, description = tr("Ավելին"))
                        DropdownMenu(
                            expanded = moreOpen,
                            onDismissRequest = { moreOpen = false },
                            shape = RoundedCornerShape(Corner.m),
                            containerColor = k.surfaceHigh,
                            tonalElevation = 0.dp,
                            shadowElevation = 12.dp,
                        ) {
                            MenuEntry(KIcons.Search, tr("Որոնել նամակներում")) {
                                moreOpen = false
                                searching = true
                            }
                            MenuEntry(KIcons.Image, tr("Մեդիա")) {
                                moreOpen = false
                                showMedia = true
                            }
                            MenuEntry(KIcons.Pin, if (chatId in prefs.pins) tr("Ապաամրացնել") else tr("Ամրացնել")) {
                                moreOpen = false
                                vm.togglePin(chatId)
                            }
                            if (!isSaved) {
                                val mutedNow = chatId in prefs.muted
                                MenuEntry(
                                    if (mutedNow) KIcons.Bell else KIcons.BellOff,
                                    if (mutedNow) tr("Միացնել ձայնը") else tr("Անջատել ձայնը"),
                                ) {
                                    moreOpen = false
                                    vm.toggleMute(chatId)
                                }
                            }
                            if (isGroup) {
                                MenuEntry(KIcons.Group, tr("Խմբի տվյալներ")) {
                                    moreOpen = false
                                    showInfo = true
                                }
                            } else if (!isSaved) {
                                MenuEntry(
                                    KIcons.Lock,
                                    if (iBlocked) tr("Ապաարգելափակել") else tr("Արգելափակել"),
                                    danger = !iBlocked,
                                ) {
                                    moreOpen = false
                                    vm.setBlocked(chatId, !iBlocked)
                                }
                            }
                        }
                    }
                }
            }
            KDivider()
            if (!searching && pinnedMsg != null) {
                PinnedBar(
                    text = previewOf(pinnedMsg.kind, pinnedMsg.text),
                    onClick = {
                        val at = shown.indexOfFirst { x -> x.id == pinnedMsg.id }
                        // the list is upside down (newest first), and a "typing" bubble takes the first place while it shows
                        if (at >= 0) scope.launch { listState.animateScrollToItem(shown.size - 1 - at + (if (typingNow) 1 else 0)) }
                    },
                    onClose = { vm.setPinned(chatId, null) },
                )
                KDivider()
            }
        }

        // ---------------- messages ----------------
        val showDown by remember { derivedStateOf { listState.firstVisibleItemIndex > 2 } }
        val dateHeads = remember(shown) {
            val set = HashSet<String>()
            var prev = -1
            for (x in shown) {
                val dk = dayKey(x.ts)
                if (dk != prev) set.add(x.id)
                prev = dk
            }
            set
        }
        val rev = remember(shown) { shown.asReversed() }
        Box(Modifier.weight(1f).fillMaxWidth().chatWallpaper()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                reverseLayout = true,
                contentPadding = PaddingValues(horizontal = Space.m, vertical = Space.s),
            ) {
                if (typingNow && !searching) {
                    item(key = "typing") {
                        Box(Modifier.padding(top = Space.s).animateItem()) { TypingBubble() }
                    }
                }
                itemsIndexed(rev, key = { _, m -> m.id }) { i, m ->
                    val joined = sameRun(rev.getOrNull(i + 1), m)
                    val isHead = m.id in dateHeads
                    Column(Modifier.animateItem().padding(top = if (isHead) 0.dp else if (joined) 2.dp else Space.s)) {
                        if (isHead) DateChip(dayLabel(m.ts))
                        Bubble(
                            m = m,
                            mine = m.from == me.uid,
                            peerRead = chat?.peerRead ?: 0L,
                            peerDelivered = chat?.peerDelivered ?: 0L,
                            showTicks = !isSaved,
                            sender = if (chat != null && chat.isGroup && m.from != me.uid && !joined) (chat.memberNames[m.from] ?: "?") else null,
                            senderKey = m.from,
                            myUid = me.uid,
                            names = chat?.memberNames ?: emptyMap(),
                            peerName = chat?.peerName ?: "",
                            starred = (chatId + "~" + m.id) in starSet,
                            joinedOlder = joined,
                            onSwipe = { vm.setReply(m) },
                            onLong = { if (!m.deleted && !m.pending && m.kind != "call" && m.kind != "sys") menuFor = m },
                        )
                    }
                }
                // the notice about encryption is the last item of the reversed list, which is the top of the chat
                val sec = secure
                if (!searching && chat != null && sec != null) {
                    item(key = "e2e") {
                        val gaps = groupMissing.map { u -> chat.memberNames[u] ?: "?" }.joinToString(", ")
                        SecureChip(
                            secure = sec,
                            saved = isSaved,
                            group = isGroup,
                            missing = gaps,
                            onClick = if (isSaved || isGroup) null else ({ showContact = true }),
                        )
                    }
                }
            }
            if (shown.isEmpty() && (searching && query.isNotBlank() || (!searching && chatReady && chat != null))) {
                if (searching) {
                    EmptyState(
                        icon = KIcons.Search,
                        title = tr("Ոչինչ չի գտնվել"),
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    EmptyState(
                        icon = KIcons.ChatLine,
                        title = tr("Նամակներ դեռ չկան"),
                        text = tr("Գրիր առաջին նամակը"),
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
            // this sits in a Box that is itself inside a Column: the explicit name picks the plain version
            androidx.compose.animation.AnimatedVisibility(
                visible = showDown,
                modifier = Modifier.align(Alignment.BottomEnd),
                enter = fadeIn(tween(Motion.Fast)) + scaleIn(tween(Motion.Fast), initialScale = 0.7f),
                exit = fadeOut(tween(Motion.Fast)) + scaleOut(tween(Motion.Fast), targetScale = 0.7f),
            ) {
                Box(
                    modifier = Modifier
                        .padding(Space.m)
                        .shadow(6.dp, CircleShape)
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(k.surface)
                        .border(1.dp, k.line, CircleShape)
                        .pressable(onClick = { scope.launch { listState.animateScrollToItem(0) } }, pressedScale = 0.9f),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(imageVector = KIcons.Down, contentDescription = tr("Ներքև"), tint = k.accent, modifier = Modifier.size(22.dp))
                }
            }
        }

        // ---------------- bottom: banners and composer ----------------
        Column(Modifier.fillMaxWidth().background(k.bar).navigationBarsPadding()) {
            KDivider()
            val rt = replyTo
            var replyCache by remember { mutableStateOf<Message?>(null) }
            SideEffect { if (rt != null) replyCache = rt }
            AnimatedVisibility(
                visible = rt != null,
                enter = expandVertically(tween(Motion.Normal)) + fadeIn(tween(Motion.Normal)),
                exit = shrinkVertically(tween(Motion.Fast)) + fadeOut(tween(Motion.Fast)),
            ) {
                val r = rt ?: replyCache
                if (r != null) {
                    ComposerBanner(
                        icon = KIcons.Reply,
                        title = if (r.from == me.uid) tr("Դու") else if (chat != null && chat.isGroup) (chat.memberNames[r.from] ?: "") else (chat?.peerName ?: ""),
                        text = previewOf(r.kind, r.text),
                        onClose = { vm.setReply(null) },
                    )
                }
            }

            val ed = editing
            var editCache by remember { mutableStateOf<Message?>(null) }
            SideEffect { if (ed != null) editCache = ed }
            AnimatedVisibility(
                visible = ed != null,
                enter = expandVertically(tween(Motion.Normal)) + fadeIn(tween(Motion.Normal)),
                exit = shrinkVertically(tween(Motion.Fast)) + fadeOut(tween(Motion.Fast)),
            ) {
                val e = ed ?: editCache
                if (e != null) {
                    ComposerBanner(
                        icon = KIcons.Edit,
                        title = tr("Խմբագրում"),
                        text = e.text,
                        onClose = {
                            editing = null
                            text = ""
                        },
                    )
                }
            }

            if (waiting) {
                Text(
                    text = trf("Սպասում ես, որ {0}-ը ընդունի հարցումդ", chat?.peerName ?: ""),
                    color = k.textSecondary,
                    style = TextStyle(fontSize = 13.sp),
                    modifier = Modifier.padding(horizontal = Space.l, vertical = Space.s),
                )
            }
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.s, vertical = Space.s),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                if (recording) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(Dim.composerField)
                            .clip(RoundedCornerShape(22.dp))
                            .background(k.surfaceHigh)
                            .padding(start = Space.l, end = Space.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PulseDot(k.danger)
                        Spacer(Modifier.width(Space.s))
                        Text(text = formatDur(recSec), color = k.text, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = tr("Չեղարկել"),
                            color = k.danger,
                            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                            modifier = Modifier
                                .clip(RoundedCornerShape(Corner.m))
                                .clickable { finishRecording(false) }
                                .padding(horizontal = Space.m, vertical = Space.s),
                        )
                    }
                    ComposerButton(icon = KIcons.Send, description = tr("Ուղարկել"), filled = true) { finishRecording(true) }
                } else {
                    KIconButton(
                        icon = KIcons.Attach,
                        onClick = { attachOpen = true },
                        description = tr("Ավելին"),
                        tint = k.textSecondary,
                        size = Dim.composerField,
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = Dim.composerField)
                            .clip(RoundedCornerShape(22.dp))
                            .background(k.surfaceHigh),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (text.isEmpty()) {
                            Text(
                                text = tr("Գրիր նամակ…"),
                                color = k.textTertiary,
                                style = TextStyle(fontSize = 16.sp),
                                modifier = Modifier.padding(horizontal = Space.l),
                            )
                        }
                        BasicTextField(
                            value = text,
                            onValueChange = {
                                text = it.take(4000)
                                if (it.isNotBlank()) vm.typing(chatId)
                            },
                            maxLines = 5,
                            textStyle = TextStyle(color = k.text, fontSize = 16.sp, lineHeight = 22.sp),
                            cursorBrush = SolidColor(k.accent),
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = 11.dp),
                        )
                    }
                    AnimatedContent(
                        targetState = text.isBlank() && editing == null,
                        transitionSpec = {
                            (fadeIn(tween(Motion.Fast)) + scaleIn(tween(Motion.Fast), initialScale = 0.7f)) togetherWith
                                (fadeOut(tween(Motion.Fast)) + scaleOut(tween(Motion.Fast), targetScale = 0.7f))
                        },
                        label = "composerAction",
                    ) { showMic ->
                        if (showMic) {
                            ComposerButton(icon = KIcons.MicLine, description = tr("Ձայնային նամակ"), filled = false) {
                                val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
                                    PackageManager.PERMISSION_GRANTED
                                if (granted) beginRecording() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        } else {
                            ComposerButton(
                                icon = if (editing != null) KIcons.Check else KIcons.Send,
                                description = tr("Ուղարկել"),
                                filled = true,
                            ) {
                                val e = editing
                                if (e != null) {
                                    vm.editMessage(chatId, e, text)
                                    editing = null
                                } else {
                                    vm.send(chatId, peerUid, text.trim())
                                }
                                text = ""
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One entry of the "more" menu: a small icon and a label. */
@Composable
private fun MenuEntry(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val k = KTheme.colors
    DropdownMenuItem(
        text = { Text(text = label, color = if (danger) k.danger else k.text, style = TextStyle(fontSize = 15.sp)) },
        leadingIcon = { Icon(imageVector = icon, contentDescription = null, tint = if (danger) k.danger else k.textSecondary, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
    )
}

/** The round button at the right of the composer: send (filled with the accent) or microphone (soft). */
@Composable
private fun ComposerButton(icon: ImageVector, description: String, filled: Boolean, onClick: () -> Unit) {
    val k = KTheme.colors
    Box(
        modifier = Modifier
            .size(Dim.composerField)
            .clip(CircleShape)
            .then(if (filled) Modifier.background(Brush.linearGradient(BrandColors)) else Modifier.background(k.accentSoft))
            .pressable(onClick = onClick, pressedScale = 0.9f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (filled) InkOnBrand else k.accent,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** The reply or edit strip above the composer. */
@Composable
private fun ComposerBanner(icon: ImageVector, title: String, text: String, onClose: () -> Unit) {
    val k = KTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Space.l, top = Space.s, bottom = Space.xs, end = Space.xs)
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().clip(CircleShape).background(k.accent))
        Spacer(Modifier.width(Space.m))
        Icon(imageVector = icon, contentDescription = null, tint = k.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Space.s))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = k.accent,
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = text,
                color = k.textSecondary,
                style = TextStyle(fontSize = 14.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        KIconButton(icon = KIcons.Close, onClick = onClose, size = 40.dp, iconSize = 20.dp, tint = k.textSecondary)
    }
}

/** The strip under the top bar of a chat: the pinned message. Tap it to jump to the message, the cross takes the pin off. */
@Composable
private fun PinnedBar(text: String, onClick: () -> Unit, onClose: () -> Unit) {
    val k = KTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = Space.l, top = 4.dp, bottom = 4.dp, end = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = KIcons.Pin, contentDescription = null, tint = k.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(
                text = tr("Ամրացված նամակ"),
                color = k.accent,
                style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
            Text(
                text = text,
                color = k.textSecondary,
                style = TextStyle(fontSize = 14.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        KIconButton(icon = KIcons.Close, onClick = onClose, size = 40.dp, iconSize = 20.dp, tint = k.textSecondary)
    }
}

/** A small red dot that breathes while the microphone is recording. */
@Composable
private fun PulseDot(color: Color) {
    val reduce = LocalReduceMotion.current
    val a = if (reduce) {
        1f
    } else {
        val t = rememberInfiniteTransition(label = "rec")
        val v by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "recAlpha")
        v
    }
    Box(Modifier.size(10.dp).alpha(a).clip(CircleShape).background(color))
}

package am.khosq.app.ui

import am.khosq.app.AppState
import am.khosq.app.membersText
import am.khosq.app.previewOf
import am.khosq.app.previewPlain
import am.khosq.app.tr
import am.khosq.app.data.Chat
import am.khosq.app.data.Profile
import am.khosq.app.data.VerifyState
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

internal fun cleanHandle(s: String): String =
    s.lowercase().filter { c -> c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-' }.take(30)

/* ---------------- first start: name and ID ---------------- */

@Composable
fun SetupScreen(vm: AppViewModel, onRecover: () -> Unit) {
    val k = KTheme.colors
    var name by remember { mutableStateOf("") }
    var handle by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.xxl, vertical = Space.l),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { LanguageChips(Modifier.width(230.dp)) }
        Spacer(Modifier.height(Space.xl))
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(Motion.Slow)) + scaleIn(tween(Motion.Slow, easing = Motion.Emphasized), initialScale = 0.86f),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BrandLogo(92.dp)
                Spacer(Modifier.height(Space.l))
                BrandTitle(30.sp)
            }
        }
        Spacer(Modifier.height(Space.xl))
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(Motion.Slow, delayMillis = 120)) +
                slideInVertically(tween(Motion.Slow, delayMillis = 120, easing = Motion.Emphasized)) { h -> h / 10 },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Text(
                    text = tr("Գրիր անունդ և ընտրիր քո ID-ն։ Ընկերդ քեզ կգտնի հենց այդ ID-ով։"),
                    color = k.textSecondary,
                    style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(bottom = Space.s),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(30) },
                    label = { Text(tr("Քո անունը")) },
                    singleLine = true,
                    shape = RoundedCornerShape(Corner.m),
                    colors = kFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = handle,
                    onValueChange = { handle = cleanHandle(it) },
                    label = { Text(tr("Քո ID-ն (լատինատառ, թվեր)")) },
                    singleLine = true,
                    shape = RoundedCornerShape(Corner.m),
                    colors = kFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                val e = error
                if (e != null) {
                    Text(text = e, color = k.danger, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium))
                }
                Spacer(Modifier.height(Space.xs))
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
                TextButton(enabled = !busy, onClick = onRecover, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("Ես արդեն ունեմ հաշիվ"))
                }
            }
        }
    }
}

/* ---------------- chat list ---------------- */

@Composable
fun HomeScreen(vm: AppViewModel, me: Profile, onOpen: (String) -> Unit, onStarred: () -> Unit, onTasks: () -> Unit, onMemory: () -> Unit, onAssistant: () -> Unit) {
    val k = KTheme.colors
    val chats by vm.chats.collectAsStateWithLifecycle()
    val stories by vm.stories.collectAsStateWithLifecycle()
    val memoryHits by vm.memoryHits.collectAsStateWithLifecycle()
    var viewing by remember { mutableStateOf<String?>(null) }
    var composing by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var showGroup by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    var homeSearch by rememberSaveable { mutableStateOf(false) }
    var homeQuery by rememberSaveable { mutableStateOf("") }
    val homeFocus = remember { FocusRequester() }
    LaunchedEffect(homeSearch) {
        if (homeSearch) homeFocus.requestFocus()
    }
    var showMe by remember { mutableStateOf(false) }
    var showSos by remember { mutableStateOf(false) }
    var showSecurity by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val calls by vm.calls.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    // 0 = all, 1 = personal, 2 = groups, 3 = archive
    var cat by rememberSaveable { mutableStateOf(0) }
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    var menuChat by remember { mutableStateOf<Chat?>(null) }
    BackHandler(enabled = tab != 0) { tab = 0 }
    BackHandler(enabled = tab == 0 && !homeSearch && cat != 0) { cat = 0 }
    BackHandler(enabled = homeSearch && tab == 0) {
        homeSearch = false
        homeQuery = ""
    }
    val unreadTotal = chats.filter { c -> c.id !in prefs.muted }.sumOf { c -> c.unread }.toInt()

    Column(Modifier.fillMaxSize()) {
        // ---- top bar (glass; it also covers the status bar, so the clock sits on it)
        Column(Modifier.glassBar().statusBarsPadding()) {
            if (homeSearch && tab == 0) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(Dim.bar).padding(horizontal = Space.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KIconButton(
                        icon = KIcons.Back,
                        onClick = {
                            homeSearch = false
                            homeQuery = ""
                        },
                        description = tr("Հետ"),
                    )
                    SearchField(
                        value = homeQuery,
                        onValueChange = { v -> homeQuery = v },
                        placeholder = tr("Որոնել զրույցներում…"),
                        focusRequester = homeFocus,
                        modifier = Modifier.weight(1f).padding(end = Space.s),
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().height(Dim.bar).padding(start = Space.l, end = Space.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BrandMark(32.dp)
                    Spacer(Modifier.width(Space.m))
                    Crossfade(targetState = tab, animationSpec = tween(Motion.Normal), label = "title", modifier = Modifier.weight(1f)) { t ->
                        Text(
                            text = when (t) {
                                0 -> tr("Զրույցներ")
                                1 -> tr("Զանգեր")
                                else -> tr("Պրոֆիլ")
                            },
                            color = k.text,
                            style = KType.Title,
                            maxLines = 1,
                        )
                    }
                    if (tab == 0) {
                        SosButton(onClick = { showSos = true }, description = tr("SOS"))
                        AssistantButton(onClick = onAssistant, description = tr("Օգնական"))
                        KIconButton(
                            icon = KIcons.Search,
                            onClick = { homeSearch = true },
                            description = tr("Որոնել"),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .padding(start = Space.xs, end = Space.xs)
                            .size(44.dp)
                            .pressable(onClick = { showMe = true }, pressedScale = 0.9f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.border(1.5.dp, Brush.linearGradient(BrandColorsLight), CircleShape).padding(2.dp)) {
                            Avatar(name = me.name, seed = me.uid, size = 32.dp)
                        }
                    }
                }
            }
        }

        // ---- body
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Crossfade(targetState = tab, animationSpec = tween(Motion.Normal), label = "tabs", modifier = Modifier.fillMaxSize()) { t ->
                when (t) {
                    0 -> Box(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize()) {
                            if (!homeSearch) {
                                StoriesRail(
                                    stories = stories,
                                    me = me,
                                    onOpen = { owner -> viewing = owner },
                                    onAdd = { composing = true },
                                )
                                CategoryChips(
                                    cat = cat,
                                    archivedUnread = chats.count { c -> c.id in prefs.archived && c.unread > 0L && c.id !in prefs.muted },
                                    onCat = { v -> cat = v },
                                )
                                KDivider()
                            }
                            val q = homeQuery.trim()
                            // while searching, every chat is looked at, archived ones too; otherwise the chosen category decides
                            val base = if (homeSearch) {
                                if (q.isEmpty()) {
                                    chats
                                } else {
                                    chats.filter { c ->
                                        c.label().contains(q, ignoreCase = true) ||
                                            c.peerHandle.contains(q, ignoreCase = true) ||
                                            previewOf(c.lastKind, c.lastText).contains(q, ignoreCase = true)
                                    }
                                }
                            } else {
                                chats.filter { c -> inCategory(c, cat, prefs.archived) }
                            }
                            val shownChats = base.sortedByDescending { c -> c.id in prefs.pins }
                            // messages found in KHOSQ Memory (only chats whose Memory is on); empty when not searching
                            LaunchedEffect(homeSearch, homeQuery) { vm.searchMemory(if (homeSearch) homeQuery else "") }
                            val memHits = if (homeSearch && q.isNotEmpty()) memoryHits else emptyList()
                            if (shownChats.isEmpty() && memHits.isEmpty()) {
                                if (homeSearch && q.isNotEmpty()) {
                                    EmptyState(icon = KIcons.Search, title = tr("Ոչինչ չի գտնվել"), modifier = Modifier.padding(top = Space.huge))
                                } else if (chats.isNotEmpty() && cat == 3) {
                                    EmptyState(
                                        icon = KIcons.Archive,
                                        title = tr("Արխիվը դատարկ է"),
                                        text = tr("Արխիվացրած զրույցները կերևան այստեղ։"),
                                        modifier = Modifier.padding(top = Space.huge),
                                    )
                                } else if (chats.isNotEmpty()) {
                                    EmptyState(icon = KIcons.ChatLine, title = tr("Այս բաժնում զրույցներ չկան"), modifier = Modifier.padding(top = Space.huge))
                                } else {
                                    EmptyState(
                                        icon = KIcons.ChatLine,
                                        title = tr("Դեռ զրույցներ չկան"),
                                        text = tr("Սեղմիր «Նոր կոնտակտ» և գրիր ընկերոջդ ID-ն։"),
                                        modifier = Modifier.padding(top = Space.huge),
                                        action = {
                                            GradientButton(
                                                text = tr("Նոր կոնտակտ"),
                                                onClick = { showAdd = true },
                                                modifier = Modifier.width(220.dp),
                                            )
                                        },
                                    )
                                }
                            } else {
                                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                                    // the Real Estate Finder bot, only once a search has been set up (it shows nothing otherwise)
                                    item(key = "finder-bot") { FinderChatRow(visible = !homeSearch && cat == 0) }
                                    items(shownChats, key = { c -> c.id }) { chat ->
                                        Column(Modifier.animateItem()) {
                                            ChatRow(
                                                chat = chat,
                                                myUid = me.uid,
                                                pinned = chat.id in prefs.pins,
                                                muted = chat.id in prefs.muted,
                                                marked = chat.id in prefs.marked,
                                                onClick = { onOpen(chat.id) },
                                                onLong = { menuChat = chat },
                                            )
                                            KDivider(inset = Space.l + Dim.avatarRow + Space.m)
                                        }
                                    }
                                    if (memHits.isNotEmpty()) {
                                        item(key = "mem-head") { SectionLabel(tr("Նամակներ հիշողության մեջ")) }
                                        items(memHits, key = { h -> h.chatId + "/" + h.msgId }) { h ->
                                            MemoryHitRow(
                                                hit = h,
                                                query = q,
                                                onClick = {
                                                    AppState.jumpTo(h.chatId, h.msgId)
                                                    onOpen(h.chatId)
                                                },
                                            )
                                            KDivider(inset = Space.l)
                                        }
                                    }
                                }
                            }
                        }
                        // this sits in a Box that is itself inside a Column: the explicit name picks the plain version
                        androidx.compose.animation.AnimatedVisibility(
                            visible = !homeSearch,
                            modifier = Modifier.align(Alignment.BottomEnd),
                            enter = fadeIn(tween(Motion.Normal)) + scaleIn(tween(Motion.Normal), initialScale = 0.7f),
                            exit = fadeOut(tween(Motion.Fast)) + scaleOut(tween(Motion.Fast), targetScale = 0.7f),
                        ) {
                            NewChatButton(onClick = { showNew = true }, modifier = Modifier.padding(Space.l))
                        }
                    }
                    1 -> CallsTab(me = me, calls = calls, chats = chats, onOpenChat = onOpen)
                    else -> SettingsTab(vm = vm, me = me, onStarred = onStarred, onTasks = onTasks, onMemory = onMemory, onAssistant = onAssistant, onSos = { showSos = true }, onSecurity = { showSecurity = true })
                }
            }
        }

        HomeTabs(
            tab = tab,
            unread = unreadTotal,
            missed = calls.count { r -> r.missed && r.ts > System.currentTimeMillis() - 24L * 3600L * 1000L },
            onTab = { t ->
                tab = t
                if (t != 0) {
                    homeSearch = false
                    homeQuery = ""
                }
            },
        )
    }

    // ---- sheets and dialogs
    val mc = menuChat
    if (mc != null) {
        val pinned = mc.id in prefs.pins
        val muted = mc.id in prefs.muted
        val actions = ArrayList<SheetAction>()
        actions.add(
            SheetAction(
                icon = KIcons.Pin,
                label = if (pinned) tr("Ապաամրացնել") else tr("Ամրացնել"),
                onClick = {
                    vm.togglePin(mc.id)
                    menuChat = null
                },
            ),
        )
        if (!mc.saved) {
            actions.add(
                SheetAction(
                    icon = if (muted) KIcons.Bell else KIcons.BellOff,
                    label = if (muted) tr("Միացնել ձայնը") else tr("Անջատել ձայնը"),
                    onClick = {
                        vm.toggleMute(mc.id)
                        menuChat = null
                    },
                ),
            )
        }
        val markedNow = mc.id in prefs.marked
        if (mc.unread > 0L) {
            actions.add(
                SheetAction(
                    icon = KIcons.Check,
                    label = tr("Նշել կարդացված"),
                    onClick = {
                        vm.markChatRead(mc.id)
                        menuChat = null
                    },
                ),
            )
        } else {
            actions.add(
                SheetAction(
                    icon = KIcons.ChatLine,
                    label = if (markedNow) tr("Հանել չկարդացված նշումը") else tr("Նշել չկարդացված"),
                    onClick = {
                        vm.toggleMarkUnread(mc.id)
                        menuChat = null
                    },
                ),
            )
        }
        val archivedNow = mc.id in prefs.archived
        actions.add(
            SheetAction(
                icon = KIcons.Archive,
                label = if (archivedNow) tr("Հանել արխիվից") else tr("Արխիվացնել"),
                onClick = {
                    vm.toggleArchive(mc.id)
                    menuChat = null
                },
            ),
        )
        ActionSheet(title = mc.label(), actions = actions, onDismiss = { menuChat = null })
    }
    if (showNew) {
        KSheet(onDismiss = { showNew = false }) {
            SheetTitle(tr("Նոր զրույց"))
            Spacer(Modifier.height(Space.xs))
            SheetRow(
                icon = KIcons.PersonAdd,
                label = tr("Նոր կոնտակտ"),
                subtitle = tr("Գտիր մարդուն նրա ID-ով"),
                onClick = {
                    showNew = false
                    showAdd = true
                },
            )
            SheetRow(
                icon = KIcons.Group,
                label = tr("Նոր խումբ"),
                subtitle = tr("Զրույց մի քանի մարդու հետ"),
                onClick = {
                    showNew = false
                    showGroup = true
                },
            )
        }
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
    if (showSecurity) {
        SecurityCenterSheet(
            vm = vm,
            me = me,
            chats = chats,
            onSos = {
                showSecurity = false
                showSos = true
            },
            onClose = { showSecurity = false },
        )
    }
    if (showSos) {
        SosHubSheet(me = me, chats = chats, onClose = { showSos = false })
    }
    if (showMe) {
        KSheet(onDismiss = { showMe = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                Avatar(name = me.name, seed = me.uid, size = 84.dp)
                Spacer(Modifier.height(Space.xs))
                Text(text = tr("Իմ ID-ն"), color = k.textSecondary, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
                Text(text = me.name, color = k.text, style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = me.handle,
                    color = k.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(Corner.m))
                        .background(k.accentSoft)
                        .padding(horizontal = Space.l, vertical = Space.s),
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                )
                HintText(
                    text = tr("Ուղարկիր այս ID-ն ընկերոջդ, որ նա քեզ գտնի։"),
                    modifier = Modifier.padding(vertical = Space.xs),
                )
                GradientButton(
                    text = tr("Պատճենել"),
                    onClick = {
                        clipboard.setText(AnnotatedString(me.handle))
                        showMe = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** The round "+" button of the chat list. It opens the small menu with "new contact" and "new group". */
@Composable
private fun NewChatButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val k = KTheme.colors
    val shape = RoundedCornerShape(Corner.l)
    Box(
        modifier = modifier
            .size(58.dp)
            .glow(k.glow, radius = 18.dp, shape = shape, strength = 0.55f)
            .clip(shape)
            .background(Brush.linearGradient(BrandColors))
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.05f))), shape)
            .pressable(onClick = onClick, pressedScale = 0.92f),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = KIcons.Edit, contentDescription = tr("Նոր զրույց"), tint = InkOnBrand, modifier = Modifier.size(26.dp))
    }
}

/* ---------------- bottom navigation ---------------- */

@Composable
private fun HomeTabs(tab: Int, unread: Int, missed: Int, onTab: (Int) -> Unit) {
    val k = KTheme.colors
    val shape = RoundedCornerShape(Corner.capsule)
    // the bar floats: it does not touch the edges of the screen, and the dark base shows around it
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Space.xl, vertical = Space.s)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .floatShadow(shape)
                .height(Dim.nav)
                .glassSurface(shape, strong = true)
                .padding(horizontal = Space.s),
        ) {
            TabItem(Modifier.weight(1f), KIcons.ChatLine, KIcons.ChatFill, tr("Զրույցներ"), tab == 0, unread, false) { onTab(0) }
            TabItem(Modifier.weight(1f), KIcons.PhoneLine, KIcons.PhoneFill, tr("Զանգեր"), tab == 1, missed, true) { onTab(1) }
            TabItem(Modifier.weight(1f), KIcons.GearLine, KIcons.GearFill, tr("Պրոֆիլ"), tab == 2, 0, false) { onTab(2) }
        }
    }
}

@Composable
private fun TabItem(
    modifier: Modifier,
    icon: ImageVector,
    iconSelected: ImageVector,
    label: String,
    selected: Boolean,
    badge: Int,
    badgeIsDot: Boolean,
    onClick: () -> Unit,
) {
    val k = KTheme.colors
    val tint by animateColorAsState(if (selected) k.accent else k.textSecondary, tween(Motion.Normal), label = "tabTint")
    // 0 -> 1 while the tab becomes the active one: the pill and its light fade in together
    val on by animateFloatAsState(if (selected) 1f else 0f, tween(Motion.Normal), label = "tabOn")
    val lift by animateFloatAsState(if (selected) 1.06f else 1f, tween(Motion.Normal), label = "tabLift")
    Column(
        modifier = modifier
            .fillMaxSize()
            .semantics(mergeDescendants = true) {
                role = Role.Tab
                this.selected = selected
            }
            .pressable(onClick = onClick, pressedScale = 0.92f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(contentAlignment = Alignment.TopEnd) {
            Box(contentAlignment = Alignment.Center) {
                // the light under the active pill
                Box(
                    Modifier
                        .size(width = 76.dp, height = 44.dp)
                        .graphicsLayer { alpha = on }
                        .background(Brush.radialGradient(listOf(k.glow.copy(alpha = if (k.dark) 0.34f else 0.16f), Color.Transparent))),
                )
                Box(
                    modifier = Modifier
                        .width(60.dp)
                        .height(30.dp)
                        .graphicsLayer { alpha = on }
                        .clip(CircleShape)
                        .background(Brush.horizontalGradient(listOf(BrandColors[0].copy(alpha = 0.30f), BrandColors[1].copy(alpha = 0.30f))))
                        .border(1.dp, k.accent.copy(alpha = 0.38f), CircleShape),
                )
                Icon(
                    imageVector = if (selected) iconSelected else icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(24.dp).graphicsLayer {
                        scaleX = lift
                        scaleY = lift
                    },
                )
            }
            if (badge > 0 && (!selected || !badgeIsDot)) {
                if (badgeIsDot) {
                    Box(Modifier.offset(x = (-12).dp, y = 2.dp).size(9.dp).clip(CircleShape).background(k.danger))
                } else {
                    CountBadge(count = badge, modifier = Modifier.offset(x = 6.dp, y = (-6).dp))
                }
            }
        }
        Spacer(Modifier.height(1.dp))
        Text(
            text = label,
            color = tint,
            style = TextStyle(fontSize = 11.5.sp, lineHeight = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, letterSpacing = 0.1.sp),
            maxLines = 1,
        )
    }
}

/* ---------------- categories of the chat list ---------------- */

/** Which category a chat belongs to: archived chats only show in "Archive"; "Saved messages" counts as personal. */
private fun inCategory(c: Chat, cat: Int, archived: Set<String>): Boolean {
    val arch = c.id in archived
    return when (cat) {
        1 -> !arch && (!c.isGroup || c.saved)
        2 -> !arch && c.isGroup && !c.saved
        3 -> arch
        else -> !arch
    }
}

/** The row of small tabs above the chat list: All, Personal, Groups, Archive. */
@Composable
private fun CategoryChips(cat: Int, archivedUnread: Int, onCat: (Int) -> Unit) {
    val k = KTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Space.l, vertical = Space.s),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val labels = listOf(tr("Բոլորը"), tr("Անձնական"), tr("Խմբեր"), tr("Արխիվ"))
        for (i in labels.indices) {
            val on = cat == i
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(Corner.l))
                    .background(if (on) k.accentSoft else k.surface)
                    .border(1.dp, if (on) k.accent.copy(alpha = 0.40f) else k.line, RoundedCornerShape(Corner.l))
                    .semantics(mergeDescendants = true) {
                        role = Role.Tab
                        this.selected = on
                    }
                    .pressable(onClick = { onCat(i) }, pressedScale = 0.95f)
                    .padding(horizontal = Space.l, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = labels[i],
                    color = if (on) k.accent else k.textSecondary,
                    style = TextStyle(fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
                    maxLines = 1,
                )
                if (i == 3 && archivedUnread > 0) {
                    Box(Modifier.padding(start = 6.dp).size(8.dp).clip(CircleShape).background(k.accent))
                }
            }
        }
    }
}

/* ---------------- one row of the chat list ---------------- */

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(chat: Chat, myUid: String, pinned: Boolean, muted: Boolean, marked: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val k = KTheme.colors
    val hasUnread = chat.unread > 0L
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (pinned) {
                    Modifier.background(Brush.horizontalGradient(listOf(k.accent.copy(alpha = 0.09f), Color.Transparent)))
                } else {
                    Modifier
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(horizontal = Space.l, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (chat.saved) SavedAvatar(Dim.avatarRow) else Avatar(name = chat.peerName, seed = chat.peerUid, size = Dim.avatarRow)
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // first line: name and time
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (chat.isGroup && !chat.saved) {
                    Icon(
                        imageVector = KIcons.Group,
                        contentDescription = null,
                        tint = k.textSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(5.dp))
                }
                // the name, and a badge after it when the contact has been verified
                val vstate = rememberVerifyState(chat.peerUid, enabled = !chat.isGroup && !chat.saved)
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = chat.label(),
                        color = k.text,
                        style = RowTitleStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (vstate != VerifyState.NONE) {
                        Spacer(Modifier.width(Space.xs))
                        VerifyBadge(state = vstate, size = 15.dp)
                    }
                }
                if (chat.lastFrom == myUid && chat.lastTs > 0L && !chat.isGroup && !chat.saved) {
                    val read = chat.peerRead >= chat.lastTs
                    val delivered = read || chat.peerDelivered >= chat.lastTs
                    Icon(
                        imageVector = if (delivered) KIcons.DoubleCheck else KIcons.Check,
                        contentDescription = null,
                        tint = if (read) k.accent else k.textTertiary,
                        modifier = Modifier.padding(end = 3.dp).size(16.dp),
                    )
                }
                if (chat.lastTs > 0L) {
                    Text(
                        text = formatTime(chat.lastTs),
                        color = if (hasUnread && !muted) k.accent else k.textSecondary,
                        style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.1.sp, fontWeight = if (hasUnread) FontWeight.SemiBold else FontWeight.Normal),
                        maxLines = 1,
                    )
                }
            }
            // second line: preview and badges
            Row(verticalAlignment = Alignment.CenterVertically) {
                val prefix = when {
                    chat.lastFrom == myUid -> tr("Դու") + ": "
                    chat.isGroup -> (chat.memberNames[chat.lastFrom] ?: "").let { n -> if (n.isEmpty()) "" else "$n: " }
                    else -> ""
                }
                val special = chat.blockedBy.isNotEmpty() || (chat.status == "pending" && chat.requester != myUid)
                // a photo, a voice message, a place, a poll, a missed call and an SOS show a vector sign, not an emoji
                val plain = chat.blockedBy.isEmpty() && chat.status != "pending" && chat.lastText.isNotEmpty()
                val leadIcon = if (plain) previewIconOf(chat.lastKind) else null
                val leadSos = plain && chat.lastKind == "sos"
                val previewText = when {
                    chat.blockedBy.isNotEmpty() -> tr("Արգելափակված")
                    chat.status == "pending" && chat.requester != myUid -> tr("Նոր հարցում")
                    chat.status == "pending" -> tr("Սպասում է հաստատման")
                    chat.lastText.isEmpty() && chat.saved -> tr("Նշումներ, հղումներ, նկարներ՝ քեզ համար")
                    chat.lastText.isEmpty() && chat.isGroup -> membersText(chat.members.size)
                    chat.lastText.isEmpty() -> "@" + chat.peerHandle
                    leadSos -> prefix.trimEnd()
                    leadIcon != null -> previewPlain(chat.lastKind, chat.lastText)
                    else -> prefix + previewOf(chat.lastKind, chat.lastText)
                }
                val previewColor = when {
                    chat.blockedBy.isNotEmpty() -> k.danger
                    special -> k.accent
                    hasUnread && !muted -> k.text
                    else -> k.textSecondary
                }
                val previewStyle = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
                if (leadIcon != null && prefix.isNotEmpty()) {
                    Text(text = prefix, color = previewColor, style = previewStyle, maxLines = 1)
                }
                if (leadIcon != null) {
                    Icon(
                        imageVector = leadIcon,
                        contentDescription = null,
                        tint = if (chat.lastKind == "call_missed") k.danger else previewColor,
                        modifier = Modifier.padding(end = 5.dp).size(16.dp),
                    )
                }
                if (leadSos) {
                    if (previewText.isNotEmpty()) Text(text = previewText + " ", color = previewColor, style = previewStyle, maxLines = 1)
                    SosTag(Modifier.padding(end = Space.xs))
                    Spacer(Modifier.weight(1f))
                } else {
                    Text(
                        text = previewText,
                        color = previewColor,
                        style = previewStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (muted) {
                    Icon(
                        imageVector = KIcons.BellOff,
                        contentDescription = null,
                        tint = k.textTertiary,
                        modifier = Modifier.padding(start = Space.s).size(16.dp),
                    )
                }
                if (hasUnread) {
                    CountBadge(count = chat.unread.toInt(), muted = muted, modifier = Modifier.padding(start = Space.s))
                } else if (marked) {
                    // "mark as unread": a plain dot until I open the chat
                    Box(Modifier.padding(start = Space.s).size(12.dp).clip(CircleShape).background(if (muted) k.textTertiary else k.accent))
                } else if (pinned) {
                    Icon(
                        imageVector = KIcons.Pin,
                        contentDescription = null,
                        tint = k.textTertiary,
                        modifier = Modifier.padding(start = Space.s).size(16.dp),
                    )
                }
            }
        }
    }
}

private val RowTitleStyle = KType.RowTitle

/* ---------------- add a contact ---------------- */

@Composable
private fun AddContactSheet(vm: AppViewModel, onClose: () -> Unit, onOpen: (String) -> Unit) {
    val k = KTheme.colors
    var handle by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showMine by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    // the QR scanner: a code that matches the person's key adds them already verified
    val scan: () -> Unit = {
        error = null
        scanQr(
            ctx,
            onText = { raw ->
                busy = true
                vm.addByQr(raw) { r ->
                    busy = false
                    val err = r.error
                    val id = r.chatId
                    if (err != null) {
                        error = err
                    } else if (id != null) {
                        showMine = false
                        onClose()
                        onOpen(id)
                    }
                }
            },
            onFail = { msg -> error = msg },
        )
    }

    KSheet(onDismiss = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Text(text = tr("Նոր կոնտակտ"), color = k.text, style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold))
            OutlinedTextField(
                value = handle,
                onValueChange = { handle = cleanHandle(it) },
                label = { Text(tr("Ընկերոջդ ID-ն")) },
                singleLine = true,
                shape = RoundedCornerShape(Corner.m),
                colors = kFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            val e = error
            if (e != null) {
                Text(text = e, color = k.danger, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium))
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
            SoftButton(text = tr("Սկանավորել QR կոդը"), enabled = !busy, modifier = Modifier.fillMaxWidth(), onClick = scan)
            SoftButton(text = tr("Իմ QR կոդը"), modifier = Modifier.fillMaxWidth(), onClick = { showMine = true })
            Spacer(Modifier.height(Space.xl))
        }
    }
    if (showMine) {
        val me = (vm.boot.value as? Boot.Ready)?.me
        if (me != null) MyQrSheet(me = me, onClose = { showMine = false }, onScan = {
            showMine = false
            scan()
        })
    }
}

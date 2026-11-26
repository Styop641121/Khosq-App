package am.khosq.app.ui

import am.khosq.app.AppState
import am.khosq.app.Lang
import am.khosq.app.data.FinderCfg
import am.khosq.app.data.FinderHit
import am.khosq.app.data.FinderHub
import am.khosq.app.data.FinderImages
import am.khosq.app.data.FinderLogic
import am.khosq.app.data.FinderState
import am.khosq.app.data.FinderStatus
import am.khosq.app.data.Task
import am.khosq.app.tr
import am.khosq.app.trf
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/*
 * KHOSQ Real Estate Finder: the bot screen, its cards, and the two small entries (the pinned row of the chat list and the row in
 * Settings). Everything here only reads FinderHub; the bot itself runs in the Worker. The composables are small on purpose: no
 * early returns and no huge bodies (a single big composable method was what Android 17 refused to load once).
 */

// ---------------------------------------------------------------------------------------------
// The words for a status
// ---------------------------------------------------------------------------------------------

private fun statusColor(s: FinderStatus, k: KColors): Color = when (s) {
    FinderStatus.WATCHING -> k.success
    FinderStatus.PROBLEM -> k.danger
    FinderStatus.PAUSED -> k.warning
    FinderStatus.WAITING -> k.warning
    FinderStatus.NONE -> k.textTertiary
}

private fun statusText(s: FinderStatus, st: FinderState?): String = when (s) {
    FinderStatus.NONE -> tr("Դեռ չի կարգավորված")
    FinderStatus.PAUSED -> tr("Դադարեցված է. ծանուցումներ չեն գալիս")
    FinderStatus.WAITING -> tr("Առաջին ստուգումը ընթացքի մեջ է…")
    FinderStatus.WATCHING -> tr("list.am-ը հսկվում է գրեթե իրական ժամանակում")
    FinderStatus.PROBLEM -> when (FinderLogic.problemKind(st?.note ?: "")) {
        "blocked" -> tr("list.am-ը չի պատասխանում մեր սերվերին. նորից կփորձենք")
        "net" -> tr("list.am-ին չհաջողվեց միանալ. նորից կփորձենք")
        else -> tr("list.am-ի էջը չհաջողվեց կարդալ. նորից կփորձենք")
    }
}

private fun shortStatus(s: FinderStatus): String = when (s) {
    FinderStatus.WATCHING -> tr("հսկում է list.am-ը")
    FinderStatus.PAUSED -> tr("դադարեցված է")
    FinderStatus.WAITING -> tr("սկսվում է…")
    FinderStatus.PROBLEM -> tr("խնդիր list.am-ի հետ")
    FinderStatus.NONE -> tr("բոտ")
}

/** What went wrong when the app asked the Worker (not list.am): [code] is an HTTP status, or -1 for no network. */
private fun failureText(code: Int): String = when {
    code == -1 -> tr("Ինտերնետ չկա. ցույց է տրվում վերջին պահպանվածը")
    code == 401 -> tr("Մուտքը ժամկետանց է. վերաբացիր հավելվածը")
    code == 429 -> tr("Չափից շատ հարցումներ. մի քիչ սպասիր")
    code == 501 -> tr("Սերվերի վրա Finder-ը դեռ միացված չէ")
    else -> tr("Սերվերը չպատասխանեց. նորից փորձիր")
}

private fun newText(n: Int): String = if (n == 1) tr("1 նոր հայտարարություն") else trf("{0} նոր հայտարարություն", n.toString())

// ---------------------------------------------------------------------------------------------
// The screen
// ---------------------------------------------------------------------------------------------

@Composable
fun FinderScreen(vm: AppViewModel, onBack: () -> Unit) {
    val state by FinderHub.state.collectAsStateWithLifecycle()
    val busy by FinderHub.busy.collectAsStateWithLifecycle()
    val failure by FinderHub.failure.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    // what had been seen when the screen opened: only listings newer than that carry the "new" tag
    val seenAtOpen = remember { FinderHub.seenTs() }
    LaunchedEffect(Unit) {
        FinderHub.load()
        FinderHub.refresh()
    }
    DisposableEffect(Unit) { onDispose { FinderHub.markSeen() } }
    val status = FinderLogic.status(state)

    Column(Modifier.fillMaxSize()) {
        FinderBar(status = status, onBack = onBack, onMenu = { menu = true })
        FinderBody(
            state = state,
            status = status,
            busy = busy,
            failure = failure,
            seenAtOpen = seenAtOpen,
            vm = vm,
            onEdit = { editing = true },
            onToggle = { state?.cfg?.let { c -> FinderHub.save(c.copy(on = !c.on)) } },
            onCheck = { FinderHub.check() },
        )
    }
    if (editing) {
        FinderFilterSheet(
            initial = state?.cfg,
            onDismiss = { editing = false },
            onSave = { c ->
                editing = false
                FinderHub.save(c)
            },
        )
    }
    if (menu) {
        FinderMenuSheet(
            hasSearch = state?.cfg != null,
            onDismiss = { menu = false },
            onCheck = { FinderHub.check() },
            onClear = { FinderHub.clear() },
        )
    }
}

@Composable
private fun FinderBar(status: FinderStatus, onBack: () -> Unit, onMenu: () -> Unit) {
    val k = KTheme.colors
    Row(
        modifier = Modifier
            .glassBar()
            .statusBarsPadding()
            .height(Dim.bar)
            .padding(horizontal = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KIconButton(icon = KIcons.Back, onClick = onBack, description = tr("Հետ"))
        BrandMark(36.dp)
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(
                text = "KHOSQ Real Estate Finder",
                color = k.text,
                style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(statusColor(status, k)))
                Spacer(Modifier.width(6.dp))
                Text(text = shortStatus(status), color = k.textSecondary, style = KType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        KIconButton(icon = KIcons.More, onClick = onMenu, description = tr("Ընտրացանկ"))
    }
}

@Composable
private fun FinderBody(
    state: FinderState?,
    status: FinderStatus,
    busy: Boolean,
    failure: Int,
    seenAtOpen: Long,
    vm: AppViewModel,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onCheck: () -> Unit,
) {
    val hits = state?.hits ?: emptyList()
    val cfg = state?.cfg
    LazyColumn(
        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        contentPadding = PaddingValues(start = Space.l, end = Space.l, top = Space.m, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        item(key = "setup") {
            FinderSetupCard(
                cfg = cfg,
                status = status,
                state = state,
                busy = busy,
                failure = failure,
                onEdit = onEdit,
                onToggle = onToggle,
                onCheck = onCheck,
            )
        }
        if (cfg != null && hits.isEmpty()) {
            item(key = "none") { FinderNoHits() }
        }
        items(hits, key = { h -> h.id }) { h ->
            FinderListingCard(hit = h, cfg = cfg, isNew = !h.sample && h.ts > seenAtOpen, vm = vm)
        }
        item(key = "note") { FinderFooterNote() }
    }
}

// ---------------------------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------------------------

/** The shell of every card of the bot: a soft tactile surface with a hairline and a faint light along the top. */
@Composable
private fun FinderCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val k = KTheme.colors
    val shape = RoundedCornerShape(Corner.l)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(k.surface)
            .background(Brush.verticalGradient(listOf(k.sheen, Color.Transparent)))
            .border(1.dp, k.line, shape),
        content = content,
    )
}

@Composable
private fun FinderSetupCard(
    cfg: FinderCfg?,
    status: FinderStatus,
    state: FinderState?,
    busy: Boolean,
    failure: Int,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onCheck: () -> Unit,
) {
    if (cfg == null) {
        FinderIntroCard(busy = busy, failure = failure, onSetup = onEdit)
    } else {
        FinderReadyCard(cfg = cfg, status = status, state = state, busy = busy, failure = failure, onEdit = onEdit, onToggle = onToggle, onCheck = onCheck)
    }
}

@Composable
private fun FinderIntroCard(busy: Boolean, failure: Int, onSetup: () -> Unit) {
    val k = KTheme.colors
    FinderCard {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark(40.dp)
                Spacer(Modifier.width(Space.m))
                Text(text = "Real Estate Finder", color = k.text, style = KType.RowTitle)
            }
            Text(
                text = tr("Ասա ինչ բնակարան ես փնտրում. ես կհսկեմ list.am-ը և ծանուցում կուղարկեմ, հենց նոր հայտարարություն հայտնվի։"),
                color = k.textSecondary,
                style = KType.Body,
            )
            Text(
                text = tr("Սերվերին է գնում միայն որոնման ֆիլտրը՝ վայր, գին, սենյակներ։ Քո նամակները և կոնտակտները չեն մասնակցում։"),
                color = k.textTertiary,
                style = KType.Caption,
            )
            if (failure != 0) Text(text = failureText(failure), color = k.danger, style = KType.Caption)
            GradientButton(text = tr("Կարգավորել"), onClick = onSetup, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        }
    }
}

@Composable
private fun FinderReadyCard(
    cfg: FinderCfg,
    status: FinderStatus,
    state: FinderState?,
    busy: Boolean,
    failure: Int,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
    onCheck: () -> Unit,
) {
    val k = KTheme.colors
    FinderCard {
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark(40.dp)
                Spacer(Modifier.width(Space.m))
                Text(
                    text = tr("Real Estate Finder. Կարգավորումն ավարտված է"),
                    color = k.text,
                    style = KType.RowTitle,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                Icon(imageVector = KIcons.Check, contentDescription = null, tint = k.success, modifier = Modifier.padding(start = Space.s).size(22.dp))
            }
            FinderFactsGrid(cfg)
            FinderStatusBlock(status = status, state = state, failure = failure)
            FinderActions(cfg = cfg, busy = busy, onEdit = onEdit, onToggle = onToggle, onCheck = onCheck)
        }
    }
}

@Composable
private fun FinderFactsGrid(cfg: FinderCfg) {
    val any = tr("Ցանկացած")
    val budget = FinderLogic.budgetText(cfg).ifEmpty { any }
    val rooms = FinderLogic.roomsText(cfg).ifEmpty { any }
    val deal = if (cfg.cat == FinderLogic.RENT) tr("Վարձակալություն") else tr("Վաճառք")
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            FinderFact(tr("Վայր"), FinderLogic.regionName(cfg.region, Lang.code), Modifier.weight(1f))
            FinderFact(tr("Տիպ"), tr("Բնակարան"), Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            FinderFact(tr("Գործարք"), deal, Modifier.weight(1f))
            FinderFact(tr("Բյուջե"), budget, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            FinderFact(tr("Սենյակներ"), rooms, Modifier.weight(1f))
            FinderFact(tr("Աղբյուր"), "list.am", Modifier.weight(1f))
        }
    }
}

/** One small tile of a card: a quiet label above a value. */
@Composable
private fun FinderFact(label: String, value: String, modifier: Modifier = Modifier) {
    val k = KTheme.colors
    val shape = RoundedCornerShape(Corner.m)
    Column(
        modifier = modifier
            .clip(shape)
            .background(k.surfaceHigh)
            .border(1.dp, k.line, shape)
            .padding(horizontal = Space.m, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = label, color = k.textTertiary, style = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp), maxLines = 1)
        Text(
            text = value,
            color = k.text,
            style = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FinderStatusBlock(status: FinderStatus, state: FinderState?, failure: Int) {
    val k = KTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(statusColor(status, k)))
            Spacer(Modifier.width(Space.s))
            Text(text = statusText(status, state), color = k.text, style = KType.Body, modifier = Modifier.weight(1f))
        }
        val run = state?.run ?: 0L
        if (run > 0L) {
            Text(text = trf("Վերջին ստուգումը՝ {0}", formatTime(run)), color = k.textTertiary, style = KType.Caption)
        }
        if (failure != 0) Text(text = failureText(failure), color = k.danger, style = KType.Caption)
    }
}

@Composable
private fun FinderActions(cfg: FinderCfg, busy: Boolean, onEdit: () -> Unit, onToggle: () -> Unit, onCheck: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            FinderPill(text = tr("Փոխել ֆիլտրերը"), onClick = onEdit, icon = KIcons.Edit, enabled = !busy, modifier = Modifier.weight(1f))
            FinderPill(
                text = if (cfg.on) tr("Դադար") else tr("Շարունակել"),
                onClick = onToggle,
                icon = if (cfg.on) KIcons.Pause else KIcons.Play,
                filled = !cfg.on,
                enabled = !busy,
                modifier = Modifier.weight(1f),
            )
        }
        if (cfg.on) {
            FinderPill(
                text = if (busy) tr("Ստուգվում է…") else tr("Ստուգել հիմա"),
                onClick = onCheck,
                icon = KIcons.Search,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A pill button: rounded all the way, either plain or filled with the brand gradient. */
@Composable
private fun FinderPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    filled: Boolean = false,
    enabled: Boolean = true,
) {
    val k = KTheme.colors
    val look = if (filled) {
        Modifier.background(Brush.horizontalGradient(BrandColors), CircleShape)
    } else {
        Modifier.background(k.surfaceHigh, CircleShape).border(1.dp, k.line, CircleShape)
    }
    val ink = (if (filled) InkOnBrand else k.text).copy(alpha = if (enabled) 1f else 0.5f)
    Row(
        modifier = modifier
            .height(42.dp)
            .clip(CircleShape)
            .then(look)
            .pressable(onClick = onClick, enabled = enabled, pressedScale = 0.96f)
            .padding(horizontal = Space.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text = text, color = ink, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FinderNoHits() {
    val k = KTheme.colors
    FinderCard {
        Text(
            text = tr("Դեռ նոր հայտարարություններ չկան։ Հենց list.am-ում հայտնվի քո ֆիլտրին համապատասխան նորը, այն կերևա այստեղ և ծանուցում կգա։"),
            color = k.textSecondary,
            style = KType.Body,
            modifier = Modifier.padding(Space.l),
        )
    }
}

@Composable
private fun FinderFooterNote() {
    Text(
        text = tr("Հայտարարությունները list.am-ից են. KHOSQ-ը list.am-ի հետ կապված չէ և տվյալների ճշտության համար պատասխանատու չէ։ Նկարները բեռնվում են list.am-ից։"),
        color = KTheme.colors.textTertiary,
        style = KType.Caption,
        modifier = Modifier.padding(horizontal = Space.xs, vertical = Space.s),
    )
}

// ---------------------------------------------------------------------------------------------
// One listing: the bento card
// ---------------------------------------------------------------------------------------------

@Composable
private fun FinderListingCard(hit: FinderHit, cfg: FinderCfg?, isNew: Boolean, vm: AppViewModel) {
    val ctx = LocalContext.current
    val lang = cfg?.lang ?: "hy"
    FinderCard {
        FinderCarousel(hit = hit, isNew = isNew)
        Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            FinderListingInfo(hit)
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                FinderPill(
                    text = tr("Պահել KHOSQ առաջադրանքներում"),
                    onClick = {
                        vm.saveTask(Task(title = FinderLogic.taskTitle(hit), note = FinderLogic.taskNote(hit, lang)), announce = true)
                    },
                    icon = KIcons.Tasks,
                    modifier = Modifier.fillMaxWidth(),
                )
                FinderPill(
                    text = tr("Բացել list.am-ում"),
                    onClick = { openFinderUrl(ctx, FinderLogic.itemUrl(hit.id, lang)) },
                    icon = KIcons.ArrowOut,
                    filled = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            FinderMapSnippet(hit = hit, cfg = cfg, onClick = { openFinderMap(ctx, FinderLogic.mapQuery(hit.place, cfg?.region ?: 1)) })
        }
    }
}

@Composable
private fun FinderCarousel(hit: FinderHit, isNew: Boolean) {
    val k = KTheme.colors
    val count = maxOf(1, hit.photos.size)
    val pager = rememberPagerState(pageCount = { count })
    Box(Modifier.fillMaxWidth().height(200.dp)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            val id = hit.photos.getOrNull(page)
            FinderPhoto(url = if (id == null) null else FinderLogic.photoUrl(id), modifier = Modifier.fillMaxSize())
        }
        if (isNew) {
            FinderTag(text = tr("Նոր"), color = k.accent, modifier = Modifier.align(Alignment.TopStart).padding(Space.m))
        } else if (hit.sample) {
            FinderTag(text = tr("Օրինակ"), color = Color(0x99000000), modifier = Modifier.align(Alignment.TopStart).padding(Space.m))
        }
        if (count > 1) {
            FinderDots(count = count, current = pager.currentPage, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Space.s))
        }
    }
}

@Composable
private fun FinderTag(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White,
        style = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp),
        modifier = modifier
            .clip(CircleShape)
            .background(color)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun FinderDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Color(0x66000000))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        for (i in 0 until count) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(if (i == current) Color.White else Color(0x66FFFFFF)))
        }
    }
}

/** One photo, loaded from list.am when it is first shown. While it loads, or when it cannot be had, a quiet placeholder shows. */
@Composable
private fun FinderPhoto(url: String?, modifier: Modifier = Modifier) {
    val k = KTheme.colors
    val img by produceState<ImageBitmap?>(null, url) {
        value = if (url == null) null else FinderImages.load(url)
    }
    Box(modifier = modifier.background(k.surfaceHigh), contentAlignment = Alignment.Center) {
        val b = img
        if (b != null) {
            Image(bitmap = b, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(imageVector = KIcons.Image, contentDescription = null, tint = k.textTertiary, modifier = Modifier.size(34.dp))
        }
    }
}

@Composable
private fun FinderListingInfo(hit: FinderHit) {
    val k = KTheme.colors
    val facts = remember(hit.facts) { FinderLogic.facts(hit.facts) }
    val dash = "—"
    val sizeText = if (facts.size.isNotEmpty()) facts.size + " " + tr("ք.մ.") else dash
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(text = hit.price, color = k.accent, style = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp))
        Text(text = hit.title, color = k.text, style = KType.RowTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            FinderFact(tr("Վայր"), hit.place.ifEmpty { dash }, Modifier.weight(1.3f))
            FinderFact(tr("Մակերես"), sizeText, Modifier.weight(1f))
            FinderFact(tr("Սենյակ"), facts.rooms.ifEmpty { dash }, Modifier.weight(0.9f))
        }
        // when the line looks different from the usual one, it is shown as it was written
        if (facts.rooms.isEmpty() && facts.size.isEmpty() && hit.facts.isNotEmpty()) {
            Text(text = hit.facts, color = k.textSecondary, style = KType.Caption)
        } else if (facts.floor.isNotEmpty()) {
            Text(text = facts.floor, color = k.textSecondary, style = KType.Caption)
        }
    }
}

/**
 * A map snippet: list.am gives only the district, not an exact point, so the card draws a quiet street pattern with the
 * approximate area marked; a tap searches the place in the phone's maps app.
 */
@Composable
private fun FinderMapSnippet(hit: FinderHit, cfg: FinderCfg?, onClick: () -> Unit) {
    val k = KTheme.colors
    val shape = RoundedCornerShape(Corner.m)
    val place = if (hit.place.isNotEmpty()) hit.place else FinderLogic.regionName(cfg?.region ?: 1, Lang.code)
    val streets = k.lineStrong
    val accent = k.accent
    val seed = hit.id.hashCode()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(shape)
            .background(k.surfaceHigh)
            .border(1.dp, k.line, shape)
            .clickable(onClick = onClick),
    ) {
        Canvas(Modifier.fillMaxSize()) { drawFinderMap(seed, streets, accent) }
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(Space.s)
                .clip(CircleShape)
                .background(k.glass)
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = KIcons.Location, contentDescription = null, tint = k.accent, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
            Text(text = trf("{0} · մոտավոր շրջան", place), color = k.text, style = KType.Pill, maxLines = 1)
        }
    }
}

private fun DrawScope.drawFinderMap(seed: Int, streets: Color, accent: Color) {
    val rnd = java.util.Random(seed.toLong())
    val w = size.width
    val h = size.height
    for (i in 0 until 9) {
        val horizontal = i % 2 == 0
        val a = Offset(if (horizontal) 0f else rnd.nextFloat() * w, if (horizontal) rnd.nextFloat() * h else 0f)
        val b = Offset(if (horizontal) w else rnd.nextFloat() * w, if (horizontal) rnd.nextFloat() * h else h)
        drawLine(color = streets, start = a, end = b, strokeWidth = (if (i % 3 == 0) 3f else 1.5f) * density)
    }
    val c = Offset(w * 0.56f, h * 0.45f)
    drawCircle(color = accent.copy(alpha = 0.16f), radius = h * 0.40f, center = c)
    drawCircle(color = accent.copy(alpha = 0.55f), radius = h * 0.40f, center = c, style = Stroke(width = 1.5f * density))
    drawCircle(color = accent, radius = 6f * density, center = c)
    drawCircle(color = Color.White, radius = 2.5f * density, center = c)
}

private fun openFinderUrl(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) {
        Toast.makeText(ctx, tr("Բրաուզեր չի գտնվել"), Toast.LENGTH_SHORT).show()
    }
}

private fun openFinderMap(ctx: Context, query: String) {
    val q = Uri.encode(query)
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q")))
    } catch (e: Exception) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$q")))
        } catch (e2: Exception) {
            Toast.makeText(ctx, tr("Քարտեզի ծրագիր չի գտնվել"), Toast.LENGTH_SHORT).show()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// The two entries: the pinned row of the chat list, and the row in Settings
// ---------------------------------------------------------------------------------------------

/** The bot at the top of the chat list. It shows only when a search has been set up (the Finder is off until then). */
@Composable
fun FinderChatRow(visible: Boolean) {
    val state by FinderHub.state.collectAsStateWithLifecycle()
    val seen by FinderHub.seen.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { FinderHub.start() }
    val s = state
    if (visible && s?.cfg != null) FinderChatRowBody(s = s, seen = seen)
}

@Composable
private fun FinderChatRowBody(s: FinderState, seen: Long) {
    val k = KTheme.colors
    val unread = FinderLogic.unread(s.hits, seen)
    val status = FinderLogic.status(s)
    val cfg = s.cfg
    val line = when {
        unread > 0 -> newText(unread)
        cfg != null && status == FinderStatus.WATCHING -> "list.am · " + FinderLogic.regionName(cfg.region, Lang.code)
        else -> statusText(status, s)
    }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.horizontalGradient(listOf(k.accent.copy(alpha = 0.09f), Color.Transparent)))
                .clickable { AppState.openFinder.value = true }
                .padding(horizontal = Space.l, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrandMark(Dim.avatarRow)
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "KHOSQ Real Estate Finder",
                        color = k.text,
                        style = KType.RowTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    FinderBotTag()
                }
                Text(
                    text = line,
                    color = if (unread > 0) k.text else k.textSecondary,
                    style = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = if (unread > 0) FontWeight.SemiBold else FontWeight.Normal),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (unread > 0) CountBadge(count = unread, modifier = Modifier.padding(start = Space.s))
        }
        KDivider(inset = Space.l + Dim.avatarRow + Space.m)
    }
}

@Composable
private fun FinderBotTag() {
    val k = KTheme.colors
    Text(
        text = "BOT",
        color = k.accent,
        style = TextStyle(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp),
        modifier = Modifier
            .padding(start = 6.dp)
            .clip(CircleShape)
            .background(k.accentSoft)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** The row in Settings that opens the bot. */
@Composable
internal fun FinderSettingsRow() {
    val state by FinderHub.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { FinderHub.load() }
    val s = state
    val cfg = s?.cfg
    val sub = when {
        cfg == null -> tr("Անջատված է. ծանուցումներ list.am-ի նոր բնակարաններից")
        !cfg.on -> tr("Դադարեցված է")
        else -> FinderLogic.regionName(cfg.region, Lang.code) + (FinderLogic.budgetText(cfg).let { b -> if (b.isEmpty()) "" else " · $b" })
    }
    SettingsRow(icon = KIcons.Search, title = "KHOSQ Real Estate Finder", subtitle = sub, onClick = { AppState.openFinder.value = true })
}

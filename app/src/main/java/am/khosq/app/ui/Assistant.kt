package am.khosq.app.ui

import am.khosq.app.AppState
import am.khosq.app.Lang
import am.khosq.app.data.AsstIntent
import am.khosq.app.data.AsstTurn
import am.khosq.app.data.AssistantParser
import am.khosq.app.data.DayRange
import am.khosq.app.data.RangeKind
import am.khosq.app.data.Task
import am.khosq.app.data.TaskFilter
import am.khosq.app.data.TaskLogic
import am.khosq.app.tr
import am.khosq.app.trf
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The questions offered on an empty screen. Each one is also written in the other languages, so the assistant understands it in any of them. */
private val EXAMPLES = listOf(
    "Ի՞նչ առաջադրանքներ ունեմ վաղը",
    "Անավարտ առաջադրանքներ",
    "Հիշեցրու վաղը ժամը 10-ին զանգահարել",
    "Գտիր հասցեն անցյալ շաբաթ",
    "Թարգմանիր ռուսերեն՝ բարի լույս",
)

/** "Tomorrow (26 Nov)", "Last week (16 Nov – 22 Nov)": what the assistant took the person's words to mean, written out. */
internal fun rangeLabel(r: DayRange): String {
    val loc = Locale(Lang.code)
    val short = SimpleDateFormat("d MMM", loc)
    val name = when (r.kind) {
        RangeKind.TODAY -> tr("Այսօր")
        RangeKind.TOMORROW -> tr("Վաղը")
        RangeKind.DAY_AFTER -> tr("Մյուս օրը")
        RangeKind.YESTERDAY -> tr("Երեկ")
        RangeKind.THIS_WEEK -> tr("Այս շաբաթ")
        RangeKind.LAST_WEEK -> tr("Անցյալ շաբաթ")
        RangeKind.NEXT_WEEK -> tr("Հաջորդ շաբաթ")
        RangeKind.THIS_MONTH -> tr("Այս ամիս")
        RangeKind.LAST_MONTH -> tr("Անցյալ ամիս")
        RangeKind.LAST_DAYS -> trf("Վերջին {0} օրը", r.n.toString())
        RangeKind.WEEKDAY -> SimpleDateFormat("EEEE", loc).format(Date(r.from)).replaceFirstChar { c -> c.uppercase() }
    }
    val dates = if (TaskLogic.dayDiff(r.from, r.to) == 0) short.format(Date(r.from)) else short.format(Date(r.from)) + " – " + short.format(Date(r.to))
    return "$name ($dates)"
}

/**
 * KHOSQ Assistant: the person asks or gives a command in plain words; the answer comes from their own tasks and from the
 * chats they switched Memory on for, found by simple rules (no language model, nothing leaves the phone). Under every
 * answer is its source. A reminder is added at once, with "Change" and "Undo". The one thing that leaves the phone is
 * the text of a "translate …" command, and only after the person allowed translation.
 */
@Composable
fun AssistantScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onOpenTasks: () -> Unit,
    onOpenMemory: () -> Unit,
) {
    val k = KTheme.colors
    val turns by vm.assistant.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var changing by remember { mutableStateOf<Pair<Long, Task>?>(null) }
    val listState = rememberLazyListState()
    val ask: (String) -> Unit = { q ->
        vm.assistantAsk(q)
    }
    val send: () -> Unit = {
        val q = input.trim()
        if (q.isNotEmpty()) {
            ask(q)
            input = ""
        }
    }
    LaunchedEffect(turns.size) {
        if (turns.isNotEmpty()) listState.animateScrollToItem(maxOf(0, listState.layoutInfo.totalItemsCount - 1))
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(k.bar)
                .statusBarsPadding()
                .height(Dim.bar)
                .padding(horizontal = Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            KIconButton(icon = KIcons.Back, onClick = onBack, description = tr("Հետ"))
            Text(
                text = tr("KHOSQ Օգնական"),
                color = k.text,
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = Space.xs).weight(1f),
            )
            if (turns.isNotEmpty()) {
                KIconButton(icon = KIcons.Trash, onClick = { vm.assistantClear() }, description = tr("Մաքրել խոսակցությունը"))
            }
        }
        KDivider()
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = Space.l, vertical = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            item(key = "intro") {
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Corner.m)).background(k.accentSoft).padding(Space.m),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(imageVector = KIcons.Assistant, contentDescription = null, tint = k.accent, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(Space.s))
                    Text(
                        text = tr("Պատասխանում եմ քո առաջադրանքներից և այն զրույցներից, որոնցում միացրել ես Հիշողությունը։ Այդ պատասխանների համար ոչինչ չի ուղարկվում սերվեր, և ամեն պատասխանի տակ ցույց եմ տալիս աղբյուրը։ Միակ բացառությունը թարգմանությունն է՝ միայն երբ դու ես խնդրում, և միայն քո թույլտվությամբ։"),
                        color = k.text,
                        style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
                    )
                }
            }
            if (turns.isEmpty()) {
                item(key = "examples") {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        Text(
                            text = tr("Օրինակներ"),
                            color = k.textSecondary,
                            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                            modifier = Modifier.padding(top = Space.xs),
                        )
                        for (e in EXAMPLES) {
                            val line = tr(e)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(Corner.l))
                                    .background(k.surfaceHigh)
                                    .clickable { ask(line) }
                                    .padding(horizontal = Space.m, vertical = Space.m),
                            ) {
                                Text(text = line, color = k.text, style = TextStyle(fontSize = 15.sp))
                            }
                        }
                    }
                }
            }
            items(turns, key = { t -> t.id }) { t ->
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    QuestionBubble(t.question)
                    AnswerCard(
                        turn = t,
                        onOpenChat = onOpenChat,
                        onOpenTasks = onOpenTasks,
                        onOpenMemory = onOpenMemory,
                        onAdd = { vm.assistantAddTask(t.id) },
                        onChange = { p -> changing = t.id to p },
                        onUndo = { vm.assistantUndoTask(t.id) },
                        onAllow = { vm.assistantAllowTranslate(t.id) },
                        onRetry = { vm.assistantRetryTranslate(t.id) },
                    )
                }
            }
        }
        KDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(k.bar)
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = Space.s, vertical = Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { v -> if (v.length <= AssistantParser.MAX_INPUT) input = v },
                placeholder = { Text(tr("Գրիր հարց կամ հրաման…")) },
                singleLine = true,
                shape = RoundedCornerShape(Corner.xl),
                colors = kFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                modifier = Modifier.weight(1f),
            )
            KIconButton(
                icon = KIcons.Send,
                onClick = send,
                description = tr("Ուղարկել"),
                tint = if (input.isBlank()) k.textTertiary else k.accent,
            )
        }
    }

    val c = changing
    if (c != null) {
        TaskEditSheet(
            start = c.second,
            onClose = { changing = null },
            onSave = { t ->
                vm.assistantChangeTask(c.first, t)
                changing = null
            },
        )
    }
}

/** What the person wrote, on the right. */
@Composable
private fun QuestionBubble(text: String) {
    val k = KTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            text = text,
            color = k.onAccent,
            style = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
            modifier = Modifier
                .padding(start = Space.xxl)
                .clip(RoundedCornerShape(Corner.l))
                .background(k.accent)
                .padding(horizontal = Space.m, vertical = Space.s),
        )
    }
}

/** The small grey line that says where an answer came from; it can lead to the place. */
@Composable
private fun SourceLine(text: String, onClick: (() -> Unit)? = null) {
    val k = KTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Space.l, vertical = Space.s),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(imageVector = KIcons.Info, contentDescription = null, tint = k.textTertiary, modifier = Modifier.size(14.dp).padding(top = 2.dp))
        Spacer(Modifier.width(Space.xs))
        Text(
            text = text,
            color = if (onClick != null) k.accent else k.textTertiary,
            style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
        )
    }
}

@Composable
private fun CardText(text: String, bold: Boolean = false, secondary: Boolean = false) {
    val k = KTheme.colors
    Text(
        text = text,
        color = if (secondary) k.textSecondary else k.text,
        style = TextStyle(
            fontSize = if (bold) 16.sp else 14.sp,
            lineHeight = if (bold) 22.sp else 20.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        ),
        modifier = Modifier.padding(horizontal = Space.l),
    )
}

/** My task inside an answer. */
@Composable
private fun AsstTaskRow(t: Task, now: Long, onClick: () -> Unit) {
    val k = KTheme.colors
    val late = TaskLogic.isOverdue(t, now)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Space.l, vertical = Space.s),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = if (t.done) KIcons.Check else KIcons.Tasks,
            contentDescription = null,
            tint = if (t.done) k.success else k.accent,
            modifier = Modifier.size(20.dp).padding(top = 1.dp),
        )
        Spacer(Modifier.width(Space.s))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = t.title,
                color = if (t.done) k.textSecondary else k.text,
                style = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (t.due > 0L) {
                Text(
                    text = dueLabel(t.due, now) + ", " + formatTime(t.due),
                    color = if (late) k.danger else k.textSecondary,
                    style = TextStyle(fontSize = 13.sp, fontWeight = if (late) FontWeight.SemiBold else FontWeight.Normal),
                )
            }
            if (t.chatName.isNotEmpty()) {
                Text(text = t.chatName, color = k.accent, style = TextStyle(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The assistant's answer: what it understood, what it found and where from. */
@Composable
private fun AnswerCard(
    turn: AsstTurn,
    onOpenChat: (String) -> Unit,
    onOpenTasks: () -> Unit,
    onOpenMemory: () -> Unit,
    onAdd: () -> Unit,
    onChange: (Task) -> Unit,
    onUndo: () -> Unit,
    onAllow: () -> Unit,
    onRetry: () -> Unit,
) {
    val k = KTheme.colors
    val now = System.currentTimeMillis()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.m))
            .background(k.surface)
            .padding(top = Space.m, bottom = Space.xs),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        val intent = turn.intent
        when (intent) {
            is AsstIntent.Help -> HelpBody()
            is AsstIntent.Later -> {
                CardText(tr("Զրույցի ամփոփումը լեզվական մոդել է պահանջում, իսկ ես այն դեռ չեմ օգտագործում։ Սա կավելանա առանձին քայլով և միայն քո թույլտվությամբ։"))
            }
            is AsstIntent.Translate -> TranslateBody(turn = turn, intent = intent, onAllow = onAllow, onRetry = onRetry)
            is AsstIntent.ListTasks -> {
                val total = turn.tasks.size + turn.moreTasks
                val range = intent.range
                if (total == 0) {
                    CardText(if (range != null) tr("Այդ ժամանակահատվածի համար առաջադրանք չկա") else tr("Համապատասխան առաջադրանք չկա"), bold = true)
                } else {
                    val count = total.toString()
                    CardText(
                        when (intent.filter) {
                            TaskFilter.OPEN -> trf("Բաց առաջադրանքներ՝ {0}", count)
                            TaskFilter.DONE -> trf("Կատարված առաջադրանքներ՝ {0}", count)
                            TaskFilter.OVERDUE -> trf("Ուշացած առաջադրանքներ՝ {0}", count)
                            TaskFilter.ALL -> trf("Բոլոր առաջադրանքները՝ {0}", count)
                        },
                        bold = true,
                    )
                }
                if (range != null) CardText(trf("Ժամանակահատված՝ {0}", rangeLabel(range)), secondary = true)
                for (t in turn.tasks) AsstTaskRow(t = t, now = now, onClick = onOpenTasks)
                if (turn.moreTasks > 0) CardText(trf("Եվս {0}՝ Առաջադրանքներ էկրանում", turn.moreTasks.toString()), secondary = true)
                SourceLine(tr("Աղբյուր՝ քո առաջադրանքները (միայն այս հեռախոսում)"), onOpenTasks)
            }
            is AsstIntent.AddTask -> {
                val p = turn.proposal
                if (p == null) {
                    CardText(tr("Ինչի՞ մասին հիշեցնեմ։ Գրիր, օրինակ՝ «հիշեցրու վաղը ժամը 10-ին զանգահարել»։"))
                } else {
                    ProposalBody(turn = turn, p = p, now = now, onAdd = onAdd, onChange = { onChange(p) }, onUndo = onUndo, onOpenTasks = onOpenTasks)
                }
            }
            is AsstIntent.Find -> FindBody(turn = turn, intent = intent, now = now, onOpenChat = onOpenChat, onOpenTasks = onOpenTasks, onOpenMemory = onOpenMemory)
        }
    }
}

@Composable
private fun HelpBody() {
    CardText(tr("Ինչ կարող եմ անել"), bold = true)
    CardText(tr("Առաջադրանքներ՝ «ի՞նչ առաջադրանքներ ունեմ վաղը», «անավարտ առաջադրանքներ», «ուշացած առաջադրանքներ»։"))
    CardText(tr("Հիշեցում՝ «հիշեցրու վաղը ժամը 10-ին զանգահարել Արմանին»։ Ավելացնում եմ անմիջապես և ցույց եմ տալիս, թե ինչ եմ հասկացել. սխալի դեպքում սեղմիր «Փոխել» կամ «Հետարկել»։"))
    CardText(tr("Որոնում՝ «գտիր հասցեն անցյալ շաբաթ», «գտիր Արման ժամ»։ Փնտրում եմ քո առաջադրանքներում և այն զրույցներում, որտեղ Հիշողությունը միացված է։"))
    CardText(tr("Օրեր՝ այսօր, վաղը, երեկ, այս / անցյալ / հաջորդ շաբաթ, այս / անցյալ ամիս, երկուշաբթի…, «վերջին 7 օրը»։"))
    CardText(tr("Թարգմանություն՝ «թարգմանիր ռուսերեն՝ բարի լույս»։ Թարգմանվող տեքստն ուղարկվում է սերվեր՝ միայն քո թույլտվությամբ։ Նամակը թարգմանելու համար երկար սեղմիր այն և ընտրիր «Թարգմանել»։"))
    CardText(tr("Ամփոփում՝ դեռ չեմ կարող, դա լեզվական մոդել է պահանջում։ Եթե չեմ հասկանում, ասում եմ և ոչինչ չեմ կռահում քո փոխարեն։"), secondary = true)
    Spacer(Modifier.height(Space.xs))
}

/** The answer to "translate …": what to do when no text was given, the permission, the wait, the failure, or the translation. */
@Composable
private fun TranslateBody(turn: AsstTurn, intent: AsstIntent.Translate, onAllow: () -> Unit, onRetry: () -> Unit) {
    val k = KTheme.colors
    val clip = LocalClipboardManager.current
    var copied by remember(turn.id) { mutableStateOf(false) }
    if (intent.text.isEmpty()) {
        CardText(tr("Ասա, թե ինչ թարգմանեմ"), bold = true)
        CardText(tr("Գրիր, օրինակ՝ «թարգմանիր ռուսերեն՝ բարի լույս»։"))
        CardText(tr("Զրույցում նամակը թարգմանելու համար երկար սեղմիր այն և ընտրիր «Թարգմանել»։"), secondary = true)
        Spacer(Modifier.height(Space.xs))
        return
    }
    val r = turn.translation
    when {
        turn.needsAllow -> {
            CardText(tr("Թարգմանության թույլտվություն"), bold = true)
            CardText(tr("Թարգմանելու համար միայն այս տեքստը կուղարկվի KHOSQ-ի սերվեր և այնտեղից՝ Cloudflare AI. այդ պահին այն չգաղտնագրված է։ KHOSQ-ի սերվերը այն չի պահում և չի գրանցում։ Թույլտվությունը կարելի է հանել Կարգավորումներում։"))
            Box(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.xs)) {
                GradientButton(text = tr("Թույլատրել և թարգմանել"), onClick = onAllow, modifier = Modifier.fillMaxWidth())
            }
        }
        turn.busy -> CardText(tr("Թարգմանում եմ…"), bold = true)
        turn.transError.isNotEmpty() -> {
            CardText(turn.transError, bold = true)
            Box(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.xs)) {
                SoftButton(text = tr("Կրկնել"), onClick = onRetry, modifier = Modifier.fillMaxWidth())
            }
        }
        r != null -> {
            if (r.same) {
                CardText(tr("Տեքստն արդեն այս լեզվով է"), bold = true)
                CardText(r.text)
            } else {
                CardText(trf("Թարգմանություն՝ {0}", langName(r.to)), secondary = true)
                CardText(r.text, bold = true)
                Box(Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.xs)) {
                    SoftButton(
                        text = if (copied) tr("Պատճենվեց") else tr("Պատճենել"),
                        onClick = {
                            clip.setText(AnnotatedString(r.text))
                            copied = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                CardText(tr("Ավտոմատ թարգմանությունը կարող է սխալներ պարունակել։"), secondary = true)
                SourceLine(
                    if (r.via.isEmpty()) tr("Աղբյուր՝ KHOSQ-ի սերվեր (Cloudflare AI). տեքստը չի պահվում")
                    else trf("Աղբյուր՝ KHOSQ-ի սերվեր (Cloudflare AI, {0}). տեքստը չի պահվում", viaName(r.via)),
                )
            }
        }
    }
}

/** The task the assistant added at the person's word: what it understood, and "Change" / "Undo" in case it understood wrongly. */
@Composable
private fun ProposalBody(
    turn: AsstTurn,
    p: Task,
    now: Long,
    onAdd: () -> Unit,
    onChange: () -> Unit,
    onUndo: () -> Unit,
    onOpenTasks: () -> Unit,
) {
    val k = KTheme.colors
    val state = turn.proposalState
    CardText(
        when {
            state == 1 -> tr("Ավելացվեց առաջադրանքներին")
            state == 2 -> tr("Առաջադրանքը ջնջվեց")
            turn.busy -> tr("Ավելացնում եմ…")
            else -> tr("Առաջադրանքը չհաջողվեց պահպանել")
        },
        bold = true,
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(imageVector = KIcons.Tasks, contentDescription = null, tint = k.accent, modifier = Modifier.size(20.dp).padding(top = 1.dp))
        Spacer(Modifier.width(Space.s))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = p.title, color = if (state == 2) k.textSecondary else k.text, style = TextStyle(fontSize = 16.sp, lineHeight = 22.sp))
            if (p.due > 0L) {
                val past = p.due <= now
                Text(
                    text = trf("Հիշեցում՝ {0}", dueLabel(p.due, now) + ", " + formatTime(p.due)),
                    color = if (past) k.danger else k.textSecondary,
                    style = TextStyle(fontSize = 13.sp),
                )
                if (past && state != 2) CardText(tr("Այդ ժամանակն արդեն անցել է, հիշեցում չի լինի"), secondary = true)
            } else {
                Text(text = tr("Ժամ չկա՝ հիշեցում չի լինի"), color = k.textSecondary, style = TextStyle(fontSize = 13.sp))
            }
        }
    }
    if (state == 1) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            SoftButton(text = tr("Փոխել"), onClick = onChange, modifier = Modifier.weight(1f))
            SoftButton(text = tr("Հետարկել"), onClick = onUndo, modifier = Modifier.weight(1f))
        }
        SourceLine(tr("Աղբյուր՝ քո առաջադրանքները (միայն այս հեռախոսում)"), onOpenTasks)
    } else if (state == 0 && !turn.busy) {
        // saving failed: the person can try again or let it go
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.xs),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            GradientButton(text = tr("Ավելացնել"), onClick = onAdd, modifier = Modifier.fillMaxWidth())
            SoftButton(text = tr("Չեղարկել"), onClick = onUndo, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun FindBody(
    turn: AsstTurn,
    intent: AsstIntent.Find,
    now: Long,
    onOpenChat: (String) -> Unit,
    onOpenTasks: () -> Unit,
    onOpenMemory: () -> Unit,
) {
    if (turn.busy) {
        CardText(tr("Փնտրում եմ…"), secondary = true)
        Spacer(Modifier.height(Space.xs))
        return
    }
    val total = turn.hits.size + turn.tasks.size
    CardText(if (total > 0) trf("Գտա {0}", total.toString()) else tr("Ոչինչ չգտա"), bold = true)
    if (total == 0) CardText(tr("Հիշեցում ավելացնելու համար սկսիր «հիշեցրու» բառով, օրինակ՝ «հիշեցրու վաղը ժամը 10-ին զանգահարել»։"), secondary = true)
    if (intent.words.isNotEmpty()) CardText(trf("Որոնեցի՝ {0}", intent.words.joinToString(", ")), secondary = true)
    val range = intent.range
    if (range != null) CardText(trf("Ժամանակահատված՝ {0}", rangeLabel(range)), secondary = true)
    if (turn.approx) CardText(tr("Ճշգրիտ համընկնում չկա. ցույց եմ տալիս այն արդյունքները, որոնցում մեկ բառ պակասում է։"), secondary = true)
    if (turn.tasks.isNotEmpty()) {
        SectionLabel(tr("Առաջադրանքներ"))
        for (t in turn.tasks) AsstTaskRow(t = t, now = now, onClick = onOpenTasks)
    }
    if (turn.hits.isNotEmpty()) {
        SectionLabel(tr("Նամակներ"))
        val query = intent.words.joinToString(" ")
        for (h in turn.hits) {
            MemoryHitRow(
                hit = h,
                query = query,
                onClick = {
                    AppState.jumpTo(h.chatId, h.msgId)
                    onOpenChat(h.chatId)
                },
            )
        }
    }
    SourceLine(tr("Աղբյուր՝ քո առաջադրանքները (միայն այս հեռախոսում)"), onOpenTasks)
    if (turn.memOff) {
        CardText(tr("Հիշողությունը միացված չէ ոչ մի զրույցում, ուստի նամակներում չեմ փնտրել։ Միացրու այն զրույցի ⋮ ընտրացանկից կամ Հիշողություն էկրանում։"), secondary = true)
        SoftButton(
            text = tr("Բացել Հիշողությունը"),
            onClick = onOpenMemory,
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.xs),
        )
    } else {
        val shown = turn.memChats.take(3).joinToString(", ")
        val more = turn.memChats.size - 3
        val names = if (more > 0) shown + " " + trf("և ևս {0}", more.toString()) else shown
        SourceLine(trf("Աղբյուր՝ Հիշողություն (զրույցներ՝ {0})", names), onOpenMemory)
    }
}

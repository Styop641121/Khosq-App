package am.khosq.app.ui

import am.khosq.app.data.Task
import am.khosq.app.data.TaskGroup
import am.khosq.app.data.TaskLogic
import am.khosq.app.tr
import am.khosq.app.trf
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Calendar
import kotlinx.coroutines.delay

/** "Today", "Tomorrow", "Yesterday" or the date. */
internal fun dueLabel(ts: Long, now: Long): String = when (TaskLogic.dayDiff(ts, now)) {
    0 -> tr("Այսօր")
    1 -> tr("Վաղը")
    -1 -> tr("Երեկ")
    else -> dayLabel(ts)
}

/**
 * My tasks: what is late, what is for today, what comes later, what has no date, and what is done.
 * A quick line at the bottom adds a task in one go; the plus opens the full form with date, time and reminder.
 */
@Composable
fun TasksScreen(vm: AppViewModel, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val k = KTheme.colors
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        // the sections move on by themselves: "today" becomes "late" when its time passes
        while (true) {
            delay(30_000L)
            now = System.currentTimeMillis()
        }
    }
    var editing by remember { mutableStateOf<Task?>(null) }
    var showDone by remember { mutableStateOf(false) }
    var quick by remember { mutableStateOf("") }
    val sections = remember(tasks, now) { TaskLogic.sections(tasks, now) }
    val addQuick: () -> Unit = {
        val x = quick.trim()
        if (x.isNotEmpty()) {
            vm.saveTask(Task(title = x))
            quick = ""
        }
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
                text = tr("Առաջադրանքներ"),
                color = k.text,
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = Space.xs).weight(1f),
            )
            KIconButton(icon = KIcons.Plus, onClick = { editing = Task() }, description = tr("Նոր առաջադրանք"), tint = k.accent)
        }
        KDivider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tasks.isEmpty()) {
                EmptyState(
                    icon = KIcons.Tasks,
                    title = tr("Առաջադրանքներ և հիշեցումներ"),
                    text = tr("Գրիր առաջադրանք ներքևի տողում կամ երկար սեղմիր նամակի վրա ու ընտրիր «Ավելացնել առաջադրանքներին»։ Ամեն ինչ մնում է միայն քո հեռախոսում։"),
                    modifier = Modifier.fillMaxSize().padding(top = Space.huge),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.l)) {
                    for ((group, list) in sections) {
                        item(key = "h-" + group.name) {
                            GroupHeader(
                                group = group,
                                count = list.size,
                                expanded = showDone,
                                onToggle = if (group == TaskGroup.DONE) ({ showDone = !showDone }) else null,
                            )
                        }
                        if (group != TaskGroup.DONE || showDone) {
                            items(list, key = { x -> x.id }) { t ->
                                Column(Modifier.animateItem()) {
                                    TaskRow(
                                        t = t,
                                        now = now,
                                        onToggle = { vm.setTaskDone(t.id, !t.done) },
                                        onClick = { editing = t },
                                    )
                                    KDivider(inset = 52.dp)
                                }
                            }
                        }
                        if (group == TaskGroup.DONE && showDone) {
                            item(key = "clear-done") {
                                SoftButton(
                                    text = tr("Մաքրել կատարվածները"),
                                    danger = true,
                                    onClick = { vm.clearDoneTasks() },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.m),
                                )
                            }
                        }
                    }
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
                value = quick,
                onValueChange = { v -> if (v.length <= TaskLogic.MAX_TITLE) quick = v },
                placeholder = { Text(tr("Նոր առաջադրանք…")) },
                singleLine = true,
                shape = RoundedCornerShape(Corner.xl),
                colors = kFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { addQuick() }),
                modifier = Modifier.weight(1f),
            )
            KIconButton(
                icon = KIcons.Send,
                onClick = addQuick,
                description = tr("Ավելացնել"),
                tint = if (quick.isBlank()) k.textTertiary else k.accent,
            )
        }
    }

    val e = editing
    if (e != null) {
        TaskEditSheet(
            start = e,
            onClose = { editing = null },
            onSave = { t -> vm.saveTask(t) },
            onDelete = if (e.id != 0L) ({ t -> vm.deleteTask(t.id) }) else null,
            onOpenChat = onOpenChat,
        )
    }
}

@Composable
private fun GroupHeader(group: TaskGroup, count: Int, expanded: Boolean, onToggle: (() -> Unit)?) {
    val k = KTheme.colors
    val label = when (group) {
        TaskGroup.OVERDUE -> tr("Ժամկետանց")
        TaskGroup.TODAY -> tr("Այսօր")
        TaskGroup.UPCOMING -> tr("Առաջիկայում")
        TaskGroup.NO_DATE -> tr("Առանց ժամկետի")
        TaskGroup.DONE -> tr("Կատարված")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(start = Space.l, end = Space.l, top = Space.l, bottom = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label + "  " + count,
            color = if (group == TaskGroup.OVERDUE) k.danger else k.textSecondary,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
            modifier = Modifier.weight(1f),
        )
        if (onToggle != null) {
            Icon(
                imageVector = if (expanded) KIcons.Down else KIcons.ChevronRight,
                contentDescription = null,
                tint = k.textSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun TaskRow(t: Task, now: Long, onToggle: () -> Unit, onClick: () -> Unit) {
    val k = KTheme.colors
    val late = TaskLogic.isOverdue(t, now)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = Space.xs, top = Space.xs, bottom = Space.xs, end = Space.l),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .then(if (t.done) Modifier.background(k.accent) else Modifier.border(2.dp, k.textTertiary, CircleShape)),
                contentAlignment = Alignment.Center,
            ) {
                if (t.done) Icon(imageVector = KIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
        Column(
            modifier = Modifier.weight(1f).padding(top = Space.s, bottom = Space.xs),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = t.title,
                color = if (t.done) k.textSecondary else k.text,
                style = TextStyle(
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    textDecoration = if (t.done) TextDecoration.LineThrough else TextDecoration.None,
                ),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            if (t.note.isNotEmpty()) {
                Text(
                    text = t.note,
                    color = k.textSecondary,
                    style = TextStyle(fontSize = 14.sp, lineHeight = 19.sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (t.due > 0L) {
                val tone = if (late) k.danger else k.textSecondary
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (t.remind) KIcons.Bell else KIcons.Clock,
                        contentDescription = null,
                        tint = tone,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = dueLabel(t.due, now) + ", " + formatTime(t.due),
                        color = tone,
                        style = TextStyle(fontSize = 13.sp, fontWeight = if (late) FontWeight.SemiBold else FontWeight.Normal),
                    )
                }
            }
            if (t.chatName.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = KIcons.Chat, contentDescription = null, tint = k.accent, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = t.chatName,
                        color = k.accent,
                        style = TextStyle(fontSize = 13.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun askDate(ctx: Context, current: Long, now: Long, onPick: (Int, Int, Int) -> Unit) {
    val c = Calendar.getInstance()
    c.timeInMillis = if (current > 0L) current else now
    DatePickerDialog(
        ctx,
        { _, y, m, d -> onPick(y, m, d) },
        c.get(Calendar.YEAR),
        c.get(Calendar.MONTH),
        c.get(Calendar.DAY_OF_MONTH),
    ).show()
}

private fun askTime(ctx: Context, current: Long, now: Long, onPick: (Int, Int) -> Unit) {
    val c = Calendar.getInstance()
    c.timeInMillis = if (current > 0L) current else now
    TimePickerDialog(
        ctx,
        { _, h, m -> onPick(h, m) },
        c.get(Calendar.HOUR_OF_DAY),
        c.get(Calendar.MINUTE),
        android.text.format.DateFormat.is24HourFormat(ctx),
    ).show()
}

@Composable
private fun PickBox(text: String, filled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val k = KTheme.colors
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(Corner.m))
            .background(k.surfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.m),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = text,
            color = if (filled) k.text else k.textSecondary,
            style = TextStyle(fontSize = 15.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun QuickChip(text: String, onClick: () -> Unit) {
    val k = KTheme.colors
    Text(
        text = text,
        color = k.accent,
        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(Corner.l))
            .background(k.accentSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = Space.m, vertical = Space.s),
    )
}

/**
 * The form of a task: what to do, a note, the date and time, and whether the phone should remind me.
 * [start] with number 0 is a new task (it may already hold text, when it comes from a chat message).
 */
@Composable
fun TaskEditSheet(
    start: Task,
    onClose: () -> Unit,
    onSave: (Task) -> Unit,
    onDelete: ((Task) -> Unit)? = null,
    onOpenChat: ((String) -> Unit)? = null,
) {
    val k = KTheme.colors
    val ctx = LocalContext.current
    val now = System.currentTimeMillis()
    val isNew = start.id == 0L
    var title by remember { mutableStateOf(start.title) }
    var note by remember { mutableStateOf(start.note) }
    var due by remember { mutableLongStateOf(start.due) }
    var remind by remember { mutableStateOf(start.remind) }
    val past = due > 0L && due <= now && !start.done

    // a new date turns the reminder on by itself the first time; clearing the date turns it off
    val setDue: (Long) -> Unit = { v ->
        if (due == 0L && v > now) remind = true
        if (v == 0L) remind = false
        due = v
    }

    KSheet(onDismiss = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Text(
                text = if (isNew) tr("Նոր առաջադրանք") else tr("Առաջադրանք"),
                color = k.text,
                style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold),
            )
            OutlinedTextField(
                value = title,
                onValueChange = { v -> if (v.length <= TaskLogic.MAX_TITLE) title = v },
                label = { Text(tr("Ինչ պետք է անել")) },
                maxLines = 3,
                shape = RoundedCornerShape(Corner.m),
                colors = kFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = note,
                onValueChange = { v -> if (v.length <= TaskLogic.MAX_NOTE) note = v },
                label = { Text(tr("Նշում")) },
                minLines = 2,
                maxLines = 5,
                shape = RoundedCornerShape(Corner.m),
                colors = kFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = tr("Ժամկետ"),
                color = k.textSecondary,
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(top = Space.xs),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                PickBox(
                    text = if (due > 0L) dueLabel(due, now) else tr("Ամսաթիվ"),
                    filled = due > 0L,
                    onClick = {
                        askDate(ctx, due, now) { y, m, d ->
                            setDue(TaskLogic.withDate(due, y, m, d))
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                PickBox(
                    text = if (due > 0L) formatTime(due) else tr("Ժամ"),
                    filled = due > 0L,
                    onClick = {
                        askTime(ctx, due, now) { h, m ->
                            setDue(TaskLogic.withTime(due, h, m, System.currentTimeMillis()))
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                if (due > 0L) {
                    KIconButton(icon = KIcons.Close, onClick = { setDue(0L) }, description = tr("Հանել ժամկետը"), tint = k.textSecondary)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                QuickChip(tr("Մեկ ժամից")) { setDue(TaskLogic.inAnHour(now)) }
                if (TaskLogic.thisEvening(now) > 0L) {
                    QuickChip(tr("Այսօր երեկոյան")) { setDue(TaskLogic.thisEvening(now)) }
                }
                QuickChip(tr("Վաղը առավոտյան")) { setDue(TaskLogic.tomorrowMorning(now)) }
            }
            if (past) {
                Text(
                    text = tr("Այս ժամն արդեն անցել է, հիշեցում չի լինի։"),
                    color = k.danger,
                    style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(imageVector = KIcons.Bell, contentDescription = null, tint = k.accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f)) {
                    Text(text = tr("Հիշեցնել"), color = k.text, style = TextStyle(fontSize = 16.sp))
                    Text(
                        text = if (due > 0L) tr("Ծանուցում՝ նշված ժամին") else tr("Նախ ընտրիր ամսաթիվ և ժամ"),
                        color = k.textSecondary,
                        style = TextStyle(fontSize = 13.sp),
                    )
                }
                Switch(
                    checked = remind && due > 0L,
                    onCheckedChange = { v -> remind = v },
                    enabled = due > now,
                    colors = kSwitchColors(),
                )
            }
            if (start.chatName.isNotEmpty() && onOpenChat != null) {
                SheetRow(
                    icon = KIcons.Chat,
                    label = trf("Նամակից՝ {0}", start.chatName),
                    subtitle = tr("Բացել զրույցը"),
                    onClick = {
                        onClose()
                        onOpenChat(start.chatId)
                    },
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                SoftButton(text = tr("Չեղարկել"), onClick = onClose, modifier = Modifier.weight(1f))
                GradientButton(
                    text = tr("Պահպանել"),
                    enabled = title.isNotBlank(),
                    onClick = {
                        onSave(start.copy(title = title, note = note, due = due, remind = remind && due > 0L))
                        onClose()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            if (onDelete != null) {
                SheetRow(
                    icon = KIcons.Trash,
                    label = tr("Ջնջել առաջադրանքը"),
                    danger = true,
                    onClick = {
                        onDelete(start)
                        onClose()
                    },
                )
            }
        }
    }
}

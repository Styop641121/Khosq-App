package am.khosq.app.ui

import am.khosq.app.data.Chat
import am.khosq.app.data.MemoryHit
import am.khosq.app.data.MemoryLogic
import am.khosq.app.tr
import am.khosq.app.trf
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** What the person is being asked on the Memory screen. */
private sealed class MemAsk {
    data class EnableOne(val chat: Chat) : MemAsk()
    data class DisableOne(val chat: Chat) : MemAsk()
    data object EnableAll : MemAsk()
    data object WipeAll : MemAsk()
}

/**
 * KHOSQ Memory: the chats whose texts are saved on this phone (a switch for each), and a search through all of them.
 * Everything here stays on the phone; switching a chat off deletes what was saved from it.
 */
@Composable
fun MemoryScreen(vm: AppViewModel, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val k = KTheme.colors
    val chats by vm.chats.collectAsStateWithLifecycle()
    val mem by vm.memoryChats.collectAsStateWithLifecycle()
    val hits by vm.memoryHits.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var ask by remember { mutableStateOf<MemAsk?>(null) }
    var viewing by remember { mutableStateOf<MemoryHit?>(null) }

    LaunchedEffect(query) { vm.searchMemory(query) }
    DisposableEffect(Unit) {
        onDispose { vm.searchMemory("") }
    }

    val usable = remember(chats) { chats.filter { c -> c.status == "active" }.sortedByDescending { c -> c.lastTs } }
    val q = query.trim()

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
                text = "KHOSQ Memory",
                color = k.text,
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(start = Space.xs),
            )
        }
        KDivider()
        SearchField(
            value = query,
            onValueChange = { v -> query = v },
            placeholder = tr("Որոնել հիշողության մեջ…"),
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.s),
        )
        if (q.isNotEmpty()) {
            if (hits.isEmpty()) {
                EmptyState(
                    icon = KIcons.Search,
                    title = tr("Ոչինչ չի գտնվել"),
                    text = tr("Որոնում է միայն այն զրույցներում, որտեղ Հիշողությունը միացված է։"),
                    modifier = Modifier.fillMaxSize().padding(top = Space.xxl),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.xxl)) {
                    items(hits, key = { h -> h.chatId + "/" + h.msgId }) { h ->
                        MemoryHitRow(hit = h, query = q, onClick = { viewing = h })
                        KDivider(inset = Space.l)
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.xxl)) {
                item(key = "info") {
                    Text(
                        text = tr("Հիշողությունը պահում է միացրած զրույցների տեքստային նամակները միայն քո հեռախոսում՝ գաղտնագրված։ Ոչինչ չի ուղարկվում սերվեր։ Զրույցում Հիշողությունն անջատելիս այդ զրույցից պահվածը ջնջվում է։"),
                        color = k.textSecondary,
                        style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
                        modifier = Modifier.padding(horizontal = Space.l, vertical = Space.s),
                    )
                }
                item(key = "label") { SectionLabel(tr("Զրույցներ")) }
                if (usable.isEmpty()) {
                    item(key = "none") {
                        Text(
                            text = tr("Դեռ զրույցներ չկան"),
                            color = k.textSecondary,
                            style = TextStyle(fontSize = 15.sp),
                            modifier = Modifier.padding(horizontal = Space.l, vertical = Space.m),
                        )
                    }
                }
                items(usable, key = { c -> "c-" + c.id }) { c ->
                    val on = mem[c.id]
                    ChatMemoryRow(
                        name = c.label(),
                        on = on != null,
                        count = on?.count ?: 0,
                        onToggle = { ask = if (on != null) MemAsk.DisableOne(c) else MemAsk.EnableOne(c) },
                    )
                    KDivider(inset = Space.l)
                }
                item(key = "actions") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.l, vertical = Space.l),
                        verticalArrangement = Arrangement.spacedBy(Space.s),
                    ) {
                        if (usable.any { c -> mem[c.id] == null }) {
                            SoftButton(text = tr("Միացնել բոլոր զրույցների համար"), onClick = { ask = MemAsk.EnableAll }, modifier = Modifier.fillMaxWidth())
                        }
                        if (mem.isNotEmpty()) {
                            SoftButton(
                                text = tr("Ջնջել ամբողջ հիշողությունը"),
                                danger = true,
                                onClick = { ask = MemAsk.WipeAll },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }

    when (val a = ask) {
        is MemAsk.EnableOne -> MemoryEnableDialog(
            name = a.chat.label(),
            onConfirm = {
                vm.memoryEnable(a.chat.id, a.chat.label())
                ask = null
            },
            onDismiss = { ask = null },
        )
        is MemAsk.DisableOne -> MemoryDisableDialog(
            name = a.chat.label(),
            onConfirm = {
                vm.memoryDisable(a.chat.id)
                ask = null
            },
            onDismiss = { ask = null },
        )
        is MemAsk.EnableAll -> MemoryEnableDialog(
            name = null,
            onConfirm = {
                for (c in usable) if (mem[c.id] == null) vm.memoryEnable(c.id, c.label())
                ask = null
            },
            onDismiss = { ask = null },
        )
        is MemAsk.WipeAll -> AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text(tr("Ջնջե՞լ ամբողջ հիշողությունը")) },
            text = { Text(tr("Բոլոր պահված նամակները կջնջվեն այս հեռախոսից, և Հիշողությունը կանջատվի բոլոր զրույցներում։ Զրույցներն իրենք չեն փոխվի։")) },
            confirmButton = {
                TextButton(onClick = {
                    vm.memoryDisableAll()
                    ask = null
                }) { Text(tr("Ջնջել")) }
            },
            dismissButton = { TextButton(onClick = { ask = null }) { Text(tr("Չեղարկել")) } },
        )
        null -> {}
    }

    val v = viewing
    if (v != null) {
        MemoryItemSheet(
            hit = v,
            onClose = { viewing = null },
            onSave = { text -> vm.memoryEdit(v, text) },
            onRemove = { vm.memoryRemove(v) },
            onOpenChat = onOpenChat,
        )
    }
}

@Composable
private fun ChatMemoryRow(name: String, on: Boolean, count: Int, onToggle: () -> Unit) {
    val k = KTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = Space.l, vertical = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = name,
                color = k.text,
                style = TextStyle(fontSize = 16.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (on) trf("Պահված է՝ {0}", count.toString()) else tr("Անջատված է"),
                color = if (on) k.accent else k.textSecondary,
                style = TextStyle(fontSize = 13.sp),
            )
        }
        Switch(checked = on, onCheckedChange = { onToggle() }, colors = kSwitchColors())
    }
}

/** One found message: the chat, who wrote it and when, and a piece of the text around the found words. */
@Composable
internal fun MemoryHitRow(hit: MemoryHit, query: String, onClick: () -> Unit) {
    val k = KTheme.colors
    val words = remember(query) { MemoryLogic.words(query) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Space.l, vertical = Space.m),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = hit.chatName + (if (hit.who.isNotEmpty()) "  ›  " + hit.who else ""),
                color = k.accent,
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = dayLabel(hit.ts) + " " + formatTime(hit.ts),
                color = k.textSecondary,
                style = TextStyle(fontSize = 12.sp),
                modifier = Modifier.padding(start = Space.s),
            )
        }
        Text(
            text = MemoryLogic.snippet(hit.text, words),
            color = k.text,
            style = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (hit.edited) {
            Text(text = tr("Փոփոխված պատճեն"), color = k.textTertiary, style = TextStyle(fontSize = 12.sp))
        }
    }
}

/** A saved message: read it whole, change the saved copy, take it out, or go to its chat. */
@Composable
private fun MemoryItemSheet(
    hit: MemoryHit,
    onClose: () -> Unit,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
    onOpenChat: (String) -> Unit,
) {
    val k = KTheme.colors
    var text by remember { mutableStateOf(hit.text) }
    val changed = text.trim() != hit.text.trim() && text.isNotBlank()
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
                text = hit.chatName,
                color = k.text,
                style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = (if (hit.mine) tr("Դու") else hit.who) + "  ·  " + dayLabel(hit.ts) + " " + formatTime(hit.ts),
                color = k.textSecondary,
                style = TextStyle(fontSize = 13.sp),
            )
            OutlinedTextField(
                value = text,
                onValueChange = { v -> if (v.length <= MemoryLogic.MAX_TEXT) text = v },
                minLines = 3,
                maxLines = 10,
                shape = RoundedCornerShape(Corner.m),
                colors = kFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = tr("Սա միայն հիշողության պատճենն է. զրույցի նամակը չի փոխվում։"),
                color = k.textSecondary,
                style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
            )
            Row(Modifier.fillMaxWidth().padding(top = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                SoftButton(text = tr("Փակել"), onClick = onClose, modifier = Modifier.weight(1f))
                GradientButton(
                    text = tr("Պահպանել"),
                    enabled = changed,
                    onClick = {
                        onSave(text)
                        onClose()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            SheetRow(
                icon = KIcons.Chat,
                label = tr("Բացել զրույցը"),
                onClick = {
                    onClose()
                    onOpenChat(hit.chatId)
                },
            )
            SheetRow(
                icon = KIcons.Trash,
                label = tr("Հանել հիշողությունից"),
                danger = true,
                onClick = {
                    onRemove()
                    onClose()
                },
            )
        }
    }
}

/** Asks before Memory starts saving a chat. [name] is the chat, or null when it is for every chat. */
@Composable
fun MemoryEnableDialog(name: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (name == null) tr("Միացնե՞լ Հիշողությունը բոլոր զրույցներում") else trf("Միացնե՞լ Հիշողությունը «{0}» զրույցում", name)) },
        text = {
            Text(
                tr("Տեքստային նամակները կպահվեն միայն այս հեռախոսում՝ գաղտնագրված հեռախոսի պաշտպանված բանալիով, որպեսզի կարողանաս դրանք որոնել։ Ոչինչ չի ուղարկվում սերվեր։ Նկարները, ձայնագրությունները և տեղադրությունները չեն պահվում։ Ցանկացած պահի կարող ես անջատել՝ պահվածը կջնջվի։"),
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(tr("Միացնել")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Չեղարկել")) } },
    )
}

/** Asks before Memory is switched off for a chat, because that deletes what was saved from it. */
@Composable
fun MemoryDisableDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(trf("Անջատե՞լ Հիշողությունը «{0}» զրույցում", name)) },
        text = { Text(tr("Այս զրույցից պահված բոլոր նամակները կջնջվեն այս հեռախոսից։ Զրույցն ինքը չի փոխվի։")) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(tr("Անջատել և ջնջել")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Չեղարկել")) } },
    )
}

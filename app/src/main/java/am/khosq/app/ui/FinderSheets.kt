package am.khosq.app.ui

import am.khosq.app.Lang
import am.khosq.app.data.FinderCfg
import am.khosq.app.data.FinderLogic
import am.khosq.app.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The two sheets of the Real Estate Finder: the filters (what to look for) and the menu (the sources and the search itself).
 * Small functions, no early returns.
 */

/** The language list.am's pages are read in: the language of the app, or Armenian when it is something else. */
private fun listLang(): String = if (Lang.code == "ru" || Lang.code == "en") Lang.code else "hy"

@Composable
internal fun FinderFilterSheet(initial: FinderCfg?, onDismiss: () -> Unit, onSave: (FinderCfg) -> Unit) {
    val k = KTheme.colors
    val start = initial ?: FinderCfg(on = true, lang = listLang())
    var cat by remember { mutableStateOf(start.cat) }
    var region by remember { mutableIntStateOf(start.region) }
    var cur by remember { mutableStateOf(start.cur) }
    var minText by remember { mutableStateOf(if (start.min > 0L) start.min.toString() else "") }
    var maxText by remember { mutableStateOf(if (start.max > 0L) start.max.toString() else "") }
    var rooms by remember { mutableIntStateOf(start.rooms) }

    KSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Text(text = tr("Ֆիլտրեր"), color = k.text, style = KType.Title)
            FinderGroup(tr("Գործարք")) {
                FinderChoices(
                    options = listOf(FinderLogic.SALE to tr("Վաճառք"), FinderLogic.RENT to tr("Վարձակալություն")),
                    selected = cat,
                    onSelect = { v -> cat = v },
                )
            }
            FinderGroup(tr("Վայր")) { FinderRegionChoices(selected = region, onSelect = { v -> region = v }) }
            FinderGroup(tr("Բյուջե")) {
                FinderChoices(options = listOf("USD" to "USD $", "AMD" to "AMD ֏"), selected = cur, onSelect = { v -> cur = v })
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    FinderAmountField(value = minText, label = tr("Սկսած"), onChange = { v -> minText = v }, modifier = Modifier.weight(1f))
                    FinderAmountField(value = maxText, label = tr("Մինչև"), onChange = { v -> maxText = v }, modifier = Modifier.weight(1f))
                }
            }
            FinderGroup(tr("Սենյակներ")) {
                FinderChoices(
                    options = (0..FinderLogic.ROOMS_MAX).map { n -> n to (if (n == 0) tr("Ցանկացած") else "$n+") },
                    selected = rooms,
                    onSelect = { v -> rooms = v },
                )
            }
            Text(
                text = tr("Սերվերին է գնում միայն այս ֆիլտրը։ Ստուգումը կատարվում է մոտ 5 րոպեն մեկ։ list.am-ի էջը կարդում է մեր սերվերը, ոչ թե քո հեռախոսը։"),
                color = k.textTertiary,
                style = KType.Caption,
            )
            GradientButton(
                text = tr("Պահել և սկսել"),
                onClick = { onSave(buildFinderCfg(cat, region, cur, minText, maxText, rooms)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.s))
        }
    }
}

/** The search as the sheet describes it. A "from" higher than the "to" is turned round, so the search is never empty by mistake. */
private fun buildFinderCfg(cat: String, region: Int, cur: String, minText: String, maxText: String, rooms: Int): FinderCfg {
    var lo = FinderLogic.parseAmount(minText)
    var hi = FinderLogic.parseAmount(maxText)
    if (lo > 0L && hi in 1L until lo) {
        val t = lo
        lo = hi
        hi = t
    }
    return FinderCfg(on = true, cat = cat, region = region, min = lo, max = hi, cur = cur, rooms = rooms, lang = listLang())
}

@Composable
private fun FinderGroup(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(
            text = label,
            color = KTheme.colors.textSecondary,
            style = TextStyle(fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp),
        )
        content()
    }
}

/** A row of chips, one of which is chosen. Chips share the width equally. */
@Composable
private fun <T> FinderChoices(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        for (o in options) {
            FinderChip(text = o.second, on = o.first == selected, onClick = { onSelect(o.first) }, modifier = Modifier.weight(1f))
        }
    }
}

/** The places, two to a row. */
@Composable
private fun FinderRegionChoices(selected: Int, onSelect: (Int) -> Unit) {
    val lang = Lang.code
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        for (pair in FinderLogic.regions.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                for (r in pair) {
                    val name = if (r == 1) FinderLogic.regionName(r, lang) + " · " + tr("ամբողջը") else FinderLogic.regionName(r, lang)
                    FinderChip(text = name, on = r == selected, onClick = { onSelect(r) }, modifier = Modifier.weight(1f))
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FinderChip(text: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val k = KTheme.colors
    val shape = RoundedCornerShape(Corner.l)
    Row(
        modifier = modifier
            .clip(shape)
            .background(if (on) k.accentSoft else k.surfaceHigh)
            .border(1.dp, if (on) k.accent.copy(alpha = 0.45f) else k.line, shape)
            .pressable(onClick = onClick, pressedScale = 0.96f)
            .padding(horizontal = Space.s, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = if (on) k.accent else k.textSecondary,
            style = TextStyle(fontSize = 13.5.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FinderAmountField(value: String, label: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { c -> c in '0'..'9' }.take(10)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = RoundedCornerShape(Corner.m),
        colors = kFieldColors(),
        modifier = modifier,
    )
}

// ---------------------------------------------------------------------------------------------
// The menu: sources and the search itself
// ---------------------------------------------------------------------------------------------

@Composable
internal fun FinderMenuSheet(hasSearch: Boolean, onDismiss: () -> Unit, onCheck: () -> Unit, onClear: () -> Unit) {
    val k = KTheme.colors
    var ask by remember { mutableStateOf(false) }
    KSheet(onDismiss = onDismiss) {
        Text(
            text = tr("Աղբյուրներ"),
            color = k.text,
            style = KType.Title,
            modifier = Modifier.padding(horizontal = Space.l, vertical = Space.s),
        )
        SheetRow(icon = KIcons.Check, label = tr("list.am (ակտիվ)"), subtitle = tr("Բնակարաններ՝ վաճառք և վարձակալություն"), onClick = {})
        // not available yet: shown greyed out, and a tap does nothing
        SheetRow(
            icon = KIcons.Plus,
            label = tr("Ավելացնել նոր աղբյուր"),
            subtitle = tr("Շուտով՝ myhome.am և այլն"),
            onClick = {},
            modifier = Modifier.alpha(0.4f),
        )
        if (hasSearch) {
            KDivider(modifier = Modifier.padding(vertical = Space.xs))
            SheetRow(
                icon = KIcons.Search,
                label = tr("Ստուգել հիմա"),
                onClick = {
                    onCheck()
                    onDismiss()
                },
            )
            SheetRow(icon = KIcons.Trash, label = tr("Ջնջել որոնումը"), danger = true, onClick = { ask = true })
        }
    }
    if (ask) FinderClearDialog(onYes = { onClear(); ask = false; onDismiss() }, onNo = { ask = false })
}

@Composable
private fun FinderClearDialog(onYes: () -> Unit, onNo: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNo,
        title = { Text(tr("Ջնջե՞լ որոնումը")) },
        text = { Text(tr("Որոնումը և գտնված հայտարարությունները կջնջվեն սերվերից և հեռախոսից։ Ծանուցումները կդադարեն։")) },
        confirmButton = { TextButton(onClick = onYes) { Text(tr("Ջնջել")) } },
        dismissButton = { TextButton(onClick = onNo) { Text(tr("Չեղարկել")) } },
    )
}

package am.khosq.app.ui

import am.khosq.app.crypto.E2E
import am.khosq.app.data.NameMatch
import am.khosq.app.data.VerifyState
import am.khosq.app.tr
import am.khosq.app.trf
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** The verification state of a contact, looked at again whenever somebody is verified or a key changes. */
@Composable
internal fun rememberVerifyState(peerUid: String, enabled: Boolean = true): VerifyState {
    val epoch by E2E.verifyEpoch.collectAsStateWithLifecycle()
    return remember(peerUid, epoch, enabled) { if (enabled && peerUid.isNotEmpty()) E2E.verifyState(peerUid) else VerifyState.NONE }
}

/** The small mark next to a contact's name: a blue badge when verified, a red triangle when the key changed after verifying; nothing otherwise. */
@Composable
internal fun VerifyBadge(state: VerifyState, size: Dp = 16.dp, modifier: Modifier = Modifier) {
    val k = KTheme.colors
    when (state) {
        VerifyState.VERIFIED -> Icon(imageVector = KIcons.Verified, contentDescription = tr("Հաստատված"), tint = k.accent, modifier = modifier.size(size))
        VerifyState.CHANGED -> Icon(imageVector = KIcons.Alert, contentDescription = tr("Անվտանգության թիվը փոխվել է"), tint = k.danger, modifier = modifier.size(size))
        VerifyState.NONE -> {}
    }
}

/** The red strip at the top of a chat with a contact who was verified and whose key has changed since. */
@Composable
internal fun VerifyChangedBar(onOpen: () -> Unit) {
    val k = KTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(k.danger.copy(alpha = 0.14f))
            .padding(horizontal = Space.l, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = KIcons.Alert, contentDescription = null, tint = k.danger, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.s))
        Text(
            text = tr("Հաստատված զրուցակցի անվտանգության թիվը փոխվել է։ Նորից համեմատիր նշանները, նախքան նրան կարևոր բան գրելը։"),
            color = k.text,
            style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(Space.s))
        SoftButton(text = tr("Բացել"), onClick = onOpen)
    }
}

/** The 8 pictures in two rows of four. */
@Composable
private fun EmojiCode(code: List<String>) {
    val k = KTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.m))
            .background(k.surface)
            .padding(vertical = Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for (row in code.chunked(4)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.l)) {
                for (e in row) Text(text = e, style = TextStyle(fontSize = 34.sp), textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * The "verify this contact" card of the contact sheet: the 8 pictures that are the same on both phones, what to do with them,
 * and the button that marks the contact as verified (or takes the mark away).
 */
@Composable
fun VerifyCard(peerUid: String, peerName: String, vm: AppViewModel? = null) {
    val k = KTheme.colors
    val state = rememberVerifyState(peerUid)
    val epoch by E2E.verifyEpoch.collectAsStateWithLifecycle()
    val code = remember(peerUid, epoch) { E2E.pairEmojis(peerUid) }
    // the QR way (needs the view model): scan the other person's code, or show mine
    val ctx = LocalContext.current
    var qrMsg by remember(peerUid) { mutableStateOf<String?>(null) }
    var showMine by remember { mutableStateOf(false) }
    val byQr = remember(peerUid, epoch, state) { E2E.verifiedByQr(peerUid) }
    val scan: () -> Unit = {
        qrMsg = null
        if (vm != null) {
            scanQr(
                ctx,
                onText = { raw -> vm.verifyScanned(raw, peerUid, peerName) { err -> qrMsg = err } },
                onFail = { msg -> qrMsg = msg },
            )
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.l))
            .background(k.surfaceHigh)
            .padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val tone = if (state == VerifyState.CHANGED) k.danger else if (state == VerifyState.VERIFIED) k.accent else k.textSecondary
            Icon(
                imageVector = if (state == VerifyState.CHANGED) KIcons.Alert else KIcons.Verified,
                contentDescription = null,
                tint = tone,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(Space.xs))
            Text(
                text = when (state) {
                    VerifyState.VERIFIED -> tr("Հաստատված զրուցակից")
                    VerifyState.CHANGED -> tr("Անվտանգության թիվը փոխվել է")
                    VerifyState.NONE -> tr("Հաստատել զրուցակցին")
                },
                color = tone,
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
            )
        }
        if (code == null) {
            Text(
                text = tr("Զրուցակցի բանալին դեռ չի ստացվել. բացիր զրույցը և փորձիր քիչ հետո։"),
                color = k.textSecondary,
                style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
            )
            return@Column
        }
        when (state) {
            VerifyState.VERIFIED -> {
                Text(
                    text = if (byQr) {
                        trf("Դու սկանավորել ես {0}-ի QR կոդը, և նրա բանալին համընկել է։ Որ դու էլ լինես ապահով նրա կողմից, թող նա էլ սկանավորի քո կոդը։ Եթե նրա կամ քո բանալին փոխվի, այս նշանը կվերանա։", peerName)
                    } else {
                        trf("Դու և {0}-ը համեմատել եք նշանները, և դրանք համընկել են։ Եթե նրա կամ քո բանալին փոխվի, այս նշանը կվերանա։", peerName)
                    },
                    color = k.textSecondary,
                    style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                )
                EmojiCode(code)
                SoftButton(text = tr("Հանել հաստատումը"), onClick = { E2E.clearVerified(peerUid) }, modifier = Modifier.fillMaxWidth())
            }
            VerifyState.CHANGED -> {
                Text(
                    text = tr("Դու նրան հաստատել էիր, բայց հետո նրա (կամ քո) բանալին փոխվել է։ Դա կարող է նշանակել նոր հեռախոս, բայց նաև որ ինչ-որ մեկը կանգնել է ձեր միջև։ Զանգիր նրան և նորից համեմատեք նշանները։"),
                    color = k.danger,
                    style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
                )
                EmojiCode(code)
                GradientButton(text = tr("Նշանները համընկնում են. հաստատել նորից"), onClick = { E2E.markVerified(peerUid) }, modifier = Modifier.fillMaxWidth())
                SoftButton(text = tr("Հանել հաստատումը"), onClick = { E2E.clearVerified(peerUid) }, modifier = Modifier.fillMaxWidth())
            }
            VerifyState.NONE -> {
                Text(
                    text = trf("Զանգիր {0}-ին կամ հանդիպեք և համեմատեք այս նշանները՝ նույն կարգով։ Եթե նույնն են, սեղմիր «Հաստատել»։ Այդպես կիմանաս, որ ձեր միջև ոչ ոք չկա։", peerName),
                    color = k.textSecondary,
                    style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                )
                EmojiCode(code)
                GradientButton(text = tr("Նշանները համընկնում են. հաստատել"), onClick = { E2E.markVerified(peerUid) }, modifier = Modifier.fillMaxWidth())
            }
        }
        if (vm != null) {
            SoftButton(text = tr("Ստուգել QR կոդով"), onClick = scan, modifier = Modifier.fillMaxWidth())
            SoftButton(text = tr("Ցույց տալ իմ QR կոդը"), onClick = { showMine = true }, modifier = Modifier.fillMaxWidth())
            val m = qrMsg
            if (m != null) {
                Text(text = m, color = k.danger, style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium))
            }
        }
    }
    if (showMine && vm != null) {
        val me = (vm.boot.value as? Boot.Ready)?.me
        if (me != null) MyQrSheet(me = me, onClose = { showMine = false }, onScan = {
            showMine = false
            scan()
        })
    }
}

/**
 * The warning on the "wants to write to you" card when the newcomer's name copies one of my contacts' names, or null when nothing
 * looks alike. [verified]: is that contact verified.
 */
internal fun lookalikeNote(matches: List<NameMatch>, verified: (String) -> Boolean): String? {
    val m = matches.firstOrNull() ?: return null
    return when {
        m.same && verified(m.id) -> trf("Ուշադրություն. այս անունը նույնն է քո հաստատված զրուցակից {0}-ի անվանը։ Դա կարող է լինել խաբեբա, ով ձևանում է նա։ Մի հավատա, մինչև չստուգես նրա հետ ուրիշ ճանապարհով։", m.name)
        m.same -> trf("Ուշադրություն. այս անունը նույնն է քո զրուցակից {0}-ի անվանը։ Դա կարող է լինել խաբեբա, ով ձևանում է նա։ Համոզվիր նրա հետ ուրիշ ճանապարհով։", m.name)
        else -> trf("Այս անունը շատ նման է քո զրուցակից {0}-ի անվանը (տարբերվում է մեկ տառով)։ Համոզվիր, որ դա նա է։", m.name)
    }
}

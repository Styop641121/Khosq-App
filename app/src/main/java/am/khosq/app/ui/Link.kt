package am.khosq.app.ui

import am.khosq.app.Prefs
import am.khosq.app.data.LinkLogic
import am.khosq.app.data.LinkRequest
import am.khosq.app.push.LinkClient
import am.khosq.app.push.LinkResult
import am.khosq.app.tr
import am.khosq.app.trf
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Linking a browser (the web version) by QR code: a row in the security settings. A tap opens the scanner; a good code opens a question
 * with the four digits the page shows; "Link" seals the identity for that page and leaves it in the Worker's post box.
 * Small functions, no early returns.
 */

private const val LAST_KEY = "web_link_at"

/** The text for the person after an attempt. */
private fun linkMessage(r: LinkResult): String = when (r) {
    LinkResult.OK -> tr("Բրաուզերը կապվեց. էջը մի քանի վայրկյանում կբացվի")
    LinkResult.TAKEN -> tr("Այս կոդն արդեն օգտագործվել է. թարմացրու էջը և նորից սկանավորիր")
    LinkResult.OFFLINE -> tr("Կապը չստացվեց. ստուգիր համացանցը և փորձիր նորից")
    LinkResult.SIGNED_OUT -> tr("Մուտքը ժամկետանց է. վերաբացիր հավելվածը")
    LinkResult.SLOW -> tr("Չափից շատ հարցումներ. մի քիչ սպասիր")
    LinkResult.OFF -> tr("Սերվերի վրա վեբ մուտքը դեռ միացված չէ")
    LinkResult.REJECTED -> tr("Կոդը սխալ է կամ ժամկետանց է. թարմացրու էջը և նորից սկանավորիր")
    LinkResult.FAILED -> tr("Սերվերը չպատասխանեց. նորից փորձիր")
}

private fun lastText(at: Long): String = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(at))

private fun startLinkScan(ctx: Context, onRequest: (LinkRequest) -> Unit) {
    scanQr(
        ctx = ctx,
        onText = { text ->
            val req = LinkLogic.parse(text)
            if (req == null) Toast.makeText(ctx, tr("Սա KHOSQ Web-ի կոդ չէ"), Toast.LENGTH_LONG).show() else onRequest(req)
        },
        onFail = { msg -> Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() },
    )
}

@Composable
internal fun LinkBrowserRow() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<LinkRequest?>(null) }
    var busy by remember { mutableStateOf(false) }
    var last by remember { mutableLongStateOf(Prefs.getLong(LAST_KEY)) }
    SettingsRow(
        icon = KIcons.Globe,
        title = tr("Կապել բրաուզեր (KHOSQ Web)"),
        subtitle = if (last > 0L) trf("Վերջին կապումը՝ {0}", lastText(last)) else tr("Բացիր KHOSQ Web-ը համակարգչում և սկանավորիր նրա QR կոդը"),
        onClick = { startLinkScan(ctx) { req -> pending = req } },
    )
    val req = pending
    if (req != null) {
        LinkConfirmDialog(
            code = LinkLogic.code(req),
            busy = busy,
            onYes = {
                busy = true
                scope.launch {
                    val r = LinkClient.send(req)
                    busy = false
                    pending = null
                    if (r == LinkResult.OK) {
                        last = System.currentTimeMillis()
                        Prefs.putLong(LAST_KEY, last)
                    }
                    Toast.makeText(ctx, linkMessage(r), Toast.LENGTH_LONG).show()
                }
            },
            onNo = { pending = null },
        )
    }
}

@Composable
private fun LinkConfirmDialog(code: String, busy: Boolean, onYes: () -> Unit, onNo: () -> Unit) {
    val k = KTheme.colors
    AlertDialog(
        onDismissRequest = { if (!busy) onNo() },
        title = { Text(tr("Կապե՞լ բրաուզերը")) },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Text(tr("Բրաուզերի էկրանին պետք է երևա նույն թիվը՝"), color = k.textSecondary)
                Text(
                    text = code,
                    color = k.text,
                    style = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = 6.sp),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    tr("Շարունակիր միայն եթե հենց հիմա դու ինքդ բացել ես KHOSQ Web-ը քո համակարգչում։ Բրաուզերը կկարդա քո զրույցները և կգրի քո անունով։ Քո անձնական բանալին փոխանցվում է գաղտնագրված՝ միայն այդ բրաուզերի համար։"),
                    color = k.textTertiary,
                    style = KType.Caption,
                )
            }
        },
        confirmButton = { TextButton(onClick = onYes, enabled = !busy) { Text(if (busy) tr("Կապվում է…") else tr("Կապել")) } },
        dismissButton = { TextButton(onClick = onNo, enabled = !busy) { Text(tr("Չեղարկել")) } },
    )
}

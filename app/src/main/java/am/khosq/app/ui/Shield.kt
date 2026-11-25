package am.khosq.app.ui

import am.khosq.app.data.ShieldFinding
import am.khosq.app.data.ShieldLevel
import am.khosq.app.data.ShieldLogic
import am.khosq.app.data.ShieldReport
import am.khosq.app.tr
import am.khosq.app.trf
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What a chat needs to put Trust Shield warnings under received messages: whether the first message from an unknown person
 * is being read, what the server said about sites, and what to do when the person taps the warning.
 */
class ShieldEnv(
    val stranger: Boolean,
    val verdicts: Map<String, String>,
    val onWhy: (String) -> Unit,
)

private fun levelColor(level: ShieldLevel, k: KColors): Color = if (level == ShieldLevel.DANGER) k.danger else k.warning

/** The small warning under a received message. It is only there when the rules found something; tapping it opens the explanation. */
@Composable
internal fun ShieldStrip(messageId: String, text: String, env: ShieldEnv) {
    val k = KTheme.colors
    val report = remember(messageId, text, env.stranger, env.verdicts) { ShieldLogic.scan(text, env.stranger, env.verdicts) }
    if (!report.risky) return
    val tone = levelColor(report.level, k)
    Row(
        modifier = Modifier
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(Corner.m))
            .background(tone.copy(alpha = 0.16f))
            .clickable { env.onWhy(messageId) }
            .padding(horizontal = Space.s, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = KIcons.Shield, contentDescription = null, tint = tone, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(
            text = (if (report.level == ShieldLevel.DANGER) tr("Վտանգավոր նամակ") else tr("Զգուշացում")) + " · " + tr("Ինչո՞ւ"),
            color = tone,
            style = TextStyle(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
        )
    }
}

/** One finding as a sentence. */
private fun explain(f: ShieldFinding): String = when (f.code) {
    "link_userinfo" -> trf("Հղման մեջ կա «@» նշան. այն ցույց է տալիս մեկ կայքի անուն, իսկ իրականում բացվում է՝ {0}", f.arg)
    "link_mixed" -> trf("{0} կայքի անվան մեջ խառնված են տարբեր այբուբենների տառեր (օրինակ՝ լատինական և կիրիլիկ). այդպես են կեղծում հայտնի կայքերը", f.arg)
    "link_brand" -> trf("Հղումը ձևանում է {0}-ի կայք, բայց դա նրա իրական կայքը չէ", f.arg)
    "link_ip" -> trf("Հղումը կայքի անվան փոխարեն թվային հասցե է՝ {0}", f.arg)
    "link_short" -> trf("Կարճացված հղում է ({0}). չի երևում, թե ուր է տանում", f.arg)
    "link_punycode" -> trf("Կայքի անունը ({0}) գրված է հատուկ կոդավորմամբ, որով հաճախ նմանակում են այլ կայքերի", f.arg)
    "link_tld" -> trf("Կայքի վերջավորությունը ({0}) հաճախ է օգտագործվում խաբեբայական կայքերի համար", f.arg)
    "link_hyphens" -> trf("Կայքի անունը ({0}) շատ գծիկներով է կազմված, ինչպես հաճախ լինում է կեղծ կայքերում", f.arg)
    "link_http_login" -> trf("Մուտքի կամ հաստատման էջ է առանց գաղտնագրված կապի (http)՝ {0}", f.arg)
    "link_blocked" -> trf("{0} կայքը Cloudflare-ի վտանգավոր կայքերի ցանկում է (չարամիտ կամ խաբեբայական)", f.arg)
    "secret_code" -> tr("Խնդրում է SMS-ով կամ հավելվածով եկած հաստատման կոդը. այն երբեք մի ասա ուրիշին՝ նույնիսկ բանկի կամ ծառայության «աշխատակցին»")
    "secret_card" -> tr("Խնդրում է քարտի համարը, ժամկետը կամ CVV կոդը")
    "secret_password" -> tr("Խնդրում է գաղտնաբառ")
    "secret_pin" -> tr("Խնդրում է PIN կոդ")
    "secret_seed" -> tr("Խնդրում է կրիպտոդրամապանակի վերականգնման բառերը. դրանք տալով կորցնում ես ամբողջ գումարը")
    "secret_id" -> tr("Խնդրում է անձնագրի կամ անձը հաստատող փաստաթղթի տվյալներ")
    "remote_tool" -> tr("Խնդրում է տեղադրել հեռակա կառավարման ծրագիր (AnyDesk, TeamViewer…). այդպես խաբեբաները տիրանում են հեռախոսին կամ բանկային հաշվին")
    "money_urgent" -> tr("Փող է խնդրում և շտապեցնում է. շտապեցնելը խաբեության ամենահայտնի նշանն է")
    "account_threat" -> tr("Սպառնում է փակել հաշիվը կամ քարտը և խնդրում է «հաստատել» տվյալները")
    "prize" -> tr("Խոստանում է մրցանակ կամ շահում")
    "invest" -> tr("Խոստանում է երաշխավորված եկամուտ կամ գումարի կրկնապատկում")
    "stranger_link" -> tr("Անծանոթ մարդը հղում է ուղարկել առաջին իսկ նամակում")
    "stranger_money" -> tr("Անծանոթ մարդը փողի մասին է խոսում առաջին նամակում")
    else -> f.code
}

private fun hostLine(verdict: String?): String = when (verdict) {
    ShieldLogic.BLOCKED -> tr("Cloudflare-ի վտանգավոր կայքերի ցանկում է")
    "ok" -> tr("Վտանգավորների ցանկում չկա. դա դեռ չի նշանակում, որ կայքն անվտանգ է")
    "unknown" -> tr("Չստուգվեց")
    else -> tr("Ստուգում եմ…")
}

/** The text of a complaint, to hand to whoever the person chooses in the share sheet. Nothing is sent by the app itself. */
private fun reportText(fromName: String, ts: Long, text: String, report: ShieldReport): String {
    val whenText = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ts))
    val sb = StringBuilder()
    sb.append(tr("KHOSQ. բողոք կասկածելի նամակի մասին")).append('\n')
    sb.append(tr("Ումից՝")).append(' ').append(fromName.ifEmpty { "?" }).append('\n')
    sb.append(tr("Ժամանակ՝")).append(' ').append(whenText).append('\n')
    sb.append(tr("Ինչու է կասկածելի՝")).append('\n')
    for (f in report.findings) sb.append("- ").append(explain(f)).append('\n')
    sb.append(tr("Նամակը՝")).append('\n').append(text.take(1500))
    return sb.toString()
}

/**
 * Explains a Trust Shield warning: the reasons, the sites the message links to (with the optional server check, which asks for
 * permission first), what to do, and the buttons to block the sender or to write a complaint.
 */
@Composable
fun ShieldSheet(
    vm: AppViewModel,
    text: String,
    ts: Long,
    stranger: Boolean,
    fromName: String,
    canBlock: Boolean,
    onBlock: () -> Unit,
    onClose: () -> Unit,
) {
    val k = KTheme.colors
    val ctx = LocalContext.current
    val linksOn by vm.shieldLinks.collectAsStateWithLifecycle()
    val verdicts by vm.hostVerdicts.collectAsStateWithLifecycle()
    val report = remember(text, stranger, verdicts) { ShieldLogic.scan(text, stranger, verdicts) }
    val hosts = report.hosts

    LaunchedEffect(linksOn, hosts) {
        if (linksOn && hosts.isNotEmpty()) vm.shieldCheckHosts(hosts)
    }

    KSheet(onDismiss = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            val tone = if (report.risky) levelColor(report.level, k) else k.accent
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = KIcons.Shield, contentDescription = null, tint = tone, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(Space.s))
                Text(text = "Trust Shield", color = k.text, style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold))
            }
            Text(
                text = when (report.level) {
                    ShieldLevel.DANGER -> tr("Վտանգավոր նամակ. նրա մեջ կան խաբեության ուժեղ նշաններ")
                    ShieldLevel.CAUTION -> tr("Զգուշացում. նամակում կան նշաններ, որոնք հանդիպում են խաբեությունների մեջ")
                    ShieldLevel.NONE -> tr("Կանոններով ոչինչ չի գտնվել")
                },
                color = tone,
                style = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
            )

            if (report.findings.isNotEmpty()) {
                Text(text = tr("Ինչո՞ւ"), color = k.text, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                for (f in report.findings) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(text = "•", color = levelColor(f.level, k), style = TextStyle(fontSize = 15.sp, lineHeight = 21.sp))
                        Spacer(Modifier.width(Space.s))
                        Text(text = explain(f), color = k.text, style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp))
                    }
                }
            }

            if (hosts.isNotEmpty()) {
                Text(text = tr("Հղումների կայքերը"), color = k.text, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                for (h in hosts) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Corner.m))
                            .background(k.surfaceHigh)
                            .padding(Space.m),
                    ) {
                        Text(text = h, color = k.text, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium))
                        if (linksOn) {
                            val v = verdicts[h]
                            Text(
                                text = hostLine(v),
                                color = if (v == ShieldLogic.BLOCKED) k.danger else k.textSecondary,
                                style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                            )
                        }
                    }
                }
                if (!linksOn) {
                    Text(
                        text = tr("Հղումները կարելի է ստուգել Cloudflare-ի վտանգավոր կայքերի ցանկով։ Այդ դեպքում KHOSQ-ի սերվեր կուղարկվեն միայն կայքերի անունները (օրինակ՝ example.com)՝ ոչ ամբողջ հղումը, ոչ նամակը, ոչ ուղարկողի անունը։ Սերվերը դրանք չի պահում և չի գրանցում, իսկ Cloudflare-ը ստանում է միայն կայքի անունը։ Այդ պահին անունը չգաղտնագրված է։ Ստուգումը հետո կատարվում է ինքնաբերաբար, քանի դեռ անջատիչը միացված է Կարգավորումներում։"),
                        color = k.textSecondary,
                        style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                    )
                    GradientButton(
                        text = tr("Թույլատրել և ստուգել հղումները"),
                        onClick = { vm.setShieldLinks(true) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (report.level != ShieldLevel.NONE) {
                Text(text = tr("Ինչ անել"), color = k.text, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                if (report.findings.any { f -> f.code.startsWith("secret_") || f.code == "remote_tool" }) {
                    Text(
                        text = tr("Երբեք մի ասա SMS կոդ, քարտի տվյալներ, գաղտնաբառ կամ PIN՝ նույնիսկ եթե ասում են, որ բանկից են։ Բանկը այդ տվյալները նամակով երբեք չի խնդրում։"),
                        color = k.text,
                        style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
                    )
                }
                Text(
                    text = if (stranger) {
                        tr("Այս մարդուն դու չես ընդունել. եթե չես սպասում նրա նամակին, մի պատասխանիր և մի բացիր հղումները։")
                    } else {
                        tr("Եթե նամակը գալիս է ծանոթից, զանգիր նրան ուրիշ ճանապարհով. նրա հաշիվը կարող է կոտրված լինել։")
                    },
                    color = k.text,
                    style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
                )
            }

            Text(
                text = tr("Սա ավտոմատ ստուգում է, այն կարող է սխալվել։ Եթե ոչինչ չի գտնվել, դա դեռ չի նշանակում, որ նամակն անվտանգ է։ Ամբողջ ստուգումը կատարվում է քո հեռախոսում։"),
                color = k.textSecondary,
                style = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
            )

            Spacer(Modifier.height(Space.xs))
            if (canBlock) {
                SoftButton(
                    text = tr("Արգելափակել"),
                    onClick = {
                        onBlock()
                        onClose()
                    },
                    danger = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            SoftButton(
                text = tr("Բողոքել"),
                onClick = {
                    try {
                        val send = Intent(Intent.ACTION_SEND)
                        send.type = "text/plain"
                        send.putExtra(Intent.EXTRA_TEXT, reportText(fromName, ts, text, report))
                        val chooser = Intent.createChooser(send, tr("Բողոքել"))
                        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(chooser)
                    } catch (e: Exception) {
                        // no app to share with: nothing to do
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = tr("«Բողոքել»-ը պատրաստում է բողոքի տեքստը, և դու ինքդ ընտրում ես՝ որտեղ ուղարկել (օրինակ՝ քո փոստին)։ KHOSQ-ը ոչինչ ինքնաբերաբար չի ուղարկում։"),
                color = k.textSecondary,
                style = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
            )
            SoftButton(text = tr("Փակել"), onClick = onClose, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(Space.s))
        }
    }
}

package am.khosq.app.ui

import am.khosq.app.data.TranscribeLogic
import am.khosq.app.data.TranscribeResult
import am.khosq.app.push.TranscribeClient
import am.khosq.app.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** The name of a language choice of the transcription sheet: "auto" or one of the three. */
private fun sttLangName(code: String): String = if (code == "auto") tr("Ավտոմատ") else langName(code)

/**
 * Writes down the words of one voice note. Nothing is sent before the person has allowed it (the first time this sheet shows
 * what is sent and where); after that only the sound of this one note goes to the KHOSQ server, when the sheet is open.
 * Nothing is saved: the words are shown here and can be copied or translated, and are forgotten when the app closes.
 */
@Composable
fun TranscribeSheet(vm: AppViewModel, messageId: String, audio: ByteArray, onClose: () -> Unit, onTranslate: (String) -> Unit) {
    val k = KTheme.colors
    val on by vm.transcribeOn.collectAsStateWithLifecycle()
    var lang by remember { mutableStateOf(TranscribeLogic.DEFAULT_LANG) }
    var result by remember { mutableStateOf<TranscribeResult?>(null) }
    var error by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val fits = TranscribeLogic.fits(audio)
    val clip = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(on, lang, attempt) {
        result = null
        error = ""
        detail = ""
        copied = false
        loading = false
        if (!on) return@LaunchedEffect
        if (!fits) {
            error = tr("Ձայնային նամակը չափազանց մեծ է տառադարձելու համար")
            return@LaunchedEffect
        }
        loading = true
        try {
            result = vm.transcribeVoice(messageId, audio, lang)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = TranscribeClient.explain(e)
            detail = TranscribeClient.detail(e)
        }
        loading = false
    }

    KSheet(onDismiss = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = KIcons.Transcribe, contentDescription = null, tint = k.accent, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(Space.s))
                Text(text = tr("Տառադարձում"), color = k.text, style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold))
            }

            if (!on) {
                Text(
                    text = tr("Տառադարձելու համար միայն այս մեկ ձայնային նամակի ձայնը կուղարկվի KHOSQ-ի սերվեր և այնտեղից՝ Cloudflare AI. այդ պահին այն չգաղտնագրված է։ KHOSQ-ի սերվերը ձայնը և տեքստը չի պահում և չի գրանցում։ Մյուս նամակները չեն ուղարկվում, և ոչինչ չի տառադարձվում առանց քո բացելու այս պատուհանը։ Եթե ձայնը քո զրուցակցինն է, տառադարձիր միայն եթե դա հարմար է նաև նրան։ Թույլտվությունը կարելի է հանել Կարգավորումներում։"),
                    color = k.text,
                    style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
                )
                Spacer(Modifier.height(Space.xs))
                GradientButton(
                    text = tr("Թույլատրել և տառադարձել"),
                    onClick = { vm.setTranscribeOn(true) },
                    modifier = Modifier.fillMaxWidth(),
                )
                SoftButton(text = tr("Չեղարկել"), onClick = onClose, modifier = Modifier.fillMaxWidth())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    for (code in TranscribeLogic.LANGS) {
                        val sel = code == lang
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(Corner.xl))
                                .background(if (sel) k.accent else k.surfaceHigh)
                                .clickable { lang = code }
                                .padding(horizontal = Space.m, vertical = Space.s),
                        ) {
                            Text(
                                text = sttLangName(code),
                                color = if (sel) k.onAccent else k.text,
                                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                            )
                        }
                    }
                }
                val r = result
                when {
                    loading -> Text(text = tr("Տառադարձում եմ…"), color = k.textSecondary, style = TextStyle(fontSize = 15.sp))
                    error.isNotEmpty() -> {
                        Text(text = error, color = k.danger, style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp))
                        if (detail.isNotEmpty()) {
                            Text(text = detail, color = k.textTertiary, style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp))
                        }
                        if (fits) SoftButton(text = tr("Կրկնել"), onClick = { attempt++ }, modifier = Modifier.fillMaxWidth())
                    }
                    r != null && r.empty -> {
                        Text(
                            text = tr("Խոսք չի գտնվել. փորձիր նշել լեզուն"),
                            color = k.textSecondary,
                            style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
                        )
                    }
                    r != null -> {
                        Text(
                            text = r.text,
                            color = k.text,
                            style = TextStyle(fontSize = 17.sp, lineHeight = 24.sp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Corner.m))
                                .background(k.accentSoft)
                                .padding(Space.m),
                        )
                        SoftButton(
                            text = if (copied) tr("Պատճենվեց") else tr("Պատճենել"),
                            onClick = {
                                clip.setText(AnnotatedString(r.text))
                                copied = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SoftButton(text = tr("Թարգմանել"), onClick = { onTranslate(r.text) }, modifier = Modifier.fillMaxWidth())
                        Text(
                            text = tr("Ավտոմատ տառադարձումը կարող է սխալներ պարունակել, հատկապես հայերենի դեպքում։"),
                            color = k.textSecondary,
                            style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                        )
                        if (r.via.isNotEmpty()) {
                            Text(text = tr("Տառադարձիչ՝") + " " + viaName(r.via), color = k.textTertiary, style = TextStyle(fontSize = 12.sp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(Space.s))
        }
    }
}

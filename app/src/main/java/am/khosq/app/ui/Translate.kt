package am.khosq.app.ui

import am.khosq.app.Lang
import am.khosq.app.data.TranslateLogic
import am.khosq.app.data.TranslateResult
import am.khosq.app.push.TranslateClient
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** The name of a language, in the language of the app. */
internal fun langName(code: String): String = when (code) {
    "hy" -> tr("Հայերեն")
    "ru" -> tr("Ռուսերեն")
    "en" -> tr("Անգլերեն")
    else -> code
}

/** "Translated by …" in small print: which model answered, so a wrong answer can be traced. */
internal fun viaName(via: String): String = when (via) {
    "gemma" -> "Gemma"
    "m2m100" -> "M2M100"
    else -> via
}

/**
 * Translates one message. Nothing is sent before the person has allowed translation (the first time this sheet shows
 * what is sent and where); after that only this one text goes to the KHOSQ server, when a language is shown.
 */
@Composable
fun TranslateSheet(vm: AppViewModel, text: String, onClose: () -> Unit) {
    val k = KTheme.colors
    val on by vm.translateOn.collectAsStateWithLifecycle()
    var to by remember { mutableStateOf(TranslateLogic.defaultTarget(text, Lang.code)) }
    var result by remember { mutableStateOf<TranslateResult?>(null) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val fits = TranslateLogic.fits(text)
    val clip = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(on, to, attempt) {
        result = null
        error = ""
        copied = false
        loading = false
        if (!on) return@LaunchedEffect
        if (!fits) {
            error = tr("Տեքստը չափազանց երկար է թարգմանելու համար")
            return@LaunchedEffect
        }
        loading = true
        try {
            result = vm.translateText(text, to)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = TranslateClient.explain(e)
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
                Icon(imageVector = KIcons.Translate, contentDescription = null, tint = k.accent, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(Space.s))
                Text(text = tr("Թարգմանություն"), color = k.text, style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold))
            }

            if (!on) {
                Text(
                    text = tr("Թարգմանելու համար միայն այս մեկ նամակի տեքստը կուղարկվի KHOSQ-ի սերվեր և այնտեղից՝ Cloudflare AI. այդ պահին այն չգաղտնագրված է։ KHOSQ-ի սերվերը այն չի պահում և չի գրանցում։ Մյուս նամակները չեն ուղարկվում, և ոչինչ չի թարգմանվում առանց քո սեղմելու «Թարգմանել»։ Թույլտվությունը կարելի է հանել Կարգավորումներում։"),
                    color = k.text,
                    style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
                )
                Spacer(Modifier.height(Space.xs))
                GradientButton(
                    text = tr("Թույլատրել և թարգմանել"),
                    onClick = { vm.setTranslateOn(true) },
                    modifier = Modifier.fillMaxWidth(),
                )
                SoftButton(text = tr("Չեղարկել"), onClick = onClose, modifier = Modifier.fillMaxWidth())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    for (code in TranslateLogic.LANGS) {
                        val sel = code == to
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(Corner.xl))
                                .background(if (sel) k.accent else k.surfaceHigh)
                                .clickable { to = code }
                                .padding(horizontal = Space.m, vertical = Space.s),
                        ) {
                            Text(
                                text = langName(code),
                                color = if (sel) k.onAccent else k.text,
                                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                            )
                        }
                    }
                }
                Text(
                    text = text,
                    color = k.textSecondary,
                    style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Corner.m))
                        .background(k.surfaceHigh)
                        .padding(Space.m),
                )
                val r = result
                when {
                    loading -> Text(text = tr("Թարգմանում եմ…"), color = k.textSecondary, style = TextStyle(fontSize = 15.sp))
                    error.isNotEmpty() -> {
                        Text(text = error, color = k.danger, style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp))
                        if (fits) SoftButton(text = tr("Կրկնել"), onClick = { attempt++ }, modifier = Modifier.fillMaxWidth())
                    }
                    r != null -> {
                        if (r.same) {
                            Text(text = tr("Տեքստն արդեն այս լեզվով է"), color = k.textSecondary, style = TextStyle(fontSize = 14.sp))
                        } else {
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
                            Text(
                                text = tr("Ավտոմատ թարգմանությունը կարող է սխալներ պարունակել։"),
                                color = k.textSecondary,
                                style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                            )
                            if (r.via.isNotEmpty()) {
                                Text(text = tr("Թարգմանիչ՝") + " " + viaName(r.via), color = k.textTertiary, style = TextStyle(fontSize = 12.sp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(Space.s))
        }
    }
}

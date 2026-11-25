package am.khosq.app.ui

import am.khosq.app.data.Chat
import am.khosq.app.data.CircleLogic
import am.khosq.app.data.Message
import am.khosq.app.data.Profile
import am.khosq.app.data.SosCodec
import am.khosq.app.data.SosReason
import am.khosq.app.data.SosTimer
import am.khosq.app.data.TrailLogic
import am.khosq.app.sos.SosEngine
import am.khosq.app.tr
import am.khosq.app.trf
import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlinx.coroutines.delay

/** How long the SOS button must be held. */
private const val HOLD_MS = 1500

/** After the hold, this many seconds to change one's mind. */
private const val COUNTDOWN_S = 5

private fun openUrl(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(ctx, tr("Քարտեզի ծրագիր չի գտնվել"), Toast.LENGTH_SHORT).show()
    }
}

/** The dial pad with 112 typed in: the person still has to press "call" (the app never calls by itself). */
private fun dial112(ctx: Context) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(ctx, tr("Զանգի ծրագիր չի գտնվել"), Toast.LENGTH_SHORT).show()
    }
}

/** The people I can put in the circle: accepted one-to-one chats (not groups, not my saved messages). */
internal fun circleCandidates(chats: List<Chat>): List<Chat> =
    chats.filter { c -> !c.isGroup && !c.saved && c.status == "active" && c.peerUid.isNotEmpty() }

// ------------------------------------------------------------------------------------------------ the hub

/** The SOS hub: the circle, the SOS button, the timer. */
@Composable
fun SosHubSheet(me: Profile, chats: List<Chat>, onClose: () -> Unit) {
    val k = KTheme.colors
    val ctx = LocalContext.current
    val rec by SosEngine.rec.collectAsStateWithLifecycle()
    val circle by SosEngine.circle.collectAsStateWithLifecycle()
    val lastPoint by SosEngine.lastPoint.collectAsStateWithLifecycle()
    val lastUpdate by SosEngine.lastUpdate.collectAsStateWithLifecycle()
    val status by SosEngine.status.collectAsStateWithLifecycle()
    val placeCount by SosEngine.pointCount.collectAsStateWithLifecycle()
    val people = remember(chats) { circleCandidates(chats) }
    val active = remember(people) { people.map { c -> c.peerUid }.toSet() }
    val members = remember(circle, active) { CircleLogic.usable(circle, active) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(500L)
        }
    }

    var showPicker by remember { mutableStateOf(false) }
    var showConsent by remember { mutableStateOf(false) }
    var pendingTimer by remember { mutableStateOf(false) }
    var minutes by remember { mutableStateOf(30) }
    var counting by remember { mutableStateOf(false) }
    var left by remember { mutableStateOf(COUNTDOWN_S) }
    var denied by remember { mutableStateOf(false) }
    var consented by remember { mutableStateOf(SosEngine.consented()) }

    fun startTimerNow() {
        denied = false
        if (!SosEngine.startTimer(ctx, minutes, me)) Toast.makeText(ctx, tr("Ժամանակաչափը չսկսվեց"), Toast.LENGTH_SHORT).show()
    }

    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        if (OneShotLocation.hasPermission(ctx)) {
            if (pendingTimer) startTimerNow()
        } else {
            denied = true
        }
        pendingTimer = false
    }

    fun onStartTimer() {
        if (members.isEmpty()) return
        if (!consented) {
            pendingTimer = true
            showConsent = true
            return
        }
        if (!OneShotLocation.hasPermission(ctx)) {
            pendingTimer = true
            askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        startTimerNow()
    }

    // the five seconds after the hold: the SOS goes out unless the person cancels (or leaves this sheet)
    LaunchedEffect(counting) {
        if (counting) {
            left = COUNTDOWN_S
            while (left > 0) {
                delay(1000L)
                left -= 1
            }
            counting = false
            if (!SosEngine.sosNow(ctx, me, OneShotLocation.hasPermission(ctx))) {
                Toast.makeText(ctx, tr("SOS-ը չուղարկվեց"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (showConsent) {
        ConsentDialog(
            onAgree = {
                SosEngine.setConsent(true)
                consented = true
                showConsent = false
                if (pendingTimer) {
                    if (OneShotLocation.hasPermission(ctx)) {
                        pendingTimer = false
                        startTimerNow()
                    } else {
                        askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                }
            },
            onCancel = {
                showConsent = false
                pendingTimer = false
            },
        )
    }
    if (showPicker) {
        CirclePickerSheet(people = people, selected = members, onClose = { showPicker = false })
    }

    KSheet(onDismiss = onClose) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = KIcons.Alert, contentDescription = null, tint = k.danger, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(Space.s))
                Text(text = tr("SOS և վստահելի շրջան"), color = k.text, style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold))
            }
            Text(
                text = tr("Մինչև 5 մարդ, ում կուղարկվի քո SOS-ը՝ գաղտնագրված նամակով, քո վայրով և ուղով։"),
                color = k.textSecondary,
                style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
            )

            // ---- the circle
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Corner.l)).background(k.surfaceHigh).padding(Space.l),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = KIcons.Group, contentDescription = null, tint = k.textSecondary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(Space.xs))
                    Text(
                        text = trf("Շրջանը՝ {0} / {1}", members.size.toString(), CircleLogic.MAX.toString()),
                        color = k.textSecondary,
                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                    )
                }
                if (members.isEmpty()) {
                    Text(
                        text = tr("Շրջանում դեռ ոչ ոք չկա։ Ընտրիր մարդկանց, ում վստահում ես։"),
                        color = k.text,
                        style = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
                    )
                } else {
                    for (id in members) {
                        val c = people.firstOrNull { x -> x.peerUid == id }
                        if (c != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Avatar(name = c.peerName, seed = c.peerUid, size = 32.dp)
                                Spacer(Modifier.width(Space.m))
                                Text(
                                    text = c.peerName,
                                    color = k.text,
                                    style = TextStyle(fontSize = 16.sp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                val v = rememberVerifyState(c.peerUid)
                                Spacer(Modifier.width(Space.xs))
                                VerifyBadge(state = v, size = 16.dp)
                            }
                        }
                    }
                }
                SoftButton(
                    text = if (members.isEmpty()) tr("Ընտրել մարդկանց") else tr("Փոխել շրջանը"),
                    onClick = { showPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = rec == null,
                )
                if (rec != null) {
                    Text(
                        text = tr("Շրջանը չի կարելի փոխել, մինչև ժամանակաչափը կամ SOS-ը աշխատում է։"),
                        color = k.textSecondary,
                        style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
                    )
                }
            }

            // ---- the SOS button
            val r = rec
            val phase = if (r == null) null else SosTimer.phase(r, now)
            val live = phase == SosTimer.Phase.LIVE || phase == SosTimer.Phase.DUE
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Corner.l)).background(k.surfaceHigh).padding(Space.l),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                if (live) {
                    LiveCard(
                        reasonButton = r?.reason == SosReason.BUTTON,
                        sentAt = r?.firedAt ?: 0L,
                        lastUpdate = lastUpdate,
                        placeCount = placeCount,
                        now = now,
                        sent = status.sent,
                        queued = status.queued,
                        failed = status.failed,
                    )
                    GradientButton(text = tr("Լավ եմ"), onClick = { okNow(ctx) }, modifier = Modifier.fillMaxWidth())
                } else if (counting) {
                    Text(text = left.toString(), color = k.danger, style = TextStyle(fontSize = 64.sp, fontWeight = FontWeight.Bold))
                    Text(
                        text = tr("SOS-ը կուղարկվի շրջանին։"),
                        color = k.text,
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                        textAlign = TextAlign.Center,
                    )
                    SoftButton(text = tr("Չեղարկել"), onClick = { counting = false }, modifier = Modifier.fillMaxWidth())
                } else {
                    HoldSosButton(enabled = members.isNotEmpty() && consented) {
                        counting = true
                    }
                    Text(
                        text = when {
                            members.isEmpty() -> tr("Նախ ընտրիր շրջանի մարդկանց։")
                            !consented -> tr("Սեղմիր «Ինչպես է աշխատում» և համաձայնիր։")
                            else -> tr("Սեղմիր և պահիր՝ SOS ուղարկելու համար։")
                        },
                        color = k.textSecondary,
                        style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                        textAlign = TextAlign.Center,
                    )
                    if (!consented) {
                        SoftButton(text = tr("Ինչպես է աշխատում"), onClick = { showConsent = true }, modifier = Modifier.fillMaxWidth())
                    }
                    if (rec == null && (status.sent + status.queued + status.failed) > 0) {
                        Text(
                            text = trf("Վերջին ուղարկումը՝ հասավ {0}, սպասում է ցանցին {1}, չստացվեց {2}", status.sent.toString(), status.queued.toString(), status.failed.toString()),
                            color = k.textSecondary,
                            style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            // ---- the timer
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Corner.l)).background(k.surfaceHigh).padding(Space.l),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                Text(text = tr("Ժամանակաչափ՝ «Կգրեմ, որ ամեն ինչ լավ է»"), color = k.textSecondary, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
                if (r != null && (phase == SosTimer.Phase.RUNNING || phase == SosTimer.Phase.WARNING)) {
                    val warn = phase == SosTimer.Phase.WARNING
                    Text(
                        text = SosTimer.clock(SosTimer.remainingMs(r, now)),
                        color = if (warn) k.danger else k.text,
                        style = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text = if (warn) {
                            tr("Սեղմիր «Լավ եմ», այլապես շրջանին SOS կուղարկվի։")
                        } else {
                            tr("Քո տեղաշարժը գրանցվում է միայն քո հեռախոսում։ Եթե ժամանակին «Լավ եմ» չասես, շրջանը կստանա քո վայրը և ուղին։")
                        },
                        color = if (warn) k.danger else k.textSecondary,
                        style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                    )
                    val p = lastPoint
                    Text(
                        text = if (p == null) tr("Վայրը դեռ որոշվում է…") else trf("Գրանցված վայրեր՝ {0}", placeCount.toString()),
                        color = k.textSecondary,
                        style = TextStyle(fontSize = 12.sp),
                    )
                    GradientButton(text = tr("Լավ եմ"), onClick = { okNow(ctx) }, modifier = Modifier.fillMaxWidth())
                    SoftButton(text = tr("Չեղարկել ժամանակաչափը"), onClick = { cancelNow(ctx) }, modifier = Modifier.fillMaxWidth())
                } else if (r == null) {
                    Text(
                        text = tr("Ընտրիր ժամանակը։ Սկսելուց անմիջապես հիշվում է քո վայրը, և տեղաշարժդ գրանցվում է։"),
                        color = k.textSecondary,
                        style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                    )
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        for (m in SosTimer.PRESETS) {
                            val on = m == minutes
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(Corner.m))
                                    .background(if (on) k.accent else k.surface)
                                    .clickable { minutes = m }
                                    .padding(horizontal = Space.l, vertical = Space.s),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = minutesText(m),
                                    color = if (on) Color.White else k.text,
                                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                                )
                            }
                        }
                    }
                    GradientButton(
                        text = trf("Սկսել՝ {0}", minutesText(minutes)),
                        onClick = { onStartTimer() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = members.isNotEmpty(),
                    )
                    if (members.isEmpty()) {
                        Text(text = tr("Նախ ընտրիր շրջանի մարդկանց։"), color = k.textSecondary, style = TextStyle(fontSize = 12.sp))
                    }
                    if (denied) {
                        Text(
                            text = tr("Ժամանակաչափի համար պետք է տեղադրության թույլտվություն։ Թույլատրիր այն Android-ի Կարգավորումներում՝ Հավելվածներ → KHOSQ → Թույլտվություններ։"),
                            color = k.danger,
                            style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                        )
                    }
                } else {
                    Text(text = tr("SOS-ը արդեն ուղարկված է։"), color = k.textSecondary, style = TextStyle(fontSize = 13.sp))
                }
            }

            // ---- always
            SoftButton(text = tr("Զանգել 112-ին"), onClick = { dial112(ctx) }, modifier = Modifier.fillMaxWidth())
            Text(
                text = tr("SOS-ը չի փոխարինում շտապ օգնությանը։ Եթե հեռախոսն անջատվի կամ ինտերնետ չլինի, նամակը չի գնա։ Կիրառիր 112-ը, երբ կյանքին վտանգ կա։"),
                color = k.textSecondary,
                style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
            )
            if (consented) {
                TextButton(onClick = { showConsent = true }) { Text(tr("Ինչպես է աշխատում")) }
            }
            Spacer(Modifier.height(Space.m))
        }
    }
}

private fun okNow(ctx: Context) {
    Thread {
        try {
            kotlinx.coroutines.runBlocking { SosEngine.ok(ctx) }
        } catch (e: Exception) {
            // the notification button does the same
        }
    }.start()
}

private fun cancelNow(ctx: Context) {
    Thread {
        try {
            kotlinx.coroutines.runBlocking { SosEngine.cancelTimer(ctx) }
        } catch (e: Exception) {
            // the notification button does the same
        }
    }.start()
}

private fun minutesText(m: Int): String = if (m % 60 == 0) trf("{0} ժ", (m / 60).toString()) else trf("{0} ր", m.toString())

/** What is shown once the SOS has gone out: who has it, when the newest places went, how many places there are. */
@Composable
private fun LiveCard(
    reasonButton: Boolean,
    sentAt: Long,
    lastUpdate: Long,
    placeCount: Int,
    now: Long,
    sent: Int,
    queued: Int,
    failed: Int,
) {
    val k = KTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = KIcons.Alert, contentDescription = null, tint = k.danger, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Space.xs))
            Text(text = tr("SOS-ը ուղարկված է"), color = k.danger, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold))
        }
        Text(
            text = if (reasonButton) tr("Սեղմվել է SOS կոճակը։") else tr("Ժամանակաչափը լրացավ առանց պատասխանի։"),
            color = k.text,
            style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
        )
        Text(
            text = trf("Ժամը՝ {0}. հասավ {1}, սպասում է ցանցին {2}, չստացվեց {3}", if (sentAt > 0L) formatTime(sentAt) else "", sent.toString(), queued.toString(), failed.toString()),
            color = k.textSecondary,
            style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
        )
        val ago = if (lastUpdate > 0L) ((now - lastUpdate) / 60_000L).toInt() else -1
        Text(
            text = when {
                ago < 0 -> tr("Նոր վայրերը կուղարկվեն մի քանի րոպեից։")
                ago == 0 -> tr("Շրջանը վերջին անգամ թարմացվել է հենց հիմա։")
                else -> trf("Շրջանը վերջին անգամ թարմացվել է {0} ր առաջ։ Հաջորդը՝ ամեն 2 րոպեն մեկ։", ago.toString())
            },
            color = k.textSecondary,
            style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
        )
        if (placeCount > 0) {
            Text(text = trf("Գրանցված վայրեր՝ {0}", placeCount.toString()), color = k.textSecondary, style = TextStyle(fontSize = 12.sp))
        }
        Text(
            text = tr("Սեղմիր «Լավ եմ», երբ ապահով ես. շրջանը կտեսնի, որ ամեն ինչ լավ է, և գրանցումը կջնջվի։"),
            color = k.text,
            style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
        )
    }
}

/** A big red button that works only when it is held: a quick tap does nothing. */
@Composable
private fun HoldSosButton(enabled: Boolean, onTrigger: () -> Unit) {
    val k = KTheme.colors
    var holding by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(holding) {
        if (holding) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(HOLD_MS, easing = LinearEasing))
            holding = false
            onTrigger()
        } else {
            progress.snapTo(0f)
        }
    }
    Box(
        modifier = Modifier
            .size(150.dp)
            .clip(CircleShape)
            .background(if (enabled) k.danger else k.danger.copy(alpha = 0.35f))
            .pointerInput(enabled) {
                detectTapGestures(
                    onPress = {
                        if (enabled) {
                            holding = true
                            tryAwaitRelease()
                            holding = false
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            progress = { progress.value },
            modifier = Modifier.size(150.dp),
            color = Color.White,
            trackColor = Color.Transparent,
            strokeWidth = 6.dp,
        )
        Text(text = "SOS", color = Color.White, style = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold))
    }
}

/** What the person agrees to before the circle can be used. Plain and complete. */
@Composable
private fun ConsentDialog(onAgree: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(tr("Ինչպես է աշխատում SOS-ը")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(tr("Երբ սկսես ժամանակաչափը կամ սեղմես SOS, հավելվածը սկսում է գրանցել քո տեղաշարժը։ Գրանցումը մնում է միայն քո հեռախոսում։"))
                Text(tr("Եթե ժամանակին «Լավ եմ» չասես, կամ սեղմես SOS, շրջանի մարդկանց կուղարկվի քո վայրը և մինչ այդ անցած ուղին՝ գաղտնագրված նամակով։ Google-ի ծանուցման մեջ երևում է միայն «SOS» բառը, առանց վայրի։"))
                Text(tr("Դրանից հետո նրանք ամեն 2 րոպեն մեկ ստանում են քո նոր վայրը՝ մինչև դու սեղմես «Լավ եմ» (առավելագույնը 3 ժամ)։"))
                Text(tr("Գրանցման ողջ ընթացքում հեռախոսում ծանուցում է երևում։ «Լավ եմ»-ից հետո գրանցածը ջնջվում է, իսկ եթե SOS արդեն ուղարկվել էր, քարտը նշվում է որպես ավարտված։"))
                Text(tr("Սա չի փոխարինում 112-ին. եթե հեռախոսն անջատվի կամ ինտերնետ չլինի, նամակը չի գնա։"))
            }
        },
        confirmButton = { TextButton(onClick = onAgree) { Text(tr("Համաձայն եմ")) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(tr("Չեղարկել")) } },
    )
}

// ------------------------------------------------------------------------------------------------ the circle

/** Choose up to five of my contacts. Every tap is saved at once. */
@Composable
private fun CirclePickerSheet(people: List<Chat>, selected: List<String>, onClose: () -> Unit) {
    val k = KTheme.colors
    val active = remember(people) { people.map { c -> c.peerUid }.toSet() }
    KSheet(onDismiss = onClose) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Text(text = tr("Ովքե՞ր են քո շրջանում"), color = k.text, style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold))
            Text(
                text = trf("Ընտրված՝ {0} / {1}. Ընտրիր մարդկանց, ում վստահում ես և ովքեր կարող են արագ արձագանքել։", selected.size.toString(), CircleLogic.MAX.toString()),
                color = k.textSecondary,
                style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
            )
            Spacer(Modifier.height(Space.xs))
            if (people.isEmpty()) {
                Text(text = tr("Դեռ կոնտակտներ չկան։"), color = k.textSecondary, style = TextStyle(fontSize = 15.sp))
            }
            for (c in people) {
                val on = c.peerUid in selected
                val full = selected.size >= CircleLogic.MAX && !on
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Corner.m))
                        .clickable(enabled = !full) {
                            SosEngine.setCircle(CircleLogic.usable(CircleLogic.toggle(selected, c.peerUid), active))
                        }
                        .padding(vertical = Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(name = c.peerName, seed = c.peerUid, size = 40.dp)
                    Spacer(Modifier.width(Space.m))
                    Text(
                        text = c.peerName,
                        color = if (full) k.textSecondary else k.text,
                        style = TextStyle(fontSize = 16.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    val v = rememberVerifyState(c.peerUid)
                    Spacer(Modifier.width(Space.xs))
                    VerifyBadge(state = v, size = 16.dp)
                    Spacer(Modifier.weight(1f))
                    Checkbox(
                        checked = on,
                        enabled = !full,
                        onCheckedChange = { SosEngine.setCircle(CircleLogic.usable(CircleLogic.toggle(selected, c.peerUid), active)) },
                    )
                }
            }
            Spacer(Modifier.height(Space.s))
            GradientButton(text = tr("Պատրաստ է"), onClick = onClose, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(Space.m))
        }
    }
}

// ------------------------------------------------------------------------------------------------ the card in a chat

/** The body of an SOS message: who needs help, since when, where they were last, and buttons that open the maps app. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SosBody(m: Message, mine: Boolean, peerName: String, ink: Color, soft: Color, onLong: () -> Unit) {
    val k = KTheme.colors
    val ctx = LocalContext.current
    val info = remember(m.text) { SosCodec.decode(m.text) }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    val tone = if (info != null && info.ended) Color(0xFF2E9E5B) else if (mine) ink else k.danger
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(Corner.m))
            .background(if (mine) Color(0x26FFFFFF) else k.danger.copy(alpha = 0.12f))
            .combinedClickable(onClick = {}, onLongClick = onLong)
            .padding(horizontal = Space.m, vertical = Space.s),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (info == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = KIcons.Alert, contentDescription = null, tint = tone, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Space.s))
                Text(text = "SOS", color = ink, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold))
            }
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = KIcons.Alert, contentDescription = null, tint = tone, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Space.s))
            Text(
                text = when {
                    info.ended -> tr("SOS-ը ավարտվեց. ամեն ինչ լավ է")
                    mine -> tr("Դու ուղարկեցիր SOS")
                    else -> trf("{0}-ը օգնության կարիք ունի", peerName)
                },
                color = ink,
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold),
            )
        }
        Text(
            text = (if (info.reason == SosReason.BUTTON) tr("Սեղմվել է SOS կոճակը") else trf("Չի պատասխանել {0} րոպեի ընթացքում", info.minutes.toString())) +
                (if (info.firedAt > 0L) " · " + formatTime(info.firedAt) else ""),
            color = soft,
            style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
        )
        val last = info.points.lastOrNull()
        if (last == null) {
            Text(text = tr("Վայրը հայտնի չէ"), color = soft, style = TextStyle(fontSize = 13.sp))
        } else {
            val mins = ((now / 1000L - last.t) / 60L).coerceAtLeast(0L).toInt()
            Text(
                text = String.format(Locale.US, "%.5f, %.5f", last.lat, last.lng),
                color = soft,
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            )
            Text(
                text = (if (mins < 1) tr("Վերջին վայրը՝ հենց հիմա") else trf("Վերջին վայրը՝ {0} ր առաջ", mins.toString())) +
                    (if (info.points.size > 1) " · " + trf("{0} վայր", info.points.size.toString()) else ""),
                color = soft,
                style = TextStyle(fontSize = 12.sp),
            )
            Spacer(Modifier.height(2.dp))
            val link = if (mine) Color(0xFFE3E8FF) else k.accent
            Text(
                text = tr("Բացել վերջին վայրը քարտեզում"),
                color = link,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.clickable { openInMaps(ctx, last.lat, last.lng) }.padding(vertical = 2.dp),
            )
            val route = TrailLogic.routeUrl(info.points)
            if (route != null) {
                Text(
                    text = tr("Բացել ուղին քարտեզում (մոտավոր)"),
                    color = link,
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.clickable { openUrl(ctx, route) }.padding(vertical = 2.dp),
                )
            }
        }
    }
}

package am.khosq.app.ui

import am.khosq.app.AppLock
import am.khosq.app.DeviceLock
import am.khosq.app.crypto.E2E
import am.khosq.app.data.AppLockLogic
import am.khosq.app.data.Chat
import am.khosq.app.data.CircleLogic
import am.khosq.app.data.Profile
import am.khosq.app.data.SecInput
import am.khosq.app.data.SecLevel
import am.khosq.app.data.SecPoint
import am.khosq.app.data.SecurityLogic
import am.khosq.app.data.VerifyState
import am.khosq.app.sos.SosEngine
import am.khosq.app.tr
import am.khosq.app.trf
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private fun openPhoneSecurity(ctx: Context) {
    try {
        ctx.startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS))
    } catch (e: Exception) {
        Toast.makeText(ctx, tr("Կարգավորումների էկրանը չբացվեց"), Toast.LENGTH_SHORT).show()
    }
}

private fun askBatteryFree(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)),
        )
    } catch (e: Exception) {
        Toast.makeText(ctx, tr("Կարգավորումների էկրանը չբացվեց"), Toast.LENGTH_SHORT).show()
    }
}

// ------------------------------------------------------------------------------------------------ the lock screen

/** What the person sees while the app is locked: nothing of the app, only a button that asks for the phone's unlock. */
@Composable
fun LockScreen() {
    val k = KTheme.colors
    val ctx = LocalContext.current
    var failed by remember { mutableStateOf(false) }
    val title = tr("KHOSQ-ի կողպ")
    val text = tr("Ապակողպիր հեռախոսի կողպով, որ բացես KHOSQ-ը։")

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        AppLock.busy = false
        if (r.resultCode == DeviceLock.OK) AppLock.unlocked() else failed = true
    }

    fun ask() {
        val i = DeviceLock.intent(ctx, title, text)
        if (i == null) {
            // the phone has no lock any more: there is nothing to ask, and the app must not stay shut for ever
            AppLock.setEnabled(false)
            AppLock.unlocked()
            return
        }
        failed = false
        AppLock.busy = true
        try {
            launcher.launch(i)
        } catch (e: Exception) {
            AppLock.busy = false
            failed = true
        }
    }

    // the unlock screen comes up by itself, once; if it is cancelled the button asks again
    LaunchedEffect(Unit) { ask() }

    Box(Modifier.fillMaxSize().background(backgroundBrush()), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.huge),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Box(
                modifier = Modifier.size(84.dp).clip(CircleShape).background(k.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(imageVector = KIcons.Lock, contentDescription = null, tint = k.accent, modifier = Modifier.size(40.dp))
            }
            Text(text = "KHOSQ", color = k.text, style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold))
            Text(
                text = tr("Հավելվածը կողպված է"),
                color = k.textSecondary,
                style = TextStyle(fontSize = 16.sp),
                textAlign = TextAlign.Center,
            )
            if (failed) {
                Text(
                    text = tr("Չապակողպվեց կամ չեղարկվեց։ Փորձիր նորից։"),
                    color = k.danger,
                    style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
                    textAlign = TextAlign.Center,
                )
            }
            GradientButton(text = tr("Ապակողպել"), onClick = { ask() }, modifier = Modifier.fillMaxWidth())
        }
    }
}

// ------------------------------------------------------------------------------------------------ the security centre

/** One point of the list: a mark (done / to do / only a note), the title, a line of explanation and, if tapping helps, the way to do it. */
@Composable
private fun PointRow(level: SecLevel, title: String, subtitle: String, warn: Boolean = false, onClick: (() -> Unit)? = null) {
    val k = KTheme.colors
    val tone = when {
        warn -> k.danger
        level == SecLevel.GOOD -> k.success
        level == SecLevel.TODO -> k.warning
        else -> k.textTertiary
    }
    val icon: ImageVector = when {
        warn -> KIcons.Alert
        level == SecLevel.GOOD -> KIcons.Check
        level == SecLevel.TODO -> KIcons.Alert
        else -> KIcons.Info
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null && level == SecLevel.TODO) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(32.dp).clip(CircleShape).background(tone.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tone, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(text = title, color = k.text, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
            Text(text = subtitle, color = k.textSecondary, style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp))
        }
        if (onClick != null && level == SecLevel.TODO) {
            Spacer(Modifier.width(Space.s))
            Icon(imageVector = KIcons.ChevronRight, contentDescription = null, tint = k.textTertiary, modifier = Modifier.size(18.dp))
        }
    }
}

/** The security centre: what is protected, what is not yet, and the switches that protect more. */
@Composable
fun SecurityCenterSheet(vm: AppViewModel, me: Profile, chats: List<Chat>, onSos: () -> Unit, onClose: () -> Unit) {
    val k = KTheme.colors
    val ctx = LocalContext.current
    val lockOn by AppLock.enabled.collectAsStateWithLifecycle()
    val grace by AppLock.grace.collectAsStateWithLifecycle()
    val guard by AppLock.screenGuard.collectAsStateWithLifecycle()
    val hide by AppLock.hideText.collectAsStateWithLifecycle()
    val shieldOn by vm.shieldOn.collectAsStateWithLifecycle()
    val circle by SosEngine.circle.collectAsStateWithLifecycle()
    val epoch by E2E.verifyEpoch.collectAsStateWithLifecycle()

    // the phone's own settings can change while this sheet is open (the person goes there and comes back)
    var tick by remember { mutableStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val watcher = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick += 1 }
        owner.lifecycle.addObserver(watcher)
        onDispose { owner.lifecycle.removeObserver(watcher) }
    }
    val phoneLock = remember(tick) { DeviceLock.secure(ctx) }
    val batteryFree = remember(tick) {
        (ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isIgnoringBatteryOptimizations(ctx.packageName) == true
    }

    val people = remember(chats) { circleCandidates(chats).filter { c -> c.blockedBy.isEmpty() } }
    val active = remember(people) { people.map { c -> c.peerUid }.toSet() }
    val circleCount = remember(circle, active) { CircleLogic.usable(circle, active).size }
    val states = remember(people, epoch) { people.associate { c -> c.peerUid to E2E.verifyState(c.peerUid) } }
    val verified = states.values.count { s -> s == VerifyState.VERIFIED }
    val changedPeople = people.filter { c -> states[c.peerUid] == VerifyState.CHANGED }
    val report = remember(phoneLock, lockOn, shieldOn, circleCount, people.size, verified, changedPeople.size, batteryFree) {
        SecurityLogic.report(
            SecInput(
                phoneLock = phoneLock,
                appLock = lockOn,
                shield = shieldOn,
                circleCount = circleCount,
                contacts = people.size,
                verified = verified,
                changed = changedPeople.size,
                batteryFree = batteryFree,
            ),
        )
    }
    val blockedByMe = remember(chats) {
        chats.filter { c -> !c.isGroup && !c.saved && c.peerUid.isNotEmpty() && me.uid in c.blockedBy }
    }

    // turning the lock on or off asks for the phone's unlock first, so nobody can lock themselves out or switch it off for the person
    var target by remember { mutableStateOf(false) }
    val asker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        AppLock.busy = false
        if (r.resultCode == DeviceLock.OK) AppLock.setEnabled(target)
    }
    val askTitle = tr("KHOSQ-ի կողպ")
    val askText = tr("Հաստատիր հեռախոսի կողպով։")
    fun changeLock(on: Boolean) {
        val i = DeviceLock.intent(ctx, askTitle, askText)
        if (i == null) {
            Toast.makeText(ctx, tr("Նախ հեռախոսում դիր էկրանի կողպ (PIN, նախշ կամ գաղտնաբառ)"), Toast.LENGTH_LONG).show()
            return
        }
        target = on
        AppLock.busy = true
        try {
            asker.launch(i)
        } catch (e: Exception) {
            AppLock.busy = false
        }
    }

    KSheet(onDismiss = onClose) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = KIcons.Shield, contentDescription = null, tint = k.accent, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(Space.s))
                Text(text = tr("Անվտանգության կենտրոն"), color = k.text, style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold))
            }

            // ---- the state
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Corner.l)).background(k.surfaceHigh).padding(Space.l),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                Text(
                    text = trf("Կատարված է՝ {0} / {1}", report.good.toString(), report.total.toString()),
                    color = k.text,
                    style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                )
                LinearProgressIndicator(
                    progress = { if (report.total <= 0) 0f else report.good / report.total.toFloat() },
                    color = if (report.good >= report.total) k.success else k.accent,
                    trackColor = k.surfaceHighest,
                    modifier = Modifier.fillMaxWidth().padding(vertical = Space.xs),
                )
                for (item in report.items) {
                    when (item.point) {
                        SecPoint.PHONE_LOCK -> PointRow(
                            level = item.level,
                            title = tr("Հեռախոսի էկրանի կողպ"),
                            subtitle = if (phoneLock) {
                                tr("Հեռախոսը կողպված է PIN-ով, նախշով կամ գաղտնաբառով։")
                            } else {
                                tr("Հեռախոսը առանց կողպի է. ով վերցնի, կբացի ամեն ինչ։ Սեղմիր և դիր կողպ։")
                            },
                            onClick = { openPhoneSecurity(ctx) },
                        )
                        SecPoint.APP_LOCK -> PointRow(
                            level = item.level,
                            title = tr("Հավելվածի կողպում"),
                            subtitle = if (item.level == SecLevel.GOOD) {
                                tr("Միացված է. KHOSQ-ը բացելիս հարցվում է հեռախոսի ապակողպումը։")
                            } else {
                                tr("Անջատված է. սեղմիր և միացրու։")
                            },
                            onClick = { if (phoneLock) changeLock(true) else openPhoneSecurity(ctx) },
                        )
                        SecPoint.SHIELD -> PointRow(
                            level = item.level,
                            title = "Trust Shield",
                            subtitle = if (shieldOn) {
                                tr("Միացված է. ստացված նամակները ստուգվում են խաբեության նշանների համար։")
                            } else {
                                tr("Անջատված է. սեղմիր և միացրու։")
                            },
                            onClick = { vm.setShieldOn(true) },
                        )
                        SecPoint.CIRCLE -> PointRow(
                            level = item.level,
                            title = tr("SOS և վստահելի շրջան"),
                            subtitle = if (circleCount > 0) {
                                trf("Շրջանում՝ {0} մարդ։", circleCount.toString())
                            } else {
                                tr("Շրջանը դատարկ է. սեղմիր և ընտրիր մարդկանց, ում կուղարկվի քո SOS-ը։")
                            },
                            onClick = onSos,
                        )
                        SecPoint.VERIFIED -> PointRow(
                            level = item.level,
                            warn = changedPeople.isNotEmpty(),
                            title = tr("Հաստատված կոնտակտներ"),
                            subtitle = when {
                                changedPeople.isNotEmpty() -> trf("{0} հաստատված կոնտակտի անվտանգության թիվը փոխվել է. համեմատիր նկարները նորից։", changedPeople.size.toString())
                                people.isEmpty() -> tr("Դեռ կոնտակտ չկա։")
                                verified > 0 -> trf("Հաստատված՝ {0} / {1}։", verified.toString(), people.size.toString())
                                else -> tr("Ոչ ոք դեռ հաստատված չէ. բացիր կոնտակտի քարտը և համեմատիր 8 նկարը։")
                            },
                        )
                        SecPoint.BATTERY -> PointRow(
                            level = item.level,
                            title = tr("Աշխատանք ֆոնում"),
                            subtitle = if (batteryFree) {
                                tr("Հեռախոսը KHOSQ-ին չի կանգնեցնում մարտկոցը խնայելու համար։")
                            } else {
                                tr("Հեռախոսը կարող է KHOSQ-ը կանգնեցնել. նամակները և SOS ժամանակաչափը կուշանան։ Սեղմիր և թույլ տուր։")
                            },
                            onClick = { askBatteryFree(ctx) },
                        )
                    }
                }
                if (changedPeople.isNotEmpty()) {
                    Text(
                        text = changedPeople.joinToString(", ") { c -> c.peerName },
                        color = k.danger,
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                        modifier = Modifier.padding(start = 44.dp),
                    )
                }
            }

            // ---- the lock of the app
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Corner.l)).background(k.surfaceHigh),
            ) {
                SettingsRow(
                    icon = KIcons.Lock,
                    title = tr("Հավելվածի կողպում"),
                    subtitle = if (lockOn) {
                        tr("KHOSQ-ը բացելիս հարցվում է հեռախոսի ապակողպումը (PIN, նախշ, գաղտնաբառ, շատ հեռախոսներում նաև մատնահետք)։")
                    } else {
                        tr("Եթե ուրիշը վերցնի քո ապակողպված հեռախոսը, KHOSQ-ը չի բացվի առանց ապակողպման։")
                    },
                    trailing = {
                        Switch(checked = lockOn, onCheckedChange = { on -> changeLock(on) }, colors = kSwitchColors())
                    },
                )
                if (lockOn) {
                    Column(Modifier.padding(start = Space.l, end = Space.l, bottom = Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Text(text = tr("Կողպել, երբ հեռանում եմ"), color = k.textSecondary, style = TextStyle(fontSize = 13.sp))
                        Segmented(
                            options = AppLockLogic.GRACE_MINUTES.map { m ->
                                m.toString() to (if (m == 0) tr("Միանգամից") else trf("{0} ր", m.toString()))
                            },
                            selected = grace.toString(),
                        ) { v -> AppLock.setGrace(v.toIntOrNull() ?: AppLockLogic.DEFAULT_GRACE) }
                    }
                }
            }

            // ---- the screen and the notifications
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Corner.l)).background(k.surfaceHigh),
            ) {
                SettingsRow(
                    icon = KIcons.EyeOff,
                    title = tr("Արգելել սքրինշոթը"),
                    subtitle = tr("Սքրինշոթը և էկրանի ձայնագրումը արգելվում են, իսկ «վերջին հավելվածներ»-ում չաթը չի երևում։ Մեկ այլ հեռախոսով էկրանը նկարելը չի կանխվում։"),
                    trailing = {
                        Switch(checked = guard, onCheckedChange = { on -> AppLock.setScreenGuard(on) }, colors = kSwitchColors())
                    },
                )
                KDivider(inset = 62.dp)
                SettingsRow(
                    icon = KIcons.Bell,
                    title = tr("Թաքցնել նամակի տեքստը ծանուցումներում"),
                    subtitle = tr("Ծանուցումը ցույց կտա միայն «Նոր նամակ»։ SOS-ը միշտ երևում է։"),
                    trailing = {
                        Switch(checked = hide, onCheckedChange = { on -> AppLock.setHideText(on) }, colors = kSwitchColors())
                    },
                )
            }

            // ---- people I blocked
            Column(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Corner.l)).background(k.surfaceHigh).padding(Space.l),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                Text(text = tr("Արգելափակված մարդիկ"), color = k.textSecondary, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
                if (blockedByMe.isEmpty()) {
                    Text(text = tr("Դու ոչ ոքի չես արգելափակել։"), color = k.text, style = TextStyle(fontSize = 15.sp))
                } else {
                    for (c in blockedByMe) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(name = c.peerName, seed = c.peerUid, size = 32.dp)
                            Spacer(Modifier.width(Space.m))
                            Text(
                                text = c.peerName,
                                color = k.text,
                                style = TextStyle(fontSize = 16.sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { vm.setBlocked(c.id, false) }) { Text(tr("Ապաարգելափակել")) }
                        }
                    }
                }
            }

            Text(
                text = tr("Սա պարզ ստուգման ցանկ է, ոչ թե երաշխիք. ոչ մի կարգավորում չի պաշտպանում ամեն ինչից։ Նամակների ծածկագրումը միշտ միացված է և չի անջատվում։"),
                color = k.textSecondary,
                style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
                modifier = Modifier.padding(vertical = Space.s),
            )
        }
    }
}

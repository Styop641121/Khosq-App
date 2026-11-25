package am.khosq.app.ui

import am.khosq.app.KeepAliveService
import am.khosq.app.MainActivity
import am.khosq.app.Prefs
import am.khosq.app.R
import am.khosq.app.data.Ago
import am.khosq.app.data.Oem
import am.khosq.app.data.OemLogic
import am.khosq.app.data.RelInput
import am.khosq.app.data.RelLevel
import am.khosq.app.data.RelStep
import am.khosq.app.data.ReliabilityLogic
import am.khosq.app.tr
import am.khosq.app.trf
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.TimeUnit

/** Where the person's marks ("I did this one") are kept, and the times of the last push and the last start of the background service. */
internal object RelStore {
    const val DONE = "rel.done"
    const val ASKED = "rel.asked"
    const val FCM_LAST = "fcm_last"
    const val SERVICE_AT = "svc_at"
}

/** The phone: who made it and which system it runs. Read on the phone only, nothing leaves it. */
internal object PhoneInfo {
    fun oem(): Oem = OemLogic.detect(Build.MANUFACTURER ?: "", Build.BRAND ?: "")

    /** A system property through the phone's own `getprop` (null when it cannot be read). */
    private fun prop(name: String): String? = try {
        val p = ProcessBuilder("getprop", name).redirectErrorStream(true).start()
        val line = p.inputStream.bufferedReader().use { r -> r.readLine() }
        p.waitFor(500, TimeUnit.MILLISECONDS)
        line?.trim()?.ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    /** The name of the maker's system for the screen: HyperOS or MIUI for Xiaomi, otherwise the maker. */
    fun title(oem: Oem): String = when (oem) {
        Oem.XIAOMI -> if (OemLogic.hyperOs(prop("ro.mi.os.version.name") ?: "")) "Xiaomi (HyperOS)" else "Xiaomi (MIUI)"
        Oem.HUAWEI -> "Huawei / Honor"
        Oem.OPPO -> "Oppo / Realme"
        Oem.VIVO -> "Vivo / iQOO"
        Oem.ONEPLUS -> "OnePlus"
        Oem.SAMSUNG -> "Samsung"
        Oem.OTHER -> ""
    }
}

private fun fullScreenOk(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT < 34) return true
    return try {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).canUseFullScreenIntent()
    } catch (e: Exception) {
        true
    }
}

/** Everything the phone can say about notifications right now, together with the person's marks. */
internal fun relInput(ctx: Context): RelInput {
    KeepAliveService.createChannels(ctx)
    val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val on = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    val channelOn = try {
        val ch = nm.getNotificationChannel(KeepAliveService.CH_MSG)
        ch == null || ch.importance != NotificationManager.IMPORTANCE_NONE
    } catch (e: Exception) {
        true
    }
    val battery = (ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isIgnoringBatteryOptimizations(ctx.packageName) == true
    return RelInput(
        sdk = Build.VERSION.SDK_INT,
        oem = PhoneInfo.oem(),
        notificationsOn = on,
        channelOn = channelOn,
        batteryFree = battery,
        fullScreenOk = fullScreenOk(ctx),
        confirmed = ReliabilityLogic.parseConfirmed(Prefs.getString(RelStore.DONE)),
    )
}

/**
 * What the phone says, read again every time the person comes back to the app (they leave to change a setting and return) and every
 * time [tick] changes. The report is made from it with [ReliabilityLogic.plan].
 */
@Composable
internal fun rememberRelInput(tick: Int = 0): State<RelInput> {
    val ctx = LocalContext.current
    val input = remember { mutableStateOf(relInput(ctx)) }
    LaunchedEffect(tick) { input.value = relInput(ctx) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val watcher = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) input.value = relInput(ctx)
        }
        owner.lifecycle.addObserver(watcher)
        onDispose { owner.lifecycle.removeObserver(watcher) }
    }
    return input
}

// ------------------------------------------------------------------------------------------------ opening the phone's screens

private fun appDetails(pkg: String): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))

private fun component(pkg: String, cls: String): Intent = Intent().setComponent(ComponentName(pkg, cls))

/**
 * The screens of the makers' own settings. They are not documented and change between versions of the systems, so every step has
 * several tries, and the last one is always the plain "App info" page of Android, where the person can look for the switch by hand.
 */
private fun makerIntents(oem: Oem, step: RelStep, pkg: String): List<Intent> {
    val out = ArrayList<Intent>()
    when (oem) {
        Oem.XIAOMI -> when (step) {
            RelStep.AUTOSTART -> {
                out.add(component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
                out.add(Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT))
            }
            RelStep.OEM_BATTERY -> {
                out.add(
                    component("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                        .putExtra("package_name", pkg)
                        .putExtra("package_label", "KHOSQ"),
                )
                out.add(Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST").addCategory(Intent.CATEGORY_DEFAULT))
            }
            RelStep.OEM_POPUP -> {
                out.add(
                    Intent("miui.intent.action.APP_PERM_EDITOR")
                        .setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
                        .putExtra("extra_pkgname", pkg),
                )
                out.add(Intent("miui.intent.action.APP_PERM_EDITOR").setPackage("com.miui.securitycenter").putExtra("extra_pkgname", pkg))
            }
            else -> {}
        }
        Oem.HUAWEI -> when (step) {
            RelStep.AUTOSTART, RelStep.OEM_BATTERY -> {
                out.add(component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"))
                out.add(component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"))
            }
            else -> {}
        }
        Oem.OPPO -> when (step) {
            RelStep.AUTOSTART, RelStep.OEM_BATTERY -> {
                out.add(component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"))
                out.add(component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"))
                out.add(component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"))
            }
            else -> {}
        }
        Oem.VIVO -> when (step) {
            RelStep.AUTOSTART, RelStep.OEM_BATTERY -> {
                out.add(component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"))
                out.add(component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"))
            }
            else -> {}
        }
        Oem.ONEPLUS -> when (step) {
            RelStep.AUTOSTART, RelStep.OEM_BATTERY -> {
                out.add(component("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"))
            }
            else -> {}
        }
        Oem.SAMSUNG -> when (step) {
            RelStep.OEM_BATTERY -> {
                out.add(component("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"))
            }
            else -> {}
        }
        Oem.OTHER -> {}
    }
    return out
}

/** Opens the phone's screen for a step: the maker's own one when it exists, otherwise the "App info" page. */
internal fun openRelStep(ctx: Context, oem: Oem, step: RelStep) {
    val pkg = ctx.packageName
    val list = ArrayList<Intent>()
    when (step) {
        RelStep.NOTIFICATIONS -> list.add(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg))
        RelStep.CHANNEL -> {
            KeepAliveService.createChannels(ctx)
            list.add(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, KeepAliveService.CH_MSG),
            )
            list.add(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg))
        }
        RelStep.BATTERY -> {
            list.add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")))
            list.add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        RelStep.FULLSCREEN -> {
            if (Build.VERSION.SDK_INT >= 34) list.add(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$pkg")))
            list.add(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg))
        }
        RelStep.AUTOSTART, RelStep.OEM_BATTERY, RelStep.OEM_POPUP, RelStep.LOCK_RECENTS -> list.addAll(makerIntents(oem, step, pkg))
    }
    list.add(appDetails(pkg))
    for (i in list) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) {
            // this screen does not exist on this phone: the next one is tried
        }
    }
    Toast.makeText(ctx, tr("Կարգավորումների էկրանը չբացվեց"), Toast.LENGTH_SHORT).show()
}

/** A notification like a message, to see on this phone how it looks and sounds (and that the phone lets it through). */
private fun sendTestNotification(ctx: Context) {
    KeepAliveService.createChannels(ctx)
    if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) {
        Toast.makeText(ctx, tr("Ծանուցումները անջատված են այս հեռախոսում"), Toast.LENGTH_LONG).show()
        return
    }
    val open = PendingIntent.getActivity(
        ctx,
        9001,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val n = NotificationCompat.Builder(ctx, KeepAliveService.CH_MSG)
        .setSmallIcon(R.drawable.ic_stat)
        .setContentTitle("KHOSQ")
        .setContentText(tr("Փորձնական ծանուցում. եթե սա տեսար, ծանուցումները այս հեռախոսում աշխատում են։"))
        .setStyle(NotificationCompat.BigTextStyle().bigText(tr("Փորձնական ծանուցում. եթե սա տեսար, ծանուցումները այս հեռախոսում աշխատում են։")))
        .setContentIntent(open)
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .build()
    try {
        NotificationManagerCompat.from(ctx).notify(9001, n)
    } catch (e: SecurityException) {
        Toast.makeText(ctx, tr("Ծանուցումները անջատված են այս հեռախոսում"), Toast.LENGTH_LONG).show()
    }
}

// ------------------------------------------------------------------------------------------------ texts

private fun stepTitle(s: RelStep, oem: Oem): String = when (s) {
    RelStep.NOTIFICATIONS -> tr("Ծանուցումները թույլատրված են")
    RelStep.CHANNEL -> tr("Նամակների ծանուցումը միացված է")
    RelStep.BATTERY -> tr("Մարտկոցի խնայողությունից բացառում")
    RelStep.FULLSCREEN -> tr("Զանգը կողպված էկրանին")
    RelStep.AUTOSTART -> tr("Ինքնագործարկում (Autostart)")
    RelStep.OEM_BATTERY -> if (oem == Oem.XIAOMI) tr("Մարտկոցի խնայողություն՝ «Առանց սահմանափակումների»") else tr("Արտադրողի մարտկոցի կանոնը")
    RelStep.OEM_POPUP -> tr("Կողպված էկրան և ֆոնային պատուհաններ")
    RelStep.LOCK_RECENTS -> tr("Հավելվածը կողպել վերջինների ցանկում")
}

private fun stepText(s: RelStep, oem: Oem, level: RelLevel): String = when (s) {
    RelStep.NOTIFICATIONS -> if (level == RelLevel.OK) tr("Թույլատրված է") else tr("Անջատված է։ Սեղմիր «Բացել» և միացրու ծանուցումները KHOSQ-ի համար։")
    RelStep.CHANNEL -> if (level == RelLevel.OK) tr("Միացված է") else tr("«Նամակներ» ծանուցումն անջատված է։ Սեղմիր «Բացել» և միացրու այն։")
    RelStep.BATTERY -> if (level == RelLevel.OK) tr("KHOSQ-ը խնայողությունից դուրս է") else tr("Սեղմիր «Բացել» և թույլ տուր KHOSQ-ին աշխատել ֆոնում։")
    RelStep.FULLSCREEN -> if (level == RelLevel.OK) tr("Զանգի էկրանը կբացվի ինքնուրույն") else tr("Անջատված է։ Սեղմիր «Բացել» և թույլատրիր ամբողջ էկրանով ծանուցումները։")
    RelStep.AUTOSTART -> tr("Առանց այս թույլտվության այս արտադրողի հեռախոսը փակ հավելվածին նամակ չի հասցնում։ Բացիր և միացրու KHOSQ-ը։")
    RelStep.OEM_BATTERY -> when (oem) {
        Oem.XIAOMI -> tr("Բացիր, ընտրիր KHOSQ-ը և դիր «Առանց սահմանափակումների»։ Եթե էկրանը չբացվեց, գտիր սա հավելվածի էջում՝ «Մարտկոցի խնայողություն»։")
        Oem.SAMSUNG -> tr("Մարտկոց → Ֆոնային օգտագործման սահմանափակումներ → «Երբեք չքնող հավելվածներ»՝ ավելացրու KHOSQ-ը։")
        else -> tr("Թույլ տուր KHOSQ-ին աշխատել ֆոնում և մի սահմանափակիր նրա մարտկոցի օգտագործումը։")
    }
    RelStep.OEM_POPUP -> tr("«Այլ թույլտվություններ» բաժնում թույլատրիր «Ցուցադրել կողպված էկրանին» և «Ցուցադրել ֆոնում աշխատելիս պատուհաններ»։ Սա պետք է, որ զանգը բացվի էկրանին։")
    RelStep.LOCK_RECENTS -> tr("Բացիր վերջին հավելվածների ցանկը, երկար սեղմիր KHOSQ-ի քարտի վրա և ընտրիր կողպի նշանը։ Այդպես «Մաքրել բոլորը»-ը այն չի փակի։")
}

private fun agoText(a: Ago): String = when (a.kind) {
    0 -> tr("դեռ չի եղել")
    1 -> tr("հենց հիմա")
    2 -> trf("{0} րոպե առաջ", a.n.toString())
    3 -> trf("{0} ժամ առաջ", a.n.toString())
    else -> trf("{0} օր առաջ", a.n.toString())
}

// ------------------------------------------------------------------------------------------------ the screen

/**
 * "Check the notifications": what the phone says about notifications, and what only the maker's own settings hold (those can not be
 * read, so the person marks them as done). Below: when the last push arrived and when the background service last started, which
 * tells whether a missing notification is a delivery problem or a display problem.
 */
@Composable
internal fun NotifyCheckSheet(onClose: () -> Unit) {
    val k = KTheme.colors
    val ctx = LocalContext.current
    val input by rememberRelInput()
    var marks by remember { mutableStateOf(Prefs.getString(RelStore.DONE)) }
    // the marks are the person's own: the report follows them at once, the rest is what the phone says
    val report = ReliabilityLogic.plan(input.withConfirmed(ReliabilityLogic.parseConfirmed(marks)))
    var refresh by remember { mutableIntStateOf(0) }
    val oem = remember { PhoneInfo.oem() }
    val maker = remember { PhoneInfo.title(oem) }
    val now = System.currentTimeMillis()
    val pushAgo = remember(refresh) { ReliabilityLogic.ago(now, Prefs.getLong(RelStore.FCM_LAST)) }
    val svcAgo = remember(refresh) { ReliabilityLogic.ago(now, Prefs.getLong(RelStore.SERVICE_AT)) }

    KSheet(onDismiss = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Text(text = tr("Ծանուցումների ստուգում"), color = k.text, style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold))
            if (maker.isNotEmpty()) {
                Text(text = trf("Քո հեռախոսը՝ {0}", maker), color = k.textSecondary, style = TextStyle(fontSize = 14.sp))
            }
            Text(
                text = if (report.allGood) tr("Ամեն ինչ կարգին է։") else trf("Ուշադրության է կարոտ՝ {0}", report.attention.toString()),
                color = if (report.allGood) k.success else k.warning,
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            )
            if (OemLogic.aggressive(oem)) {
                Text(
                    text = tr("Այս արտադրողի համակարգը ֆոնում փակում է հավելվածները, եթե թույլ չես տվել։ Անցիր բոլոր կետերով՝ վերևից ներքև։"),
                    color = k.textSecondary,
                    style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
                )
            }
            for (item in report.items) {
                val marked = item.step in ReliabilityLogic.parseConfirmed(marks)
                val manual = item.step == RelStep.AUTOSTART || item.step == RelStep.OEM_BATTERY || item.step == RelStep.OEM_POPUP || item.step == RelStep.LOCK_RECENTS
                RelCard(
                    level = item.level,
                    title = stepTitle(item.step, oem),
                    text = stepText(item.step, oem, item.level),
                    openLabel = if (item.step == RelStep.LOCK_RECENTS || item.level == RelLevel.OK && !manual) null else tr("Բացել"),
                    onOpen = { openRelStep(ctx, oem, item.step) },
                    done = if (manual) marked else null,
                    onToggle = {
                        marks = ReliabilityLogic.toggle(marks, item.step)
                        Prefs.putString(RelStore.DONE, marks)
                        // the list reads the marks again at once
                        refresh += 1
                    },
                    k = k,
                )
            }
            SoftButton(
                text = tr("Ուղարկել փորձնական ծանուցում"),
                onClick = { sendTestNotification(ctx) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(text = tr("Ախտորոշում"), color = k.text, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = Space.s))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Corner.m))
                    .background(k.surfaceHigh)
                    .padding(Space.m),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(text = trf("Վերջին push-ը ստացվել է՝ {0}", agoText(pushAgo)), color = k.text, style = TextStyle(fontSize = 14.sp))
                Text(text = trf("Ֆոնային ծառայությունը վերջին անգամ սկսվել է՝ {0}", agoText(svcAgo)), color = k.text, style = TextStyle(fontSize = 14.sp))
                Text(
                    text = tr("Փակիր KHOSQ-ը և խնդրիր, որ քեզ նամակ գրեն։ Եթե «Վերջին push-ը» թարմացվեց, բայց ծանուցում չերևաց, խնդիրը ցուցադրման կարգավորումներում է (վերևի կետերը)։ Եթե չթարմացվեց, նամակը չի հասնում փակ հավելվածին, և պետք է անցնել արտադրողի կետերով։"),
                    color = k.textSecondary,
                    style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                )
                Text(
                    text = tr("Թարմացնել"),
                    color = k.accent,
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier
                        .clip(RoundedCornerShape(Corner.s))
                        .clickable { refresh += 1 }
                        .padding(vertical = 6.dp, horizontal = 2.dp),
                )
            }
            Spacer(Modifier.height(Space.xl))
        }
    }
}

@Composable
private fun RelCard(
    level: RelLevel,
    title: String,
    text: String,
    openLabel: String?,
    onOpen: () -> Unit,
    done: Boolean?,
    onToggle: () -> Unit,
    k: KColors,
) {
    val tone = when (level) {
        RelLevel.OK -> k.success
        RelLevel.TODO -> k.warning
        RelLevel.CHECK -> k.textSecondary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.m))
            .background(k.surfaceHigh)
            .padding(Space.m),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.padding(top = 2.dp).size(24.dp).clip(CircleShape).background(tone.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            when (level) {
                RelLevel.OK -> Icon(imageVector = KIcons.Check, contentDescription = null, tint = tone, modifier = Modifier.size(16.dp))
                RelLevel.TODO -> Icon(imageVector = KIcons.Alert, contentDescription = null, tint = tone, modifier = Modifier.size(15.dp))
                RelLevel.CHECK -> Text(text = "?", color = tone, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold))
            }
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = title, color = k.text, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
            Text(text = text, color = k.textSecondary, style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                if (openLabel != null) {
                    Text(
                        text = openLabel,
                        color = k.accent,
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier
                            .clip(RoundedCornerShape(Corner.s))
                            .background(k.accentSoft)
                            .clickable(onClick = onOpen)
                            .padding(horizontal = Space.m, vertical = 6.dp),
                    )
                }
                if (done != null) {
                    Text(
                        text = if (done) tr("✓ Արդեն արել եմ") else tr("Արդեն արել եմ"),
                        color = if (done) k.success else k.textSecondary,
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier
                            .clip(RoundedCornerShape(Corner.s))
                            .clickable(onClick = onToggle)
                            .padding(horizontal = Space.s, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

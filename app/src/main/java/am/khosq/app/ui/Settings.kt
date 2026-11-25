package am.khosq.app.ui

import am.khosq.app.AppTheme
import am.khosq.app.KeepAliveService
import am.khosq.app.Lang
import am.khosq.app.data.Chat
import am.khosq.app.data.Profile
import am.khosq.app.tr
import am.khosq.app.trf
import am.khosq.app.update.UpState
import am.khosq.app.update.Updater
import am.khosq.app.crypto.E2E
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** True when the incoming call may open by itself above the lock screen (phones older than Android 14 always allow it). */
private fun fullScreenAllowed(ctx: Context): Boolean {
    if (Build.VERSION.SDK_INT < 34) return true
    return try {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).canUseFullScreenIntent()
    } catch (e: Exception) {
        true
    }
}

/** Opens the system screen where "full-screen notifications" are allowed for this app. */
private fun openFullScreenSettings(ctx: Context) {
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: Exception) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e2: Exception) {
            // the phone has no such screen
        }
    }
}

/** Opens the system screen where the person picks the sound, vibration and importance of message notifications. */
private fun openSoundSettings(ctx: Context) {
    KeepAliveService.createChannels(ctx)
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, KeepAliveService.CH_MSG)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: Exception) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e2: Exception) {
            // the phone has no such screen
        }
    }
}


/** A titled group of rows on one soft card, the way system settings screens are built. */
@Composable
private fun SettingsGroup(title: String?, content: @Composable () -> Unit) {
    val k = KTheme.colors
    val shape = RoundedCornerShape(Corner.l)
    Column(Modifier.fillMaxWidth()) {
        if (title != null) SectionLabel(title)
        Column(
            modifier = Modifier
                .padding(horizontal = Space.l)
                .fillMaxWidth()
                .clip(shape)
                .background(k.surface)
                .border(1.dp, k.line, shape),
        ) {
            content()
        }
    }
}

/** One row of a settings card: a tinted icon, a title, an optional second line and something at the end. */
@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleColor: Color = KTheme.colors.text,
    tone: Color = KTheme.colors.accent,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val k = KTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Space.l, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(tone.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tone, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(text = title, color = titleColor, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium))
            if (subtitle != null) {
                Text(text = subtitle, color = k.textSecondary, style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp))
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(Space.s))
            trailing()
        } else if (onClick != null) {
            Icon(imageVector = KIcons.ChevronRight, contentDescription = null, tint = k.textTertiary, modifier = Modifier.size(20.dp))
        }
    }
}

/** Pick one of a few options. The chosen one rises out of the track. */
@Composable
private fun Segmented(
    options: List<Pair<String, String>>,
    selected: String,
    modifier: Modifier = Modifier,
    weights: List<Float> = emptyList(),
    onSelect: (String) -> Unit,
) {
    val k = KTheme.colors
    val raised = if (k.dark) k.surfaceHighest else k.surface
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Corner.m))
            .background(k.surfaceHigh)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for ((i, o) in options.withIndex()) {
            val on = o.first == selected
            val bg by animateColorAsState(if (on) raised else Color.Transparent, tween(Motion.Normal), label = "seg")
            Box(
                modifier = Modifier
                    .weight(if (i < weights.size) weights[i] else 1f)
                    .clip(RoundedCornerShape(Corner.m - 3.dp))
                    .background(bg)
                    .clickable { onSelect(o.first) }
                    .padding(horizontal = Space.xs, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = o.second,
                    color = if (on) k.text else k.textSecondary,
                    style = TextStyle(fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The language buttons. They are also shown on the very first screen, so the person can switch before registering. */
@Composable
fun LanguageChips(modifier: Modifier = Modifier) {
    Segmented(
        options = Lang.options.map { o -> o.first to o.second },
        selected = Lang.code,
        modifier = modifier,
    ) { code -> Lang.set(code) }
}

@Composable
internal fun kSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = KTheme.colors.accent,
    checkedBorderColor = Color.Transparent,
    uncheckedThumbColor = Color.White,
    uncheckedTrackColor = KTheme.colors.textTertiary.copy(alpha = 0.55f),
    uncheckedBorderColor = Color.Transparent,
)

/** Third tab: my photo, name, "about", ID and the app settings. */
@Composable
fun SettingsTab(vm: AppViewModel, me: Profile, onStarred: () -> Unit, onTasks: () -> Unit, onMemory: () -> Unit, onAssistant: () -> Unit) {
    val k = KTheme.colors
    val stars by vm.stars.collectAsStateWithLifecycle()
    val myTasks by vm.tasks.collectAsStateWithLifecycle()
    val memChats by vm.memoryChats.collectAsStateWithLifecycle()
    val translateOn by vm.translateOn.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var name by remember(me.name) { mutableStateOf(me.name) }
    var about by remember(me.about) { mutableStateOf(me.about) }
    val changed = name.trim() != me.name || about.trim() != me.about
    var copied by remember { mutableStateOf(false) }

    // the answer to "may the call open above the lock screen" can change in the system settings, so it is read again on return
    var fullScreenOk by remember { mutableStateOf(fullScreenAllowed(ctx)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val watcher = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) fullScreenOk = fullScreenAllowed(ctx) }
        owner.lifecycle.addObserver(watcher)
        onDispose { owner.lifecycle.removeObserver(watcher) }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.Default) {
                val bytes = MediaUtil.avatar(ctx, uri)
                if (bytes != null) vm.setPhoto(bytes)
            }
        }
    }
    val pickPhoto = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Space.xxl),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        // ---- who I am
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = Space.s, bottom = Space.s),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(contentAlignment = Alignment.BottomEnd) {
                Avatar(
                    name = me.name,
                    seed = me.uid,
                    size = 100.dp,
                    modifier = Modifier.clip(CircleShape).clickable { pickPhoto() },
                )
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .border(3.dp, k.bg, CircleShape)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(BrandColors))
                        .clickable { pickPhoto() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = KIcons.Camera,
                        contentDescription = tr("Փոխել նկարը"),
                        tint = InkOnBrand,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(Space.m))
            Text(
                text = me.name,
                color = k.text,
                style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = Space.xl),
            )
            Row(
                modifier = Modifier
                    .padding(top = Space.xs)
                    .clip(RoundedCornerShape(Corner.m))
                    .clickable {
                        clipboard.setText(AnnotatedString(me.handle))
                        copied = true
                    }
                    .padding(horizontal = Space.m, vertical = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "@" + me.handle,
                    color = k.accent,
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                )
                Spacer(Modifier.width(Space.s))
                Icon(imageVector = if (copied) KIcons.Check else KIcons.Copy, contentDescription = tr("Պատճենել"), tint = k.accent, modifier = Modifier.size(16.dp))
                if (copied) {
                    Spacer(Modifier.width(Space.xs))
                    Text(text = tr("Պատճենվեց"), color = k.textSecondary, style = TextStyle(fontSize = 12.sp))
                }
            }
            if (me.pv > 0L) {
                Text(
                    text = tr("Ջնջել նկարը"),
                    color = k.danger,
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier
                        .clip(RoundedCornerShape(Corner.m))
                        .clickable { vm.setPhoto(null) }
                        .padding(horizontal = Space.m, vertical = Space.s),
                )
            }
        }

        // ---- edit profile
        SettingsGroup(tr("Իմ պրոֆիլը")) {
            Column(Modifier.padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { v -> name = v.take(30) },
                    label = { Text(tr("Քո անունը")) },
                    singleLine = true,
                    shape = RoundedCornerShape(Corner.m),
                    colors = kFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = about,
                    onValueChange = { v -> about = v.take(140) },
                    label = { Text(tr("Իմ մասին")) },
                    placeholder = { Text(tr("Մի քանի բառ քո մասին…")) },
                    maxLines = 3,
                    shape = RoundedCornerShape(Corner.m),
                    colors = kFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                AnimatedVisibility(
                    visible = changed && name.isNotBlank(),
                    enter = fadeIn(tween(Motion.Normal)) + expandVertically(tween(Motion.Normal)),
                    exit = fadeOut(tween(Motion.Fast)) + shrinkVertically(tween(Motion.Fast)),
                ) {
                    GradientButton(
                        text = tr("Պահպանել"),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { vm.saveProfile(name, about) },
                    )
                }
            }
        }

        // ---- my tasks
        SettingsGroup(tr("Օգնական")) {
            val open = myTasks.count { x -> !x.done }
            SettingsRow(
                icon = KIcons.Assistant,
                title = tr("KHOSQ Օգնական"),
                subtitle = tr("Հարցեր, հրամաններ, թարգմանություն"),
                onClick = onAssistant,
            )
            KDivider(inset = 62.dp)
            SettingsRow(
                icon = KIcons.Tasks,
                title = tr("Առաջադրանքներ և հիշեցումներ"),
                subtitle = if (open > 0) trf("Բաց՝ {0}", open.toString()) else tr("Միայն քո հեռախոսում"),
                onClick = onTasks,
            )
            KDivider(inset = 62.dp)
            SettingsRow(
                icon = KIcons.Memory,
                title = "KHOSQ Memory",
                subtitle = if (memChats.isNotEmpty()) trf("Միացված է՝ {0} զրույցում", memChats.size.toString()) else tr("Անջատված է"),
                onClick = onMemory,
            )
            KDivider(inset = 62.dp)
            SettingsRow(
                icon = KIcons.Translate,
                title = tr("Թարգմանություն"),
                subtitle = if (translateOn) {
                    tr("Միացված է. նամակը թարգմանվում է միայն երբ ընտրում ես «Թարգմանել», և միայն այդ նամակի տեքստն է ուղարկվում սերվեր։")
                } else {
                    tr("Անջատված է. ոչինչ չի ուղարկվում սերվեր։")
                },
                trailing = {
                    Switch(checked = translateOn, onCheckedChange = { on -> vm.setTranslateOn(on) }, colors = kSwitchColors())
                },
            )
        }

        // ---- chats
        SettingsGroup(tr("Զրույցներ")) {
            SettingsRow(
                icon = KIcons.Star,
                title = tr("Աստղանշված նամակներ"),
                subtitle = if (stars.isNotEmpty()) stars.size.toString() else null,
                tone = k.warning,
                onClick = onStarred,
            )
            KDivider(inset = 62.dp)
            Column(Modifier.padding(horizontal = Space.l, vertical = Space.m), verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(k.accent.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(imageVector = KIcons.Image, contentDescription = null, tint = k.accent, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(Space.m))
                    Text(text = tr("Զրույցի ֆոն"), color = k.text, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    for (i in 0 until WALLPAPER_COUNT) {
                        val on = Wallpaper.index == i
                        val swatch = Modifier
                            .size(width = 62.dp, height = 88.dp)
                            .clip(RoundedCornerShape(Corner.m))
                            .then(if (i == 0) Modifier.background(backgroundBrush()) else Modifier.wallpaper(i))
                            .border(
                                width = if (on) 2.5.dp else 1.dp,
                                color = if (on) k.accent else k.lineStrong,
                                shape = RoundedCornerShape(Corner.m),
                            )
                            .clickable { Wallpaper.set(i) }
                        Box(swatch, contentAlignment = Alignment.Center) {
                            if (on) {
                                Icon(
                                    imageVector = KIcons.Check,
                                    contentDescription = null,
                                    tint = k.accent,
                                    modifier = Modifier.size(22.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- look and language
        SettingsGroup(tr("Թեմա")) {
            Segmented(
                options = listOf(
                    "system" to tr("Ինչպես հեռախոսում"),
                    "light" to tr("Լուսավոր"),
                    "dark" to tr("Մութ"),
                ),
                selected = AppTheme.mode,
                weights = listOf(1.5f, 1f, 1f),
                modifier = Modifier.padding(Space.m),
            ) { m -> AppTheme.set(m) }
        }
        SettingsGroup(tr("Լեզու")) {
            LanguageChips(Modifier.padding(Space.m))
        }

        // ---- notifications and privacy
        SettingsGroup(tr("Ծանուցումներ")) {
            SettingsRow(
                icon = KIcons.Bell,
                title = tr("Նամակների ձայնը"),
                subtitle = tr("Ընտրիր ձայնը և թրթռոցը"),
                onClick = { openSoundSettings(ctx) },
            )
            if (Build.VERSION.SDK_INT >= 34) {
                SettingsRow(
                    icon = KIcons.Call,
                    title = tr("Զանգը կողպված էկրանին"),
                    subtitle = if (fullScreenOk) {
                        tr("Միացված է. զանգի էկրանը կբացվի ինքնուրույն")
                    } else {
                        tr("Անջատված է. սեղմիր և թույլ տուր, որ զանգին կարողանաս պատասխանել առանց ապակողպելու")
                    },
                    onClick = { openFullScreenSettings(ctx) },
                )
            }
        }
        SettingsGroup(tr("Գաղտնիություն")) {
            SettingsRow(
                icon = KIcons.EyeOff,
                title = tr("Ցույց տալ «առցանց»"),
                subtitle = tr("Եթե անջատես, ուրիշները չեն տեսնի՝ երբ ես առցանց, ոչ էլ վերջին այցս։"),
                trailing = {
                    Switch(checked = !me.hideSeen, onCheckedChange = { on -> vm.setHideSeen(!on) }, colors = kSwitchColors())
                },
            )
        }
        UpdateSection()
        Spacer(Modifier.height(Space.m))
    }
}

private fun megabytes(n: Long): String = if (n <= 0L) "" else ((n + 524_288L) / 1_048_576L).toString()


/** App version and the "check for a new version" button. */
@Composable
private fun UpdateSection() {
    val k = KTheme.colors
    val ctx = LocalContext.current
    val up by Updater.state.collectAsStateWithLifecycle()
    SettingsGroup(tr("Հավելվածը")) {
        SettingsRow(
            icon = KIcons.Info,
            title = "KHOSQ " + Updater.currentName(ctx),
            subtitle = when (val u = up) {
                is UpState.Checking -> tr("Ստուգվում է…")
                is UpState.UpToDate -> tr("Ունես վերջին տարբերակը ✓")
                is UpState.Failed -> tr("Չհաջողվեց ստուգել թարմացումը")
                is UpState.Available -> trf("Նոր տարբերակ՝ {0}", u.info.name)
                is UpState.Downloading -> trf("Ներբեռնվում է… {0}%", u.percent.toString())
                else -> null
            },
        )
        when (val u = up) {
            is UpState.Available -> {
                Column(Modifier.padding(start = Space.l, end = Space.l, bottom = Space.l)) {
                    GradientButton(
                        text = tr("Թարմացնել"),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { Updater.startDownload(ctx, u.info) },
                    )
                }
            }
            is UpState.Downloading -> {
                LinearProgressIndicator(
                    progress = { u.percent / 100f },
                    color = k.accent,
                    trackColor = k.surfaceHighest,
                    modifier = Modifier.fillMaxWidth().padding(start = Space.l, end = Space.l, bottom = Space.l),
                )
            }
            is UpState.Ready -> {
                Column(Modifier.padding(start = Space.l, end = Space.l, bottom = Space.l)) {
                    GradientButton(
                        text = tr("Տեղադրել"),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { Updater.install(ctx, u.path) },
                    )
                }
            }
            else -> Unit
        }
        if (up !is UpState.Downloading && up !is UpState.Ready && up !is UpState.Available) {
            KDivider(inset = 62.dp)
            SettingsRow(
                icon = KIcons.Download,
                title = tr("Ստուգել թարմացումը"),
                onClick = if (up is UpState.Checking) null else ({ Updater.checkAsync(ctx, false) }),
            )
        }
    }
}

/** Pops up when a newer build exists, while it downloads, and when it is ready to install. */
@Composable
fun UpdateDialog() {
    val ctx = LocalContext.current
    val up by Updater.state.collectAsStateWithLifecycle()
    var hidden by remember { mutableStateOf("") }
    val u = up
    val key = when (u) {
        is UpState.Available -> "a" + u.info.code
        is UpState.Downloading -> "d"
        is UpState.Ready -> "r" + u.info.code
        else -> ""
    }
    if (key.isEmpty() || key == hidden) return
    when (u) {
        is UpState.Available -> AlertDialog(
            onDismissRequest = { hidden = key },
            title = { Text(tr("Նոր տարբերակ կա")) },
            text = {
                val mb = megabytes(u.info.size)
                Text(if (mb.isEmpty()) u.info.name else trf("{0} · {1} ՄԲ", u.info.name, mb))
            },
            confirmButton = { TextButton(onClick = { Updater.startDownload(ctx, u.info) }) { Text(tr("Թարմացնել")) } },
            dismissButton = { TextButton(onClick = { hidden = key }) { Text(tr("Ավելի ուշ")) } },
        )
        is UpState.Downloading -> AlertDialog(
            onDismissRequest = { hidden = key },
            title = { Text(tr("Ներբեռնվում է…")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LinearProgressIndicator(progress = { u.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    Text(text = u.percent.toString() + "%", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { hidden = key }) { Text(tr("Թաքցնել")) } },
        )
        is UpState.Ready -> AlertDialog(
            onDismissRequest = { hidden = key },
            title = { Text(tr("Թարմացումը պատրաստ է")) },
            text = { Text(tr("Սեղմիր «Տեղադրել»։ Եթե Android-ը հարցնի, թույլ տուր տեղադրումը այս հավելվածից։")) },
            confirmButton = { TextButton(onClick = { Updater.install(ctx, u.path) }) { Text(tr("Տեղադրել")) } },
            dismissButton = { TextButton(onClick = { hidden = key }) { Text(tr("Ավելի ուշ")) } },
        )
        else -> Unit
    }
}

/**
 * The contact card: what another person has told about themselves (photo, name, ID, "about", last seen),
 * the groups we are both in, the photos of this chat, the safety number and the block button.
 */
@Composable
fun ContactSheet(
    chat: Chat,
    about: String,
    status: String,
    commonGroups: List<String>,
    photoCount: Int,
    iBlocked: Boolean,
    onMedia: () -> Unit,
    onBlock: () -> Unit,
    onClose: () -> Unit,
) {
    val k = KTheme.colors
    KSheet(onDismiss = onClose) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Avatar(name = chat.peerName, seed = chat.peerUid, size = 112.dp)
            Spacer(Modifier.height(Space.s))
            Text(
                text = chat.peerName,
                color = k.text,
                style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (chat.peerHandle.isNotEmpty()) {
                Text(
                    text = "@" + chat.peerHandle,
                    color = k.accent,
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                )
            }
            if (status.isNotEmpty()) {
                Text(text = status, color = k.textSecondary, style = TextStyle(fontSize = 13.sp))
            }
            Spacer(Modifier.height(Space.m))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Corner.l))
                    .background(k.surfaceHigh)
                    .padding(Space.l),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                Text(text = tr("Նկարագրություն"), color = k.textSecondary, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
                Text(
                    text = if (about.isBlank()) tr("Դեռ ոչինչ չի գրել") else about,
                    color = if (about.isBlank()) k.textSecondary else k.text,
                    style = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
                )
            }

            Spacer(Modifier.height(Space.s))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Corner.l))
                    .background(k.surfaceHigh),
            ) {
                SheetRow(
                    icon = KIcons.Image,
                    label = tr("Մեդիա"),
                    subtitle = trf("Նկարներ՝ {0}", photoCount.toString()),
                    onClick = onMedia,
                )
            }
            if (commonGroups.isNotEmpty()) {
                Spacer(Modifier.height(Space.s))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Corner.l))
                        .background(k.surfaceHigh)
                        .padding(Space.l),
                    verticalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    Text(text = tr("Ընդհանուր խմբեր"), color = k.textSecondary, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
                    for (g in commonGroups.take(10)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = KIcons.Group, contentDescription = null, tint = k.textSecondary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(Space.s))
                            Text(
                                text = g,
                                color = k.text,
                                style = TextStyle(fontSize = 16.sp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // the safety number: the same on both phones as long as nobody stands between them
            val number by produceState<String?>(null, chat.peerUid) {
                value = try {
                    E2E.safetyNumber(chat.peerUid)
                } catch (e: Exception) {
                    null
                }
            }
            var changed by remember(chat.peerUid) { mutableStateOf(E2E.keyChanged(chat.peerUid)) }
            val shownNumber = number
            if (shownNumber != null) {
                Spacer(Modifier.height(Space.s))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Corner.l))
                        .background(k.surfaceHigh)
                        .padding(Space.l),
                    verticalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = KIcons.Lock, contentDescription = null, tint = k.textSecondary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(Space.xs))
                        Text(
                            text = tr("Անվտանգության թիվ"),
                            color = k.textSecondary,
                            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                        )
                    }
                    Text(
                        text = shownNumber,
                        color = k.text,
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, lineHeight = 24.sp),
                    )
                    Text(
                        text = tr("Համեմատիր այս թիվը զրուցակցիդ հեռախոսի վրա. եթե նույնն է, ոչ ոք չի կարող միջամտել նամակներին։"),
                        color = k.textSecondary,
                        style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                    )
                    if (changed) {
                        Spacer(Modifier.height(Space.xs))
                        Text(
                            text = tr("Զրուցակցի անվտանգության թիվը փոխվել է։ Դա կարող է նշանակել, որ նա նոր հեռախոս է օգտագործում։ Համեմատիր թիվը նրա հետ։"),
                            color = k.warning,
                            style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
                        )
                        SoftButton(
                            text = tr("Հասկացա"),
                            onClick = {
                                E2E.acknowledge(chat.peerUid)
                                changed = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            Spacer(Modifier.height(Space.m))
            SoftButton(
                text = if (iBlocked) tr("Ապաարգելափակել") else tr("Արգելափակել"),
                onClick = {
                    onBlock()
                    onClose()
                },
                modifier = Modifier.fillMaxWidth(),
                danger = !iBlocked,
            )
            Spacer(Modifier.height(Space.s))
        }
    }
}

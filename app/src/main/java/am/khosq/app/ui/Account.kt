package am.khosq.app.ui

import am.khosq.app.Prefs
import am.khosq.app.crypto.KeyFile
import am.khosq.app.crypto.RestoreResult
import am.khosq.app.data.AccountLogic
import am.khosq.app.data.AuthKind
import am.khosq.app.data.KeyBackup
import am.khosq.app.data.Profile
import am.khosq.app.data.PwProblem
import am.khosq.app.tr
import am.khosq.app.trf
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Preference key: when the last key file was made (ms), 0 = never. */
internal const val KEY_BACKUP_AT = "key_bk_at"

internal fun authText(k: AuthKind): String = when (k) {
    AuthKind.NETWORK -> tr("Ինտերնետ կապ չկա։")
    AuthKind.NOT_ENABLED -> tr("Էլ. փոստով մուտքը Firebase-ում միացված չէ (Authentication → Sign-in method → Email/Password)։")
    AuthKind.EMAIL_IN_USE -> tr("Այս էլ. փոստը արդեն օգտագործվում է մեկ այլ հաշվում։")
    AuthKind.WEAK_PASSWORD -> tr("Գաղտնաբառը շատ թույլ է։")
    AuthKind.BAD_EMAIL -> tr("Էլ. փոստը ճիշտ չէ։")
    AuthKind.WRONG_LOGIN -> tr("Էլ. փոստը կամ գաղտնաբառը սխալ է։")
    AuthKind.TOO_MANY -> tr("Շատ փորձեր։ Սպասիր մի քանի րոպե և փորձիր նորից։")
    AuthKind.DISABLED -> tr("Այս հաշիվը անջատված է։")
    AuthKind.OK, AuthKind.OTHER -> tr("Չհաջողվեց։ Փորձիր նորից։")
}

private fun restoreText(r: RestoreResult): String = when (r) {
    RestoreResult.WRONG_PASSPHRASE -> tr("Ծածկագիրը սխալ է (կամ ֆայլը փոխված է)։")
    RestoreResult.NOT_A_KEY_FILE -> tr("Սա KHOSQ-ի բանալու ֆայլ չէ։")
    RestoreResult.OTHER_ACCOUNT -> tr("Այս ֆայլը մեկ այլ հաշվի համար է։")
    RestoreResult.FAILED, RestoreResult.OK -> tr("Բանալին չվերականգնվեց։ Փորձիր նորից։")
}

/** Why a typed password (or the passphrase of the key file) is not accepted; null when it is fine. */
private fun passProblemText(p: PwProblem, minLen: Int): String? = when (p) {
    PwProblem.OK -> null
    PwProblem.SHORT -> trf("Առնվազն {0} նշան", minLen.toString())
    PwProblem.WEAK -> tr("Շատ հեշտ է գուշակել. ավելացրու տառեր, թվեր կամ մի քանի բառ")
}

@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.take(AccountLogic.MAX_LEN)) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        shape = RoundedCornerShape(Corner.m),
        colors = kFieldColors(),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun MailField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.take(254)) },
        label = { Text(tr("Էլ. փոստ")) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        shape = RoundedCornerShape(Corner.m),
        colors = kFieldColors(),
        modifier = modifier.fillMaxWidth(),
    )
}

// ------------------------------------------------------------------------------------------------ in the security centre

/** The account's email and password (to open it on another phone) and the key file (to open the old messages there). */
@Composable
fun AccountBackupCard(vm: AppViewModel, me: Profile, backupAt: Long, onBackedUp: () -> Unit) {
    val k = KTheme.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val email by vm.accountEmail.collectAsStateWithLifecycle()
    var mail by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var askPass by remember { mutableStateOf(false) }
    var pass by remember { mutableStateOf("") }
    var askRestore by remember { mutableStateOf(false) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) restoreUri = uri
    }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val secret = pass
        pass = ""
        if (uri != null && secret.isNotEmpty()) {
            busy = true
            error = null
            note = null
            scope.launch {
                val ok = withContext(Dispatchers.IO) { KeyFile.create(ctx, uri, me.uid, secret) }
                busy = false
                if (ok) {
                    Prefs.putLong(KEY_BACKUP_AT, System.currentTimeMillis())
                    onBackedUp()
                    note = tr("Բանալու ֆայլը պահվեց և ստուգվեց։ Պահիր այն ապահով տեղում, ծածկագիրն էլ՝ առանձին։")
                } else {
                    error = tr("Ֆայլը չպահվեց կամ չստուգվեց։ Փորձիր ուրիշ տեղում։")
                }
            }
        }
    }

    if (askPass) {
        var p1 by remember { mutableStateOf("") }
        var p2 by remember { mutableStateOf("") }
        var problem by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { askPass = false },
            title = { Text(tr("Ֆայլի ծածկագիրը")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text(
                        text = tr("Ծածկագիրը փակում է ֆայլը։ Եթե այն մոռանաս, ոչ ես, ոչ որևէ մեկը չի կարող բացել ֆայլը։ Գրիր այն թղթի վրա։"),
                        color = k.textSecondary,
                        style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
                    )
                    SecretField(p1, { v -> p1 = v }, tr("Ծածկագիր"))
                    SecretField(p2, { v -> p2 = v }, tr("Ծածկագիրը նորից"))
                    val pr = problem
                    if (pr != null) Text(text = pr, color = k.danger, style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val why = passProblemText(AccountLogic.passphraseProblem(p1), AccountLogic.MIN_PASSPHRASE)
                    if (why != null) {
                        problem = why
                    } else if (p1 != p2) {
                        problem = tr("Երկու ծածկագրերը նույնը չեն")
                    } else {
                        pass = p1
                        askPass = false
                        saver.launch(KeyBackup.fileName(me.handle))
                    }
                }) { Text(tr("Շարունակել")) }
            },
            dismissButton = { TextButton(onClick = { askPass = false }) { Text(tr("Չեղարկել")) } },
        )
    }

    if (askRestore) {
        var rp by remember { mutableStateOf("") }
        var rerr by remember { mutableStateOf<String?>(null) }
        var rbusy by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!rbusy) askRestore = false },
            title = { Text(tr("Բանալու վերականգնում")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text(
                        text = tr("Եթե նոր հեռախոսում շարունակել ես առանց ֆայլի, հիմա կարող ես վերադարձնել հին բանալին։ Ընկերներդ նորից կտեսնեն «անվտանգության թիվը փոխվել է»։ Հետո փակիր և նորից բացիր հավելվածը։"),
                        color = k.textSecondary,
                        style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
                    )
                    SoftButton(
                        text = if (restoreUri == null) tr("Ընտրել ֆայլը") else tr("Ֆայլը ընտրված է ✓ (փոխել)"),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !rbusy,
                        onClick = { picker.launch(arrayOf("*/*")) },
                    )
                    SecretField(rp, { v -> rp = v }, tr("Ֆայլի ծածկագիրը"))
                    val re = rerr
                    if (re != null) Text(text = re, color = k.danger, style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp))
                    if (rbusy) Text(text = tr("Ստուգվում է… սա մի քանի վայրկյան է տևում։"), color = k.textSecondary, style = TextStyle(fontSize = 13.sp))
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !rbusy,
                    onClick = {
                        val u = restoreUri
                        if (u == null) {
                            rerr = tr("Նախ ընտրիր ֆայլը։")
                        } else if (rp.isEmpty()) {
                            rerr = tr("Գրիր ֆայլի ծածկագիրը։")
                        } else {
                            rerr = null
                            rbusy = true
                            val secret = rp
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { KeyFile.restore(ctx, u, secret, me.uid) }
                                rbusy = false
                                if (r == RestoreResult.OK) {
                                    vm.republishKey()
                                    restoreUri = null
                                    askRestore = false
                                    error = null
                                    note = tr("Բանալին վերականգնվեց։ Փակիր հավելվածը և նորից բացիր։")
                                } else {
                                    rerr = restoreText(r)
                                }
                            }
                        }
                    },
                ) { Text(tr("Վերականգնել")) }
            },
            dismissButton = { TextButton(enabled = !rbusy, onClick = { askRestore = false }) { Text(tr("Չեղարկել")) } },
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Corner.l)).background(k.surfaceHigh).padding(Space.l),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Text(text = tr("Հաշիվ և պահուստ"), color = k.text, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold))

        // ---- the email
        val mailNow = email
        if (mailNow == null) {
            Text(
                text = tr("Հավելվածը ջնջելիս կամ հեռախոսը փոխելիս հաշիվդ կորչում է։ Կապիր էլ. փոստ և գաղտնաբառ, որ նոր հեռախոսում մտնես նույն հաշվով՝ նույն ID-ով, նույն չաթերով։"),
                color = k.textSecondary,
                style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
            )
            MailField(mail, { v -> mail = v })
            SecretField(pw, { v -> pw = v }, tr("Գաղտնաբառ"))
            SecretField(pw2, { v -> pw2 = v }, tr("Գաղտնաբառը նորից"))
            GradientButton(
                text = tr("Կապել հաշվին"),
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    note = null
                    val why = passProblemText(AccountLogic.passwordProblem(pw), AccountLogic.MIN_PASSWORD)
                    if (!AccountLogic.validEmail(mail)) {
                        error = tr("Էլ. փոստը ճիշտ չէ։")
                    } else if (why != null) {
                        error = tr("Գաղտնաբառ") + ". " + why
                    } else if (pw != pw2) {
                        error = tr("Երկու գաղտնաբառերը նույնը չեն")
                    } else {
                        error = null
                        busy = true
                        scope.launch {
                            val r = vm.linkEmail(mail, pw)
                            busy = false
                            if (r == AuthKind.OK) {
                                pw = ""
                                pw2 = ""
                                note = tr("Հաշիվը կապվեց։ Հիմա ստեղծիր նաև բանալու ֆայլը։")
                            } else {
                                error = authText(r)
                            }
                        }
                    }
                },
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = KIcons.Check, contentDescription = null, tint = k.success, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Space.s))
                Text(
                    text = trf("Հաշիվը կապված է՝ {0}", mailNow),
                    color = k.text,
                    style = TextStyle(fontSize = 15.sp, lineHeight = 20.sp),
                )
            }
            TextButton(
                enabled = !busy,
                onClick = {
                    error = null
                    note = null
                    busy = true
                    scope.launch {
                        val r = vm.resetPassword(mailNow)
                        busy = false
                        if (r == AuthKind.OK) note = tr("Նամակը ուղարկվեց. բացիր այն և ընտրիր նոր գաղտնաբառ։") else error = authText(r)
                    }
                },
            ) { Text(tr("Ուղարկել գաղտնաբառը փոխելու նամակ")) }
        }

        // ---- the key file
        Text(text = tr("Բանալու ֆայլ"), color = k.text, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = Space.s))
        Text(
            text = if (backupAt > 0L) {
                trf("Վերջին ֆայլը՝ {0}", DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(backupAt)))
            } else {
                tr("Դեռ չի ստեղծվել։ Առանց դրա նոր հեռախոսում հին նամակները չեն բացվի։")
            },
            color = k.textSecondary,
            style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
        )
        SoftButton(
            text = if (backupAt > 0L) tr("Ստեղծել նոր բանալու ֆայլ") else tr("Ստեղծել բանալու ֆայլ"),
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                error = null
                note = null
                askPass = true
            },
        )

        TextButton(
            enabled = !busy,
            onClick = {
                error = null
                note = null
                askRestore = true
            },
        ) { Text(tr("Վերականգնել բանալին ֆայլից")) }

        val e = error
        if (e != null) Text(text = e, color = k.danger, style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium))
        val n = note
        if (n != null) Text(text = n, color = k.success, style = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium))

        Text(
            text = tr("Նոր հեռախոսում՝ «Ես արդեն ունեմ հաշիվ» → էլ. փոստ և գաղտնաբառ → բանալու ֆայլ։ Առանց ֆայլի չաթերի ցանկը կվերադառնա, բայց հին նամակները չեն բացվի, իսկ ընկերներդ կտեսնեն «անվտանգության թիվը փոխվել է»։"),
            color = k.textSecondary,
            style = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
        )
    }
}

// ------------------------------------------------------------------------------------------------ a new phone

/** Opening the account made on another phone: email and password first, then (if there is one) the key file. */
@Composable
fun RecoverScreen(vm: AppViewModel, onBack: () -> Unit, onDone: () -> Unit) {
    val k = KTheme.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(0) }
    var mail by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var fileUri by remember { mutableStateOf<Uri?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var askSkip by remember { mutableStateOf(false) }

    BackHandler(enabled = step == 0) { onBack() }

    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            fileUri = uri
            error = null
        }
    }

    if (askSkip) {
        AlertDialog(
            onDismissRequest = { askSkip = false },
            title = { Text(tr("Շարունակել առանց ֆայլի՞")) },
            text = {
                Text(
                    tr("Չաթերի ցանկդ կվերադառնա, բայց հին նամակները չեն բացվի, իսկ ընկերներդ կտեսնեն «անվտանգության թիվը փոխվել է» նախազգուշացում։ Հետո կարող ես վերադարձնել բանալին Անվտանգության կենտրոնից ֆայլով, բայց ընկերներդ նորից կտեսնեն նախազգուշացում։"),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askSkip = false
                    vm.recoverFinish()
                    onDone()
                }) { Text(tr("Շարունակել")) }
            },
            dismissButton = { TextButton(onClick = { askSkip = false }) { Text(tr("Չեղարկել")) } },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Space.xxl, vertical = Space.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { LanguageChips(Modifier.width(230.dp)) }
        Spacer(Modifier.height(Space.l))
        BrandLogo(72.dp)
        Text(
            text = tr("Մուտք գոյություն ունեցող հաշվով"),
            color = k.text,
            style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
        )

        when (step) {
            0 -> {
                Text(
                    text = tr("Գրիր էլ. փոստը և գաղտնաբառը, որ կապել էիր հին հեռախոսում։"),
                    color = k.textSecondary,
                    style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
                    textAlign = TextAlign.Center,
                )
                MailField(mail, { v -> mail = v })
                SecretField(pw, { v -> pw = v }, tr("Գաղտնաբառ"))
                val e = error
                if (e != null) Text(text = e, color = k.danger, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium), textAlign = TextAlign.Center)
                val n = note
                if (n != null) Text(text = n, color = k.success, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium), textAlign = TextAlign.Center)
                GradientButton(
                    text = tr("Մուտք"),
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        note = null
                        if (!AccountLogic.validEmail(mail) || pw.isEmpty()) {
                            error = tr("Գրիր էլ. փոստը և գաղտնաբառը։")
                        } else {
                            error = null
                            busy = true
                            scope.launch {
                                val r = vm.recoverSignIn(mail, pw)
                                busy = false
                                if (r == AuthKind.OK) {
                                    pw = ""
                                    step = if (vm.recoverHasProfile()) 1 else 2
                                } else {
                                    error = authText(r)
                                }
                            }
                        }
                    },
                )
                TextButton(
                    enabled = !busy,
                    onClick = {
                        note = null
                        if (!AccountLogic.validEmail(mail)) {
                            error = tr("Նախ գրիր էլ. փոստը վերևում։")
                        } else {
                            error = null
                            busy = true
                            scope.launch {
                                val r = vm.resetPassword(mail)
                                busy = false
                                if (r == AuthKind.OK) note = tr("Նամակը ուղարկվեց. բացիր այն և ընտրիր նոր գաղտնաբառ։") else error = authText(r)
                            }
                        }
                    },
                ) { Text(tr("Մոռացել եմ գաղտնաբառը")) }
                TextButton(enabled = !busy, onClick = { onBack() }) { Text(tr("Հետ")) }
            }
            1 -> {
                Text(
                    text = tr("Որ հին նամակները բացվեն, ընտրիր բանալու ֆայլը, որ ստեղծել էիր հին հեռախոսում, և գրիր նրա ծածկագիրը։"),
                    color = k.textSecondary,
                    style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
                    textAlign = TextAlign.Center,
                )
                SoftButton(
                    text = if (fileUri == null) tr("Ընտրել ֆայլը") else tr("Ֆայլը ընտրված է ✓ (փոխել)"),
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { chooser.launch(arrayOf("*/*")) },
                )
                SecretField(pass, { v -> pass = v }, tr("Ֆայլի ծածկագիրը"))
                val e = error
                if (e != null) Text(text = e, color = k.danger, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium), textAlign = TextAlign.Center)
                GradientButton(
                    text = tr("Բացել ֆայլը"),
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val u = fileUri
                        if (u == null) {
                            error = tr("Նախ ընտրիր ֆայլը։")
                        } else if (pass.isEmpty()) {
                            error = tr("Գրիր ֆայլի ծածկագիրը։")
                        } else {
                            error = null
                            busy = true
                            val secret = pass
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { KeyFile.restore(ctx, u, secret, vm.recoverUid()) }
                                busy = false
                                if (r == RestoreResult.OK) {
                                    pass = ""
                                    vm.recoverFinish()
                                    onDone()
                                } else {
                                    error = restoreText(r)
                                }
                            }
                        }
                    },
                )
                if (busy) {
                    Text(text = tr("Ստուգվում է… սա մի քանի վայրկյան է տևում։"), color = k.textSecondary, style = TextStyle(fontSize = 13.sp))
                }
                TextButton(enabled = !busy, onClick = { askSkip = true }) { Text(tr("Շարունակել առանց ֆայլի")) }
            }
            else -> {
                Text(
                    text = tr("Այս հաշվի համար պրոֆիլ չկա։ Կստեղծես նորը։"),
                    color = k.textSecondary,
                    style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
                    textAlign = TextAlign.Center,
                )
                GradientButton(
                    text = tr("Շարունակել"),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        vm.recoverFinish()
                        onDone()
                    },
                )
            }
        }
    }
}

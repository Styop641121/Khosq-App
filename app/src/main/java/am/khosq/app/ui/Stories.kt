package am.khosq.app.ui

import am.khosq.app.data.Profile
import am.khosq.app.data.Story
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val StoryBackgrounds: List<Brush> = listOf(
    Brush.linearGradient(listOf(Color(0xFFFFC84A), Color(0xFFFF6B57))),
    Brush.linearGradient(listOf(Color(0xFF4F7BFF), Color(0xFF9B4BFF))),
    Brush.linearGradient(listOf(Color(0xFF12C2A3), Color(0xFF2E5BFF))),
    Brush.linearGradient(listOf(Color(0xFFFF5E8A), Color(0xFFFF9A3D))),
    Brush.linearGradient(listOf(Color(0xFF1B2466), Color(0xFF5B2A86))),
    Brush.linearGradient(listOf(Color(0xFF0F2027), Color(0xFF2C5364))),
)

private fun storyBrush(i: Int): Brush = StoryBackgrounds[i.coerceIn(0, StoryBackgrounds.size - 1)]

/* ---------------- the row at the top of the chat list ---------------- */

@Composable
fun StoriesRail(stories: List<Story>, me: Profile, onOpen: (String) -> Unit, onAdd: () -> Unit) {
    val now = System.currentTimeMillis()
    val active = stories.filter { s -> s.exp > now }
    val mine = active.filter { s -> s.owner == me.uid }
    val others = active.filter { s -> s.owner != me.uid }.groupBy { s -> s.owner }.entries
        .map { e ->
            val unseen = e.value.any { s -> me.uid !in s.views }
            Triple(e.key, unseen, e.value)
        }
        .sortedWith(compareByDescending<Triple<String, Boolean, List<Story>>> { t -> t.second }
            .thenByDescending { t -> t.third.maxOf { s -> s.ts } })

    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            StoryTile(
                label = "Իմ սթորին",
                name = me.name,
                seed = me.uid,
                ring = if (mine.isEmpty()) 0 else 1,
                plus = true,
                onClick = { if (mine.isEmpty()) onAdd() else onOpen(me.uid) },
                onPlus = onAdd,
            )
        }
        items(others, key = { t -> t.first }) { t ->
            StoryTile(
                label = t.third.first().ownerName,
                name = t.third.first().ownerName,
                seed = t.first,
                ring = if (t.second) 1 else 2,
                plus = false,
                onClick = { onOpen(t.first) },
                onPlus = {},
            )
        }
    }
}

@Composable
private fun StoryTile(
    label: String,
    name: String,
    seed: String,
    ring: Int,
    plus: Boolean,
    onClick: () -> Unit,
    onPlus: () -> Unit,
) {
    val ringBrush: Brush = when (ring) {
        1 -> Brush.sweepGradient(
            listOf(Color(0xFFFFCB52), Color(0xFFFF6B57), Color(0xFFB04BFF), Color(0xFF4F7BFF), Color(0xFFFFCB52)),
        )
        2 -> Brush.linearGradient(listOf(Color(0x55AAAAAA), Color(0x55AAAAAA)))
        else -> Brush.linearGradient(listOf(Color(0x00000000), Color(0x00000000)))
    }
    Column(
        modifier = Modifier.width(76.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(modifier = Modifier.size(70.dp)) {
            Box(
                modifier = Modifier
                    .size(70.dp)
                    .clip(CircleShape)
                    .background(ringBrush)
                    .then(if (ring == 0) Modifier.border(2.dp, MaterialTheme.colorScheme.outline, CircleShape) else Modifier)
                    .padding(4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Avatar(name = name, seed = seed, size = 62.dp, circle = true)
            }
            if (plus) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(BrandColors))
                        .clickable(onClick = onPlus),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "+", color = InkOnBrand, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                }
            }
        }
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/* ---------------- full-screen viewer ---------------- */

@Composable
fun StoryViewer(ownerId: String, me: Profile, vm: AppViewModel, onClose: () -> Unit) {
    val all by vm.stories.collectAsStateWithLifecycle()
    val now = System.currentTimeMillis()
    val list = all.filter { s -> s.owner == ownerId && s.exp > now }.sortedBy { s -> s.ts }
    var idx by remember {
        mutableStateOf(list.indexOfFirst { s -> me.uid !in s.views }.let { i -> if (i < 0) 0 else i })
    }
    var progress by remember { mutableStateOf(0f) }
    var paused by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf("") }

    if (list.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val safeIdx = idx.coerceIn(0, list.size - 1)
    val story = list[safeIdx]
    val isMine = story.owner == me.uid

    LaunchedEffect(story.id) {
        progress = 0f
        if (!isMine && me.uid !in story.views) vm.viewStory(story.id)
        val duration = if (story.kind == "txt") 6500f else 5500f
        while (progress < 1f) {
            delay(50)
            if (!paused && !typing) progress += 50f / duration
        }
        if (safeIdx < list.size - 1) idx = safeIdx + 1 else onClose()
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(if (story.kind == "txt") storyBrush(story.bg) else Brush.linearGradient(listOf(Color.Black, Color.Black)))
                    .pointerInput(story.id) {
                        detectTapGestures(
                            onPress = {
                                paused = true
                                tryAwaitRelease()
                                paused = false
                            },
                            onTap = { offset ->
                                typing = false
                                if (offset.x < size.width * 0.32f) {
                                    if (safeIdx > 0) idx = safeIdx - 1 else progress = 0f
                                } else {
                                    if (safeIdx < list.size - 1) idx = safeIdx + 1 else onClose()
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (story.kind == "img") {
                    val bmp = remember(story.id) {
                        val d = story.data
                        if (d == null) null else BitmapFactory.decodeByteArray(d, 0, d.size)?.asImageBitmap()
                    }
                    if (bmp != null) {
                        Image(bitmap = bmp, contentDescription = "Սթորի", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    }
                    if (story.text.isNotEmpty()) {
                        Text(
                            text = story.text,
                            color = Color.White,
                            fontSize = 17.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 110.dp)
                                .background(Color(0x66000000), RoundedCornerShape(14.dp))
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                } else {
                    Text(
                        text = story.text,
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
            }

            Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    for (i in list.indices) {
                        val fraction = if (i < safeIdx) 1f else if (i == safeIdx) progress.coerceIn(0f, 1f) else 0f
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color(0x4DFFFFFF)),
                        ) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(Color.White))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(name = if (isMine) me.name else story.ownerName, seed = story.owner, size = 40.dp, circle = true)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (isMine) "Իմ սթորին" else story.ownerName,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(text = formatTime(story.ts), color = Color(0xCCFFFFFF), fontSize = 12.sp)
                    }
                    TextButton(onClick = onClose) { Text(text = "✕", color = Color.White, fontSize = 20.sp) }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (isMine) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "👁 " + story.views.size,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            vm.deleteStory(story.id)
                            if (list.size <= 1) onClose()
                        }) { Text(text = "Ջնջել", color = Color.White) }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = reply,
                            onValueChange = { reply = it.take(500) },
                            placeholder = { Text("Պատասխանել սթորիին…", color = Color(0xCCFFFFFF)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions.Default,
                            textStyle = TextStyle(color = Color.White),
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(26.dp))
                                .pointerInput(Unit) { detectTapGestures(onPress = { typing = true }) },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color(0x33FFFFFF),
                                unfocusedContainerColor = Color(0x33FFFFFF),
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                            ),
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(BrandColors))
                                .clickable {
                                    vm.replyToStory(story, reply)
                                    reply = ""
                                    typing = false
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text = "➤", color = InkOnBrand, fontSize = 18.sp)
                        }
                    }
                }
            }
        }
    }
}

/* ---------------- creating a story ---------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoryComposer(vm: AppViewModel, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("txt") }
    var bg by remember { mutableStateOf(0) }
    var text by remember { mutableStateOf("") }
    var caption by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<ByteArray?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.Default) {
                val bytes = MediaUtil.compressImage(ctx, uri)
                if (bytes != null) {
                    photo = bytes
                    mode = "img"
                } else {
                    error = "Այս նկարը չհաջողվեց բացել"
                }
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onClose, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(text = "Նոր սթորի", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth())

            Box(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .aspectRatio(9f / 13f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(if (mode == "txt") storyBrush(bg) else Brush.linearGradient(listOf(Color.Black, Color.Black))),
                contentAlignment = Alignment.Center,
            ) {
                if (mode == "txt") {
                    TextField(
                        value = text,
                        onValueChange = { text = it.take(280) },
                        placeholder = {
                            Text(
                                "Գրիր քո մտքերը…",
                                color = Color(0xCCFFFFFF),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                    )
                } else {
                    val bmp = remember(photo) {
                        val d = photo
                        if (d == null) null else BitmapFactory.decodeByteArray(d, 0, d.size)?.asImageBitmap()
                    }
                    if (bmp != null) {
                        Image(bitmap = bmp, contentDescription = "Նկար", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
            }

            if (mode == "txt") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (i in StoryBackgrounds.indices) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(StoryBackgrounds[i])
                                .then(
                                    if (i == bg) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier,
                                )
                                .clickable { bg = i },
                        )
                    }
                }
            } else {
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it.take(120) },
                    label = { Text("Մակագրություն (ըստ ցանկության)") },
                    singleLine = true,
                    shape = RoundedCornerShape(17.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { mode = "txt" }, modifier = Modifier.weight(1f)) { Text("Aa Տեքստ") }
                TextButton(
                    onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.weight(1f),
                ) { Text("📷 Նկար") }
            }

            val e = error
            if (e != null) Text(text = e, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)

            Text(
                text = "Սթորին 24 ժամ կերևա քո զրույցների մարդկանց։",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
            GradientButton(
                text = "Հրապարակել",
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (mode == "txt") {
                        if (text.isBlank()) {
                            error = "Գրիր տեքստ կամ ընտրիր նկար"
                        } else {
                            vm.postStory("txt", text.trim(), bg, null)
                            onClose()
                        }
                    } else {
                        val p = photo
                        if (p == null) {
                            error = "Ընտրիր նկար"
                        } else {
                            vm.postStory("img", caption.trim(), bg, p)
                            onClose()
                        }
                    }
                },
            )
        }
    }
}

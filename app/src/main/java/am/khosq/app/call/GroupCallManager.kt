package am.khosq.app.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

enum class GPhase { IDLE, RINGING, JOINING, ACTIVE, ENDED }

/** One of the other people of a call: [state] is "connecting", "connected" or "lost"; [video] is their picture while it arrives. */
data class GPerson(
    val uid: String,
    val name: String,
    val state: String,
    val muted: Boolean,
    val cam: Boolean,
    val video: VideoTrack?,
)

data class GCallUi(
    val phase: GPhase = GPhase.IDLE,
    val callId: String = "",
    val chatId: String = "",
    val title: String = "",
    val video: Boolean = false,
    val hostUid: String = "",
    val hostName: String = "",
    val outgoing: Boolean = false,
    val muted: Boolean = false,
    val speaker: Boolean = true,
    val cameraOn: Boolean = false,
    val noCamera: Boolean = false,
    val localVideo: VideoTrack? = null,
    val people: List<GPerson> = emptyList(),
    /** How many people are in the call, me included. */
    val count: Int = 0,
    /** While it rings: the names of the people who are in the call already. */
    val ringIn: List<String> = emptyList(),
    /** Why a call ended (shown for a moment): "ended", "full", "denied", "noMedia", "error", "noanswer", "elsewhere" or "". */
    val note: String = "",
    val startedAt: Long = 0L,
    /** Me: my id and the name my tile shows. */
    val meUid: String = "",
    val meName: String = "",
)

/** The bar of a group chat whose group has a call going on. */
data class GBanner(
    val callId: String,
    val chatId: String,
    val video: Boolean,
    val hostName: String,
    val people: List<String>,
    val count: Int,
    val full: Boolean,
    val mine: Boolean,
)

/**
 * Group voice and video calls (up to [GroupCallRules.MAX_PARTS] people at once, everybody connected to everybody: a "mesh").
 * It does what docs/gcalls.js does on the web page, step by step, and writes the very same documents (see GroupCallRules.kt for them), so a
 * phone and a page can be in one call together. All of it runs on the main thread.
 *
 * Who offers: the one who came LATER offers (larger j, then larger uid); the one who was there waits for the offer and answers.
 * The chat list that KeepAliveService already listens to tells this object about a call that goes on in a group ([onChats]): that is what rings.
 */
object GroupCallManager {
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val db get() = FirebaseFirestore.getInstance()

    private val _ui = MutableStateFlow(GCallUi())
    val ui: StateFlow<GCallUi> = _ui.asStateFlow()
    private val _banner = MutableStateFlow<GBanner?>(null)
    val banner: StateFlow<GBanner?> = _banner.asStateFlow()

    private class GChat(
        val id: String,
        val group: Boolean,
        val saved: Boolean,
        val title: String,
        val names: Map<String, String>,
        val pointer: GroupCallRules.Pointer?,
    )

    private class Link(
        val peerUid: String,
        val peerSid: String,
        val lid: String,
        val offerer: Boolean,
        val tries: Int,
    ) {
        var conn: PeerConnection? = null
        var state = "connecting"
        var video: VideoTrack? = null
        var remoteSet = false
        var answered = false
        var closed = false
        val remoteCand = ArrayList<IceCandidate>()
        val out = ArrayList<Map<String, Any>>()
        var outT: Runnable? = null
        var connT: Runnable? = null
        var graceT: Runnable? = null
    }

    private class Banner(val chatId: String) {
        var id = ""
        var ptr: GroupCallRules.Pointer? = null
        var doc: Map<String, Any>? = null
        var reg: ListenerRegistration? = null
        var tick: Runnable? = null
        var lv = GroupCallRules.Liveness { System.currentTimeMillis() }
    }

    private var myUid = ""
    private var myName = ""
    private var chats: Map<String, GChat> = emptyMap()

    private var gen = 0 // the number of the call in progress: what an older call is still waiting for is dropped
    private var sid = ""
    private var myJ = 0L
    private var joined = false // my entry has been written
    private var seenMine = false // my entry has been seen in the document (a document that does not show it yet is only the old one)
    private var doc: Map<String, Any>? = null // the last data of the call document
    private var live: List<GroupCallRules.Person> = emptyList()
    private var lv = GroupCallRules.Liveness { System.currentTimeMillis() }
    private var lastQ = -1
    private val links = HashMap<String, Link>() // peer uid -> link
    private val inbox = LinkedHashMap<String, ArrayList<Map<String, Any>>>() // "uid|lid" -> addresses that came before their offer
    private val pendingOffers = HashMap<String, Pair<Map<String, Any>, Long>>() // peer uid -> an offer from somebody the call document does not show yet
    private val early = ArrayList<Map<String, Any>>() // messages that came while my own entry was still on its way to the server
    private val handled = HashSet<String>() // calls I have answered, refused or let go: not rung for again
    private var bw: Banner? = null

    private var docReg: ListenerRegistration? = null
    private var sigReg: ListenerRegistration? = null
    private var ringReg: ListenerRegistration? = null
    private var ringLv = GroupCallRules.Liveness { System.currentTimeMillis() }

    private var beatT: Runnable? = null
    private var ringT: Runnable? = null
    private var aloneT: Runnable? = null
    private var resetT: Runnable? = null
    private var docRetryT: Runnable? = null
    private var firstT: Runnable? = null
    private var ringDropT: Runnable? = null

    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var camPausedByApp = false

    private var ringtone: Ringtone? = null
    private var tone: ToneGenerator? = null
    private var savedMode = AudioManager.MODE_NORMAL
    private var focus: AudioFocusRequest? = null
    private var audioActive = false

    private fun nowMs() = System.currentTimeMillis()

    private fun rid(): String {
        val a = "abcdefghijklmnopqrstuvwxyz0123456789"
        val sb = StringBuilder()
        for (i in 0 until 12) sb.append(a[(Math.random() * a.length).toInt().coerceIn(0, a.length - 1)])
        return sb.toString()
    }

    fun init(ctx: Context) {
        if (!::app.isInitialized) {
            app = ctx.applicationContext
            CallManager.init(ctx) // starts the WebRTC library (once)
        }
    }

    /** Who I am (the page of the app tells it when the profile is known); my name goes into my entry of a call. */
    fun setMe(uid: String, name: String) {
        myUid = uid
        if (name.isNotEmpty()) myName = name
    }

    /** True while I am in a group call (joining, or in it): a one-to-one call that comes in is then answered "busy". */
    fun inCall(): Boolean {
        val p = _ui.value.phase
        return p == GPhase.JOINING || p == GPhase.ACTIVE
    }

    private fun oneToOneBusy(): Boolean {
        val p = CallManager.ui.value.phase
        return p != Phase.IDLE && p != Phase.ENDED
    }

    private fun str(v: Any?): String = (v as? String) ?: ""
    private fun num(v: Any?): Long = (v as? Number)?.toLong() ?: 0L

    private fun nameOf(uid: String, claimed: String, chatId: String): String {
        val own = chats[chatId]?.names?.get(uid) ?: ""
        val n = if (own.isNotEmpty()) own else claimed
        return (if (n.isEmpty()) "?" else n).take(60)
    }

    private fun myNameFor(chatId: String): String {
        if (myName.isNotEmpty()) return myName
        return chats[chatId]?.names?.get(myUid) ?: ""
    }

    private fun set(f: (GCallUi) -> GCallUi) {
        _ui.value = f(_ui.value)
    }

    // ------------------------------------------------------------------------------------------------ the chats (what rings)

    /** A new list of my chats (from the listener of KeepAliveService): a group that shows a call I have not met makes the phone ring. */
    fun onChats(uid: String, docs: List<DocumentSnapshot>) {
        if (!::app.isInitialized) return
        val m = HashMap<String, GChat>()
        for (d in docs) {
            val group = d.getBoolean("group") == true
            val names = HashMap<String, String>()
            val raw = d.get("names") as? Map<*, *>
            if (raw != null) for ((k, v) in raw) if (k is String && v is String) names[k] = v
            m[d.id] = GChat(
                id = d.id,
                group = group,
                saved = group && d.getBoolean("saved") == true,
                title = d.getString("title") ?: "",
                names = names,
                pointer = if (group) GroupCallRules.pointerOf(d.get("gcall") as? Map<*, *>) else null,
            )
        }
        main.post {
            if (myUid.isEmpty()) myUid = uid
            chats = m
            val cur = _ui.value
            if (cur.phase == GPhase.RINGING) {
                val p = chats[cur.chatId]?.pointer
                if (p == null || p.id != cur.callId) dropRing()
            }
            checkRing()
            bannerRefresh()
        }
    }

    private fun checkRing() {
        val ph = _ui.value.phase
        if (ph != GPhase.IDLE && ph != GPhase.ENDED) return
        if (myUid.isEmpty()) return
        for (c in chats.values) {
            if (!c.group || c.saved) continue
            val p = c.pointer ?: continue
            if (p.host.isEmpty() || p.host == myUid || handled.contains(p.id)) continue
            if (nowMs() - p.ts > GroupCallRules.RING_SHOW_MS) continue
            if (oneToOneBusy()) return
            ring(c, p)
            return
        }
    }

    private fun ring(c: GChat, p: GroupCallRules.Pointer) {
        if (_ui.value.phase == GPhase.ENDED) resetNow()
        ringLv = GroupCallRules.Liveness { nowMs() }
        _ui.value = GCallUi(
            phase = GPhase.RINGING,
            callId = p.id,
            chatId = c.id,
            title = c.title.take(60),
            video = p.video,
            hostUid = p.host,
            hostName = nameOf(p.host, p.hostName, c.id),
            cameraOn = p.video,
            speaker = true,
        )
        stopRing()
        val id = p.id
        val chatId = c.id
        ringReg = db.collection("gcalls").document(id).addSnapshotListener { d, err ->
            if (err != null) return@addSnapshotListener
            main.post { onRingDoc(id, chatId, d) }
        }
        val drop = Runnable { dropRing() }
        ringDropT = drop
        main.postDelayed(drop, maxOf(1000L, GroupCallRules.RING_SHOW_MS - maxOf(0L, nowMs() - p.ts)))
        am.khosq.app.push.IceClient.refresh()
        startRinging()
    }

    private fun onRingDoc(id: String, chatId: String, d: DocumentSnapshot?) {
        val cur = _ui.value
        if (cur.phase != GPhase.RINGING || cur.callId != id) return
        if (d == null || !d.exists()) {
            dropRing()
            return
        }
        if (d.getString("state") != "active") {
            dropRing()
            return
        }
        val parts = d.get("parts") as? Map<*, *>
        val people = ringLv.live(parts)
        if (people.isEmpty()) {
            if (parts == null || parts.isEmpty()) closeDead(chatId, id) // everybody left at the same moment: nobody closed it
            dropRing()
            return
        }
        set { it.copy(ringIn = people.map { q -> nameOf(q.uid, q.name, chatId) }) }
    }

    private fun stopRing() {
        ringReg?.remove()
        ringReg = null
        ringDropT?.let { main.removeCallbacks(it) }
        ringDropT = null
    }

    /** The ring ends without a word: refused, over, or nobody left to answer. */
    private fun dropRing() {
        if (_ui.value.phase != GPhase.RINGING) return
        handled.add(_ui.value.callId)
        stopRing()
        stopRinging()
        resetNow()
        checkRing()
    }

    /** Refuses the ring (nothing is written: the others do not hear about it). */
    fun decline() {
        if (_ui.value.phase == GPhase.RINGING) dropRing()
    }

    // ------------------------------------------------------------------------------------------------ starting, joining, leaving

    /** Starts a group call in a group chat (the permissions are asked by the screen before this). */
    fun start(chatId: String, title: String, video: Boolean) {
        if (!::app.isInitialized || myUid.isEmpty()) return
        val p = _ui.value.phase
        if (p != GPhase.IDLE && p != GPhase.ENDED) return
        if (oneToOneBusy()) return
        if (chatId.isEmpty() || chatId.contains('/') || chatId.contains('_')) return
        if (p == GPhase.ENDED) resetNow()
        val id = db.collection("gcalls").document().id
        enter(true, id, chatId, title, video, myUid, myNameFor(chatId))
    }

    /** Joins the call that rings (the one on the screen), or the call of a chat that the chat list shows. */
    fun join(chatId: String? = null) {
        if (!::app.isInitialized || myUid.isEmpty()) return
        val cur = _ui.value
        if (cur.phase == GPhase.RINGING && (chatId == null || chatId == cur.chatId)) {
            stopRing()
            stopRinging()
            enter(false, cur.callId, cur.chatId, cur.title, cur.video, cur.hostUid, cur.hostName)
            return
        }
        if (cur.phase != GPhase.IDLE && cur.phase != GPhase.ENDED) return
        if (oneToOneBusy()) return
        val c = if (chatId == null) null else chats[chatId]
        val p = c?.pointer
        if (c == null || p == null) {
            flash("ended", c?.title ?: "")
            return
        }
        if (cur.phase == GPhase.ENDED) resetNow()
        enter(false, p.id, c.id, c.title, p.video, p.host, nameOf(p.host, p.hostName, c.id))
    }

    /** A note on the screen for a call that did not start (nothing was begun, so there is nothing to finish). */
    private fun flash(note: String, title: String = "") {
        _ui.value = GCallUi(phase = GPhase.ENDED, note = note, title = title.take(60))
        scheduleReset(_ui.value.callId, note)
    }

    fun leave() {
        val p = _ui.value.phase
        if (p == GPhase.RINGING) decline()
        else if (p == GPhase.JOINING || p == GPhase.ACTIVE) finish(null, false)
    }

    /** Common start of making a call and joining one. */
    private fun enter(create: Boolean, id: String, chatId: String, title: String, video: Boolean, hostUid: String, hostName: String) {
        val g = ++gen
        sid = rid()
        myJ = nowMs()
        joined = false
        seenMine = false
        doc = null
        live = emptyList()
        lv = GroupCallRules.Liveness { nowMs() }
        lastQ = -1
        closeLinks()
        inbox.clear()
        pendingOffers.clear()
        early.clear()
        am.khosq.app.push.IceClient.refresh()
        _ui.value = GCallUi(
            phase = GPhase.JOINING,
            callId = id,
            chatId = chatId,
            title = title.take(60),
            video = video,
            hostUid = hostUid,
            hostName = hostName,
            outgoing = create,
            cameraOn = video,
            speaker = true,
            meUid = myUid,
            meName = myNameFor(chatId),
        )
        startAudioMode(true)
        CallService.start(app)
        if (!create) {
            // look at the call first: nothing is asked of the microphone for a call that is over or full
            var done = false
            val timeout = Runnable {
                if (!done && g == gen) {
                    done = true
                    afterFirst(g, create, id, chatId, title, video, null)
                }
            }
            firstT = timeout
            main.postDelayed(timeout, GroupCallRules.FIRST_DOC_MS)
            watchDoc(id, g) { snap ->
                if (!done) {
                    done = true
                    main.removeCallbacks(timeout)
                    afterFirst(g, create, id, chatId, title, video, snap)
                }
            }
            return
        }
        writeEntry(g, create, id, chatId, title, video)
    }

    private fun afterFirst(g: Int, create: Boolean, id: String, chatId: String, title: String, video: Boolean, snap: DocumentSnapshot?) {
        if (g != gen) return
        if (snap == null || !snap.exists() || snap.getString("state") != "active") {
            finish("ended", true)
            return
        }
        val people = GroupCallRules.Liveness { nowMs() }.live(snap.get("parts") as? Map<*, *>)
        if (people.isEmpty()) {
            finish("ended", true)
            return
        }
        if (people.size >= GroupCallRules.MAX_PARTS && people.none { it.uid == myUid }) {
            finish("full", true)
            return
        }
        writeEntry(g, create, id, chatId, title, video)
    }

    /** The microphone (and the camera) are taken, my entry is written, and from then on I am in the call. */
    private fun writeEntry(g: Int, create: Boolean, id: String, chatId: String, title: String, video: Boolean) {
        if (!CallPermissions.has(app, false)) {
            finish("denied", true)
            return
        }
        var noCamera = false
        var camOn = false
        try {
            val wantVideo = video && CallPermissions.has(app, true)
            if (video && !wantVideo) noCamera = true
            try {
                buildLocal(wantVideo)
                camOn = wantVideo
            } catch (e: Exception) {
                if (!wantVideo) throw e
                releaseLocal()
                buildLocal(false) // a video call can be joined with the microphone alone (no camera, or one that is busy)
                noCamera = true
            }
        } catch (e: Exception) {
            finish("noMedia", true)
            return
        }
        set { it.copy(localVideo = videoTrack, noCamera = noCamera, cameraOn = camOn) }
        watchSig(id, g)
        val name = myNameFor(chatId)
        val part = hashMapOf<String, Any>("sid" to sid, "j" to myJ, "at" to nowMs(), "name" to name, "m" to false, "cam" to camOn)
        val fail = {
            main.post {
                if (g == gen) finish("error", true)
                if (create) abandon(true, id, chatId)
            }
        }
        if (create) {
            val ts = nowMs()
            val data = hashMapOf<String, Any>(
                "chat" to chatId,
                "host" to myUid,
                "hostName" to name,
                "title" to title.take(60),
                "kind" to (if (video) "video" else "audio"),
                "state" to "active",
                "ts" to ts,
                "parts" to hashMapOf<String, Any>(myUid to part),
            )
            db.collection("gcalls").document(id).set(data)
                .addOnSuccessListener {
                    main.post {
                        if (g != gen) {
                            abandon(true, id, chatId)
                            return@post
                        }
                        db.collection("chats").document(chatId).update(
                            "gcall",
                            hashMapOf<String, Any>("id" to id, "host" to myUid, "hostName" to name, "kind" to (if (video) "video" else "audio"), "ts" to ts),
                        )
                            .addOnSuccessListener { main.post { joinedNow(g, true, id, chatId, video) } }
                            .addOnFailureListener { fail() }
                    }
                }
                .addOnFailureListener { fail() }
        } else {
            db.collection("gcalls").document(id).update("parts.$myUid", part)
                .addOnSuccessListener { main.post { joinedNow(g, false, id, chatId, video) } }
                .addOnFailureListener { fail() }
        }
    }

    private fun joinedNow(g: Int, create: Boolean, id: String, chatId: String, video: Boolean) {
        if (g != gen) {
            // I left while the writes were on their way
            abandon(create, id, chatId)
            return
        }
        joined = true
        handled.add(id)
        stopRing()
        stopRinging()
        set { it.copy(phase = GPhase.ACTIVE) }
        if (create) {
            watchDoc(id, g, null) // the document is looked at from now on
            am.khosq.app.push.PushClient.notify(chatId, "call", myNameFor(chatId), if (video) "📹" else "📞")
            startRingback()
            val r = Runnable {
                ringT = null
                if (g == gen && _ui.value.phase == GPhase.ACTIVE && live.size <= 1 && num(doc?.get("con")) == 0L) finish("noanswer", false)
            }
            ringT?.let { main.removeCallbacks(it) }
            ringT = r
            main.postDelayed(r, GroupCallRules.RING_MS)
        }
        val replay = ArrayList(early)
        early.clear()
        for (x in replay) onSig(x, g)
        if (!create) sync()
        startBeat(g)
    }

    /** What is undone when the person left a call that was still being made. */
    private fun abandon(create: Boolean, id: String, chatId: String) {
        if (create) {
            db.collection("gcalls").document(id).update(mapOf("state" to "ended", "parts.$myUid" to FieldValue.delete()))
            db.collection("chats").document(chatId).update("gcall", FieldValue.delete())
        } else {
            db.collection("gcalls").document(id).update("parts.$myUid", FieldValue.delete())
        }
    }

    // ------------------------------------------------------------------------------------------------ the local sound and picture

    private fun buildLocal(video: Boolean) {
        val f = CallManager.ensureFactory()
        val aSrc = f.createAudioSource(MediaConstraints())
        audioSource = aSrc
        val aTrack = f.createAudioTrack("khosq_ga0", aSrc)
        audioTrack = aTrack
        if (video) {
            val cap = createCapturer() ?: throw IllegalStateException("camera")
            val h = SurfaceTextureHelper.create("khosq_gcap", CallManager.eglContext())
            val vSrc = f.createVideoSource(cap.isScreencast)
            cap.initialize(h, app, vSrc.capturerObserver)
            cap.startCapture(480, 360, 20) // smaller than a one-to-one picture: up to five are sent
            val vTrack = f.createVideoTrack("khosq_gv0", vSrc)
            capturer = cap
            helper = h
            videoSource = vSrc
            videoTrack = vTrack
        }
    }

    private fun createCapturer(): CameraVideoCapturer? {
        val en = Camera2Enumerator(app)
        for (n in en.deviceNames) {
            if (en.isFrontFacing(n)) {
                val c = en.createCapturer(n, null)
                if (c != null) return c
            }
        }
        for (n in en.deviceNames) {
            val c = en.createCapturer(n, null)
            if (c != null) return c
        }
        return null
    }

    /** Lets the sound and the picture go (the light of the camera goes off); the pieces are freed a moment later. */
    private fun releaseLocal() {
        val cap = capturer
        val h = helper
        val vSrc = videoSource
        val vTrack = videoTrack
        val aSrc = audioSource
        val aTrack = audioTrack
        capturer = null
        helper = null
        videoSource = null
        videoTrack = null
        audioSource = null
        audioTrack = null
        camPausedByApp = false
        try { cap?.stopCapture() } catch (e: Exception) { }
        main.postDelayed({
            try { vTrack?.dispose() } catch (e: Exception) { }
            try { vSrc?.dispose() } catch (e: Exception) { }
            try { cap?.dispose() } catch (e: Exception) { }
            try { h?.dispose() } catch (e: Exception) { }
            try { aTrack?.dispose() } catch (e: Exception) { }
            try { aSrc?.dispose() } catch (e: Exception) { }
        }, 900L)
    }

    // ------------------------------------------------------------------------------------------------ controls

    fun toggleMute() {
        val cur = _ui.value
        if (cur.phase != GPhase.ACTIVE) return
        val track = audioTrack ?: return
        val m = !cur.muted
        track.setEnabled(!m)
        _ui.value = cur.copy(muted = m)
        db.collection("gcalls").document(cur.callId).update("parts.$myUid.m", m)
    }

    fun toggleCamera() {
        val cur = _ui.value
        if (cur.phase != GPhase.ACTIVE || !cur.video || cur.noCamera) return
        val track = videoTrack ?: return
        val on = !cur.cameraOn
        track.setEnabled(on)
        _ui.value = cur.copy(cameraOn = on)
        db.collection("gcalls").document(cur.callId).update("parts.$myUid.cam", on)
    }

    fun switchCamera() {
        try {
            capturer?.switchCamera(null)
        } catch (e: Exception) {
            // no second camera
        }
    }

    fun toggleSpeaker() {
        val s = !_ui.value.speaker
        try {
            audioManager().isSpeakerphoneOn = s
        } catch (e: Exception) {
            // the phone keeps the way it had
        }
        _ui.value = _ui.value.copy(speaker = s)
    }

    /** The app went out of sight (screen locked, another app): the camera stops, and starts again when it is back. */
    fun appVisible(visible: Boolean) {
        val c = capturer ?: return
        val s = _ui.value
        if (!s.video || (s.phase != GPhase.ACTIVE && s.phase != GPhase.JOINING)) return
        try {
            if (!visible && !camPausedByApp && s.cameraOn) {
                c.stopCapture()
                camPausedByApp = true
            } else if (visible && camPausedByApp) {
                c.startCapture(480, 360, 20)
                camPausedByApp = false
            }
        } catch (e: Exception) {
            // the camera is busy; the next change of visibility tries again
        }
    }

    // ------------------------------------------------------------------------------------------------ the pairs

    private fun publish() {
        val cur = _ui.value
        val people = ArrayList<GPerson>()
        for (p in live) {
            if (p.uid == myUid) continue
            val l = links[p.uid]
            people.add(
                GPerson(
                    uid = p.uid,
                    name = nameOf(p.uid, p.name, cur.chatId),
                    state = l?.state ?: "connecting",
                    muted = p.muted,
                    cam = p.cam,
                    video = if (l != null && l.state != "lost") l.video else null,
                ),
            )
        }
        val n = if (live.any { it.uid == myUid }) live.size else live.size + 1
        _ui.value = cur.copy(people = people, count = n)
    }

    private fun makeLink(peerUid: String, peerSid: String, lid: String, offerer: Boolean, tries: Int): Link? {
        val link = Link(peerUid, peerSid, lid, offerer, tries)
        try {
            val f = CallManager.ensureFactory()
            val cfg = PeerConnection.RTCConfiguration(CallManager.iceServers())
            cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            cfg.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            val conn = f.createPeerConnection(cfg, observerFor(link)) ?: return null
            link.conn = conn
            val a = audioTrack
            if (a != null) conn.addTrack(a, listOf("khosq_gs0"))
            val v = videoTrack
            if (v != null) conn.addTrack(v, listOf("khosq_gs0"))
        } catch (e: Exception) {
            return null
        }
        links[peerUid] = link
        val ct = Runnable {
            link.connT = null
            if (link.state == "connecting") failLink(link)
        }
        link.connT = ct
        main.postDelayed(ct, GroupCallRules.CONNECT_MS)
        return link
    }

    private fun observerFor(link: Link) = object : PeerConnection.Observer {
        override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
        override fun onAddStream(s: MediaStream?) {}
        override fun onRemoveStream(s: MediaStream?) {}
        override fun onDataChannel(d: org.webrtc.DataChannel?) {}
        override fun onRenegotiationNeeded() {}

        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {
            if (s == PeerConnection.IceGatheringState.COMPLETE) main.post { if (links[link.peerUid] === link) flushOut(link) }
        }

        override fun onIceCandidate(c: IceCandidate?) {
            if (c == null) return
            main.post {
                if (links[link.peerUid] !== link || link.closed) return@post
                link.out.add(hashMapOf<String, Any>("m" to (c.sdpMid ?: ""), "i" to c.sdpMLineIndex, "s" to c.sdp))
                if (link.out.size >= 12) {
                    flushOut(link)
                } else if (link.outT == null) {
                    val r = Runnable { flushOut(link) }
                    link.outT = r
                    main.postDelayed(r, GroupCallRules.BATCH_MS)
                }
            }
        }

        override fun onTrack(t: RtpTransceiver?) {
            val track = t?.receiver?.track() ?: return
            if (track is VideoTrack) {
                main.post {
                    if (links[link.peerUid] !== link || link.closed) return@post
                    track.setEnabled(true)
                    link.video = track
                    publish()
                }
            }
        }

        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
            main.post {
                if (links[link.peerUid] !== link || link.closed) return@post
                when (s) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        link.graceT?.let { main.removeCallbacks(it) }
                        link.connT?.let { main.removeCallbacks(it) }
                        link.graceT = null
                        link.connT = null
                        link.state = "connected"
                        stopRingback()
                        if (_ui.value.startedAt == 0L) _ui.value = _ui.value.copy(startedAt = nowMs())
                        publish()
                        quality(true)
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        link.graceT?.let { main.removeCallbacks(it) }
                        val r = Runnable { failLink(link) }
                        link.graceT = r
                        main.postDelayed(r, GroupCallRules.GRACE_MS)
                    }
                    PeerConnection.IceConnectionState.FAILED -> failLink(link)
                    else -> Unit
                }
            }
        }
    }

    private fun flushOut(link: Link) {
        link.outT?.let { main.removeCallbacks(it) }
        link.outT = null
        if (link.out.isEmpty()) return
        val c = ArrayList<Map<String, Any>>(link.out)
        link.out.clear()
        sendSig(link.peerUid, link.peerSid, link.lid, "cand", null, c)
    }

    private fun stopTimers(link: Link) {
        link.outT?.let { main.removeCallbacks(it) }
        link.connT?.let { main.removeCallbacks(it) }
        link.graceT?.let { main.removeCallbacks(it) }
        link.outT = null
        link.connT = null
        link.graceT = null
    }

    private fun closeConn(link: Link) {
        link.closed = true
        val conn = link.conn
        link.conn = null
        try { conn?.close() } catch (e: Exception) { }
        main.postDelayed({ try { conn?.dispose() } catch (e: Exception) { } }, 700L)
    }

    private fun dropLink(link: Link) {
        stopTimers(link)
        if (links[link.peerUid] === link) links.remove(link.peerUid)
        closeConn(link)
    }

    private fun closeLinks() {
        for (l in ArrayList(links.values)) dropLink(l)
        links.clear()
    }

    /** A pair that did not work: it is let go and shown as lost; the one who offers tries again a little later (RETRIES times). */
    private fun failLink(link: Link) {
        if (links[link.peerUid] !== link || _ui.value.phase != GPhase.ACTIVE) return
        val g = gen
        stopTimers(link)
        closeConn(link)
        link.state = "lost"
        link.video = null
        publish()
        if (link.offerer && link.tries < GroupCallRules.RETRIES) {
            main.postDelayed({
                if (g != gen || _ui.value.phase != GPhase.ACTIVE || links[link.peerUid] !== link) return@postDelayed
                val p = live.firstOrNull { it.uid == link.peerUid && it.sid == link.peerSid } ?: return@postDelayed
                links.remove(link.peerUid)
                startOffer(p, link.tries + 1)
                publish()
            }, GroupCallRules.RETRY_MS)
        }
    }

    private fun startOffer(p: GroupCallRules.Person, tries: Int) {
        val link = makeLink(p.uid, p.sid, rid(), true, tries) ?: return
        val conn = link.conn ?: return
        val fail = { main.post { failLink(link) }; Unit }
        conn.createOffer(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription?) {
                if (d == null) return
                conn.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(x: SessionDescription?) {}
                    override fun onCreateFailure(e: String?) {}
                    override fun onSetSuccess() {
                        main.post {
                            if (links[p.uid] !== link || link.closed) return@post
                            sendSig(p.uid, p.sid, link.lid, "offer", d.description, null)
                        }
                    }
                    override fun onSetFailure(e: String?) {
                        fail()
                    }
                }, d)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(e: String?) {
                fail()
            }
            override fun onSetFailure(e: String?) {
                fail()
            }
        }, MediaConstraints())
    }

    private fun sendSig(to: String, tsid: String, lid: String, k: String, sdp: String?, c: List<Map<String, Any>>?) {
        val id = _ui.value.callId
        if (id.isEmpty()) return
        val body = hashMapOf<String, Any>("from" to myUid, "to" to to, "fsid" to sid, "tsid" to tsid, "lid" to lid, "k" to k, "at" to nowMs())
        if (sdp != null) body["sdp"] = sdp
        if (c != null) body["c"] = c
        db.collection("gcalls").document(id).collection("sig").add(body) // one message less is not the end: the pair is tried again
    }

    private fun addRemote(link: Link, c: Map<String, Any>) {
        val conn = link.conn ?: return
        val mid = str(c["m"])
        val ice = IceCandidate(mid, num(c["i"]).toInt(), str(c["s"]))
        try {
            conn.addIceCandidate(ice)
        } catch (e: Exception) {
            // an address that does not fit is only one address less
        }
    }

    private fun flushRemote(link: Link) {
        val list = ArrayList(link.remoteCand.map { ic -> hashMapOf<String, Any>("m" to ic.sdpMid, "i" to ic.sdpMLineIndex, "s" to ic.sdp) })
        link.remoteCand.clear()
        for (c in list) addRemote(link, c)
        val key = link.peerUid + "|" + link.lid
        val early = inbox.remove(key)
        if (early != null) for (c in early) addRemote(link, c)
    }

    private fun candList(x: Map<String, Any>): List<Map<String, Any>> {
        val raw = x["c"] as? List<*> ?: return emptyList()
        val out = ArrayList<Map<String, Any>>()
        for (c in raw) {
            val m = c as? Map<*, *> ?: continue
            val s = str(m["s"])
            if (s.isEmpty()) continue
            out.add(hashMapOf<String, Any>("m" to str(m["m"]), "i" to num(m["i"]), "s" to s))
            if (out.size >= 40) break
        }
        return out
    }

    private fun onSig(x: Map<String, Any>, g: Int) {
        if (g != gen) return
        val phase = _ui.value.phase
        if (phase == GPhase.JOINING) {
            if (early.size < 200) early.add(x)
            return
        }
        if (phase != GPhase.ACTIVE) return
        val from = str(x["from"])
        if (from.isEmpty() || from == myUid || str(x["to"]) != myUid || str(x["tsid"]) != sid) return // not for this session of mine
        val lid = str(x["lid"])
        if (lid.isEmpty()) return
        when (x["k"]) {
            "offer" -> {
                val sdp = str(x["sdp"])
                if (sdp.isEmpty() || sdp.length > 200_000 || str(x["fsid"]).isEmpty()) return
                val p = live.firstOrNull { it.uid == from }
                if (p == null || p.sid != str(x["fsid"])) {
                    pendingOffers[from] = Pair(x, nowMs()) // the call document may not show them yet: asked again when it changes
                    return
                }
                answer(from, x)
            }
            "answer" -> {
                val link = links[from] ?: return
                if (link.lid != lid || !link.offerer || link.answered) return
                val sdp = str(x["sdp"])
                if (sdp.isEmpty() || sdp.length > 200_000) return
                link.answered = true
                val conn = link.conn ?: return
                conn.setRemoteDescription(object : SdpObserver {
                    override fun onCreateSuccess(d: SessionDescription?) {}
                    override fun onCreateFailure(e: String?) {}
                    override fun onSetSuccess() {
                        main.post {
                            if (links[from] !== link || link.closed) return@post
                            link.remoteSet = true
                            flushRemote(link)
                        }
                    }
                    override fun onSetFailure(e: String?) {
                        main.post { failLink(link) }
                    }
                }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
            }
            "cand" -> {
                val list = candList(x)
                if (list.isEmpty()) return
                val link = links[from]
                if (link != null && link.lid == lid) {
                    if (link.remoteSet) {
                        for (c in list) addRemote(link, c)
                    } else {
                        for (c in list) link.remoteCand.add(IceCandidate(str(c["m"]), num(c["i"]).toInt(), str(c["s"])))
                    }
                } else {
                    val key = "$from|$lid"
                    if (!inbox.containsKey(key) && inbox.size >= 40) {
                        val first = inbox.keys.iterator().next()
                        inbox.remove(first)
                    }
                    val all = ArrayList<Map<String, Any>>(inbox[key] ?: ArrayList())
                    all.addAll(list)
                    inbox[key] = ArrayList(all.take(80))
                }
            }
        }
    }

    private fun answer(from: String, x: Map<String, Any>) {
        val mine = links[from]
        val me = live.firstOrNull { it.uid == myUid }
        val p = live.firstOrNull { it.uid == from }
        // the later of the two makes the offer (both read the same numbers): an offer from the one who should be answering is not taken
        if (me != null && p != null && GroupCallRules.iOffer(me.j, myUid, p.j, p.uid)) return
        val xl = str(x["lid"])
        if (mine != null && mine.lid == xl) return // the same offer again
        if (mine != null) dropLink(mine)
        val g = gen
        val link = makeLink(from, str(x["fsid"]), xl, false, 0) ?: return
        val conn = link.conn ?: return
        val fail = { main.post { failLink(link) }; Unit }
        conn.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(d: SessionDescription?) {}
            override fun onCreateFailure(e: String?) {}
            override fun onSetFailure(e: String?) {
                fail()
            }
            override fun onSetSuccess() {
                main.post {
                    if (g != gen || links[from] !== link || link.closed) return@post
                    link.remoteSet = true
                    flushRemote(link)
                    conn.createAnswer(object : SdpObserver {
                        override fun onSetSuccess() {}
                        override fun onSetFailure(e: String?) {
                            fail()
                        }
                        override fun onCreateFailure(e: String?) {
                            fail()
                        }
                        override fun onCreateSuccess(d: SessionDescription?) {
                            if (d == null) return
                            conn.setLocalDescription(object : SdpObserver {
                                override fun onCreateSuccess(y: SessionDescription?) {}
                                override fun onCreateFailure(e: String?) {}
                                override fun onSetFailure(e: String?) {
                                    fail()
                                }
                                override fun onSetSuccess() {
                                    main.post {
                                        if (g != gen || links[from] !== link || link.closed) return@post
                                        sendSig(from, link.peerSid, link.lid, "answer", d.description, null)
                                        publish()
                                    }
                                }
                            }, d)
                        }
                    }, MediaConstraints())
                }
            }
        }, SessionDescription(SessionDescription.Type.OFFER, str(x["sdp"])))
    }

    /** The video this device sends is made lighter when more people are in the call. */
    private fun quality(force: Boolean) {
        val n = live.size
        if (!force && n == lastQ) return
        lastQ = n
        val q = GroupCallRules.qualityFor(n)
        for (l in links.values) {
            val conn = l.conn ?: continue
            if (l.closed) continue
            try {
                for (s in conn.senders) {
                    val t = s.track() ?: continue
                    if (t.kind() != "video") continue
                    val p = s.parameters
                    val encs = p.encodings
                    if (encs == null || encs.isEmpty()) continue
                    encs[0].maxBitrateBps = if (q.maxBitrate > 0) q.maxBitrate else null
                    encs[0].scaleResolutionDownBy = q.scale
                    s.setParameters(p)
                }
            } catch (e: Exception) {
                // the picture stays as it is
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ the call document

    /** Looks at the document again: who is there, which pairs must exist, who has to leave, whether I am alone. */
    private fun sync() {
        if (_ui.value.phase != GPhase.ACTIVE) return
        val d = doc ?: return
        val parts = d["parts"] as? Map<*, *>
        val all = lv.live(parts)
        live = all.take(GroupCallRules.MAX_PARTS) // when more came at once, the later ones are not in the call (they leave by themselves, below)
        val mineEntry = parts?.get(myUid) as? Map<*, *>
        if (mineEntry != null && mineEntry["sid"] == sid) seenMine = true
        if (mineEntry == null && !seenMine) return // the document shown is the one from before my entry was written: the next one has it
        if (mineEntry == null || mineEntry["sid"] != sid) {
            // my entry is gone or is another session's (the same account joined on another device): this one is out, and must not touch the entry
            if (joined) finish("elsewhere", true)
            return
        }
        val me = all.firstOrNull { it.uid == myUid } ?: return
        if (all.indexOf(me) >= GroupCallRules.MAX_PARTS) {
            finish("full", false)
            return
        }
        val want = HashSet<String>()
        for (p in live) {
            if (p.uid == myUid) continue
            want.add(p.uid)
            var link = links[p.uid]
            if (link != null && link.peerSid != p.sid) {
                dropLink(link)
                link = null
            }
            val wait = pendingOffers[p.uid]
            if (wait != null) {
                pendingOffers.remove(p.uid)
                if (str(wait.first["fsid"]) == p.sid && nowMs() - wait.second < 30_000L) answer(p.uid, wait.first)
                link = links[p.uid]
            }
            if (link == null && GroupCallRules.iOffer(me.j, myUid, p.j, p.uid)) startOffer(p, 0)
        }
        for (link in ArrayList(links.values)) if (!want.contains(link.peerUid)) dropLink(link)
        for (k in ArrayList(pendingOffers.keys)) {
            val t = pendingOffers[k]?.second ?: 0L
            if (nowMs() - t > 30_000L) pendingOffers.remove(k) // an offer waits for the person's entry to show, but not for ever
        }
        publish()
        quality(false)
        // the first of the people there marks the moment two of them were together (the length of the call)
        if (live.size >= 2 && num(d["con"]) == 0L && live[0].uid == myUid) {
            val t = nowMs()
            doc = d + ("con" to t)
            db.collection("gcalls").document(_ui.value.callId).update("con", t)
        }
        if (live.size == 1 && num(doc?.get("con")) > 0L) {
            if (aloneT == null) {
                val g = gen
                val r = Runnable {
                    aloneT = null
                    if (g == gen && _ui.value.phase == GPhase.ACTIVE && live.size == 1) finish(null, false)
                }
                aloneT = r
                main.postDelayed(r, GroupCallRules.ALONE_MS)
            }
        } else {
            aloneT?.let { main.removeCallbacks(it) }
            aloneT = null
        }
    }

    private fun onCallDoc(g: Int, d: DocumentSnapshot?) {
        if (g != gen) return
        if (d == null || !d.exists()) {
            doc = null
            if (_ui.value.phase == GPhase.ACTIVE && joined) finish(null, true)
            return
        }
        doc = d.data
        if (d.getString("state") == "ended") {
            if (_ui.value.phase == GPhase.ACTIVE) finish(null, true) // (a join that finds it over says so itself)
            return
        }
        sync()
    }

    private fun watchDoc(id: String, g: Int, first: ((DocumentSnapshot?) -> Unit)?) {
        docReg?.remove()
        var cb = first
        docReg = db.collection("gcalls").document(id).addSnapshotListener { d, err ->
            if (err != null) {
                main.post {
                    if (g != gen) return@post
                    cb?.invoke(null)
                    cb = null
                    val ph = _ui.value.phase
                    if (ph != GPhase.IDLE && ph != GPhase.ENDED) {
                        // a listener that failed does not come back by itself: look again soon
                        docRetryT?.let { main.removeCallbacks(it) }
                        val r = Runnable {
                            docRetryT = null
                            if (g == gen) watchDoc(id, g, null)
                        }
                        docRetryT = r
                        main.postDelayed(r, 5000L)
                    }
                }
                return@addSnapshotListener
            }
            main.post {
                if (g != gen) return@post
                onCallDoc(g, d)
                cb?.invoke(d)
                cb = null
            }
        }
    }

    private fun watchSig(id: String, g: Int) {
        sigReg?.remove()
        sigReg = db.collection("gcalls").document(id).collection("sig").whereEqualTo("to", myUid)
            .addSnapshotListener { snap, err ->
                if (err != null || snap == null) return@addSnapshotListener
                val list = ArrayList<Map<String, Any>>()
                for (ch in snap.documentChanges) {
                    if (ch.type != DocumentChange.Type.ADDED) continue
                    val data = ch.document.data
                    list.add(data)
                }
                main.post { for (x in list) onSig(x, g) }
            }
    }

    private fun startBeat(g: Int) {
        beatT?.let { main.removeCallbacks(it) }
        val r = Runnable {
            if (g != gen || _ui.value.phase != GPhase.ACTIVE) return@Runnable
            db.collection("gcalls").document(_ui.value.callId).update("parts.$myUid.at", nowMs()) // a sign of life
            sync() // people whose sign of life stopped are dropped even when nothing else is written
            startBeat(g)
        }
        beatT = r
        main.postDelayed(r, GroupCallRules.BEAT_MS)
    }

    // ------------------------------------------------------------------------------------------------ the end

    /** The "call" line in the group's chat, written by the one who closes the call. Returns the length in seconds (or null: no line). */
    private fun logCall(cur: GCallUi, d: Map<String, Any>?, note: String?): Long? {
        val hosted = cur.hostUid == myUid
        var code = ""
        var sec = 0L
        val con = num(d?.get("con"))
        if (con > 0L) {
            code = "call_done"
            sec = maxOf(0L, (nowMs() - con) / 1000L)
        } else if (hosted && note == "noanswer") {
            code = "call_missed"
        } else if (hosted && note == null) {
            code = "call_cancelled"
        }
        if (code.isEmpty()) return null
        db.collection("chats").document(cur.chatId).collection("messages").add(
            hashMapOf<String, Any>(
                "from" to myUid,
                "text" to "",
                "kind" to "call",
                "code" to code,
                "vid" to cur.video,
                "dur" to sec,
                "ts" to FieldValue.serverTimestamp(),
            ),
        )
        return sec
    }

    private fun clearPointer(chatId: String, id: String) {
        val p = chats[chatId]?.pointer
        if (p == null || p.id != id) return
        db.collection("chats").document(chatId).update("gcall", FieldValue.delete())
    }

    private fun closeDead(chatId: String, id: String) {
        db.collection("gcalls").document(id).update("state", "ended")
        clearPointer(chatId, id)
    }

    private fun resetNow() {
        resetT?.let { main.removeCallbacks(it) }
        resetT = null
        _ui.value = GCallUi()
    }

    private fun scheduleReset(endedId: String, note: String?) {
        resetT?.let { main.removeCallbacks(it) }
        val r = Runnable {
            resetT = null
            val c = _ui.value
            if (c.phase == GPhase.ENDED && c.callId == endedId) resetNow()
            checkRing()
        }
        resetT = r
        val long = note == "denied" || note == "noMedia" || note == "full"
        main.postDelayed(r, if (note.isNullOrEmpty()) 400L else if (long) GroupCallRules.NOTE_LONG_MS else GroupCallRules.NOTE_MS)
    }

    /** Leaves the call in progress: everything is let go (the camera and microphone light goes off), and the reason stays on the screen a moment. */
    private fun finish(note: String?, quiet: Boolean) {
        val cur = _ui.value
        if (cur.phase == GPhase.IDLE || cur.phase == GPhase.ENDED) return
        val d = doc
        val wasLive = live
        val wasIn = joined
        val mineSid = sid
        joined = false
        for (t in listOf(beatT, ringT, aloneT, docRetryT, firstT)) if (t != null) main.removeCallbacks(t)
        beatT = null
        ringT = null
        aloneT = null
        docRetryT = null
        firstT = null
        docReg?.remove()
        sigReg?.remove()
        docReg = null
        sigReg = null
        closeLinks()
        inbox.clear()
        pendingOffers.clear()
        doc = null
        live = emptyList()
        gen++
        releaseLocal()
        stopRing()
        stopRinging()
        stopRingback()
        stopAudioMode()
        if (cur.callId.isNotEmpty()) handled.add(cur.callId)
        _ui.value = cur.copy(phase = GPhase.ENDED, note = note ?: "", localVideo = null, people = emptyList(), count = 0, muted = false)
        if (wasIn && !quiet && cur.callId.isNotEmpty()) {
            // my entry goes; the last one out closes the call, writes the line and takes the sign out of the chat
            val ref = db.collection("gcalls").document(cur.callId)
            val dParts = d?.get("parts") as? Map<*, *>
            val mineNow = dParts?.get(myUid) as? Map<*, *>
            val other = mineNow != null && mineNow["sid"] != mineSid // the same account is in the call on another device
            val others = wasLive.count { it.uid != myUid }
            if (!other) {
                if (others == 0) {
                    val sec = logCall(cur, d, note) ?: 0L
                    ref.update(mapOf("parts.$myUid" to FieldValue.delete(), "state" to "ended", "dur" to sec))
                    clearPointer(cur.chatId, cur.callId)
                } else {
                    ref.update("parts.$myUid", FieldValue.delete())
                }
            }
        }
        scheduleReset(cur.callId, note)
    }

    // ------------------------------------------------------------------------------------------------ the bar of the open chat

    /** Tells [banner] about the call of this chat for as long as the chat is open (call it with null when the chat closes). */
    fun watchBanner(chatId: String?) {
        val old = bw
        if (old != null) dropBanner(old)
        bw = null
        _banner.value = null
        if (chatId.isNullOrEmpty() || !::app.isInitialized) return
        bw = Banner(chatId)
        bannerRefresh()
    }

    private fun dropBanner(w: Banner) {
        w.reg?.remove()
        w.reg = null
        w.tick?.let { main.removeCallbacks(it) }
        w.tick = null
        w.id = ""
    }

    private fun bannerRefresh() {
        val w = bw ?: return
        val p = chats[w.chatId]?.pointer
        if (p == null) {
            dropBanner(w)
            w.doc = null
            _banner.value = null
            return
        }
        if (w.id == p.id) return
        dropBanner(w)
        w.id = p.id
        w.ptr = p
        w.doc = null
        w.lv = GroupCallRules.Liveness { nowMs() }
        w.reg = db.collection("gcalls").document(p.id).addSnapshotListener { d, err ->
            if (err != null) {
                main.post { if (bw === w) _banner.value = null }
                return@addSnapshotListener
            }
            main.post {
                if (bw !== w || w.id != p.id) return@post
                w.doc = if (d != null && d.exists()) d.data else null
                if (w.doc == null && nowMs() - p.ts > GroupCallRules.DEAD_MS) closeDead(w.chatId, p.id)
                bannerEval(w)
            }
        }
    }

    private fun bannerEval(w: Banner) {
        if (bw !== w) return
        w.tick?.let { main.removeCallbacks(it) }
        val t = Runnable { bannerEval(w) }
        w.tick = t
        main.postDelayed(t, 20_000L)
        val x = w.doc
        if (x == null || str(x["state"]) != "active") {
            _banner.value = null
            return
        }
        val parts = x["parts"] as? Map<*, *>
        val people = w.lv.live(parts)
        if (people.isEmpty()) {
            _banner.value = null
            val ptr = w.ptr
            if (parts == null || parts.isEmpty() || (ptr != null && nowMs() - ptr.ts > GroupCallRules.DEAD_MS)) closeDead(w.chatId, w.id)
            return
        }
        val cur = _ui.value
        val mine = cur.callId == w.id && (cur.phase == GPhase.ACTIVE || cur.phase == GPhase.JOINING)
        _banner.value = GBanner(
            callId = w.id,
            chatId = w.chatId,
            video = str(x["kind"]) == "video",
            hostName = nameOf(str(x["host"]), str(x["hostName"]), w.chatId),
            people = people.map { q -> nameOf(q.uid, q.name, w.chatId) },
            count = minOf(people.size, GroupCallRules.MAX_PARTS),
            full = people.size >= GroupCallRules.MAX_PARTS,
            mine = mine,
        )
    }

    // ------------------------------------------------------------------------------------------------ audio (the same as a one-to-one call)

    private fun audioManager() = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun startAudioMode(speaker: Boolean) {
        if (audioActive) return
        audioActive = true
        val am = audioManager()
        savedMode = am.mode
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        focus = req
        am.requestAudioFocus(req)
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isSpeakerphoneOn = speaker
    }

    private fun stopAudioMode() {
        if (!audioActive) return
        audioActive = false
        val am = audioManager()
        am.isSpeakerphoneOn = false
        am.mode = savedMode
        focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun startRinging() {
        try {
            val r = RingtoneManager.getRingtone(app, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            if (Build.VERSION.SDK_INT >= 28) r.isLooping = true
            r.play()
            ringtone = r
        } catch (e: Exception) {
            ringtone = null
        }
        try {
            val v = app.getSystemService(Vibrator::class.java)
            v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 700), 0))
        } catch (e: Exception) {
            // no vibration
        }
    }

    private fun stopRinging() {
        try { ringtone?.stop() } catch (e: Exception) { }
        ringtone = null
        try { app.getSystemService(Vibrator::class.java)?.cancel() } catch (e: Exception) { }
    }

    private fun startRingback() {
        if (tone != null) return
        try {
            val t = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 60)
            t.startTone(ToneGenerator.TONE_SUP_RINGTONE)
            tone = t
        } catch (e: Exception) {
            tone = null
        }
    }

    private fun stopRingback() {
        try { tone?.stopTone(); tone?.release() } catch (e: Exception) { }
        tone = null
    }
}

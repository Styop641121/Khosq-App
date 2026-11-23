package am.khosq.app.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

enum class Phase { IDLE, OUTGOING, INCOMING, CONNECTING, CONNECTED, ENDED }

data class CallUi(
    val phase: Phase = Phase.IDLE,
    val callId: String = "",
    val peerUid: String = "",
    val peerName: String = "",
    val video: Boolean = false,
    val muted: Boolean = false,
    val speaker: Boolean = false,
    val cameraOn: Boolean = true,
    val remoteVideo: VideoTrack? = null,
    val localVideo: VideoTrack? = null,
    val note: String = "",
    val startedAt: Long = 0L,
)

object CallPermissions {
    fun needed(video: Boolean): Array<String> =
        if (video) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        else arrayOf(Manifest.permission.RECORD_AUDIO)

    fun has(c: Context, video: Boolean): Boolean =
        needed(video).all { p -> ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED }
}

/**
 * One-to-one voice/video calls over WebRTC. Signalling goes through Firestore (calls/{id}).
 * Lives as long as the process; the app and the background service both use it.
 */
object CallManager {
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val db get() = FirebaseFirestore.getInstance()

    private val _ui = MutableStateFlow(CallUi())
    val ui: StateFlow<CallUi> = _ui.asStateFlow()

    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null

    private var incomingReg: ListenerRegistration? = null
    private var callReg: ListenerRegistration? = null
    private var candReg: ListenerRegistration? = null
    private var listenedUid: String? = null

    private var isCaller = false
    private var myUidCur = ""
    private var pendingOffer: SessionDescription? = null
    private var remoteSet = false
    private var answerApplied = false
    private var docReady = false
    private val remoteCand = ArrayList<IceCandidate>()
    private val outCand = ArrayList<IceCandidate>()

    private var ringtone: Ringtone? = null
    private var tone: ToneGenerator? = null
    private var savedMode = AudioManager.MODE_NORMAL
    private var focus: AudioFocusRequest? = null
    private var audioActive = false

    private val ringTimeout = Runnable {
        if (_ui.value.phase == Phase.OUTGOING) {
            setState("missed")
            finish("Չպատասխանեց")
        }
    }
    /** A call that has been answered but never gets a connection must not hang on "Connecting" forever. */
    private val connectTimeout = Runnable {
        if (_ui.value.phase == Phase.CONNECTING) finish("Կապը չհաջողվեց")
    }
    private val graceTimeout = Runnable {
        val p = _ui.value.phase
        if (p == Phase.CONNECTED || p == Phase.CONNECTING) finish("Կապը խզվեց")
    }

    fun init(ctx: Context) {
        if (!::app.isInitialized) {
            app = ctx.applicationContext
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions(),
            )
        }
    }

    fun eglContext(): EglBase.Context {
        var e = egl
        if (e == null) {
            e = EglBase.create()
            egl = e
        }
        return e!!.eglBaseContext
    }

    private fun ensureFactory(): PeerConnectionFactory {
        var f = factory
        if (f == null) {
            val ctx = eglContext()
            f = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(ctx, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(ctx))
                .createPeerConnectionFactory()
            factory = f
        }
        return f!!
    }

    // ---------------------------------------------------------------- incoming

    fun listen(uid: String) {
        if (!::app.isInitialized) return
        if (listenedUid == uid) return
        listenedUid = uid
        am.khosq.app.push.IceClient.refresh()
        incomingReg?.remove()
        incomingReg = db.collection("calls")
            .whereEqualTo("callee", uid)
            .whereEqualTo("state", "ringing")
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    // a failed listener never comes back by itself: drop it and listen again soon
                    main.postDelayed({
                        incomingReg?.remove()
                        incomingReg = null
                        if (listenedUid == uid) {
                            listenedUid = null
                            listen(uid)
                        }
                    }, 5000L)
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener
                val docs = snap.documents
                main.post { onIncoming(docs) }
            }
    }

    private fun onIncoming(docs: List<DocumentSnapshot>) {
        val now = System.currentTimeMillis()
        for (d in docs) {
            val ts = d.getLong("ts") ?: 0L
            if (now - ts > 120_000L) continue
            val cur = _ui.value
            if (cur.phase != Phase.IDLE) {
                if (d.id != cur.callId) db.collection("calls").document(d.id).update("state", "declined")
                continue
            }
            val offer = d.get("offer") as? Map<*, *>
            val sdp = offer?.get("sdp") as? String ?: continue
            pendingOffer = SessionDescription(SessionDescription.Type.OFFER, sdp)
            val video = d.getString("kind") == "video"
            _ui.value = CallUi(
                phase = Phase.INCOMING,
                callId = d.id,
                peerUid = d.getString("caller") ?: "",
                peerName = d.getString("callerName") ?: "KHOSQ",
                video = video,
                speaker = video,
                cameraOn = video,
            )
            isCaller = false
            am.khosq.app.push.IceClient.refresh()
            startRinging()
            watchCall(d.id)
            return
        }
    }

    fun accept() {
        val cur = _ui.value
        val offer = pendingOffer
        if (cur.phase != Phase.INCOMING || offer == null) return
        stopRinging()
        _ui.value = cur.copy(phase = Phase.CONNECTING)
        main.removeCallbacks(connectTimeout)
        main.postDelayed(connectTimeout, 30_000L)
        startAudioMode(cur.speaker)
        CallService.start(app)
        try {
            buildPeer(cur.video)
        } catch (e: Exception) {
            setState("ended")
            finish("Սխալ՝ " + (e.message ?: "զանգը չհաջողվեց"))
            return
        }
        val conn = pc ?: return
        conn.setRemoteDescription(object : Sdp() {
            override fun onSetSuccess() {
                main.post {
                    remoteSet = true
                    flushRemote()
                    conn.createAnswer(object : Sdp() {
                        override fun onCreateSuccess(d: SessionDescription?) {
                            if (d == null) return
                            conn.setLocalDescription(object : Sdp() {
                                override fun onSetSuccess() {
                                    db.collection("calls").document(cur.callId).update(
                                        mapOf(
                                            "answer" to mapOf("type" to "answer", "sdp" to d.description),
                                            "state" to "accepted",
                                        ),
                                    )
                                }
                            }, d)
                        }
                    }, MediaConstraints())
                }
            }
        }, offer)
        docReady = true
        watchCandidates("callerCandidates")
    }

    fun decline() {
        val cur = _ui.value
        if (cur.phase != Phase.INCOMING) return
        setState("declined")
        finish(null)
    }

    // ---------------------------------------------------------------- outgoing

    fun startOutgoing(myUid: String, myName: String, peerUid: String, peerName: String, video: Boolean) {
        if (!::app.isInitialized || _ui.value.phase != Phase.IDLE || peerUid.isEmpty()) return
        isCaller = true
        myUidCur = myUid
        am.khosq.app.push.IceClient.refresh()
        val id = db.collection("calls").document().id
        _ui.value = CallUi(
            phase = Phase.OUTGOING,
            callId = id,
            peerUid = peerUid,
            peerName = peerName,
            video = video,
            speaker = video,
            cameraOn = video,
        )
        startAudioMode(video)
        startRingback()
        CallService.start(app)
        try {
            buildPeer(video)
        } catch (e: Exception) {
            finish("Սխալ՝ " + (e.message ?: "զանգը չհաջողվեց"))
            return
        }
        val conn = pc ?: return
        conn.createOffer(object : Sdp() {
            override fun onCreateSuccess(d: SessionDescription?) {
                if (d == null) return
                conn.setLocalDescription(object : Sdp() {
                    override fun onSetSuccess() {
                        main.post {
                            val data = hashMapOf<String, Any>(
                                "caller" to myUid,
                                "callerName" to myName,
                                "callee" to peerUid,
                                "calleeName" to peerName,
                                "kind" to (if (video) "video" else "audio"),
                                "state" to "ringing",
                                "ts" to System.currentTimeMillis(),
                                "offer" to mapOf("type" to "offer", "sdp" to d.description),
                            )
                            db.collection("calls").document(id).set(data)
                                .addOnSuccessListener {
                                    main.post {
                                        if (_ui.value.callId != id) return@post
                                        docReady = true
                                        val a = if (myUid < peerUid) myUid + "_" + peerUid else peerUid + "_" + myUid
                                        am.khosq.app.push.PushClient.notify(
                                            a, "call", myName, if (video) "📹" else "📞",
                                        )
                                        for (c in outCand) pushCandidate(c)
                                        outCand.clear()
                                        watchCall(id)
                                        watchCandidates("calleeCandidates")
                                        main.postDelayed(ringTimeout, 45_000L)
                                    }
                                }
                                .addOnFailureListener { e ->
                                    main.post { finish("Սխալ՝ " + (e.message ?: "")) }
                                }
                        }
                    }
                }, d)
            }
        }, MediaConstraints())
    }

    fun hangup() {
        val p = _ui.value.phase
        when (p) {
            Phase.INCOMING -> decline()
            Phase.IDLE, Phase.ENDED -> Unit
            else -> {
                setState("ended")
                finish(null)
            }
        }
    }

    // ---------------------------------------------------------------- controls

    fun toggleMute() {
        val m = !_ui.value.muted
        audioTrack?.setEnabled(!m)
        _ui.value = _ui.value.copy(muted = m)
    }

    fun toggleSpeaker() {
        val s = !_ui.value.speaker
        setSpeaker(s)
        _ui.value = _ui.value.copy(speaker = s)
        updateProximity()
    }

    private var prox: PowerManager.WakeLock? = null
    private var camPausedByApp = false

    /** In a voice call held to the ear the screen goes dark by itself (so a cheek cannot press buttons). */
    private fun updateProximity() {
        val s = _ui.value
        val want = s.phase == Phase.CONNECTED && !s.video && !s.speaker
        try {
            if (want) {
                if (prox == null) {
                    val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
                    if (pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                        prox = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "khosq:proximity")
                    }
                }
                val w = prox
                if (w != null && !w.isHeld) w.acquire(4L * 3600L * 1000L)
            } else {
                val w = prox
                if (w != null && w.isHeld) w.release()
            }
        } catch (e: Exception) {
            // no proximity sensor: the screen simply stays on
        }
    }

    /** The app went out of sight (screen locked, another app): the camera stops, and starts again when it is back. */
    fun appVisible(visible: Boolean) {
        val c = capturer ?: return
        val s = _ui.value
        if (!s.video || s.phase == Phase.IDLE || s.phase == Phase.ENDED) return
        try {
            if (!visible && !camPausedByApp && s.cameraOn) {
                c.stopCapture()
                camPausedByApp = true
            } else if (visible && camPausedByApp) {
                c.startCapture(640, 480, 24)
                camPausedByApp = false
            }
        } catch (e: Exception) {
            // the camera is busy; the next change of visibility tries again
        }
    }

    fun toggleCamera() {
        val on = !_ui.value.cameraOn
        videoTrack?.setEnabled(on)
        _ui.value = _ui.value.copy(cameraOn = on)
    }

    fun switchCamera() {
        try {
            capturer?.switchCamera(null)
        } catch (e: Exception) {
            // no second camera
        }
    }

    // ---------------------------------------------------------------- WebRTC

    private fun fallbackIce(): List<PeerConnection.IceServer> = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
            .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
            .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
        PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443?transport=tcp")
            .setUsername("openrelayproject").setPassword("openrelayproject").createIceServer(),
    )

    /** Cloudflare's TURN when we have fresh settings from the Worker, the free public servers otherwise. */
    private fun iceServers(): List<PeerConnection.IceServer> {
        val own = am.khosq.app.push.IceClient.fresh()
        return if (own.isEmpty()) fallbackIce() else own + fallbackIce().take(2)
    }

    private fun buildPeer(video: Boolean) {
        remoteSet = false
        answerApplied = false
        docReady = false
        remoteCand.clear()
        outCand.clear()
        val f = ensureFactory()
        val cfg = PeerConnection.RTCConfiguration(iceServers())
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        cfg.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        val conn = f.createPeerConnection(cfg, observer) ?: throw IllegalStateException("PeerConnection")
        pc = conn

        val aSrc = f.createAudioSource(MediaConstraints())
        audioSource = aSrc
        val aTrack = f.createAudioTrack("khosq_a0", aSrc)
        audioTrack = aTrack
        conn.addTrack(aTrack, listOf("khosq_s0"))

        if (video) {
            val cap = createCapturer() ?: throw IllegalStateException("Տեսախցիկ չի գտնվել")
            val h = SurfaceTextureHelper.create("khosq_cap", eglContext())
            val vSrc = f.createVideoSource(cap.isScreencast)
            cap.initialize(h, app, vSrc.capturerObserver)
            cap.startCapture(640, 480, 24)
            val vTrack = f.createVideoTrack("khosq_v0", vSrc)
            capturer = cap
            helper = h
            videoSource = vSrc
            videoTrack = vTrack
            conn.addTrack(vTrack, listOf("khosq_s0"))
            _ui.value = _ui.value.copy(localVideo = vTrack)
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

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
        override fun onAddStream(s: MediaStream?) {}
        override fun onRemoveStream(s: MediaStream?) {}
        override fun onDataChannel(d: org.webrtc.DataChannel?) {}
        override fun onRenegotiationNeeded() {}

        override fun onIceCandidate(c: IceCandidate?) {
            if (c == null) return
            main.post {
                if (docReady) pushCandidate(c) else outCand.add(c)
            }
        }

        override fun onTrack(t: RtpTransceiver?) {
            val track = t?.receiver?.track() ?: return
            if (track is VideoTrack) {
                main.post {
                    track.setEnabled(true)
                    _ui.value = _ui.value.copy(remoteVideo = track)
                }
            }
        }

        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
            main.post {
                when (s) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        main.removeCallbacks(graceTimeout)
                        main.removeCallbacks(connectTimeout)
                        main.removeCallbacks(ringTimeout)
                        stopRingback()
                        val cur = _ui.value
                        if (cur.phase == Phase.CONNECTING || cur.phase == Phase.OUTGOING || cur.phase == Phase.CONNECTED) {
                            _ui.value = cur.copy(
                                phase = Phase.CONNECTED,
                                startedAt = if (cur.startedAt == 0L) System.currentTimeMillis() else cur.startedAt,
                            )
                            updateProximity()
                        }
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        main.removeCallbacks(graceTimeout)
                        main.postDelayed(graceTimeout, 8_000L)
                    }
                    PeerConnection.IceConnectionState.FAILED -> finish("Կապը չհաջողվեց")
                    else -> Unit
                }
            }
        }
    }

    private fun pushCandidate(c: IceCandidate) {
        val id = _ui.value.callId
        if (id.isEmpty()) return
        val sub = if (isCaller) "callerCandidates" else "calleeCandidates"
        db.collection("calls").document(id).collection(sub).add(
            mapOf(
                "sdpMid" to (c.sdpMid ?: ""),
                "sdpMLineIndex" to c.sdpMLineIndex,
                "sdp" to c.sdp,
            ),
        )
    }

    private fun watchCandidates(sub: String) {
        candReg?.remove()
        val id = _ui.value.callId
        candReg = db.collection("calls").document(id).collection(sub)
            .addSnapshotListener { snap, err ->
                if (err != null || snap == null) return@addSnapshotListener
                val list = ArrayList<IceCandidate>()
                for (ch in snap.documentChanges) {
                    if (ch.type != DocumentChange.Type.ADDED) continue
                    val d = ch.document
                    val sdp = d.getString("sdp") ?: continue
                    list.add(IceCandidate(d.getString("sdpMid") ?: "", (d.getLong("sdpMLineIndex") ?: 0L).toInt(), sdp))
                }
                main.post {
                    for (c in list) {
                        if (remoteSet) pc?.addIceCandidate(c) else remoteCand.add(c)
                    }
                }
            }
    }

    private fun flushRemote() {
        for (c in remoteCand) pc?.addIceCandidate(c)
        remoteCand.clear()
    }

    private fun watchCall(id: String) {
        callReg?.remove()
        callReg = db.collection("calls").document(id).addSnapshotListener { d, err ->
            if (err != null) return@addSnapshotListener
            main.post { onCallDoc(id, d) }
        }
    }

    private fun onCallDoc(id: String, d: DocumentSnapshot?) {
        val cur = _ui.value
        if (cur.callId != id || cur.phase == Phase.IDLE || cur.phase == Phase.ENDED) return
        if (d == null || !d.exists()) {
            finish(null)
            return
        }
        when (d.getString("state")) {
            "declined" -> {
                finish(if (isCaller) "Զանգը մերժվեց" else null)
                return
            }
            "ended" -> {
                finish(null)
                return
            }
            "missed" -> {
                finish(null)
                return
            }
        }
        if (isCaller && !answerApplied) {
            val a = d.get("answer") as? Map<*, *>
            val sdp = a?.get("sdp") as? String
            if (sdp != null) {
                answerApplied = true
                main.removeCallbacks(ringTimeout)
                stopRingback()
                _ui.value = cur.copy(phase = Phase.CONNECTING)
                main.removeCallbacks(connectTimeout)
                main.postDelayed(connectTimeout, 30_000L)
                pc?.setRemoteDescription(object : Sdp() {
                    override fun onSetSuccess() {
                        main.post {
                            remoteSet = true
                            flushRemote()
                        }
                    }
                }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
            }
        }
    }

    private fun setState(s: String) {
        val id = _ui.value.callId
        if (id.isEmpty()) return
        db.collection("calls").document(id).update("state", s)
    }

    private fun finish(note: String?) {
        val cur = _ui.value
        if (cur.phase == Phase.IDLE || cur.phase == Phase.ENDED) return
        main.removeCallbacks(ringTimeout)
        main.removeCallbacks(graceTimeout)
        main.removeCallbacks(connectTimeout)
        callReg?.remove(); callReg = null
        candReg?.remove(); candReg = null
        stopRinging()
        stopRingback()
        stopAudioMode()
        camPausedByApp = false
        try {
            val w = prox
            if (w != null && w.isHeld) w.release()
        } catch (e: Exception) {
            // already released
        }

        val conn = pc
        val cap = capturer
        val h = helper
        val vSrc = videoSource
        val vTrack = videoTrack
        val aSrc = audioSource
        val aTrack = audioTrack
        pc = null; capturer = null; helper = null
        videoSource = null; videoTrack = null; audioSource = null; audioTrack = null
        pendingOffer = null
        remoteCand.clear(); outCand.clear()
        docReady = false

        try { cap?.stopCapture() } catch (e: Exception) { }
        try { conn?.close() } catch (e: Exception) { }

        _ui.value = cur.copy(phase = Phase.ENDED, note = note ?: "", remoteVideo = null, localVideo = null)
        val endedId = cur.callId
        if (isCaller) {
            logCall(cur, note)
            if (cur.startedAt > 0L && cur.callId.isNotEmpty()) {
                val sec = ((System.currentTimeMillis() - cur.startedAt) / 1000).toInt().coerceAtLeast(0)
                db.collection("calls").document(cur.callId).update("dur", sec)
            }
        }

        main.postDelayed({
            try { conn?.dispose() } catch (e: Exception) { }
            try { vTrack?.dispose() } catch (e: Exception) { }
            try { vSrc?.dispose() } catch (e: Exception) { }
            try { cap?.dispose() } catch (e: Exception) { }
            try { h?.dispose() } catch (e: Exception) { }
            try { aTrack?.dispose() } catch (e: Exception) { }
            try { aSrc?.dispose() } catch (e: Exception) { }
        }, 700L)
        main.postDelayed({
            val c = _ui.value
            if (c.phase == Phase.ENDED && c.callId == endedId) _ui.value = CallUi()
        }, if (note.isNullOrEmpty()) 400L else 1800L)
    }

    /** Writes a small "call" line into the chat so the history shows missed and finished calls. */
    private fun logCall(cur: CallUi, note: String?) {
        if (myUidCur.isEmpty() || cur.peerUid.isEmpty()) return
        var missed = false
        var sec = 0
        val code: String = if (cur.startedAt > 0L) {
            sec = ((System.currentTimeMillis() - cur.startedAt) / 1000).toInt().coerceAtLeast(0)
            "call_done"
        } else when (note) {
            null -> "call_cancelled"
            "Չպատասխանեց" -> {
                missed = true
                "call_missed"
            }
            "Զանգը մերժվեց" -> "call_declined"
            else -> return
        }
        val a = myUidCur
        val b = cur.peerUid
        val chatId = if (a < b) a + "_" + b else b + "_" + a
        val chat = db.collection("chats").document(chatId)
        val batch = db.batch()
        // The line stores only a code; every phone writes it in its own language.
        batch.set(
            chat.collection("messages").document(),
            hashMapOf<String, Any>(
                "from" to a,
                "text" to "",
                "kind" to "call",
                "code" to code,
                "vid" to cur.video,
                "dur" to sec.toLong(),
                "ts" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
            ),
        )
        if (missed) {
            batch.update(
                chat,
                hashMapOf<String, Any>(
                    "lastText" to "📵",
                    "lastKind" to "call_missed",
                    "lastTs" to System.currentTimeMillis(),
                    "lastFrom" to a,
                ),
            )
        }
        batch.commit()
    }

    // ---------------------------------------------------------------- audio

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

    private fun setSpeaker(on: Boolean) {
        audioManager().isSpeakerphoneOn = on
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

    private open class Sdp : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) {
            main.post { finish("Սխալ՝ " + (e ?: "")) }
        }
        override fun onSetFailure(e: String?) {
            main.post { finish("Սխալ՝ " + (e ?: "")) }
        }
    }
}

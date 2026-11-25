package am.khosq.app.sos

import am.khosq.app.MainActivity
import am.khosq.app.Prefs
import am.khosq.app.R
import am.khosq.app.crypto.E2E
import am.khosq.app.data.CircleLogic
import am.khosq.app.data.Profile
import am.khosq.app.data.Repository
import am.khosq.app.data.SosCodec
import am.khosq.app.data.SosInfo
import am.khosq.app.data.SosPoint
import am.khosq.app.data.SosReason
import am.khosq.app.data.SosRefs
import am.khosq.app.data.SosTimer
import am.khosq.app.data.TrailLogic
import am.khosq.app.tr
import am.khosq.app.ui.OneShotLocation
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.auth.FirebaseAuth
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The trusted circle's SOS: one running timer (or one live SOS) at a time, kept in the preferences so it survives the app being closed.
 *
 * - The timer records where the person goes (on the phone only) and, if the person does not say "I am fine" in time, sends an SOS card
 *   with the start, the way so far and the last place to everybody in the circle, end-to-end like any message; then it keeps that card
 *   up to date every two minutes until the person says "I am fine" (or three hours pass).
 * - The SOS button sends the same card at once and goes live the same way.
 * - "I am fine" before the SOS: everything recorded is deleted and nothing is sent. After it: the card is marked as ended.
 *
 * The recording and the clock run in [SosService]; this object holds the state and does the sending.
 */
object SosEngine {
    const val K_REC = "sos.rec"
    const val K_REFS = "sos.refs"
    const val K_ME = "sos.me"
    const val K_CIRCLE = "sos.circle"
    const val K_CONSENT = "sos_ok"

    const val ACTION_TICK = "am.khosq.app.SOS_TICK"
    const val ACTION_FIRE = "am.khosq.app.SOS_FIRE"
    const val ACTION_OK = "am.khosq.app.SOS_OK"
    const val ACTION_CANCEL = "am.khosq.app.SOS_CANCEL"
    const val EXTRA_REASON = "reason"

    const val CH_RUN = "sos_run"
    const val CH_ALERT = "sos_alert"
    const val ID_RUN = 41
    const val ID_ALERT = 42

    private const val TRAIL_FILE = "sos_trail.txt"
    private const val RC_WARN = 4101
    private const val RC_FIRE = 4102
    private const val RC_UPDATE = 4103

    /** How the last sending went: reached the server, waiting for the network, failed. */
    data class Status(val sent: Int = 0, val queued: Int = 0, val failed: Int = 0)

    private val _rec = MutableStateFlow<SosTimer.Rec?>(null)

    /** The running timer or live SOS, or null. */
    val rec: StateFlow<SosTimer.Rec?> = _rec.asStateFlow()

    private val _lastPoint = MutableStateFlow<SosPoint?>(null)

    /** The newest place recorded. */
    val lastPoint: StateFlow<SosPoint?> = _lastPoint.asStateFlow()

    private val _count = MutableStateFlow(0)

    /** How many places are recorded. */
    val pointCount: StateFlow<Int> = _count.asStateFlow()

    private val _lastUpdate = MutableStateFlow(0L)

    /** When the circle last got the newest places (ms), 0 = not yet. */
    val lastUpdate: StateFlow<Long> = _lastUpdate.asStateFlow()

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _circle = MutableStateFlow<List<String>>(emptyList())

    /** The people in the circle (ids). */
    val circle: StateFlow<List<String>> = _circle.asStateFlow()

    private val lock = Mutex()
    private var warnedFor = 0L

    // ---------------------------------------------------------------- what is saved

    /** Called when the app process starts. */
    fun load(ctx: Context) {
        _circle.value = CircleLogic.parse(Prefs.getString(K_CIRCLE))
        val r = SosTimer.decode(Prefs.getString(K_REC))
        _rec.value = r
        val pts = if (r != null) trail(ctx) else emptyList()
        _lastPoint.value = pts.lastOrNull()
        _count.value = pts.size
    }

    fun current(): SosTimer.Rec? = SosTimer.decode(Prefs.getString(K_REC))

    fun setCircle(list: List<String>) {
        val l = CircleLogic.parse(CircleLogic.join(list))
        Prefs.putStringSync(K_CIRCLE, CircleLogic.join(l))
        _circle.value = l
    }

    fun consented(): Boolean = Prefs.getString(K_CONSENT) == "1"

    fun setConsent(on: Boolean) {
        Prefs.putStringSync(K_CONSENT, if (on) "1" else "")
    }

    private fun rememberMe(me: Profile) {
        Prefs.putStringSync(K_ME, listOf(me.uid, me.handle.replace("|", " "), me.name.replace("|", " ")).joinToString("|"))
    }

    private fun savedMe(uid: String): Profile? {
        val p = Prefs.getString(K_ME).split("|", limit = 3)
        if (p.size < 3 || p[0] != uid) return null
        return Profile(uid = p[0], handle = p[1], name = p[2])
    }

    private fun trailFile(ctx: Context) = File(ctx.applicationContext.filesDir, TRAIL_FILE)

    @Synchronized
    fun trail(ctx: Context): List<SosPoint> {
        val f = trailFile(ctx)
        if (!f.exists()) return emptyList()
        val out = ArrayList<SosPoint>()
        try {
            for (line in f.readLines()) {
                val p = TrailLogic.parseLine(line) ?: continue
                out.add(p)
            }
        } catch (e: Exception) {
            // an unreadable file is the same as no places
        }
        return out
    }

    @Synchronized
    private fun appendPoint(ctx: Context, p: SosPoint) {
        val f = trailFile(ctx)
        try {
            f.appendText(TrailLogic.line(p) + "\n")
            _count.value = _count.value + 1
            if (f.length() > 90_000L) {
                // a very long walk: keep half of the places, evenly spread
                val all = trail(ctx)
                if (all.size > TrailLogic.MAX_STORED) {
                    val thin = TrailLogic.thin(all, TrailLogic.MAX_STORED / 2)
                    f.writeText(thin.joinToString("\n") { x -> TrailLogic.line(x) } + "\n")
                    _count.value = thin.size
                }
            }
        } catch (e: Exception) {
            // the place is lost, the next one is tried
        }
    }

    @Synchronized
    private fun clearTrail(ctx: Context) {
        try {
            trailFile(ctx).delete()
            _count.value = 0
        } catch (e: Exception) {
            // nothing more to delete
        }
    }

    // ---------------------------------------------------------------- starting and stopping

    /** Starts the timer. False when one is already running, the circle is empty or the person has not agreed yet. */
    fun startTimer(ctx: Context, minutes: Int, me: Profile): Boolean {
        val c = ctx.applicationContext
        if (current() != null) return false
        if (CircleLogic.parse(Prefs.getString(K_CIRCLE)).isEmpty() || !consented()) return false
        val r = SosTimer.Rec(System.currentTimeMillis(), SosTimer.clampMinutes(minutes), 0L, SosReason.TIMER)
        begin(c, r, me)
        SosService.start(c)
        return true
    }

    /**
     * The SOS button. With the location permission the SOS card goes out at once and then stays live (same as an expired timer).
     * Without it the card goes out once, without places. A timer that is running turns into the SOS right now.
     */
    fun sosNow(ctx: Context, me: Profile, placesAllowed: Boolean): Boolean {
        val c = ctx.applicationContext
        if (CircleLogic.parse(Prefs.getString(K_CIRCLE)).isEmpty() || !consented()) return false
        val running = current()
        if (running != null) {
            if (running.firedAt > 0L) return false
            SosService.start(c, ACTION_FIRE, SosReason.BUTTON)
            return true
        }
        if (!placesAllowed) {
            Thread {
                try {
                    kotlinx.coroutines.runBlocking { sendOnce(c, me) }
                } catch (e: Exception) {
                    // nothing more can be done from here
                }
            }.start()
            return true
        }
        val now = System.currentTimeMillis()
        begin(c, SosTimer.Rec(now, 0, 0L, SosReason.BUTTON), me)
        SosService.start(c, ACTION_FIRE, SosReason.BUTTON)
        return true
    }

    private fun begin(c: Context, r: SosTimer.Rec, me: Profile) {
        rememberMe(me)
        clearTrail(c)
        warnedFor = 0L
        Prefs.putStringSync(K_REFS, "")
        Prefs.putStringSync(K_REC, SosTimer.encode(r))
        _rec.value = r
        _lastPoint.value = null
        _lastUpdate.value = 0L
        _status.value = Status()
        schedule(c, r)
    }

    /** Everything recorded is deleted, the alarms and notifications go away. Nothing is sent. */
    fun clear(ctx: Context) {
        val c = ctx.applicationContext
        cancelAlarms(c)
        clearTrail(c)
        Prefs.putStringSync(K_REC, "")
        Prefs.putStringSync(K_REFS, "")
        _rec.value = null
        _lastPoint.value = null
        _lastUpdate.value = 0L
        warnedFor = 0L
        try {
            NotificationManagerCompat.from(c).cancel(ID_ALERT)
        } catch (e: SecurityException) {
            // notifications are off; nothing to remove
        }
    }

    // ---------------------------------------------------------------- the clock

    /** Called by the service's loop and by the alarms: does whatever the moment asks for. False when there is nothing running any more. */
    suspend fun tick(ctx: Context): Boolean {
        val c = ctx.applicationContext
        val r = current() ?: return false
        val now = System.currentTimeMillis()
        when (SosTimer.phase(r, now)) {
            SosTimer.Phase.RUNNING -> {}
            SosTimer.Phase.WARNING -> warn(c, r)
            SosTimer.Phase.DUE -> fire(c, null)
            SosTimer.Phase.LIVE -> if (now - maxOf(_lastUpdate.value, r.firedAt) >= SosTimer.UPDATE_MS) update(c, false)
            SosTimer.Phase.OVER -> {
                clear(c)
                stoppedNote(c)
                return false
            }
        }
        return current() != null
    }

    /** The actions of the notification buttons and of the alarms. */
    suspend fun handle(ctx: Context, action: String?, reason: SosReason?) {
        when (action) {
            ACTION_OK -> ok(ctx)
            ACTION_CANCEL -> cancelTimer(ctx)
            ACTION_FIRE -> fire(ctx, reason)
            else -> tick(ctx)
        }
    }

    /** "I am fine". Before the SOS: everything is deleted. After it: the card is marked as ended, then everything is deleted. */
    suspend fun ok(ctx: Context) {
        val c = ctx.applicationContext
        lock.withLock {
            val r = current() ?: return
            if (r.firedAt > 0L) {
                try {
                    sendUpdate(c, r, true)
                } catch (e: Exception) {
                    // the card stays as it was; the person is fine anyway
                }
            }
            clear(c)
        }
    }

    /** The person cancels the timer before it ran out. */
    suspend fun cancelTimer(ctx: Context) {
        val c = ctx.applicationContext
        lock.withLock {
            val r = current() ?: return
            if (r.firedAt == 0L) clear(c)
        }
    }

    private fun warn(c: Context, r: SosTimer.Rec) {
        if (warnedFor == r.startedAt) return
        warnedFor = r.startedAt
        ensureChannels(c)
        try {
            val n = NotificationCompat.Builder(c, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(tr("Ժամանակը լրանում է"))
                .setContentText(tr("Մեկ րոպեից SOS կուղարկվի շրջանին։ Սեղմիր «Լավ եմ»։"))
                .setStyle(NotificationCompat.BigTextStyle().bigText(tr("Մեկ րոպեից SOS կուղարկվի շրջանին։ Սեղմիր «Լավ եմ»։")))
                .setContentIntent(openApp(c))
                .addAction(0, tr("Լավ եմ"), actionPi(c, ACTION_OK, 1))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setTimeoutAfter(SosTimer.WARN_MS + 5_000L)
                .setAutoCancel(false)
                .build()
            NotificationManagerCompat.from(c).notify(ID_ALERT, n)
        } catch (e: SecurityException) {
            // notifications are off: the ongoing notification and the app still show the time
        }
    }

    private fun stoppedNote(c: Context) {
        ensureChannels(c)
        try {
            val n = NotificationCompat.Builder(c, CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(tr("SOS-ի ուղարկումը դադարեց"))
                .setContentText(tr("Անցել է 3 ժամ։ Շրջանը այլևս նոր վայրեր չի ստանում։"))
                .setContentIntent(openApp(c))
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(c).notify(ID_ALERT, n)
        } catch (e: SecurityException) {
            // notifications are off
        }
    }

    // ---------------------------------------------------------------- the places

    /** A new fix from the phone: kept only when it is worth it (moved enough, or long enough since the last one). */
    @Synchronized
    fun onFix(ctx: Context, loc: Location, maxAccuracy: Float) {
        val r = current() ?: return
        val c = ctx.applicationContext
        val t = if (loc.time > 0L) loc.time / 1000L else System.currentTimeMillis() / 1000L
        val last = _lastPoint.value ?: trail(c).lastOrNull()
        val acc = if (loc.hasAccuracy()) loc.accuracy else 0f
        if (!TrailLogic.accept(last, loc.latitude, loc.longitude, t, acc, maxAccuracy)) return
        // a fix that arrives after the timer ended must not leak into the next one
        if (current()?.startedAt != r.startedAt) return
        val p = SosPoint(loc.latitude, loc.longitude, t)
        appendPoint(c, p)
        _lastPoint.value = p
    }

    /** One fresh place right now, waiting at most [waitMs]. Whatever arrives later is still kept. */
    suspend fun freshFix(ctx: Context, waitMs: Long, maxAccuracy: Float = 5000f) {
        val c = ctx.applicationContext
        withTimeoutOrNull(waitMs) {
            suspendCancellableCoroutine<Boolean> { cont ->
                Handler(Looper.getMainLooper()).post {
                    OneShotLocation.request(c) { loc ->
                        if (loc != null) onFix(c, loc, maxAccuracy)
                        cont.resume(loc != null)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- sending

    private fun myUid(): String = FirebaseAuth.getInstance().currentUser?.uid ?: ""

    private fun profile(uid: String): Profile = savedMe(uid) ?: Profile(uid = uid, handle = "", name = "KHOSQ")

    private fun infoOf(c: Context, r: SosTimer.Rec, ended: Boolean): SosInfo =
        SosInfo(r.reason, r.firedAt, if (r.minutes > 0) r.startedAt else 0L, r.minutes, ended, trail(c))

    private inline fun <T> awake(c: Context, block: () -> T): T {
        val pm = c.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wl = try {
            pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "khosq:sos")?.also { w -> w.acquire(90_000L) }
        } catch (e: Exception) {
            null
        }
        try {
            return block()
        } finally {
            try {
                if (wl != null && wl.isHeld) wl.release()
            } catch (e: Exception) {
                // already released
            }
        }
    }

    /** The timer ran out (or the button was pressed): the card goes to everybody in the circle and the live updates begin. */
    suspend fun fire(ctx: Context, reason: SosReason?) {
        val c = ctx.applicationContext
        lock.withLock {
            val before = current() ?: return
            if (before.firedAt > 0L) return
            awake(c) {
                // the newest place first (the recording may have nothing recent), but never for long: this is an emergency
                freshFix(c, if (reason == SosReason.BUTTON) 5_000L else 8_000L)
                val now = System.currentTimeMillis()
                val r = before.copy(firedAt = now, reason = reason ?: before.reason)
                Prefs.putStringSync(K_REC, SosTimer.encode(r))
                _rec.value = r
                val refs = sendCards(c, infoOf(c, r, false))
                Prefs.putStringSync(K_REFS, SosRefs.encode(refs))
                _lastUpdate.value = now
                scheduleUpdate(c)
            }
            try {
                NotificationManagerCompat.from(c).cancel(ID_ALERT)
            } catch (e: SecurityException) {
                // nothing to cancel
            }
        }
    }

    /** The newest places go to the cards already sent (a person who got none yet gets a new card). */
    suspend fun update(ctx: Context, ended: Boolean) {
        val c = ctx.applicationContext
        lock.withLock {
            val r = current() ?: return
            if (r.firedAt == 0L) return
            awake(c) {
                try {
                    sendUpdate(c, r, ended)
                } catch (e: Exception) {
                    // the next round tries again
                }
            }
            scheduleUpdate(c)
        }
    }

    private suspend fun sendUpdate(c: Context, r: SosTimer.Rec, ended: Boolean) {
        val uid = myUid()
        if (uid.isEmpty()) return
        if (!ended) freshFix(c, 6_000L)
        val payload = SosCodec.encode(infoOf(c, r, ended))
        val repo = Repository()
        val me = profile(uid)
        val refs = LinkedHashMap(SosRefs.decode(Prefs.getString(K_REFS)))
        for (peer in CircleLogic.parse(Prefs.getString(K_CIRCLE))) {
            val chatId = repo.chatId(uid, peer)
            try {
                E2E.keyFor(chatId, uid)
            } catch (e: Exception) {
                // sent plain only if the key really cannot be made
            }
            val ref = refs[peer]
            if (ref != null) {
                repo.updateSos(chatId, ref.msgId, payload, ref.sealed)
            } else if (!ended) {
                val sent = try {
                    repo.sendSos(chatId, me, listOf(peer), payload)
                } catch (e: Exception) {
                    null
                }
                if (sent != null) refs[peer] = SosRefs.Ref(sent.msgId, sent.sealed)
            }
        }
        Prefs.putStringSync(K_REFS, SosRefs.encode(refs))
        _lastUpdate.value = System.currentTimeMillis()
    }

    private suspend fun sendCards(c: Context, info: SosInfo): Map<String, SosRefs.Ref> {
        val uid = myUid()
        if (uid.isEmpty()) return emptyMap()
        val payload = SosCodec.encode(info)
        val repo = Repository()
        val me = profile(uid)
        val people = CircleLogic.parse(Prefs.getString(K_CIRCLE))
        val results = coroutineScope {
            people.map { peer ->
                async {
                    val chatId = repo.chatId(uid, peer)
                    try {
                        E2E.keyFor(chatId, uid)
                    } catch (e: Exception) {
                        // sent plain only if the key really cannot be made
                    }
                    val sent = try {
                        repo.sendSos(chatId, me, listOf(peer), payload)
                    } catch (e: Exception) {
                        null
                    }
                    peer to sent
                }
            }.awaitAll()
        }
        val refs = LinkedHashMap<String, SosRefs.Ref>()
        var ok = 0
        var queued = 0
        var failed = 0
        for ((peer, sent) in results) {
            if (sent == null) {
                failed++
            } else {
                refs[peer] = SosRefs.Ref(sent.msgId, sent.sealed)
                if (sent.confirmed) ok++ else queued++
            }
        }
        _status.value = Status(ok, queued, failed)
        return refs
    }

    /** The button without the location permission: one card, no places, no live updates. */
    private suspend fun sendOnce(c: Context, me: Profile) {
        val uid = myUid()
        if (uid.isEmpty()) return
        rememberMe(me)
        val now = System.currentTimeMillis()
        val info = SosInfo(SosReason.BUTTON, now, 0L, 0, false, emptyList())
        sendCards(c, info)
    }

    // ---------------------------------------------------------------- alarms and notifications

    private fun alarmPi(c: Context, code: Int): PendingIntent = PendingIntent.getBroadcast(
        c,
        code,
        Intent(c, SosReceiver::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    internal fun actionPi(c: Context, action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        c,
        code,
        Intent(c, SosReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    internal fun openApp(c: Context): PendingIntent = PendingIntent.getActivity(
        c,
        0,
        Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun canBeExact(am: AlarmManager): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

    private fun setWake(c: Context, at: Long, code: Int) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmPi(c, code)
        try {
            if (canBeExact(am)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** The warning one minute before and the SOS itself. The SOS uses an alarm-clock alarm: the phone never delays those, even in deep sleep. */
    fun schedule(ctx: Context, r: SosTimer.Rec) {
        val c = ctx.applicationContext
        cancelAlarms(c)
        if (r.firedAt > 0L || r.minutes <= 0) return
        val now = System.currentTimeMillis()
        val warnAt = r.deadline - SosTimer.WARN_MS
        if (warnAt > now) setWake(c, warnAt, RC_WARN)
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(maxOf(r.deadline, now + 1000L), openApp(c)), alarmPi(c, RC_FIRE))
        } catch (e: SecurityException) {
            setWake(c, maxOf(r.deadline, now + 1000L), RC_FIRE)
        }
    }

    private fun scheduleUpdate(c: Context) {
        setWake(c, System.currentTimeMillis() + SosTimer.UPDATE_MS, RC_UPDATE)
    }

    /** After a restart of the phone or an update of the app the alarms are gone: sets them again. */
    fun reschedule(ctx: Context) {
        val c = ctx.applicationContext
        val r = current() ?: return
        if (r.firedAt > 0L) scheduleUpdate(c) else schedule(c, r)
    }

    private fun cancelAlarms(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for (code in intArrayOf(RC_WARN, RC_FIRE, RC_UPDATE)) am.cancel(alarmPi(c, code))
    }

    fun ensureChannels(c: Context) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_RUN, tr("SOS և ժամանակաչափ"), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERT, tr("SOS նախազգուշացումներ"), NotificationManager.IMPORTANCE_HIGH))
    }
}

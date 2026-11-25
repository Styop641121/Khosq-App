package am.khosq.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import am.khosq.app.call.CallManager
import am.khosq.app.call.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * Keeps listening to the chats while the app is closed and shows a notification for every new message.
 * It runs as a foreground service, so Android keeps it alive.
 */
class KeepAliveService : Service() {
    private var registration: ListenerRegistration? = null
    private val seenTs = HashMap<String, Long>()
    private var first = true
    private var retryMs = 4000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        // when the service last started: shown in "Check the notifications"
        Prefs.putLong("svc_at", System.currentTimeMillis())
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(this, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("KHOSQ")
            .setContentText(tr("Աշխատում է. նամակները կհասնեն"))
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        } else {
            startForeground(ID_SERVICE, n)
        }
        listen()
        CallManager.init(applicationContext)
        FirebaseAuth.getInstance().currentUser?.uid?.let { CallManager.listen(it) }
        scope.launch {
            CallManager.ui.collect { c ->
                if (c.phase == Phase.INCOMING && !AppState.inForeground) showCall(c.peerName, c.video)
                else NotificationManagerCompat.from(this@KeepAliveService).cancel(ID_CALL)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val wl = pm.newWakeLock(
                android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    android.os.PowerManager.ON_AFTER_RELEASE,
                "khosq:call",
            )
            wl.acquire(10_000L)
        } catch (e: Exception) {
            // the full-screen notification still tries to wake the phone
        }
    }

    private fun showCall(name: String, video: Boolean) {
        wakeScreen()
        // the call screen is its own small activity: it may show above the lock screen, the chats may not
        val screen = Intent(this, CallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val tap = PendingIntent.getActivity(
            this,
            77,
            Intent(screen),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val answer = PendingIntent.getActivity(
            this,
            79,
            Intent(screen).putExtra(CallActivity.EXTRA_ANSWER, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val decline = PendingIntent.getService(
            this,
            78,
            Intent(this, KeepAliveService::class.java).setAction(ACTION_DECLINE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CH_CALL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(name)
            .setContentText(if (video) tr("Մուտքային տեսազանգ") else tr("Մուտքային զանգ"))
            .setContentIntent(tap)
            .setFullScreenIntent(tap, true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, tr("Պատասխանել"), answer)
            .addAction(0, tr("Մերժել"), decline)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(ID_CALL, n)
        } catch (e: SecurityException) {
            // notifications are not allowed
        }
    }

    private fun listen() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            stopSelf()
            return
        }
        registration?.remove()
        registration = FirebaseFirestore.getInstance().collection("chats")
            .whereArrayContains("members", uid)
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    // the listener is dead after an error; start it again later so notifications keep coming
                    registration?.remove()
                    val wait = retryMs
                    retryMs = minOf(60_000L, retryMs * 2)
                    scope.launch {
                        delay(wait)
                        listen()
                    }
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener
                retryMs = 4000L
                for (d in snap.documents) handle(d, uid)
                first = false
            }
    }

    private fun handle(d: DocumentSnapshot, me: String) {
        val ts = d.getLong("lastTs") ?: 0L
        val stamp = ((d.get("dlv") as? Map<*, *>)?.get(me) as? Number)?.toLong() ?: 0L
        if (!d.metadata.hasPendingWrites()) {
            am.khosq.app.data.Delivery.mark(d.id, me, ts, d.getString("lastFrom") ?: "", stamp)
        }
        val before = seenTs[d.id] ?: 0L
        if (!first && ts > before && d.getString("lastFrom") != me && !AppState.inForeground && !Prefs.isMuted(d.id)) {
            showMessage(d, me)
        }
        seenTs[d.id] = maxOf(ts, before)
    }

    private fun showMessage(d: DocumentSnapshot, me: String) {
        val members = d.get("members") as? List<*> ?: return
        val names = d.get("names") as? Map<*, *>
        val sealed = d.getString("lastText") ?: return
        if (sealed.isEmpty()) return
        if (d.getBoolean("lastEnc") == true) {
            // an encrypted message is opened on this phone; the key may need a moment
            val from = d.getString("lastFrom") ?: ""
            val wraps = HashMap<String, String>()
            val rawWraps = d.get("lastW") as? Map<*, *>
            if (rawWraps != null) {
                for ((k, v) in rawWraps) {
                    if (k is String && v is String) wraps[k] = v
                }
            }
            scope.launch {
                val plain = try {
                    am.khosq.app.crypto.E2E.openLast(d.id, me, from, sealed, wraps)
                } catch (e: Exception) {
                    null
                }
                postMessage(d, me, members, names, plain ?: tr("Նոր նամակ"))
            }
            return
        }
        postMessage(d, me, members, names, sealed)
    }

    private fun postMessage(d: DocumentSnapshot, me: String, members: List<*>, names: Map<*, *>?, raw: String) {
        val preview = previewOf(d.getString("lastKind") ?: "", raw)
        // "hide the text in notifications" (an SOS stays readable: it holds only the word, never a place)
        val hide = am.khosq.app.data.NotifyPrivacy.hidden(AppLock.hideTextNow(), preview)
        val last = if (hide) tr("Նոր նամակ") else preview
        val name: String
        val text: String
        if (d.getBoolean("group") == true) {
            val from = (names?.get(d.getString("lastFrom") ?: "") as? String) ?: ""
            name = d.getString("title") ?: "KHOSQ"
            text = if (from.isEmpty() || hide) last else "$from: $last"
        } else {
            val peer = members.firstOrNull { it != me } as? String ?: return
            name = (names?.get(peer) as? String) ?: "KHOSQ"
            text = last
        }
        val tap = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("chat", d.id)
        val pi = PendingIntent.getActivity(
            this,
            d.id.hashCode(),
            tap,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CH_MSG)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(name)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(d.id.hashCode(), n)
        } catch (e: SecurityException) {
            // notifications are not allowed; the message is still in the app
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DECLINE) CallManager.decline()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        registration?.remove()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CH_SERVICE = "service"
        const val CH_MSG = "messages"
        const val CH_CALL = "calls_v1"
        const val ID_SERVICE = 1
        const val ID_CALL = 2
        const val ACTION_DECLINE = "am.khosq.app.DECLINE"

        fun createChannels(c: Context) {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val svc = NotificationChannel(CH_SERVICE, tr("Աշխատանք ֆոնում"), NotificationManager.IMPORTANCE_LOW)
            svc.setShowBadge(false)
            val msg = NotificationChannel(CH_MSG, tr("Նամակներ"), NotificationManager.IMPORTANCE_HIGH)
            val call = NotificationChannel(CH_CALL, tr("Զանգեր"), NotificationManager.IMPORTANCE_HIGH)
            call.setSound(null, null)
            call.enableVibration(false)
            nm.createNotificationChannel(call)
            nm.createNotificationChannel(svc)
            nm.createNotificationChannel(msg)
        }

        fun start(c: Context) {
            try {
                ContextCompat.startForegroundService(c, Intent(c, KeepAliveService::class.java))
            } catch (e: Exception) {
                // Android may refuse a background start; the app starts it again when opened
            }
        }
    }
}

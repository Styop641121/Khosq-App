package am.khosq.app.call

import am.khosq.app.MainActivity
import am.khosq.app.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps the process and the microphone/camera alive during a call. */
class CallService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_ACTIVE, "Ընթացիկ զանգ", NotificationManager.IMPORTANCE_LOW),
        )
        val ui = CallManager.ui.value
        val n = build(ui.peerName, ui.video)
        var type = 0
        if (Build.VERSION.SDK_INT >= 29) {
            if (granted(android.Manifest.permission.RECORD_AUDIO)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (ui.video && granted(android.Manifest.permission.CAMERA)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        try {
            if (Build.VERSION.SDK_INT >= 29 && type != 0) startForeground(ID, n, type) else startForeground(ID, n)
        } catch (e: Exception) {
            stopSelf()
            return
        }
        job = scope.launch {
            CallManager.ui.collect { s ->
                if (s.phase == Phase.IDLE || s.phase == Phase.ENDED) stopSelf()
            }
        }
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun build(name: String, video: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hang = PendingIntent.getService(
            this,
            2,
            Intent(this, CallService::class.java).setAction(ACTION_HANGUP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CH_ACTIVE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (name.isEmpty()) "KHOSQ" else name)
            .setContentText(if (video) "Տեսազանգ" else "Զանգ")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .addAction(0, "Ավարտել", hang)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HANGUP) CallManager.hangup()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val CH_ACTIVE = "call_active"
        const val ID = 3
        const val ACTION_HANGUP = "am.khosq.app.HANGUP"

        fun start(c: Context) {
            try {
                ContextCompat.startForegroundService(c, Intent(c, CallService::class.java))
            } catch (e: Exception) {
                // the call still works while the app is open
            }
        }
    }
}

package am.khosq.app.sos

import am.khosq.app.R
import am.khosq.app.data.SosReason
import am.khosq.app.data.SosTimer
import am.khosq.app.data.TrailLogic
import am.khosq.app.tr
import am.khosq.app.trf
import am.khosq.app.ui.OneShotLocation
import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Runs while a timer or a live SOS is on: shows the ongoing notification (always visible while the phone records where the person
 * goes), records the places, and lets [SosEngine] do whatever each moment asks for. It is a foreground service of the location kind,
 * so Android lets it keep reading the position while the app is closed, as long as it was started while the app was open.
 */
class SosService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null
    private var manager: LocationManager? = null
    private var listener: LocationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val c = applicationContext
        val action = intent?.action
        val reason = when (intent?.getStringExtra(SosEngine.EXTRA_REASON)) {
            "B" -> SosReason.BUTTON
            "T" -> SosReason.TIMER
            else -> null
        }
        SosEngine.ensureChannels(c)
        val rec = SosEngine.current()
        // a service started with startForegroundService must become a foreground service at once, whatever happens next
        val foreground = goForeground(notification(rec))
        if (rec == null) {
            stopNow()
            return START_NOT_STICKY
        }
        if (!foreground) {
            // Android did not allow a foreground service right now: still do what was asked (send, cancel), then stop
            scope.launch {
                try {
                    SosEngine.handle(c, action ?: SosEngine.ACTION_TICK, reason)
                } catch (e: Exception) {
                    // the alarm or the next start tries again
                }
                stopNow()
            }
            return START_NOT_STICKY
        }
        startTracking()
        if (action == SosEngine.ACTION_OK || action == SosEngine.ACTION_CANCEL || action == SosEngine.ACTION_FIRE) {
            scope.launch {
                try {
                    SosEngine.handle(c, action, reason)
                } catch (e: Exception) {
                    // the loop below tries the clock again in a few seconds
                }
            }
        }
        startLoop()
        return START_STICKY
    }

    private fun goForeground(n: Notification): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(SosEngine.ID_RUN, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(SosEngine.ID_RUN, n)
            return true
        } catch (e: Exception) {
            // for example the location permission was taken away: try without a type
        }
        try {
            startForeground(SosEngine.ID_RUN, n)
            return true
        } catch (e: Exception) {
            return false
        }
    }

    private fun stopNow() {
        stopTracking()
        loop?.cancel()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            // not in the foreground
        }
        stopSelf()
    }

    private fun startLoop() {
        if (loop?.isActive == true) return
        val c = applicationContext
        loop = scope.launch {
            while (true) {
                val alive = try {
                    SosEngine.tick(c)
                } catch (e: Exception) {
                    SosEngine.current() != null
                }
                if (!alive || SosEngine.current() == null) {
                    stopNow()
                    return@launch
                }
                refreshNotification()
                delay(5_000L)
            }
        }
    }

    private fun refreshNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.notify(SosEngine.ID_RUN, notification(SosEngine.current()))
        } catch (e: Exception) {
            // the notification stays as it was
        }
    }

    private fun notification(r: SosTimer.Rec?): Notification {
        val c = applicationContext
        val now = System.currentTimeMillis()
        val phase = if (r == null) null else SosTimer.phase(r, now)
        val title: String
        val text: String
        when (phase) {
            null -> {
                title = "KHOSQ"
                text = tr("SOS և ժամանակաչափ")
            }
            SosTimer.Phase.RUNNING, SosTimer.Phase.WARNING -> {
                title = tr("Ժամանակաչափը աշխատում է")
                text = trf("Մնաց {0}. տեղաշարժդ գրանցվում է միայն քո հեռախոսում", SosTimer.clock(SosTimer.remainingMs(r!!, now)))
            }
            SosTimer.Phase.DUE -> {
                title = tr("SOS-ը ուղարկվում է…")
                text = tr("Շրջանը ստանում է քո վերջին վայրը")
            }
            SosTimer.Phase.LIVE, SosTimer.Phase.OVER -> {
                title = tr("SOS-ը ուղարկված է")
                text = tr("Շրջանը տեսնում է քո տեղաշարժը։ Սեղմիր «Լավ եմ», երբ ապահով ես։")
            }
        }
        val b = NotificationCompat.Builder(c, SosEngine.CH_RUN)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(SosEngine.openApp(c))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (phase != null) b.addAction(0, tr("Լավ եմ"), SosEngine.actionPi(c, SosEngine.ACTION_OK, 2))
        if (phase == SosTimer.Phase.RUNNING || phase == SosTimer.Phase.WARNING) {
            b.addAction(0, tr("Չեղարկել"), SosEngine.actionPi(c, SosEngine.ACTION_CANCEL, 3))
        }
        return b.build()
    }

    // ---- the places

    private fun startTracking() {
        if (listener != null) return
        val c = applicationContext
        if (!OneShotLocation.hasPermission(c)) return
        val m = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val fine = ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val maxAccuracy = if (fine) TrailLogic.GOOD_ACCURACY else 3000f
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                SosEngine.onFix(c, location, maxAccuracy)
            }

            @Deprecated("kept for older Android versions")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

            override fun onProviderEnabled(provider: String) {}

            override fun onProviderDisabled(provider: String) {}
        }
        try {
            var any = false
            if (fine && m.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 15_000L, 0f, l, Looper.getMainLooper())
                any = true
            }
            if (m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 30_000L, 0f, l, Looper.getMainLooper())
                any = true
            }
            if (any) {
                manager = m
                listener = l
            }
        } catch (e: SecurityException) {
            // the permission was taken away: the timer still runs, without places
        }
        // the very first place, right now (what arrives later is still kept)
        scope.launch { SosEngine.freshFix(c, 20_000L) }
    }

    private fun stopTracking() {
        val m = manager
        val l = listener
        if (m != null && l != null) {
            try {
                m.removeUpdates(l)
            } catch (e: SecurityException) {
                // nothing more to stop
            }
        }
        manager = null
        listener = null
    }

    override fun onDestroy() {
        stopTracking()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Starts (or pokes) the service. If Android refuses a foreground service right now, the action is carried out here without it. */
        fun start(c: Context, action: String? = null, reason: SosReason? = null) {
            val i = Intent(c, SosService::class.java)
            if (action != null) i.action = action
            if (reason != null) i.putExtra(SosEngine.EXTRA_REASON, reason.code)
            try {
                ContextCompat.startForegroundService(c, i)
            } catch (e: Exception) {
                Thread {
                    try {
                        runBlocking { SosEngine.handle(c, action ?: SosEngine.ACTION_TICK, reason) }
                    } catch (e2: Exception) {
                        // the next alarm tries again
                    }
                }.start()
            }
        }
    }
}

/** The alarms (the warning, the SOS, the next update) and the buttons of the notifications. */
class SosReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = context.applicationContext
        val a = intent.action ?: return
        if (SosEngine.current() == null) return
        when (a) {
            SosEngine.ACTION_TICK, SosEngine.ACTION_OK, SosEngine.ACTION_CANCEL -> SosService.start(c, a)
        }
    }
}

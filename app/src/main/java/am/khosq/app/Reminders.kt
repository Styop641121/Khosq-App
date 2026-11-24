package am.khosq.app

import am.khosq.app.data.Task
import am.khosq.app.data.TaskLogic
import am.khosq.app.data.TaskStore
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Everything that happens to a task and its reminder: saving, finishing, snoozing, ringing.
 * The phone's alarm clock wakes the app at the right moment even when it is closed; after a restart or an update
 * (which clear all alarms) [rescheduleAll] sets them again, and a reminder that was missed meanwhile rings at once.
 */
object Reminders {
    const val CHANNEL = "reminders"
    const val ACTION_FIRE = "am.khosq.app.REMINDER_FIRE"
    const val ACTION_DONE = "am.khosq.app.REMINDER_DONE"
    const val ACTION_SNOOZE = "am.khosq.app.REMINDER_SNOOZE"
    const val EXTRA_ID = "task"
    const val EXTRA_OPEN_TASKS = "tasks"
    private const val TAG = "task"

    @Volatile
    private var app: Context? = null

    /** Called when the app process starts. */
    fun init(ctx: Context) {
        val c = ctx.applicationContext
        app = c
        TaskStore.init(c)
        try {
            createChannel(c)
        } catch (e: Exception) {
            // made again when the first reminder is shown
        }
    }

    private fun createChannel(c: Context) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("Հիշեցումներ"), NotificationManager.IMPORTANCE_HIGH))
    }

    // ---- the alarm clock

    private fun firePi(c: Context, id: Long): PendingIntent = PendingIntent.getBroadcast(
        c,
        id.toInt(),
        Intent(c, ReminderReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Exact timing needs a permission on Android 12 and newer; the app asks for the one Android grants by itself. */
    private fun canBeExact(am: AlarmManager): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()

    private fun schedule(c: Context, id: Long, at: Long) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = firePi(c, id)
        try {
            if (canBeExact(am)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            // exact alarms were switched off in the meantime: a reminder a few minutes late is better than none
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun cancelAlarm(c: Context, id: Long) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(firePi(c, id))
    }

    private fun dismiss(c: Context, id: Long) {
        NotificationManagerCompat.from(c).cancel(TAG, id.toInt())
    }

    /** Makes the alarm and the notification match the stored task. */
    private fun sync(c: Context, t: Task) {
        cancelAlarm(c, t.id)
        dismiss(c, t.id)
        if (!t.done && t.remindAt > 0L) schedule(c, t.id, t.remindAt)
    }

    // ---- what the app and the notification buttons do

    /** Saves a new or changed task. False when it has no title or the list is full. */
    fun save(t: Task): Boolean {
        val c = app ?: return false
        val p = TaskLogic.prepare(t, System.currentTimeMillis()) ?: return false
        val stored: Task
        if (p.id == 0L) {
            stored = TaskStore.insert(p) ?: return false
        } else {
            TaskStore.update(p)
            stored = p
        }
        sync(c, stored)
        return true
    }

    fun setDone(id: Long, done: Boolean) {
        val c = app ?: return
        val old = TaskStore.get(id) ?: return
        val t = TaskLogic.withDone(old, done, System.currentTimeMillis())
        TaskStore.update(t)
        sync(c, t)
    }

    /** "Later": rings again in ten minutes. */
    fun snooze(id: Long) {
        val c = app ?: return
        val old = TaskStore.get(id) ?: return
        if (old.done) return
        val t = TaskLogic.snoozed(old, System.currentTimeMillis())
        TaskStore.update(t)
        sync(c, t)
    }

    fun delete(id: Long) {
        val c = app ?: return
        TaskStore.delete(id)
        cancelAlarm(c, id)
        dismiss(c, id)
    }

    fun clearDone() {
        val c = app ?: return
        for (id in TaskStore.clearDone()) {
            cancelAlarm(c, id)
            dismiss(c, id)
        }
    }

    /** The alarm of task [id] went off. */
    fun fire(id: Long) {
        val t = TaskStore.claimFire(id, System.currentTimeMillis()) ?: return
        show(t)
    }

    /**
     * Sets every waiting alarm again (the phone forgets them on restart and on an app update) and rings, right now,
     * the ones whose time passed while nothing could ring.
     */
    fun rescheduleAll() {
        val c = app ?: return
        val now = System.currentTimeMillis()
        for (t in TaskStore.waiting()) {
            if (t.remindAt <= now + TaskLogic.SLACK_MS) {
                val due = TaskStore.claimFire(t.id, now)
                if (due != null) show(due)
            } else {
                schedule(c, t.id, t.remindAt)
            }
        }
    }

    private fun actionPi(c: Context, action: String, id: Long): PendingIntent = PendingIntent.getBroadcast(
        c,
        id.toInt(),
        Intent(c, ReminderReceiver::class.java).setAction(action).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun show(t: Task) {
        val c = app ?: return
        try {
            createChannel(c)
            val open = PendingIntent.getActivity(
                c,
                t.id.toInt(),
                Intent(c, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_OPEN_TASKS, true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val time = if (t.due > 0L) android.text.format.DateFormat.getTimeFormat(c).format(java.util.Date(t.due)) else ""
            val body = if (t.note.isNotEmpty()) t.note else if (time.isNotEmpty()) trf("Հիշեցում՝ {0}", time) else tr("Հիշեցում")
            val n = NotificationCompat.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(t.title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .addAction(0, tr("Կատարված"), actionPi(c, ACTION_DONE, t.id))
                .addAction(0, tr("Հետաձգել 10 ր"), actionPi(c, ACTION_SNOOZE, t.id))
                .build()
            NotificationManagerCompat.from(c).notify(TAG, t.id.toInt(), n)
        } catch (e: SecurityException) {
            // notifications are switched off for the app: the task is still in the list
        }
    }
}

/** Receives the alarm and the two buttons of the reminder notification. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val id = intent.getLongExtra(Reminders.EXTRA_ID, 0L)
        if (id == 0L) return
        val c = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                Reminders.init(c)
                when (action) {
                    Reminders.ACTION_FIRE -> Reminders.fire(id)
                    Reminders.ACTION_DONE -> Reminders.setDone(id, true)
                    Reminders.ACTION_SNOOZE -> Reminders.snooze(id)
                }
            } catch (e: Exception) {
                // nothing more can be done from here; the next app start sets the alarms again
            } finally {
                pending.finish()
            }
        }.start()
    }
}

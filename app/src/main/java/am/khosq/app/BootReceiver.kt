package am.khosq.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * After the phone restarts: starts the background service and sets the task reminders again.
 * After an update of the app (which also clears all alarms): sets the task reminders again.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED) KeepAliveService.start(context)
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val c = context.applicationContext
            val pending = goAsync()
            Thread {
                try {
                    Reminders.init(c)
                    Reminders.rescheduleAll()
                } catch (e: Exception) {
                    // the next app start tries again
                } finally {
                    pending.finish()
                }
            }.start()
        }
    }
}

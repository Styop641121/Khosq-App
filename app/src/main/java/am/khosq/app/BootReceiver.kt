package am.khosq.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the background service again after the phone restarts. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) KeepAliveService.start(context)
    }
}

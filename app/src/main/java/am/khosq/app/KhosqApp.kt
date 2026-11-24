package am.khosq.app

import am.khosq.app.data.ProfileStore
import android.app.Application

/** Runs before anything else: loads the saved language and theme so every screen and service starts with them. */
class KhosqApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        ProfileStore.init(this)
        am.khosq.app.update.Updater.cleanup(this)
        // KHOSQ Memory: the database of saved texts opens lazily; the list of chats with Memory on is read in the background
        am.khosq.app.data.MemoryStore.init(this)
        // task reminders: the phone forgets alarms on restart/update, so they are set again whenever the app starts
        Reminders.init(this)
        Thread {
            try {
                Reminders.rescheduleAll()
            } catch (e: Exception) {
                // tried again at the next start
            }
        }.start()
        // the phone's encryption keys are made or loaded in the background, so the first message does not wait for them
        Thread {
            try {
                am.khosq.app.crypto.E2E.myPublic()
            } catch (e: Exception) {
                // tried again when the first message is sent
            }
        }.start()
    }
}

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
    }
}

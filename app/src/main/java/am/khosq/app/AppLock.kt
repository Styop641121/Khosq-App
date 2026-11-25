package am.khosq.app

import am.khosq.app.data.AppLockLogic
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.view.Window
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The lock of the app and the other small privacy switches of the security centre.
 *
 * The lock does not keep a password of its own: it asks for the unlock of the phone itself (PIN, pattern, password, and on many phones
 * the fingerprint). It closes the app after a cold start and after the person has been away for the chosen time.
 */
object AppLock {
    private const val K_LOCK = "sec_lock"
    private const val K_GRACE = "sec_grace"
    private const val K_SCREEN = "sec_screen"
    private const val K_HIDE = "sec_hide"

    private val _enabled = MutableStateFlow(false)

    /** The person switched the lock on. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _locked = MutableStateFlow(false)

    /** The app is closed right now: the screens are not shown, only the unlock screen. */
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val _grace = MutableStateFlow(AppLockLogic.DEFAULT_GRACE)

    /** Minutes away from the app before it locks again (0 = at once). */
    val grace: StateFlow<Int> = _grace.asStateFlow()

    private val _screenGuard = MutableStateFlow(false)

    /** Screenshots and screen recording are blocked, and the app shows nothing in the list of recent apps. */
    val screenGuard: StateFlow<Boolean> = _screenGuard.asStateFlow()

    private val _hideText = MutableStateFlow(false)

    /** Notifications of messages do not show the text. */
    val hideText: StateFlow<Boolean> = _hideText.asStateFlow()

    /** True while the unlock screen of the phone is on top of the app: leaving for it must not count as leaving the app. */
    @Volatile
    var busy: Boolean = false

    @Volatile
    private var leftAt: Long = 0L

    /** Called once when the process starts: the app starts closed when the lock is on. */
    fun load() {
        _enabled.value = Prefs.getString(K_LOCK) == "1"
        _grace.value = AppLockLogic.parseGrace(Prefs.getString(K_GRACE))
        _screenGuard.value = Prefs.getString(K_SCREEN) == "1"
        _hideText.value = Prefs.getString(K_HIDE) == "1"
        _locked.value = _enabled.value
        leftAt = 0L
    }

    fun setEnabled(on: Boolean) {
        Prefs.putStringSync(K_LOCK, if (on) "1" else "0")
        _enabled.value = on
        leftAt = 0L
        if (!on) _locked.value = false
    }

    fun setGrace(minutes: Int) {
        val m = AppLockLogic.clampGrace(minutes)
        Prefs.putString(K_GRACE, m.toString())
        _grace.value = m
    }

    fun setScreenGuard(on: Boolean) {
        Prefs.putString(K_SCREEN, if (on) "1" else "0")
        _screenGuard.value = on
    }

    fun setHideText(on: Boolean) {
        Prefs.putString(K_HIDE, if (on) "1" else "0")
        _hideText.value = on
    }

    /** Read straight from the saved settings, for services that may run before anything else has loaded. */
    fun hideTextNow(): Boolean = Prefs.getString(K_HIDE) == "1"

    /** The last screen of the app went away. */
    fun left(now: Long) {
        if (busy) return
        leftAt = now
    }

    /** A screen of the app came back; maybe the app has to close. */
    fun back(now: Long, callOn: Boolean) {
        if (busy) return
        if (AppLockLogic.shouldLock(_enabled.value, _locked.value, leftAt, now, _grace.value, callOn)) {
            _locked.value = true
        } else {
            // the person is back in time: an old "left at" must not count against them later (a screen turning over is also a return)
            leftAt = 0L
        }
    }

    /** The phone's unlock was passed. */
    fun unlocked() {
        _locked.value = false
        leftAt = 0L
    }

    /** Puts the "no screenshots" mark on a window, or takes it off, as the switch says. */
    fun applyScreen(window: Window?) {
        if (window == null) return
        if (Prefs.getString(K_SCREEN) == "1") {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

/** The unlock screen of the phone itself. */
object DeviceLock {
    /** The phone has a PIN, a pattern or a password. */
    fun secure(ctx: Context): Boolean {
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return false
        return km.isDeviceSecure
    }

    /** The screen that asks for the phone's unlock; null when the phone has none (so nothing can be asked). */
    fun intent(ctx: Context, title: String, text: String): Intent? {
        val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return null
        if (!km.isDeviceSecure) return null
        return km.createConfirmDeviceCredentialIntent(title, text)
    }

    /** Result code of a passed unlock. */
    const val OK = Activity.RESULT_OK
}

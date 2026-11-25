package am.khosq.app.data

/** The maker of the phone, as far as it matters for keeping an app alive in the background. */
enum class Oem { XIAOMI, HUAWEI, OPPO, VIVO, ONEPLUS, SAMSUNG, OTHER }

/** One thing that must be right for notifications to arrive when the app is closed. */
enum class RelStep {
    /** Notifications of the app are allowed in the phone's settings. */
    NOTIFICATIONS,

    /** The channel of messages is not switched off. */
    CHANNEL,

    /** The app is outside the battery saver of Android itself. */
    BATTERY,

    /** The maker's own "start by itself" permission (Xiaomi, Huawei, Oppo, Vivo, OnePlus). Cannot be read: the person confirms it. */
    AUTOSTART,

    /** The maker's own battery rule for the app. Cannot be read: the person confirms it. */
    OEM_BATTERY,

    /** Xiaomi: "show on the lock screen" and "pop-up windows in the background", needed for a call to open the screen. Confirmed by the person. */
    OEM_POPUP,

    /** The app is locked in the list of recent apps, so a "clean all" does not close it. Confirmed by the person. */
    LOCK_RECENTS,

    /** Android 14 and newer: full-screen notifications (a call above the lock screen) are allowed. */
    FULLSCREEN,
}

enum class RelLevel {
    /** In order. */
    OK,

    /** Wrong, and the phone can say so: it must be fixed. */
    TODO,

    /** The phone cannot say: the person must look and confirm. */
    CHECK,
}

class RelItem(val step: RelStep, val level: RelLevel)

/** What is known about the phone. [confirmed] holds the steps that the person has marked as done. */
class RelInput(
    val sdk: Int,
    val oem: Oem,
    val notificationsOn: Boolean,
    val channelOn: Boolean,
    val batteryFree: Boolean,
    val fullScreenOk: Boolean,
    val confirmed: Set<RelStep>,
) {
    /** The same phone with other marks of the person. */
    fun withConfirmed(c: Set<RelStep>): RelInput = RelInput(sdk, oem, notificationsOn, channelOn, batteryFree, fullScreenOk, c)
}

class RelReport(val items: List<RelItem>) {
    val todo: Int get() = items.count { x -> x.level == RelLevel.TODO }
    val check: Int get() = items.count { x -> x.level == RelLevel.CHECK }

    /** Steps that still want the person's attention. */
    val attention: Int get() = todo + check
    val allGood: Boolean get() = attention == 0
}

/** How long ago something happened: [kind] 0 = never, 1 = just now, 2 = minutes, 3 = hours, 4 = days; [n] is the number. */
class Ago(val kind: Int, val n: Long)

object OemLogic {
    /** Finds the maker from the phone's manufacturer and brand (Redmi and Poco are Xiaomi, Honor is Huawei's old brand, Realme goes with Oppo). */
    fun detect(manufacturer: String, brand: String): Oem {
        val m = manufacturer.trim().lowercase()
        val b = brand.trim().lowercase()
        fun has(vararg names: String): Boolean = names.any { n -> m.contains(n) || b.contains(n) }
        return when {
            has("xiaomi", "redmi", "poco") -> Oem.XIAOMI
            has("huawei", "honor") -> Oem.HUAWEI
            has("oppo", "realme") -> Oem.OPPO
            has("vivo", "iqoo") -> Oem.VIVO
            has("oneplus") -> Oem.ONEPLUS
            has("samsung") -> Oem.SAMSUNG
            else -> Oem.OTHER
        }
    }

    /** Makers whose systems are known to close apps in the background unless the person allows it in several places. */
    fun aggressive(o: Oem): Boolean = o == Oem.XIAOMI || o == Oem.HUAWEI || o == Oem.OPPO || o == Oem.VIVO || o == Oem.ONEPLUS

    /** HyperOS is the newer system of Xiaomi (its name property starts with "OS"); older ones are MIUI. */
    fun hyperOs(osName: String): Boolean = osName.trim().uppercase().startsWith("OS")
}

object ReliabilityLogic {
    /** The steps that belong to a maker, in the order the person should go through them. */
    fun makerSteps(o: Oem): List<RelStep> = when (o) {
        Oem.XIAOMI -> listOf(RelStep.AUTOSTART, RelStep.OEM_BATTERY, RelStep.OEM_POPUP, RelStep.LOCK_RECENTS)
        Oem.HUAWEI, Oem.OPPO, Oem.VIVO, Oem.ONEPLUS -> listOf(RelStep.AUTOSTART, RelStep.OEM_BATTERY, RelStep.LOCK_RECENTS)
        Oem.SAMSUNG -> listOf(RelStep.OEM_BATTERY)
        Oem.OTHER -> emptyList()
    }

    /** The list for this phone: what Android itself can say first, then what only the maker's settings hold. */
    fun plan(i: RelInput): RelReport {
        val out = ArrayList<RelItem>()
        out.add(RelItem(RelStep.NOTIFICATIONS, if (i.notificationsOn) RelLevel.OK else RelLevel.TODO))
        // channels exist from Android 8, which is the oldest the app installs on
        out.add(RelItem(RelStep.CHANNEL, if (i.channelOn || !i.notificationsOn) RelLevel.OK else RelLevel.TODO))
        out.add(RelItem(RelStep.BATTERY, if (i.batteryFree) RelLevel.OK else RelLevel.TODO))
        if (i.sdk >= 34) out.add(RelItem(RelStep.FULLSCREEN, if (i.fullScreenOk) RelLevel.OK else RelLevel.TODO))
        for (s in makerSteps(i.oem)) out.add(RelItem(s, if (s in i.confirmed) RelLevel.OK else RelLevel.CHECK))
        return RelReport(out)
    }

    /** The marked steps, kept as names with commas. A name that this version does not know is dropped. */
    fun parseConfirmed(csv: String): Set<RelStep> {
        val out = LinkedHashSet<RelStep>()
        for (x in csv.split(',')) {
            val s = RelStep.values().firstOrNull { v -> v.name == x.trim() }
            if (s != null) out.add(s)
        }
        return out
    }

    fun encodeConfirmed(set: Set<RelStep>): String = RelStep.values().filter { v -> v in set }.joinToString(",") { v -> v.name }

    /** Marks the step done, or takes the mark away when it was there. */
    fun toggle(csv: String, step: RelStep): String {
        val set = LinkedHashSet(parseConfirmed(csv))
        if (!set.add(step)) set.remove(step)
        return encodeConfirmed(set)
    }

    fun ago(now: Long, then: Long): Ago {
        if (then <= 0L) return Ago(0, 0)
        val d = now - then
        return when {
            d < 60_000L -> Ago(1, 0)
            d < 3_600_000L -> Ago(2, d / 60_000L)
            d < 86_400_000L -> Ago(3, d / 3_600_000L)
            else -> Ago(4, d / 86_400_000L)
        }
    }

    /** True when the first-run question should be asked: a maker that closes apps, the question not asked before, and not everything in order. */
    fun shouldAsk(o: Oem, alreadyAsked: Boolean, report: RelReport): Boolean = OemLogic.aggressive(o) && !alreadyAsked && !report.allGood
}

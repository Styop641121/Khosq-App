package am.khosq.app.ui

import am.khosq.app.data.Fix
import am.khosq.app.data.LocationPick
import am.khosq.app.tr
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/**
 * One position, asked for once. Nothing keeps listening afterwards: the request ends as soon as there is a good enough answer
 * (or after 20 seconds). It uses Android's own LocationManager, so no extra library and no Google location service.
 *
 * To be quick, it does not wait for the GPS alone (indoors that takes 10-20 seconds or more): it first takes what the phone already
 * knows (a reading of the last two minutes counts as "now"), then asks every provider that is on at the same time (the phone's
 * fused provider on Android 12+, the network, the GPS) and stops at the first reading that is exact enough. [LocationPick] holds the
 * rules. The exact accuracy of the answer is in [Location.getAccuracy].
 */
object OneShotLocation {
    private fun granted(ctx: Context, p: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun hasPermission(ctx: Context): Boolean =
        granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) || granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun fixOf(l: Location): Fix {
        val ageMs = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000L
        return Fix(
            lat = l.latitude,
            lng = l.longitude,
            accuracy = LocationPick.accuracyOf(l.accuracy, l.hasAccuracy()),
            ageMs = ageMs.coerceAtLeast(0L),
            source = l.provider ?: "",
            ref = l,
        )
    }

    /**
     * Calls [onDone] on the main thread with the position, or null when there is none (no permission, no provider on, no reading).
     * Returns a function that gives the request up (then [onDone] is not called).
     */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun request(ctx: Context, onDone: (Location?) -> Unit): () -> Unit {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null || !hasPermission(ctx)) {
            onDone(null)
            return {}
        }
        val fine = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val providers = ArrayList<String>()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
                providers.add(LocationManager.FUSED_PROVIDER)
            }
            if (fine && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) providers.add(LocationManager.GPS_PROVIDER)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) providers.add(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            // a provider that cannot be asked is simply left out
        }
        if (providers.isEmpty()) {
            onDone(null)
            return {}
        }

        val handler = Handler(Looper.getMainLooper())
        val started = SystemClock.elapsedRealtime()
        var best: Fix? = null
        var finished = false
        var waiting = providers.size
        val signals = ArrayList<android.os.CancellationSignal>()
        val listeners = ArrayList<LocationListener>()

        fun stopAll() {
            handler.removeCallbacksAndMessages(null)
            for (c in signals) c.cancel()
            for (l in listeners) {
                try {
                    lm.removeUpdates(l)
                } catch (e: Exception) {
                    // nothing more to stop
                }
            }
        }

        fun finish(f: Fix?) {
            if (finished) return
            finished = true
            stopAll()
            onDone(f?.ref as? Location)
        }

        fun offer(l: Location?) {
            if (finished) return
            if (l != null) best = LocationPick.best(best, fixOf(l))
            val elapsed = SystemClock.elapsedRealtime() - started
            if (LocationPick.enough(best, elapsed)) finish(best)
        }

        // 1. what the phone already knows: no waiting at all when it is recent and exact enough
        val known = ArrayList<String>(providers)
        if (fine) known.add(LocationManager.PASSIVE_PROVIDER)
        for (p in known) {
            try {
                val l = lm.getLastKnownLocation(p)
                if (l != null) best = LocationPick.best(best, fixOf(l))
            } catch (e: Exception) {
                // this provider has nothing to say
            }
        }
        if (LocationPick.enough(best, 0L)) {
            onDone(best?.ref as? Location)
            return {}
        }

        // 2. ask everybody at once; the first exact enough answer ends it
        for (p in providers) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val c = android.os.CancellationSignal()
                    signals.add(c)
                    lm.getCurrentLocation(
                        p,
                        c,
                        ContextCompat.getMainExecutor(ctx),
                        java.util.function.Consumer<Location?> { loc ->
                            waiting -= 1
                            offer(loc)
                            // every provider answered and none gave enough: take the best of them now, do not sit to the end
                            if (!finished && waiting <= 0) finish(if (best != null && LocationPick.usable(best!!)) best else null)
                        },
                    )
                } else {
                    val l = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            offer(location)
                        }

                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

                        override fun onProviderEnabled(provider: String) {}

                        override fun onProviderDisabled(provider: String) {}
                    }
                    listeners.add(l)
                    lm.requestSingleUpdate(p, l, Looper.getMainLooper())
                }
            } catch (e: Exception) {
                // this provider could not be asked (permission taken back, provider gone)
                waiting -= 1
            }
        }
        if (waiting <= 0 && !finished) {
            finish(if (best != null && LocationPick.usable(best!!)) best else null)
            return {}
        }
        // a fairly exact reading is taken when the grace time is over; at the end whatever is best is taken
        handler.postDelayed({ offer(null) }, LocationPick.GRACE_MS)
        handler.postDelayed({
            finish(if (best != null && LocationPick.usable(best!!)) best else null)
        }, LocationPick.MAX_MS)
        return {
            if (!finished) {
                finished = true
                stopAll()
            }
        }
    }
}

/** "40.18111,44.51361" -> (40.18111, 44.51361); null when the text is not a position. */
internal fun parseCoords(s: String): Pair<Double, Double>? {
    val p = s.split(",")
    if (p.size != 2) return null
    val a = p[0].trim().toDoubleOrNull() ?: return null
    val b = p[1].trim().toDoubleOrNull() ?: return null
    if (a < -90.0 || a > 90.0 || b < -180.0 || b > 180.0) return null
    return a to b
}

/** Opens the place in whatever maps app the phone has (a "geo:" link), or in the browser as a fallback. */
internal fun openInMaps(ctx: Context, lat: Double, lng: Double) {
    val ll = String.format(java.util.Locale.US, "%.5f,%.5f", lat, lng)
    val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$ll?q=$ll"))
    val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$ll"))
    try {
        ctx.startActivity(geo)
    } catch (e: Exception) {
        try {
            ctx.startActivity(web)
        } catch (e2: Exception) {
            Toast.makeText(ctx, tr("Քարտեզի ծրագիր չի գտնվել"), Toast.LENGTH_SHORT).show()
        }
    }
}

/** The body of a location message: a small card with the coordinates and a button that opens the maps app. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LocationBody(m: am.khosq.app.data.Message, mine: Boolean, ink: Color, soft: Color, onLong: () -> Unit) {
    val k = KTheme.colors
    val ctx = LocalContext.current
    val pos = parseCoords(m.text)
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(Corner.m))
            .background(if (mine) Color(0x26FFFFFF) else k.accentSoft)
            .combinedClickable(
                onClick = { if (pos != null) openInMaps(ctx, pos.first, pos.second) },
                onLongClick = onLong,
            )
            .padding(horizontal = Space.m, vertical = Space.s),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                Icon(imageVector = KIcons.Location, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(Space.s))
            Text(text = tr("Տեղադրություն"), color = ink, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold))
        }
        if (pos != null) {
            Text(
                text = String.format(java.util.Locale.US, "%.5f, %.5f", pos.first, pos.second),
                color = soft,
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = tr("Բացել քարտեզում"),
                color = if (mine) Color(0xFFE3E8FF) else k.accent,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(bottom = 1.dp),
            )
        }
    }
}

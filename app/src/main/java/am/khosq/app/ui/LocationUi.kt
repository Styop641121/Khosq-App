package am.khosq.app.ui

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
 * One position, asked for once. Nothing keeps listening afterwards: the request ends as soon as there is an answer
 * (or after 20 seconds). It uses Android's own LocationManager, so no extra library and no Google location service.
 */
object OneShotLocation {
    private fun granted(ctx: Context, p: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun hasPermission(ctx: Context): Boolean =
        granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) || granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Calls [onDone] on the main thread with the position, or null when there is none (no permission, GPS off, no fix). */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun request(ctx: Context, onDone: (Location?) -> Unit) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null || !hasPermission(ctx)) {
            onDone(null)
            return
        }
        val fine = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val provider = when {
            fine && lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> null
        }
        if (provider == null) {
            onDone(null)
            return
        }
        val handler = Handler(Looper.getMainLooper())
        var finished = false
        var cancel: android.os.CancellationSignal? = null
        var listener: LocationListener? = null
        fun finish(loc: Location?) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            cancel?.cancel()
            val l = listener
            if (l != null) {
                try {
                    lm.removeUpdates(l)
                } catch (e: SecurityException) {
                    // nothing more to stop
                }
            }
            // a recent position the phone already knows is better than none
            val fallback = if (loc == null) {
                try {
                    lm.getLastKnownLocation(provider)
                } catch (e: SecurityException) {
                    null
                }
            } else {
                null
            }
            onDone(loc ?: fallback)
        }
        handler.postDelayed({ finish(null) }, 20_000L)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11 and newer: one fix, asked for directly
                val c = android.os.CancellationSignal()
                cancel = c
                lm.getCurrentLocation(
                    provider,
                    c,
                    ContextCompat.getMainExecutor(ctx),
                    java.util.function.Consumer<Location?> { loc -> finish(loc) },
                )
            } else {
                // older Android: a single update, which ends itself after the first answer
                val l = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        finish(location)
                    }

                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

                    override fun onProviderEnabled(provider: String) {}

                    override fun onProviderDisabled(provider: String) {}
                }
                listener = l
                lm.requestSingleUpdate(provider, l, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            finish(null)
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

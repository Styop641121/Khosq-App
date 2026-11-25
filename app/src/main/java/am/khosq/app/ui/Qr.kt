package am.khosq.app.ui

import am.khosq.app.crypto.E2E
import am.khosq.app.data.Profile
import am.khosq.app.data.QrLogic
import am.khosq.app.tr
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Opens the QR scanner of Google Play services. Its screen belongs to Google's own service, so the app needs no camera permission
 * for it. [onText] gets the text of the code; closing the scanner calls nothing; [onFail] gets a text for the person.
 */
fun scanQr(ctx: Context, onText: (String) -> Unit, onFail: (String) -> Unit) {
    try {
        GmsBarcodeScanning.getClient(ctx)
            .startScan()
            .addOnSuccessListener { barcode ->
                val raw = barcode.rawValue
                if (raw == null) onFail(tr("Կոդը չկարդացվեց")) else onText(raw)
            }
            .addOnFailureListener { _ ->
                onFail(tr("Սկաները չբացվեց։ Ստուգիր համացանցը և Google Play ծառայությունների թարմացումը։"))
            }
    } catch (e: Exception) {
        onFail(tr("Սկաները չբացվեց։ Ստուգիր համացանցը և Google Play ծառայությունների թարմացումը։"))
    }
}

/** A QR code drawn as black squares (quiet space and a white card are the caller's job). */
@Composable
fun QrImage(text: String, dp: Dp) {
    val matrix = remember(text) {
        try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 0,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            )
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        } catch (e: Exception) {
            null
        }
    }
    if (matrix == null) return
    Canvas(modifier = Modifier.size(dp)) {
        val n = matrix.width
        val cell = this.size.width / n
        for (y in 0 until n) {
            for (x in 0 until n) {
                if (matrix.get(x, y)) {
                    // a little over the edge, so no hairline shows between two squares
                    drawRect(color = Color.Black, topLeft = Offset(x * cell, y * cell), size = Size(cell + 0.6f, cell + 0.6f))
                }
            }
        }
    }
}

/**
 * My QR code: my ID, my account and the fingerprint of my key. Somebody who stands next to me scans it to add me, or to confirm
 * that my key is the real one. [onScan] (when given) is the way to scan theirs next.
 */
@Composable
fun MyQrSheet(me: Profile, onClose: () -> Unit, onScan: (() -> Unit)? = null) {
    val k = KTheme.colors
    val text = remember(me.uid, me.handle) { QrLogic.make(me.handle, me.uid, E2E.myPublic()) }
    KSheet(onDismiss = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Text(text = tr("Իմ QR կոդը"), color = k.text, style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold))
            // black on white whatever the theme is, so every scanner reads it
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(Corner.l))
                    .background(Color.White)
                    .padding(Space.l),
            ) {
                QrImage(text = text, dp = 240.dp)
            }
            Text(text = "@" + me.handle, color = k.text, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
            Text(
                text = tr("Թող ընկերդ սկանավորի այս կոդը («Նոր կոնտակտ» → «Սկանավորել QR կոդը»)։ Այդպես նա կավելացնի քեզ և կհամոզվի, որ քո բանալին իսկապես քոնն է։ Երկուսդ էլ սկանավորեք միմյանց կոդը, և երկու կողմն էլ ապահով կլինեն։ Ցույց տուր կոդը միայն այն մարդուն, ում հանդիպել ես։"),
                color = k.textSecondary,
                style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center),
            )
            if (onScan != null) {
                GradientButton(text = tr("Սկանավորել ընկերոջ կոդը"), onClick = onScan, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(Space.xl))
        }
    }
}

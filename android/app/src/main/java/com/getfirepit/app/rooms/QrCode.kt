package com.getfirepit.app.rooms

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders an invite as a QR code.
 *
 * Always drawn dark-on-white regardless of theme: a themed QR code with low
 * contrast is slow or impossible to scan, and scanning is the whole point.
 */
@Composable
fun QrCode(content: String, modifier: Modifier = Modifier, sizePx: Int = 640) {
    val bitmap = remember(content, sizePx) { encodeQr(content, sizePx) }

    Image(
        bitmap = bitmap.asImageBitmap(),
        // The surrounding card carries the description; the pattern itself is
        // meaningless to a screen reader.
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

private fun encodeQr(content: String, sizePx: Int): Bitmap {
    val hints = mapOf(
        // Medium correction: enough to survive a fingerprint on the screen
        // without inflating the code so far it stops scanning at arm's length.
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)

    return createBitmap(matrix.width, matrix.height).apply {
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
    }
}

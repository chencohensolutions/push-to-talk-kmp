package com.chencohen.ptt.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** [text] as a black-on-white QR code of [size] pixels (journal-client-kmp's qrBitmap), with a quiet zone of 2. */
internal fun qrBitmap(text: String, size: Int): Bitmap {
    val hints = mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(size * size) { i -> if (matrix.get(i % size, i / size)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

/** A QR code on white, whatever the theme (scanners read dark on light), with [description] for TalkBack. */
@Composable
internal fun QrImage(text: String, description: String, modifier: Modifier = Modifier, size: Dp = 240.dp) {
    val bitmap = remember(text) { qrBitmap(text, 720).asImageBitmap() }
    Image(
        bitmap,
        contentDescription = null,
        modifier = modifier
            .size(size)
            .background(Color.White, RoundedCornerShape(8.dp))
            .padding(4.dp)
            .semantics { contentDescription = description },
    )
}

/**
 * Google's scanner screen, from Play services (already there for enrollment): it needs no camera permission and adds
 * no camera code to the app. [onRead] gets the code's text; [onFailed] runs when the scanner can't start or read.
 * Closing the scanner calls neither.
 */
internal fun scanQrCode(context: Context, onRead: (String) -> Unit, onFailed: () -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    GmsBarcodeScanning.getClient(context, options).startScan()
        .addOnSuccessListener { barcode -> barcode.rawValue?.let(onRead) ?: onFailed() }
        .addOnFailureListener { onFailed() }
}

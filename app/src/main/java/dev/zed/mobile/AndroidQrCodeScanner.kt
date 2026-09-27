package dev.zed.mobile

import android.app.Activity
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Thin adapter over Google Code Scanner. It launches the Play-services scanner UI and therefore
 * does not require the app to request or declare the CAMERA permission.
 */
class AndroidQrCodeScanner(activity: Activity) {
    private val scanner: GmsBarcodeScanner = GmsBarcodeScanning.getClient(
        activity,
        GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build(),
    )

    suspend fun scan(): String = suspendCancellableCoroutine { continuation ->
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                val value = barcode.rawValue
                if (value.isNullOrEmpty()) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(GatewayException("The scanned QR code has no readable value."))
                    }
                } else if (continuation.isActive) {
                    continuation.resume(value)
                }
            }
            .addOnCanceledListener {
                if (continuation.isActive) continuation.cancel()
            }
            .addOnFailureListener { error ->
                if (continuation.isActive) {
                    continuation.resumeWithException(GatewayException("Unable to scan the pairing QR code: ${error.message ?: "scanner error"}"))
                }
            }
    }
}

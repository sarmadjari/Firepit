package com.getfirepit.app.rooms

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.getfirepit.app.permissions.PermissionNeeded
import com.getfirepit.app.permissions.openAppSettings
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Camera preview that reports QR codes as it reads them.
 *
 * Decoding runs on a single background thread. The same code is only reported
 * once, so a failed join can be retried by scanning the next rotation rather
 * than backing out of the screen.
 */
@Composable
fun QrScanner(
    onScanned: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnScanned by rememberUpdatedState(onScanned)
    val currentEnabled by rememberUpdatedState(enabled)

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { result -> granted = result }

    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!granted) {
        PermissionNeeded(
            title = "Camera needed to scan",
            body = "An invite is a QR code, so Firepit needs the camera to read it. " +
                "Nothing is recorded or sent anywhere.",
            onOpenSettings = { context.openAppSettings() },
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    val executor = remember { Executors.newSingleThreadExecutor() }
    val lastScanned = remember { AtomicReference<String?>(null) }
    val reader = remember {
        MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }
    }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { viewContext ->
            val previewView = PreviewView(viewContext)
            val providerFuture = ProcessCameraProvider.getInstance(viewContext)

            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build()
                    .also { it.surfaceProvider = previewView.surfaceProvider }

                val analysis = ImageAnalysis.Builder()
                    // Dropping frames keeps the preview smooth; the next frame
                    // will carry the same code.
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { useCase ->
                        useCase.setAnalyzer(executor) { image ->
                            if (currentEnabled) {
                                image.decodeQr(reader)?.let { text ->
                                    // Invites rotate, so the next window gives a
                                    // different string and scanning resumes.
                                    if (lastScanned.getAndSet(text) != text) {
                                        ContextCompat.getMainExecutor(viewContext).execute {
                                            currentOnScanned(text)
                                        }
                                    }
                                }
                            }
                            image.close()
                        }
                    }

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }, ContextCompat.getMainExecutor(viewContext))

            previewView
        },
    )
}

/** ZXing reads the luminance plane directly, so the YUV frame needs no conversion. */
private fun ImageProxy.decodeQr(reader: MultiFormatReader): String? {
    val plane = planes.firstOrNull() ?: return null
    val bytes = ByteArray(plane.buffer.remaining()).also(plane.buffer::get)

    val source = PlanarYUVLuminanceSource(
        bytes,
        plane.rowStride,
        height,
        0,
        0,
        width.coerceAtMost(plane.rowStride),
        height,
        false,
    )

    return try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: Exception) {
        // No code in this frame is the normal case, not an error.
        null
    } finally {
        reader.reset()
    }
}

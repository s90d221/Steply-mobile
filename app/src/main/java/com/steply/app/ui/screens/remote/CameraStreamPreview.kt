package com.steply.app.ui.screens.remote

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.steply.app.remote.RemoteCameraStreamer
import com.steply.app.ui.screens.components.SteplyCorners
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

@SuppressLint("MissingPermission")
@Composable
fun CameraStreamPreview(
    remoteCameraStreamer: RemoteCameraStreamer?,
    onCameraStatus: (String) -> Unit,
    onCameraError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnStatus by rememberUpdatedState(onCameraStatus)
    val currentOnError by rememberUpdatedState(onCameraError)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
            contentDescription = "Camera preview for PC streaming"
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(SteplyCorners.Card))
            .background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = 1.dp,
                color = androidx.compose.material3.MaterialTheme.colorScheme.outline.copy(alpha = 0.42f),
                shape = RoundedCornerShape(SteplyCorners.Card),
            )
            .padding(1.dp),
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(SteplyCorners.Card)),
        )
    }

    DisposableEffect(context, lifecycleOwner, previewView, remoteCameraStreamer) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val cameraExecutor = Executors.newSingleThreadExecutor()
        var cameraProvider: ProcessCameraProvider? = null
        var lastRemoteFrameSentAt = 0L
        var lastSendFailureReportedAt = 0L

        cameraProviderFuture.addListener(
            {
                runCatching {
                    val provider = cameraProviderFuture.get()
                    cameraProvider = provider

                    val preview = Preview.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .build()
                        .also { it.setSurfaceProvider(previewView.surfaceProvider) }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build()

                    val frontSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                    val backSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    val useFrontCamera = runCatching { provider.hasCamera(frontSelector) }
                        .getOrDefault(false)
                    val selector = if (useFrontCamera) frontSelector else backSelector

                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        try {
                            val now = SystemClock.uptimeMillis()
                            if (now - lastRemoteFrameSentAt >= REMOTE_CAMERA_FRAME_INTERVAL_MS) {
                                lastRemoteFrameSentAt = now
                                val capturedAtMs = SystemClock.uptimeMillis()
                                val jpegBytes = imageProxy.toRotatedJpegBytes(useFrontCamera = useFrontCamera)
                                val activeStreamer = remoteCameraStreamer
                                val sent = activeStreamer?.sendJpeg(jpegBytes, capturedAtMs = capturedAtMs) == true
                                if (!sent && activeStreamer != null && now - lastSendFailureReportedAt >= SEND_FAILURE_REPORT_INTERVAL_MS) {
                                    lastSendFailureReportedAt = now
                                    mainHandler.post {
                                        currentOnError("Frame captured, but the PC connection is not ready yet.")
                                    }
                                }
                            }
                        } catch (exception: Throwable) {
                            mainHandler.post {
                                currentOnError(exception.message ?: "Failed to send camera frame.")
                            }
                        } finally {
                            imageProxy.close()
                        }
                    }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        selector,
                        preview,
                        imageAnalysis,
                    )
                    currentOnStatus(
                        if (useFrontCamera) {
                            "Front camera preview and stream are ready."
                        } else {
                            "Rear camera preview and stream are ready."
                        },
                    )
                }.onFailure { exception ->
                    currentOnError(exception.message ?: "Could not start the camera.")
                }
            },
            ContextCompat.getMainExecutor(context),
        )

        onDispose {
            cameraProvider?.unbindAll()
            cameraExecutor.shutdown()
        }
    }
}

private fun ImageProxy.toRotatedJpegBytes(
    useFrontCamera: Boolean,
    maxWidth: Int = 640,
    quality: Int = 62,
): ByteArray {
    val bitmapBuffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmapBuffer.copyPixelsFromBuffer(planes[0].buffer)

    val matrix = Matrix().apply {
        postRotate(imageInfo.rotationDegrees.toFloat())
        if (useFrontCamera) {
            postScale(-1f, 1f, width.toFloat(), height.toFloat())
        }
    }
    val rotatedBitmap = Bitmap.createBitmap(
        bitmapBuffer,
        0,
        0,
        bitmapBuffer.width,
        bitmapBuffer.height,
        matrix,
        true,
    )
    bitmapBuffer.recycle()

    val scaledBitmap = if (rotatedBitmap.width > maxWidth) {
        val targetHeight = (rotatedBitmap.height * (maxWidth.toFloat() / rotatedBitmap.width)).toInt().coerceAtLeast(1)
        Bitmap.createScaledBitmap(rotatedBitmap, maxWidth, targetHeight, true)
    } else {
        rotatedBitmap
    }

    return ByteArrayOutputStream().use { output ->
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
        if (scaledBitmap !== rotatedBitmap) scaledBitmap.recycle()
        rotatedBitmap.recycle()
        output.toByteArray()
    }
}

// §6.4: nominal 30 fps input cadence. KEEP_ONLY_LATEST drops work under pressure
// instead of queuing stale frames, so measurement time remains capture-driven.
internal const val REMOTE_CAMERA_FRAME_INTERVAL_MS = 33L
private const val SEND_FAILURE_REPORT_INTERVAL_MS = 1_000L

package com.godavin.vince

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * One-shot screen capture via Android's MediaProjection API - captures a
 * single frame of WHATEVER APP IS CURRENTLY ON SCREEN when VINCE is
 * triggered (TradingView, WhatsApp, anything), not a hardcoded specific
 * app. Android requires a real system permission prompt each fresh
 * capture session - this can't be silently granted once and remembered,
 * by OS design (see the Stage 5 planning notes in project memory).
 *
 * HONEST FLAG: MediaProjection has genuine device/Android-version quirks
 * (Android 14+ in particular tightened background/foreground-service
 * rules around screen capture) that can't be fully verified without
 * running this on real hardware. This is built as carefully and
 * correctly as the documented API allows, but if capture silently fails
 * or behaves oddly on a specific device/Android version, that's the
 * first place to look - report back exactly what happens (or doesn't)
 * so this can be diagnosed precisely rather than guessed at again.
 */
class ScreenCaptureHelper(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null

    fun createCaptureIntent(mediaProjectionManager: MediaProjectionManager): Intent {
        return mediaProjectionManager.createScreenCaptureIntent()
    }

    /** Call after the user has granted screen-capture permission. Captures
     * exactly one frame of the current screen and returns it as a Bitmap,
     * or null on failure. Cleans up all MediaProjection resources itself
     * before returning - this is a single snapshot, not a persistent
     * screen-recording session left running in the background. */
    suspend fun captureSingleFrame(
        mediaProjectionManager: MediaProjectionManager,
        resultCode: Int,
        resultData: Intent
    ): Bitmap? {
        return suspendCancellableCoroutine { continuation ->
            try {
                val metrics = context.resources.displayMetrics
                val width = metrics.widthPixels
                val height = metrics.heightPixels
                val density = metrics.densityDpi

                val thread = HandlerThread("VinceScreenCapture").apply { start() }
                handlerThread = thread
                val handler = Handler(thread.looper)

                val projection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
                mediaProjection = projection

                val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                imageReader = reader

                var resumed = false

                reader.setOnImageAvailableListener({ imgReader ->
                    if (resumed) return@setOnImageAvailableListener
                    val image = imgReader.acquireLatestImage()
                    val bitmap: Bitmap? = if (image != null) {
                        val plane = image.planes[0]
                        val pixelStride = plane.pixelStride
                        val rowStride = plane.rowStride
                        val rowPadding = rowStride - pixelStride * width

                        val raw = Bitmap.createBitmap(
                            width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                        )
                        raw.copyPixelsFromBuffer(plane.buffer)
                        image.close()

                        if (rowPadding == 0) raw else Bitmap.createBitmap(raw, 0, 0, width, height)
                    } else null

                    resumed = true
                    cleanup()
                    if (continuation.isActive) continuation.resume(bitmap)
                }, handler)

                virtualDisplay = projection.createVirtualDisplay(
                    "VinceScreenCapture",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface, null, handler
                )

                continuation.invokeOnCancellation { cleanup() }
            } catch (e: Exception) {
                cleanup()
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    private fun cleanup() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        handlerThread?.quitSafely()
        handlerThread = null
    }
}

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
 * KNOWN FIX APPLIED (v2): a freshly-created virtual display's very first
 * frame(s) commonly come back black/blank on real devices - the display
 * compositor hasn't actually pushed real content into the capture
 * surface yet. Confirmed this was happening here (blank capture on
 * every app tested, not just secured ones). Fixed by deliberately
 * skipping the first few frames and only accepting a later one, once
 * the display has actually stabilized - same practical effect as
 * "record briefly then grab a later frame," without needing a real
 * video-recording pipeline.
 */
class ScreenCaptureHelper(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null

    // How many early frames to discard before trusting one as real
    // content, not a blank/incomplete compositor frame.
    private val FRAMES_TO_SKIP = 4

    fun createCaptureIntent(mediaProjectionManager: MediaProjectionManager): Intent {
        return mediaProjectionManager.createScreenCaptureIntent()
    }

    /** Call after the user has granted screen-capture permission. Captures
     * one real frame of the current screen (after skipping early blank
     * frames) and returns it as a Bitmap, or null on failure. Cleans up
     * all MediaProjection resources itself before returning - this is a
     * single snapshot, not a persistent screen-recording session left
     * running in the background. */
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

                // Buffer depth matched to FRAMES_TO_SKIP+1 so early frames
                // can be discarded without starving the reader.
                val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, FRAMES_TO_SKIP + 2)
                imageReader = reader

                var resumed = false
                var frameCount = 0

                reader.setOnImageAvailableListener({ imgReader ->
                    if (resumed) return@setOnImageAvailableListener
                    val image = imgReader.acquireLatestImage()
                    if (image == null) return@setOnImageAvailableListener

                    frameCount++
                    if (frameCount <= FRAMES_TO_SKIP) {
                        // Early frame - likely blank/incomplete, discard and wait for a later one.
                        image.close()
                        return@setOnImageAvailableListener
                    }

                    val plane = image.planes[0]
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * width

                    val raw = Bitmap.createBitmap(
                        width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                    )
                    raw.copyPixelsFromBuffer(plane.buffer)
                    image.close()

                    val bitmap = if (rowPadding == 0) raw else Bitmap.createBitmap(raw, 0, 0, width, height)

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

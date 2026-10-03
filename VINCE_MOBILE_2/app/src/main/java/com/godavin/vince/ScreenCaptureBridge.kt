package com.godavin.vince

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper

/**
 * Simple same-process bridge: ChatScreen registers a callback before
 * starting ScreenCaptureService, and the service delivers its result
 * here once the capture finishes. Always dispatches on the main thread -
 * the service's capture work happens on a background dispatcher, and
 * Compose state can only be touched safely from the main thread.
 */
object ScreenCaptureBridge {
    private var pendingCallback: ((Bitmap?) -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun awaitCapture(onResult: (Bitmap?) -> Unit) {
        pendingCallback = onResult
    }

    fun deliverResult(bitmap: Bitmap?) {
        val callback = pendingCallback
        pendingCallback = null
        mainHandler.post { callback?.invoke(bitmap) }
    }
}

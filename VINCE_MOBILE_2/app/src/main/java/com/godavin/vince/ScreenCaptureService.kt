package com.godavin.vince

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runs the actual screen capture inside a proper foreground service, not
 * just a bare API call from the Activity. This is Google's own documented
 * requirement for MediaProjection - it's not specific to one Android
 * version or device; running the capture without a foreground service is
 * what's most likely to fail (silently or with a permission-style error)
 * depending on the exact Android version, which is almost certainly what
 * was hit during testing. A foreground service is safe to use on every
 * supported Android version here (26+), not just the newest ones, so
 * this is the generalized fix - it shouldn't matter which phone or
 * Android version this runs on going forward.
 *
 * Shows a brief, low-priority "VINCE is reading your screen" notification
 * while capturing - required by the OS for any foreground service, not
 * something added by choice (same constraint already noted for the
 * planned floating-widget stage).
 */
class ScreenCaptureService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

        if (resultCode != Activity.RESULT_OK || resultData == null) {
            ScreenCaptureBridge.deliverResult(null)
            stopSelf()
            return START_NOT_STICKY
        }

        val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val helper = ScreenCaptureHelper(this)

        CoroutineScope(Dispatchers.Default).launch {
            // Safety timeout - if frames never arrive on some device (rather
            // than just arriving blank, which the frame-skip fix already
            // handles), this still can't hang forever.
            val bitmap = withTimeoutOrNull(8000) {
                helper.captureSingleFrame(mediaProjectionManager, resultCode, resultData)
            }
            ScreenCaptureBridge.deliverResult(bitmap)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "vince_screen_capture"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "VINCE Screen Capture", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("VINCE")
            .setContentText("Reading your screen...")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()

        // ServiceCompat.startForeground handles the version differences
        // itself (the service-type argument is required from Android 10+
        // and enforced strictly from Android 14+; it's simply ignored on
        // older versions) - no manual SDK_INT branching needed.
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val NOTIF_ID = 4201
    }
}

package com.godavin.vince

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * Stage 15 - screen vision from the floating overlay widget. Same
 * transparent-trampoline approach as OverlayCameraActivity:
 * MediaProjection's permission prompt needs a real Activity to appear
 * from, so this one exists purely to request it, then hands off to the
 * EXISTING ScreenCaptureService (unchanged, same one in-app Screen
 * vision uses) and speaks the result - then finishes, same 3-second
 * "switch to what you want read" warning as the in-app flow so the
 * capture doesn't just grab VINCE's own background.
 */
class OverlayScreenActivity : ComponentActivity() {

    private val screenPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            proceedWithCapture(result.resultCode, data)
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenPermissionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    private fun proceedWithCapture(resultCode: Int, data: Intent) {
        val persona = PersonaState.getActive(applicationContext)

        ScreenCaptureBridge.awaitCapture { bitmap ->
            CoroutineScope(Dispatchers.Main).launch {
                val reply = if (bitmap == null) {
                    "Couldn't capture the screen. Try again in a moment."
                } else {
                    try {
                        val baos = ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
                        val apiKey = ApiKeyStore.getKey(applicationContext, Provider.GEMINI)
                        GeminiVision.describeImage(apiKey, baos.toByteArray(), DEFAULT_OVERLAY_VISION_PROMPT)
                            .fold(
                                onSuccess = { it },
                                onFailure = { e -> "Couldn't analyze the screen. (${e.message})" }
                            )
                    } catch (e: Exception) {
                        "Couldn't process the screen capture. (${e.message})"
                    }
                }
                VoiceOutput.speak(reply, persona)
                finish()
            }
        }

        Toast.makeText(
            applicationContext,
            "Switch to what you want VINCE to read - capturing in 3 seconds",
            Toast.LENGTH_SHORT
        ).show()

        CoroutineScope(Dispatchers.Main).launch {
            delay(3000)
            moveTaskToBack(true)
            delay(400)

            val serviceIntent = Intent(applicationContext, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(applicationContext, serviceIntent)
        }
    }
}

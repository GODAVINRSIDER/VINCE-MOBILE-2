package com.godavin.vince

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File

// Same default chart-analysis prompt used by in-app Camera/Screen vision
// (ChatScreen.kt) - kept here too since ChatScreen's copy is file-private
// and this needs to behave identically when triggered from the floating
// widget instead of from inside the app.
internal const val DEFAULT_OVERLAY_VISION_PROMPT = "You're looking at a trading chart for an " +
    "experienced price-action/smart-money-concepts trader. Start with ONE quick summary " +
    "line in this exact style: 'This chart on this [timeframe if visible] is in a " +
    "[downtrend/uptrend/range]; key level spotted: [FVG/order block/support/resistance/" +
    "breakout-retest/etc]; roughly [XX-YY]% probability for a [buy/sell] position.' Then, " +
    "on a new line, give a focused interactive breakdown: the key support/resistance " +
    "levels or liquidity zones visible, notable structure (order blocks, fair value gaps, " +
    "trendlines, break of structure), and your honest thoughts on what the chart is " +
    "suggesting. Be direct and specific like a second pair of eyes on the chart, not a " +
    "generic disclaimer-heavy description."

/**
 * Stage 15 - camera vision from the floating overlay widget. The system
 * Camera app needs a real Activity to hand a result back to, so this is
 * a fully transparent trampoline (Theme.NoDisplay, see manifest): it
 * launches the camera, waits for the photo, sends it straight to Gemini
 * vision using the same default chart-analysis prompt as in-app Camera,
 * speaks the reply out loud, then finishes and hands focus straight back
 * to whatever app was open before - no chat bubble, no thread, this is
 * a pure voice-in/voice-out shortcut from wherever the user is.
 */
class OverlayCameraActivity : ComponentActivity() {

    private var pendingUri: Uri? = null

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingUri
        if (success && uri != null) {
            analyzeAndSpeak(uri)
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dir = File(cacheDir, "camera_captures").apply { mkdirs() }
        val file = File(dir, "overlay_capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "com.godavin.vince.fileprovider", file)
        pendingUri = uri
        cameraLauncher.launch(uri)
    }

    private fun analyzeAndSpeak(uri: Uri) {
        val persona = PersonaState.getActive(applicationContext)
        CoroutineScope(Dispatchers.Main).launch {
            val reply = try {
                val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                if (bitmap == null) {
                    "Couldn't read the photo just taken."
                } else {
                    val baos = ByteArrayOutputStream()
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
                    val apiKey = ApiKeyStore.getKey(applicationContext, Provider.GEMINI)
                    GeminiVision.describeImage(apiKey, baos.toByteArray(), DEFAULT_OVERLAY_VISION_PROMPT)
                        .fold(
                            onSuccess = { it },
                            onFailure = { e -> "Couldn't analyze the photo. (${e.message})" }
                        )
                }
            } catch (e: Exception) {
                "Couldn't process the photo. (${e.message})"
            }
            VoiceOutput.speak(reply, persona)
            finish()
        }
    }
}

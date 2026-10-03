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

// Fix - same neutral, flexible vision prompt as ChatScreen's
// DEFAULT_VISION_PROMPT (kept here too since that one's file-private and
// this needs to behave identically triggered from the floating widget).
// No longer forces a "this is a trading chart" assumption onto every
// image - describes what's actually there first, only leans into a
// price-action read if it genuinely is a chart.
internal const val DEFAULT_OVERLAY_VISION_PROMPT = "Look at this image and describe what it " +
    "actually is first, in one line, before analyzing anything - don't assume it's a " +
    "trading chart unless it genuinely is one. If it IS a trading chart, then give a quick " +
    "summary line in plain sentence form (no Markdown, no headers, no tables): the " +
    "timeframe if visible, trend, key level, rough buy/sell lean, then ask if a fuller " +
    "breakdown is wanted. If it's anything else, just describe/answer naturally based on " +
    "what it actually shows - stay flexible to whatever the image actually is, don't force " +
    "a chart-analysis framing onto something that isn't one."

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
                    val geminiKey = ApiKeyStore.getKey(applicationContext, Provider.GEMINI)
                    val groqKey = ApiKeyStore.getKey(applicationContext, Provider.GROQ)
                    val openRouterKey = ApiKeyStore.getKey(applicationContext, Provider.OPENROUTER)
                    VisionRouter.describeImage(geminiKey, groqKey, openRouterKey, baos.toByteArray(), DEFAULT_OVERLAY_VISION_PROMPT)
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

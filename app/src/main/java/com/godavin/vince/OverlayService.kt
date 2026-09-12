package com.godavin.vince

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Stage 15 - floating overlay widget shown on top of whatever app is
 * currently open, as long as VINCE is running in the background.
 * Requires the one-time "display over other apps" system permission
 * (requested from HomeScreen before this service is started).
 *
 * LOOK: at rest, a medium, circular dark bubble showing the VINCE
 * triangle logo - not a plain colored circle with a letter. While
 * actively listening for a voice command, a colored halo ring appears
 * around it in the active persona's color, then disappears once
 * listening ends.
 *
 * TAP: starts a voice command straight from wherever the user currently
 * is - goes through the exact same local-command-first-then-AI pipeline
 * as the in-app chat (RealTimeTools -> PersonalTools -> BrainRouter),
 * then speaks the reply. No need to open the full app first.
 *
 * HOLD (500ms+): cycles the active persona VINCE -> CLARA -> DAVINA,
 * shared with PersonaState so the in-app chat picks up the same choice
 * next time it's opened. The logo itself doesn't change persona-to-
 * persona (it's the app's own mark) - only the halo color reflects the
 * active persona.
 *
 * DRAG: moves the whole widget anywhere on screen, stays there.
 *
 * The low-priority "VINCE is running" notification is a platform
 * requirement for any foreground service since Android 8 - it can't be
 * hidden, that's an Android rule, not a VINCE choice.
 *
 * NOT YET WIRED IN THIS PATCH: camera vision and screen vision from this
 * floating entry point (both need a full Activity to request their
 * respective system permissions/pickers) - voice + persona-switch is the
 * first working slice; vision-from-overlay is a fast follow-up.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var haloView: View? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main)

    companion object {
        private const val CHANNEL_ID = "vince_overlay"
        private const val NOTIFICATION_ID = 9001

        // Medium, not-too-big sizing (in dp, converted to px at runtime).
        private const val BUBBLE_DP = 56
        private const val HALO_DP = 80
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
        showOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayView?.let {
            try { windowManager.removeView(it) } catch (e: Exception) { /* already gone */ }
        }
        speechRecognizer?.destroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "VINCE overlay", NotificationManager.IMPORTANCE_MIN
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("VINCE is running")
            .setContentText("Tap the floating bubble to talk - hold it to switch persona.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun personaColorInt(persona: Persona): Int = when (persona) {
        Persona.VINCE -> Color.rgb(0, 190, 190)
        Persona.CLARA -> Color.rgb(230, 60, 140)
        Persona.DAVINA -> Color.rgb(150, 80, 220)
    }

    private fun showOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val haloSizePx = dp(HALO_DP)
        val bubbleSizePx = dp(BUBBLE_DP)

        // Halo ring - transparent center, colored stroke, hidden until a
        // voice command is actively being listened for.
        val halo = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(3), personaColorInt(PersonaState.getActive(applicationContext)))
            }
            visibility = View.INVISIBLE
        }

        // The bubble itself - dark circular background with the VINCE
        // triangle logo centered inside. This is the app's own mark, not
        // persona-tinted - only the halo reflects the active persona.
        val logo = ImageView(this).apply {
            setImageDrawable(ContextCompat.getDrawable(this@OverlayService, R.drawable.ic_vince_triangle))
        }
        val bubble = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(235, 18, 18, 20))
            }
            addView(
                logo,
                FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER)
            )
        }

        // Root container - sized to the halo (the largest element), with
        // the bubble centered inside it. Dragging/tapping is handled on
        // the root so the halo and bubble always move/react together.
        val root = FrameLayout(this).apply {
            addView(halo, FrameLayout.LayoutParams(haloSizePx, haloSizePx, Gravity.CENTER))
            addView(bubble, FrameLayout.LayoutParams(bubbleSizePx, bubbleSizePx, Gravity.CENTER))
        }
        haloView = halo

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            haloSizePx, haloSizePx,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 400
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var isDrag = false
        var downTimeMillis = 0L

        root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    isDrag = false
                    downTimeMillis = System.currentTimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) isDrag = true
                    params.x = startX + dx
                    params.y = startY + dy
                    windowManager.updateViewLayout(root, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val heldMillis = System.currentTimeMillis() - downTimeMillis
                    if (!isDrag) {
                        if (heldMillis >= 500) {
                            cyclePersona()
                        } else {
                            startVoiceCommand()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(root, params)
        overlayView = root
    }

    private fun setHaloColor(persona: Persona) {
        (haloView?.background as? GradientDrawable)?.setStroke(dp(3), personaColorInt(persona))
    }

    private fun cyclePersona() {
        val current = PersonaState.getActive(applicationContext)
        val next = when (current) {
            Persona.VINCE -> Persona.CLARA
            Persona.CLARA -> Persona.DAVINA
            Persona.DAVINA -> Persona.VINCE
        }
        PersonaState.setActive(applicationContext, next)
        setHaloColor(next)
        VoiceOutput.speak("${next.displayName} here.", next)
    }

    private fun startVoiceCommand() {
        val activePersona = PersonaState.getActive(applicationContext)

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            VoiceOutput.speak(
                "Speech recognition isn't available on this device right now.",
                activePersona
            )
            return
        }

        setHaloColor(activePersona)
        haloView?.visibility = View.VISIBLE

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    haloView?.visibility = View.INVISIBLE
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!text.isNullOrBlank()) handleSpokenCommand(text)
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    haloView?.visibility = View.INVISIBLE
                }
                override fun onError(error: Int) {
                    haloView?.visibility = View.INVISIBLE
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun handleSpokenCommand(text: String) {
        val persona = PersonaState.getActive(applicationContext)
        serviceScope.launch {
            val localReply = RealTimeTools.handleLocalCommand(applicationContext, text)
                ?: PersonalTools.handleLocalCommand(applicationContext, text)
            val reply = localReply ?: BrainRouter.sendMessage(applicationContext, text, persona)
            VoiceOutput.speak(reply, persona)
        }
    }
}

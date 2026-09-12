package com.godavin.vince

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
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
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Stage 15 (first slice) - a floating, draggable mic button shown on top
 * of whatever app is currently open, as long as VINCE is running in the
 * background. Requires the one-time "display over other apps" system
 * permission (requested from HomeScreen before this service is started).
 *
 * TAP: starts a voice command straight from wherever the user currently
 * is - goes through the exact same local-command-first-then-AI pipeline
 * as the in-app chat (RealTimeTools -> PersonalTools -> BrainRouter),
 * then speaks the reply. No need to open the full app first.
 *
 * HOLD (500ms+): cycles the active persona VINCE -> CLARA -> DAVINA,
 * shared with PersonaState so the in-app chat picks up the same choice
 * next time it's opened, and the bubble's color/label update to match.
 *
 * The low-priority "VINCE is running" notification is a platform
 * requirement for any foreground service since Android 8 - it can't be
 * hidden, that's an Android rule, not a VINCE choice.
 *
 * NOT YET WIRED IN THIS PATCH: camera vision and screen vision from this
 * floating entry point (both need a full Activity to request their
 * respective system permissions/pickers) - voice + persona-switch is the
 * first working slice; vision-from-overlay is a fast follow-up once this
 * is confirmed working.
 */
class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main)

    companion object {
        private const val CHANNEL_ID = "vince_overlay"
        private const val NOTIFICATION_ID = 9001
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

        val activePersona = PersonaState.getActive(applicationContext)

        val label = TextView(this).apply {
            text = activePersona.displayName.take(1)
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
        }
        val bubble = FrameLayout(this).apply {
            setBackgroundColor(personaColorInt(activePersona))
            addView(
                label,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            150, 150,
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

        bubble.setOnTouchListener { _, event ->
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
                    windowManager.updateViewLayout(bubble, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val heldMillis = System.currentTimeMillis() - downTimeMillis
                    if (!isDrag) {
                        if (heldMillis >= 500) {
                            cyclePersona(label, bubble)
                        } else {
                            startVoiceCommand()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(bubble, params)
        overlayView = bubble
    }

    private fun cyclePersona(label: TextView, bubble: FrameLayout) {
        val current = PersonaState.getActive(applicationContext)
        val next = when (current) {
            Persona.VINCE -> Persona.CLARA
            Persona.CLARA -> Persona.DAVINA
            Persona.DAVINA -> Persona.VINCE
        }
        PersonaState.setActive(applicationContext, next)
        label.text = next.displayName.take(1)
        bubble.setBackgroundColor(personaColorInt(next))
        VoiceOutput.speak("${next.displayName} here.", next)
    }

    private fun startVoiceCommand() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            VoiceOutput.speak(
                "Speech recognition isn't available on this device right now.",
                PersonaState.getActive(applicationContext)
            )
            return
        }
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (!text.isNullOrBlank()) handleSpokenCommand(text)
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onError(error: Int) {}
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

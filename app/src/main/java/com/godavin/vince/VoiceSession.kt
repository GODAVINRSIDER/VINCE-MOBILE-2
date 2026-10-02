package com.godavin.vince

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * v2.3 - Jarvis-style continuous voice conversation, shared by the in-app chat
 * and the floating widget.
 *
 * One tap starts a loop: listen -> think -> speak -> listen again. It keeps
 * going, with or without earphones, until the user says an end phrase
 * ("end convo", "I'm done", "that's all", "stop listening", "goodbye"...), taps
 * the mic/widget again, or 3 minutes pass with nothing said (standby).
 *
 * Interrupting: while VINCE is speaking the mic stays open (unless switched
 * off with the "Mic during reply" chip). Anything the recognizer hears that is
 * NOT just VINCE's own voice echoing back (checked against the reply text)
 * cuts the speech off, and what was said becomes the next command. A tap on
 * "Interrupt" does the same by hand. Speaker echo makes voice interruption
 * best-effort on some phones; the toggle exists for that reason.
 */
object VoiceSession {
    enum class Phase { OFF, LISTENING, THINKING, SPEAKING }

    var phase by mutableStateOf(Phase.OFF)
        private set
    val active: Boolean get() = phase != Phase.OFF

    /** "chat" or "widget" - who started the current session. */
    var owner: String = ""
        private set

    private const val STANDBY_MS = 3 * 60_000L
    private const val PREFS = "vince_voice_session"
    private const val KEY_BARGE = "barge_in"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var recognizer: SpeechRecognizer? = null
    private var onPhase: ((Phase) -> Unit)? = null

    fun bargeInEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BARGE, true)

    fun setBargeIn(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_BARGE, enabled).apply()
    }

    private fun changePhase(p: Phase) {
        phase = p
        onPhase?.invoke(p)
    }

    /**
     * [handler] receives each thing the user said and returns the text VINCE
     * should speak (null/blank = say nothing). The handler is responsible for
     * showing messages on screen / keeping history.
     */
    fun start(
        context: Context,
        owner: String,
        personaProvider: () -> Persona,
        onPhase: ((Phase) -> Unit)? = null,
        handler: suspend (String) -> String?
    ) {
        if (active) return
        val app = context.applicationContext
        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            VoiceOutput.speak("Speech recognition isn't available on this device right now.", personaProvider())
            return
        }
        this.owner = owner
        this.onPhase = onPhase
        changePhase(Phase.LISTENING)
        job = scope.launch {
            try {
                runLoop(app, personaProvider, handler)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // never crash the app over a voice loop
            } finally {
                cleanup()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        cleanup()
    }

    /** Tap-to-interrupt: cuts VINCE's speech and goes straight back to listening. */
    fun interruptSpeech() {
        VoiceOutput.stop()
    }

    private fun cleanup() {
        try { recognizer?.cancel() } catch (e: Exception) { }
        try { recognizer?.destroy() } catch (e: Exception) { }
        recognizer = null
        VoiceOutput.stop()
        if (phase != Phase.OFF) changePhase(Phase.OFF)
        onPhase = null
    }

    // ------------------------------------------------------------------

    private suspend fun runLoop(
        ctx: Context,
        personaProvider: () -> Persona,
        handler: suspend (String) -> String?
    ) {
        var lastActivity = System.currentTimeMillis()
        var carry: String? = null
        var hardErrors = 0

        while (true) {
            changePhase(Phase.LISTENING)
            val heard: String?
            if (carry != null) {
                heard = carry
                carry = null
            } else {
                val res = listenOnce(ctx, null)
                heard = res.text?.trim()?.takeIf { it.isNotBlank() }
                if (heard == null) {
                    when (res.error) {
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                            VoiceOutput.speakAwait("I need microphone permission for that.", personaProvider())
                            return
                        }
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> delay(600)
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, 0 -> {
                            hardErrors = 0
                            delay(150)
                        }
                        else -> {
                            hardErrors++
                            delay(1000)
                        }
                    }
                    if (hardErrors >= 6) {
                        VoiceOutput.speakAwait("Voice recognition keeps failing, so I'm stopping the conversation.", personaProvider())
                        return
                    }
                    if (System.currentTimeMillis() - lastActivity > STANDBY_MS) {
                        VoiceOutput.speakAwait("Going on standby. Tap me when you need me.", personaProvider())
                        return
                    }
                    continue
                }
            }

            hardErrors = 0
            lastActivity = System.currentTimeMillis()

            if (isEndPhrase(heard)) {
                VoiceOutput.speakAwait("Okay, ending the conversation.", personaProvider())
                return
            }

            changePhase(Phase.THINKING)
            val reply = try {
                handler(heard)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }

            if (!reply.isNullOrBlank()) {
                changePhase(Phase.SPEAKING)
                carry = speakWithBargeIn(ctx, reply, personaProvider())
            }
            lastActivity = System.currentTimeMillis()
        }
    }

    // ------------------------------------------------------------------
    // Listening

    private data class ListenResult(val text: String?, val error: Int)

    private suspend fun listenOnce(ctx: Context, onPartial: ((String) -> Unit)?): ListenResult =
        suspendCancellableCoroutine { cont ->
            val r = SpeechRecognizer.createSpeechRecognizer(ctx)
            recognizer = r
            var done = false
            fun finish(res: ListenResult) {
                if (done) return
                done = true
                try { r.destroy() } catch (e: Exception) { }
                if (recognizer === r) recognizer = null
                if (cont.isActive) cont.resume(res)
            }
            r.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    finish(ListenResult(t, 0))
                }
                override fun onError(error: Int) { finish(ListenResult(null, error)) }
                override fun onPartialResults(partialResults: Bundle?) {
                    val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!t.isNullOrBlank() && !done) onPartial?.invoke(t)
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            cont.invokeOnCancellation {
                done = true
                try { r.cancel() } catch (e: Exception) { }
                try { r.destroy() } catch (e: Exception) { }
                if (recognizer === r) recognizer = null
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2200L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            }
            try {
                r.startListening(intent)
            } catch (e: Exception) {
                finish(ListenResult(null, SpeechRecognizer.ERROR_CLIENT))
            }
        }

    // ------------------------------------------------------------------
    // Speaking, with the mic still open so the user can cut in

    private suspend fun speakWithBargeIn(ctx: Context, speech: String, persona: Persona): String? = coroutineScope {
        var interrupted = false
        var captured: String? = null
        val speaker = launch { VoiceOutput.speakAwait(speech, persona) }

        if (bargeInEnabled(ctx)) {
            val barge = launch {
                delay(700) // let the voice start before listening
                while (isActive && speaker.isActive) {
                    val res = listenOnce(ctx) { partial ->
                        if (!interrupted && isRealInterruption(partial, speech)) {
                            interrupted = true
                            VoiceOutput.stop()
                        }
                    }
                    if (interrupted) {
                        captured = res.text
                        break
                    }
                    delay(250)
                }
            }
            speaker.join()
            if (interrupted) barge.join() else barge.cancel()
        } else {
            speaker.join()
        }
        if (interrupted) cleanInterruptWords(captured) else null
    }

    private fun words(s: String): List<String> =
        s.lowercase().replace(Regex("[^a-z0-9' ]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }

    /** True when the heard text is NOT just VINCE's own voice coming back in. */
    private fun isRealInterruption(partial: String, spoken: String): Boolean {
        val heard = words(partial)
        if (heard.isEmpty()) return false
        val spokenSet = words(spoken).toSet()
        val matched = heard.count { it in spokenSet }
        val echoShare = matched.toFloat() / heard.size
        return echoShare < 0.6f
    }

    private val LEADING_INTERRUPT = Regex(
        "^(?:(?:okay|ok|hey|vince|clara|davina|sorry)[, ]+)*(?:stop|wait|hold on|hang on|shut up|pause|one second|one sec|enough)\\b[,. ]*",
        RegexOption.IGNORE_CASE
    )

    private fun cleanInterruptWords(text: String?): String? {
        var t = text?.trim().orEmpty()
        if (t.isBlank()) return null
        var prev: String
        do {
            prev = t
            t = LEADING_INTERRUPT.replace(t, "").trim()
        } while (t != prev && t.isNotBlank())
        return t.takeIf { it.isNotBlank() }
    }

    // ------------------------------------------------------------------

    private val END_PHRASES = listOf(
        "end convo", "end the convo", "end conversation", "end the conversation", "end chat",
        "end call", "end voice", "i'm done", "im done", "i am done", "we're done", "were done",
        "we are done", "that's all", "thats all", "that is all", "stop listening", "stop the conversation",
        "goodbye", "good bye", "bye vince", "bye clara", "bye davina", "go to sleep", "go on standby",
        "exit voice", "close voice", "that will be all"
    )

    private fun isEndPhrase(text: String): Boolean {
        val w = words(text)
        if (w.isEmpty() || w.size > 8) return false
        val joined = w.joinToString(" ")
        return END_PHRASES.any { joined.contains(it) } || joined == "bye" || joined == "end"
    }
}

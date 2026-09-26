package com.arenaai.duagents.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Real-time voice input over the platform SpeechRecognizer.
 *
 * Engineering decision: continuous-conversation mode — after every final result (or a
 * silent no-match) the recognizer is transparently recreated and re-armed, so the user can
 * talk back and forth naturally, exactly like a human conversation. While the agents are
 * thinking/speaking the orchestrator pauses us to avoid the microphone hearing the TTS
 * (barge-in is intentionally disabled in v1 for reliability).
 */
class SpeechInputManager(
    private val context: Context,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onStateMessage: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var userWantsListening = false
    private var paused = false

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        userWantsListening = true
        paused = false
        main.post { arm() }
    }

    fun stop() {
        userWantsListening = false
        paused = false
        main.post { disarm() }
    }

    /** Called by the orchestrator while the agents think/speak. */
    fun pause() {
        if (!userWantsListening) return
        paused = true
        main.post { disarm() }
    }

    /** Called when the agents go idle again. */
    fun resume() {
        if (!userWantsListening) return
        paused = false
        main.post { arm() }
    }

    fun destroy() {
        userWantsListening = false
        paused = false
        main.post {
            try {
                recognizer?.destroy()
            } catch (_: Exception) {
            }
            recognizer = null
        }
    }

    private fun disarm() {
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }

    private fun arm() {
        if (!userWantsListening || paused) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onStateMessage("speech_unavailable")
            userWantsListening = false
            return
        }
        disarm()
        try {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(listener())
                startListening(buildIntent())
            }
        } catch (e: Exception) {
            // Some OEM builds throw RejectedExecutionException under load — recoverable.
            onStateMessage("speech_unavailable")
            userWantsListening = false
        }
    }

    private fun buildIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

    private fun restartAfter(delayMs: Long) {
        if (!userWantsListening || paused) return
        main.postDelayed({ arm() }, delayMs)
    }

    private inner class listener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            onPartial("")
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_AUDIO -> restartAfter(250)
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_NETWORK -> {
                    onStateMessage("network")
                    restartAfter(1500)
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> restartAfter(600)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    onStateMessage("permission")
                    userWantsListening = false
                }
                SpeechRecognizer.ERROR_CLIENT -> restartAfter(400)
                else -> restartAfter(600)
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank()) {
                onPartial("")
                // Pause during processing+speech; orchestrator resumes when idle again.
                paused = true
                disarm()
                onFinal(text.trim())
            } else {
                restartAfter(250)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank()) onPartial(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}

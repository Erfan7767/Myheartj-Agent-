package com.arenaai.duagents.voice

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.os.Handler
import android.os.Looper
import com.arenaai.duagents.core.AgentPersona
import com.arenaai.duagents.core.AgentRegistry
import java.util.ArrayDeque
import java.util.Locale

/**
 * Real-time voice output over the platform TextToSpeech engine.
 *
 * Engineering decision (dual-agent voice identity):
 *  1. Voices are chained sentence-by-sentence through UtteranceProgressListener so each line
 *     is synthesized with the SPEAKING agent's own voice parameters — this is what makes a
 *     genuine two-voice collaboration audible.
 *  2. Gender differentiation is layered: (a) if the engine exposes a voice whose name marks
 *     the preferred gender ("female"/"male"), it is selected; (b) independently of that,
 *     each persona carries distinct base pitch/rate (Elena brighter, Irfan deeper), so the
 *     identity is always audible even on engines without gendered voices.
 *  3. Emotional intelligence modulates pitch/rate per line via EmotionalReading scales.
 *  4. Playback starts on the FIRST spoken line — near-zero perceived latency.
 */
class SpeechOutputManager(context: Context) {

    data class SpokenLine(
        val agentId: String,
        val text: String,
        val pitch: Float,
        val rate: Float
    )

    var onSpeakingStarted: ((String) -> Unit)? = null   // agent id
    var onSpeakingEnded: (() -> Unit)? = null
    var onEngineReady: ((Boolean) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    private var engineUsable = false
    private val lineQueue = ArrayDeque<SpokenLine>()
    private val voiceCache = HashMap<String, Locale?>()

    init {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    configureEngine()
                } else {
                    engineUsable = false
                    onEngineReady?.invoke(false)
                    flushSafely() // never leave the UI stuck in "speaking"
                }
            }
        } catch (_: Exception) {
            engineUsable = false
            onEngineReady?.invoke(false)
        }
    }

    val isReady: Boolean get() = ready

    private fun configureEngine() {
        val engine = tts ?: return
        val locale = Locale.getDefault()
        var result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            result = engine.setLanguage(Locale.US)
        }
        engineUsable = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val agentId = utteranceId?.substringBefore('#') ?: ""
                main.post { onSpeakingStarted?.invoke(agentId) }
            }

            @Deprecated("Deprecated in Java")
            override fun onDone(utteranceId: String?) {
                main.post { speakNext() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post { speakNext() }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                main.post { speakNext() }
            }
        })
        ready = engineUsable
        onEngineReady?.invoke(engineUsable)
        if (engineUsable) {
            main.post { speakNext() } // flush anything queued before init completed
        } else {
            flushSafely()
        }
    }

    /** Enqueues lines; playback begins immediately if the engine is up. */
    @Synchronized
    fun speak(lines: List<SpokenLine>) {
        if (lines.isEmpty()) return
        synchronized(lineQueue) {
            lineQueue.clear()
            lineQueue.addAll(lines)
        }
        if (!ready) return // init will flush, or failure path ends the phase
        main.post { speakNext() }
    }

    @Synchronized
    fun stop() {
        synchronized(lineQueue) { lineQueue.clear() }
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        main.post { onSpeakingEnded?.invoke() }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ready = false
    }

    private fun speakNext() {
        val line = synchronized(lineQueue) { lineQueue.pollFirst() }
        if (line == null) {
            onSpeakingEnded?.invoke()
            return
        }
        val engine = tts
        if (!ready || engine == null) {
            onSpeakingEnded?.invoke()
            return
        }
        engine.setPitch(line.pitch)
        engine.setSpeechRate(line.rate)
        voiceFor(line.agentId)?.let { engine.voice = it }
        engine.speak(
            line.text.take(900),
            TextToSpeech.QUEUE_FLUSH,
            null,
            "${line.agentId}#${System.nanoTime()}"
        )
    }

    private fun flushSafely() {
        synchronized(lineQueue) { lineQueue.clear() }
        main.post { onSpeakingEnded?.invoke() }
    }

    /** Best real voice for the persona's gender within the active language, or null → engine
     *  default + pitch differentiation. Heuristic on voice names, documented decision. */
    private fun voiceFor(agentId: String): android.speech.tts.Voice? {
        val persona: AgentPersona = AgentRegistry.byId(agentId)
        val engine = tts ?: return null
        var locale = Locale.getDefault()
        val langResult = engine.setLanguage(locale)
        if (langResult == TextToSpeech.LANG_MISSING_DATA ||
            langResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            locale = Locale.US
        }
        val genderToken = persona.preferredGender.lowercase()
        return engine.voices
            .filter { it.locale?.language == locale.language }
            .sortedByDescending { it.quality }
            .firstOrNull { it.name.lowercase().contains(genderToken) }
    }
}

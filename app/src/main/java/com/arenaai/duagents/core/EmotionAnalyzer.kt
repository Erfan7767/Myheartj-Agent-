package com.arenaai.duagents.core

import java.util.Locale

/**
 * Engineering decision: emotional state is detected locally (heuristic multilingual lexicon,
 * Arabic + English) so empathy adapts even before the network responds. The reading adjusts
 * (1) the system-prompt guidance sent to the agents, (2) the TTS pitch/rate so the *voice*
 * itself carries the empathy, and (3) the UI mood indicator.
 */
enum class Emotion(val emoji: String) {
    JOY("\uD83D\uDE04"),
    SADNESS("\uD83E\uDD7A"),
    ANGER("\uD83D\uDE20"),
    FEAR("\uD83D\uDE1F"),
    STRESS("\uD83D\uDE13"),
    NEUTRAL("\uD83D\uDE42")
}

data class EmotionalReading(
    val emotion: Emotion,
    val intensity: Float,          // 0..1
    val promptGuidance: String,
    val pitchScale: Float,
    val rateScale: Float
)

object EmotionAnalyzer {

    private val lexicon: Map<Emotion, List<String>> = mapOf(
        Emotion.JOY to listOf(
            "happy", "great", "excited", "awesome", "amazing", "love it", "wonderful",
            "thank you", "thanks", "سعيد", "سعيدة", "فرحان", "مبسوط", "رائع", "ممتاز",
            "الحمد لله", "شكرا", "جميل", "متحمس"
        ),
        Emotion.SADNESS to listOf(
            "sad", "depressed", "lonely", "crying", "heartbroken", "hurt", "down lately",
            "حزين", "حزينة", "زعلان", "زعلانة", "تعبان", "مكسور", "وحيد", "بكيت", "مضايق", "ضايق"
        ),
        Emotion.ANGER to listOf(
            "angry", "furious", "hate", "annoyed", "mad at", "fed up", "غاضب", "متعصب",
            "كرهت", "اغضب", "انفعلت", "مستاء", "زعقت"
        ),
        Emotion.FEAR to listOf(
            "scared", "afraid", "anxious", "worried", "nervous", "panic", "خايف", "خايفة",
            "خوف", "قلق", "قلقان", "متوتر", "مرعوب", "تخوفت"
        ),
        Emotion.STRESS to listOf(
            "stressed", "overwhelmed", "exhausted", "too much", "burnout", "pressure",
            "ضغط", "مرهق", "مرهقة", "مشغول جدا", "انهار", "ضغوط", "مشغولة جدا", "متعب جدا"
        )
    )

    private val guidance: Map<Emotion, String> = mapOf(
        Emotion.JOY to
            "The user seems JOYFUL. Match their positive energy, celebrate with them warmly, keep the tone bright and momentum high.",
        Emotion.SADNESS to
            "The user seems SAD or down. Open with a genuine, brief acknowledgment of their feeling — no clichés — then support gently and concretely. Softer, warmer tone; short comforting sentences.",
        Emotion.ANGER to
            "The user seems ANGRY or frustrated. Stay calm, validate the frustration honestly, never argue, and move steadily toward a concrete fix. Clear, steady sentences.",
        Emotion.FEAR to
            "The user seems ANXIOUS or afraid. Be a steady presence: acknowledge the worry, reduce uncertainty with clear structure and small concrete next steps.",
        Emotion.STRESS to
            "The user seems STRESSED or overwhelmed. Slow down, be calm and organized, break the problem into small manageable steps, and relieve pressure rather than adding to it.",
        Emotion.NEUTRAL to
            "The user's mood appears neutral. Keep a warm, natural, human tone."
    )

    fun analyze(raw: String): EmotionalReading {
        val t = raw.lowercase(Locale.ROOT)
        var bestEmotion = Emotion.NEUTRAL
        var bestScore = 0
        for ((emotion, words) in lexicon) {
            val score = words.count { t.contains(it) }
            if (score > bestScore) {
                bestScore = score
                bestEmotion = emotion
            }
        }
        val intensity = minOf(1f, bestScore / 3f)
        val (pitch, rate) = when (bestEmotion) {
            Emotion.JOY -> 1.06f to 1.04f
            Emotion.SADNESS -> 0.94f to 0.92f
            Emotion.ANGER -> 0.98f to 0.95f
            Emotion.FEAR -> 1.02f to 0.94f
            Emotion.STRESS -> 0.97f to 0.93f
            Emotion.NEUTRAL -> 1.0f to 1.0f
        }
        return EmotionalReading(bestEmotion, intensity, guidance.getValue(bestEmotion), pitch, rate)
    }
}

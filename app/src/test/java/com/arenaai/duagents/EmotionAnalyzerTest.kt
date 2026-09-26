package com.arenaai.duagents

import com.arenaai.duagents.core.Emotion
import com.arenaai.duagents.core.EmotionAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmotionAnalyzerTest {

    @Test
    fun `arabic sadness is detected`() {
        val reading = EmotionAnalyzer.analyze("أنا حزين جداً اليوم ولا أعرف ماذا أفعل")
        assertEquals(Emotion.SADNESS, reading.emotion)
        assertTrue(reading.pitchScale < 1.0f)
        assertTrue(reading.promptGuidance.isNotBlank())
    }

    @Test
    fun `english joy is detected`() {
        val reading = EmotionAnalyzer.analyze("I am so happy and excited about the news!")
        assertEquals(Emotion.JOY, reading.emotion)
        assertTrue(reading.pitchScale >= 1.0f)
    }

    @Test
    fun `stress keywords calm the voice`() {
        val reading = EmotionAnalyzer.analyze("I feel totally stressed and overwhelmed with work pressure")
        assertEquals(Emotion.STRESS, reading.emotion)
        assertTrue(reading.rateScale < 1.0f)
    }

    @Test
    fun `neutral text stays neutral`() {
        val reading = EmotionAnalyzer.analyze("What is the capital of Japan?")
        assertEquals(Emotion.NEUTRAL, reading.emotion)
        assertEquals(1.0f, reading.pitchScale, 0.0001f)
        assertEquals(1.0f, reading.rateScale, 0.0001f)
    }
}

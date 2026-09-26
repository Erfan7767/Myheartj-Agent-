package com.arenaai.duagents

import com.arenaai.duagents.data.TextProcessing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextProcessingTest {

    @Test
    fun `artifacts are extracted from fenced blocks`() {
        val text = "Here is the code:\n```kotlin\nfun main() { println(1) }\n```\nEnjoy!"
        val artifacts = TextProcessing.parseArtifacts(text)
        assertEquals(1, artifacts.size)
        assertEquals("kotlin", artifacts[0].language)
        assertTrue(artifacts[0].code.contains("println(1)"))
    }

    @Test
    fun `speech sanitizer removes code blocks and markdown`() {
        val text = "Sure **friend**.\n```python\nprint('hi')\n```\nThat's #1 the plan."
        val spoken = TextProcessing.sanitizeForSpeech(text)
        assertFalse(spoken.contains("print('hi')"))
        assertFalse(spoken.contains("**"))
        assertTrue(spoken.contains("code artifact"))
        assertTrue(spoken.contains("Sure friend"))
    }

    @Test
    fun `agent labels are stripped in collaboration`() {
        assertEquals(
            "my part here",
            TextProcessing.stripAgentLabel("IRFAN: my part here")
        )
        assertEquals(
            "جزئي هنا",
            TextProcessing.stripAgentLabel("ELENA: جزئي هنا")
        )
    }

    @Test
    fun `spoken lines merge short fragments`() {
        val lines = TextProcessing.splitToSpokenLines("Hi. Ok. Now this is a full sentence with several words. Done!")
        assertTrue(lines.first().startsWith("Hi"))
        assertTrue(lines.size < 4)
    }
}

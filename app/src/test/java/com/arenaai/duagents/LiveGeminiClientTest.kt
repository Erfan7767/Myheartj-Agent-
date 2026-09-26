package com.arenaai.duagents

import com.arenaai.duagents.core.ApiKeyProvider
import com.arenaai.duagents.core.textTurn
import com.arenaai.duagents.network.GeminiClient
import com.arenaai.duagents.network.GeminiOutcome
import com.arenaai.duagents.network.ToolDeclaration
import com.arenaai.duagents.network.ToolParamSchema
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * LIVE integration tests — these run the PRODUCTION network layer (GeminiClient: real
 * OkHttpClient, real request building, real JSON parsing) against Google's REAL servers,
 * using the key from the git-ignored local.properties (same source the APK build injects).
 *
 * Engineering decision: tests are skipped cleanly when no key is configured, so the suite
 * stays green in keyless environments — and when a key exists, it proves the full wire path.
 */
class LiveGeminiClientTest {

    private val keyProvider = object : ApiKeyProvider {
        private val key: String = findLocalProperties().getProperty("GEMINI_API_KEY")?.trim().orEmpty()
        override fun get(): String = key
        override fun modelOverride(): String? = null
    }

    private val client = GeminiClient(keyProvider)

    private fun findLocalProperties(): java.util.Properties {
        val props = java.util.Properties()
        var dir: File? = File(System.getProperty("user.dir"))
        repeat(4) {
            val candidate = dir?.resolve("local.properties")
            if (candidate != null && candidate.exists()) {
                candidate.inputStream().use { props.load(it) }
                return props
            }
            dir = dir?.parentFile
        }
        return props
    }

    @Test
    fun `LIVE - gemini 3-5 flash answers in Arabic through the production client`() = runBlocking {
        assumeTrue("Live test needs a Gemini API key in local.properties", keyProvider.get().isNotBlank())

        val outcome = client.generate(
            systemInstruction =
                "You are Irfan, a wise, warm AI agent. Always answer in the user's language. " +
                    "Keep answers to a single short sentence.",
            turns = listOf(textTurn("user", null, "مرحبًا عرفان، أجب بجملة واحدة: ما هي مسؤوليتك في هذا التطبيق؟")),
            toolDeclarations = emptyList()
        )
        assertTrue("Expected success, got: $outcome", outcome is GeminiOutcome.Ok)
        val ok = outcome as GeminiOutcome.Ok
        val text = ok.parts.filterIsInstance<com.arenaai.duagents.data.Part.Text>()
            .joinToString("") { it.text }
        println("LIVE modelUsed=${ok.modelUsed}")
        println("LIVE reply=$text")
        assertTrue(text.isNotBlank())
    }

    @Test
    fun `LIVE - function calling roundtrip returns a real calculator call`() = runBlocking {
        assumeTrue("Live test needs a Gemini API key in local.properties", keyProvider.get().isNotBlank())

        val calculator = ToolDeclaration(
            name = "calculator",
            description = "Evaluates a mathematical expression with absolute precision: + - * / % ^, " +
                "parentheses, sqrt, sin, cos, tan, log, ln, abs, round, floor, ceil, exp, pi, e.",
            params = listOf(ToolParamSchema("expression", "The math expression", true))
        )
        val outcome = client.generate(
            systemInstruction = "You are Irfan. Use the calculator tool for ANY non-trivial arithmetic.",
            turns = listOf(textTurn("user", null, "كم يساوي (1240*3)/7 + sqrt(2)؟")),
            toolDeclarations = listOf(calculator)
        )
        assertTrue("Expected success, got: $outcome", outcome is GeminiOutcome.Ok)
        val ok = outcome as GeminiOutcome.Ok
        val call = ok.parts.filterIsInstance<com.arenaai.duagents.data.Part.FunctionCall>()
            .firstOrNull()
        val text = ok.parts.filterIsInstance<com.arenaai.duagents.data.Part.Text>()
            .joinToString(" ") { it.text }
        println("LIVE functionCall=${call?.name} args=${call?.args}")
        // The model must either call the calculator tool for real, or answer with the precise value.
        assertTrue(
            "Expected a calculator function call or a precise numeric answer, got text=$text",
            (call != null && call.name == "calculator") || text.contains("532.8")
        )
    }
}

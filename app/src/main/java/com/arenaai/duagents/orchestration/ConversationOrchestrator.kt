package com.arenaai.duagents.orchestration

import com.arenaai.duagents.core.AgentPersona
import com.arenaai.duagents.core.AgentRegistry
import com.arenaai.duagents.core.ConversationMemory
import com.arenaai.duagents.core.EmotionalReading
import com.arenaai.duagents.core.KeyVault
import com.arenaai.duagents.core.textTurn
import com.arenaai.duagents.data.Artifact
import com.arenaai.duagents.data.ConversationPhase
import com.arenaai.duagents.data.OrchestratorEvent
import com.arenaai.duagents.data.Part
import com.arenaai.duagents.data.TextProcessing
import com.arenaai.duagents.data.Turn
import com.arenaai.duagents.network.GeminiClient
import com.arenaai.duagents.network.GeminiErrorKind
import com.arenaai.duagents.network.GeminiOutcome
import com.arenaai.duagents.tools.ToolRegistry
import com.arenaai.duagents.voice.SpeechOutputManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The conversational brain.
 *
 * Responsibilities:
 *  • Runs the real agentic loop: model → function calls → REAL tool execution → function
 *    responses → model … until a final answer (bounded rounds, engineering decision: 6).
 *  • Single-agent execution: the chosen agent runs alone with zero interference.
 *  • Collaborative execution: a genuine two-pass joint run — Irfan produces his real
 *    contribution first, Elena then receives it and adds hers; each part is displayed and
 *    SPOKEN by its own agent's voice.
 *  • Emotional context, execution ethos, tool manual (incl. ToolForge self-creation manual)
 *    are composed into the system instruction per request.
 *  • Persists the exchange to ConversationMemory and emits structured UI events.
 */
class ConversationOrchestrator(
    private val gemini: GeminiClient,
    private val registry: ToolRegistry,
    private val memory: ConversationMemory,
    private val speech: SpeechOutputManager
) {
    var onEvent: ((OrchestratorEvent) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val busy = AtomicBoolean(false)
    val isBusy: Boolean get() = busy.get()

    private companion object {
        const val MAX_TOOL_ROUNDS = 6
    }

    fun submit(userText: String, decision: com.arenaai.duagents.core.RouteDecision, emotion: EmotionalReading) {
        if (!busy.compareAndSet(false, true)) {
            onEvent?.invoke(OrchestratorEvent.Note("busy"))
            return
        }
        scope.launch {
            try {
                execute(userText, decision, emotion)
            } catch (t: Throwable) {
                onEvent?.invoke(
                    OrchestratorEvent.Error("Execution fault: ${t.message ?: t.javaClass.simpleName}")
                )
                emitPhase(ConversationPhase.IDLE)
            } finally {
                busy.set(false)
            }
        }
    }

    private suspend fun execute(
        userText: String,
        decision: com.arenaai.duagents.core.RouteDecision,
        emotion: EmotionalReading
    ) {
        emitPhase(ConversationPhase.THINKING)
        memory.append(textTurn("user", null, userText))
        val history = memory.load()

        val replies = if (decision.collaborative) {
            runCollaborative(history, emotion)
        } else {
            val agent = decision.participants.first()
            val reply = runAgentPass(agent, history, buildSystemPrompt(agent, emotion, null))
            listOfNotNull(reply)
        }

        if (replies.isEmpty()) {
            emitPhase(ConversationPhase.IDLE)
            return
        }

        // Persist the real exchange (one memory turn per contributing agent).
        val modelTurns = replies.map { textTurn("model", it.agent.id, it.text) }
        memory.appendAll(modelTurns)

        // Speak with per-agent voices, starting on the first sentence.
        val spokenLines = ArrayList<SpeechOutputManager.SpokenLine>()
        for (reply in replies) {
            val sanitized = TextProcessing.sanitizeForSpeech(reply.text)
            for (chunk in TextProcessing.splitToSpokenLines(sanitized)) {
                spokenLines.add(
                    SpeechOutputManager.SpokenLine(
                        reply.agent.id,
                        chunk,
                        reply.agent.basePitch * emotion.pitchScale,
                        reply.agent.baseRate * emotion.rateScale
                    )
                )
            }
        }
        emitPhase(ConversationPhase.SPEAKING, replies.first().agent.id)
        speech.speak(spokenLines)
        // Phase returns to IDLE via SpeechOutputManager.onSpeakingEnded (wired in the ViewModel).
    }

    // ── Single-agent agentic loop ───────────────────────────────────────────

    private suspend fun runAgentPass(
        agent: AgentPersona,
        baseTurns: List<Turn>,
        systemPrompt: String
    ): AgentReply? {
        val working = ArrayList<Turn>(baseTurns)
        repeat(MAX_TOOL_ROUNDS) {
            when (val outcome = gemini.generate(systemPrompt, working, registry.declarations())) {
                is GeminiOutcome.Failure -> {
                    onEvent?.invoke(OrchestratorEvent.Error(outcome.userMessage))
                    if (outcome.kind == GeminiErrorKind.MISSING_KEY) {
                        emitPhase(ConversationPhase.IDLE)
                    }
                    return null
                }
                is GeminiOutcome.Ok -> {
                    val calls = outcome.parts.filterIsInstance<Part.FunctionCall>()
                    if (calls.isEmpty()) {
                        val text = outcome.parts.filterIsInstance<Part.Text>()
                            .joinToString("\n") { it.text }
                        return AgentReply(agent, TextProcessing.stripAgentLabel(text))
                    }
                    // Real tool execution round.
                    working.add(Turn("model", agent.id, outcome.parts))
                    val responses = ArrayList<Part>()
                    for (call in calls) {
                        onEvent?.invoke(OrchestratorEvent.ToolUsed(call.name))
                        call.args.optJSONObject("_forged")?.let {
                            onEvent?.invoke(OrchestratorEvent.ToolForged(it.optString("name", "")))
                        }
                        val result = registry.dispatch(call.name, call.args)
                        if (result.optString("_forged").isNotBlank()) {
                            onEvent?.invoke(OrchestratorEvent.ToolForged(result.optString("_forged")))
                        }
                        responses.add(Part.FunctionResponse(call.name, result))
                    }
                    working.add(Turn("user", agent.id, responses))
                }
            }
        }
        // Round budget exhausted — return what we have rather than failing.
        val fallback = working.lastOrNull { it.role == "model" }
            ?.parts?.filterIsInstance<Part.Text>()?.joinToString("\n").orEmpty()
        return if (fallback.isNotBlank()) AgentReply(agent, TextProcessing.stripAgentLabel(fallback))
        else null.also {
            onEvent?.invoke(OrchestratorEvent.Error("Tool loop reached its safety budget without a final answer."))
        }
    }

    // ── Genuine collaborative execution ─────────────────────────────────────

    private suspend fun runCollaborative(
        history: List<Turn>,
        emotion: EmotionalReading
    ): List<AgentReply> {
        val irfan = AgentRegistry.IRFAN
        val elena = AgentRegistry.ELENA

        val irfanReply = runAgentPass(irfan, history, buildSystemPrompt(irfan, emotion, Role.LEAD))
        irfanReply?.let { emitReply(it) }

        val elenaInput = if (irfanReply != null) {
            history + Turn("model", irfan.id, listOf(Part.Text(irfanReply.text)))
        } else history
        val elenaSystem = buildSystemPrompt(elena, emotion, Role.SUPPORT)
            .replace("<<<IRFAN_PART>>>", irfanReply?.text ?: "(Irfan could not complete his pass — carry the task alone.)")
        val elenaReply = runAgentPass(elena, elenaInput, elenaSystem)
        elenaReply?.let { emitReply(it) }

        return listOfNotNull(irfanReply, elenaReply)
    }

    // ── System prompt composition ───────────────────────────────────────────

    private enum class Role { LEAD, SUPPORT }

    data class AgentReply(val agent: AgentPersona, val text: String)

    private fun buildSystemPrompt(
        agent: AgentPersona,
        emotion: EmotionalReading,
        role: Role?
    ): String = buildString {
        append(agent.systemPrompt)
        append("\n\n").append(AgentRegistry.EXECUTION_ETHOS)
        append("\n== EMOTIONAL CONTEXT ==\n").append(emotion.promptGuidance)
        append("\n\n== LANGUAGE ==\nMirror the user's language exactly.")
        append("\n\n").append(AgentRegistry.TOOL_MANUAL_HEADER)
        append("\n== CURRENTLY REGISTERED TOOLS ==\n")
        registry.all().forEach { tool ->
            append("• ").append(tool.name).append("(")
                .append(tool.params.joinToString(", ") { it.name }).append("): ")
                .append(tool.description).append('\n')
        }
        when (role) {
            Role.LEAD -> append("\n").append(AgentRegistry.COLLAB_LEAD)
            Role.SUPPORT -> append("\n").append(AgentRegistry.COLLAB_SUPPORT)
            null -> Unit
        }
    }

    // ── Events ──────────────────────────────────────────────────────────────

    private fun emitReply(reply: AgentReply) {
        onEvent?.invoke(
            OrchestratorEvent.AgentReply(
                reply.agent.id,
                reply.text,
                TextProcessing.parseArtifacts(reply.text)
            )
        )
    }

    private fun emitPhase(phase: ConversationPhase, agentId: String? = null) {
        onEvent?.invoke(OrchestratorEvent.Phase(phase, agentId))
    }
}

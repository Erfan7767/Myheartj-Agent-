package com.arenaai.duagents.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arenaai.duagents.DuAgentsApp
import com.arenaai.duagents.R
import com.arenaai.duagents.core.AgentPersona
import com.arenaai.duagents.core.AgentRegistry
import com.arenaai.duagents.core.AgentRouter
import com.arenaai.duagents.core.Emotion
import com.arenaai.duagents.core.EmotionAnalyzer
import com.arenaai.duagents.core.textTurn
import com.arenaai.duagents.data.AgentMode
import com.arenaai.duagents.data.ConversationPhase
import com.arenaai.duagents.data.OrchestratorEvent
import com.arenaai.duagents.data.TextProcessing
import com.arenaai.duagents.data.UiMessage
import com.arenaai.duagents.data.UiRole
import com.arenaai.duagents.orchestration.ConversationOrchestrator
import com.arenaai.duagents.voice.SpeechInputManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ToolSummary(
    val name: String,
    val description: String,
    val forged: Boolean
)

data class UiState(
    val messages: List<UiMessage> = emptyList(),
    val phase: ConversationPhase = ConversationPhase.IDLE,
    val phaseAgent: AgentPersona? = null,
    val partial: String = "",
    val listening: Boolean = false,
    val micPermissionGranted: Boolean = false,
    val speechAvailable: Boolean = true,
    val mode: AgentMode = AgentMode.AUTO,
    val activeAgent: AgentPersona = AgentRegistry.IRFAN,
    val emotion: Emotion? = null,
    val hasApiKey: Boolean = false,
    val apiKeyFromBuildConfig: Boolean = false,
    val resolvedModel: String = "",
    val tools: List<ToolSummary> = emptyList(),
    val inputText: String = ""
)

/**
 * Single source of UI truth. Bridges: SpeechInput (user voice) → Router → Orchestrator
 * (Gemini + tools + collaboration) → SpeechOutput (agent voices) → Compose state.
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as DuAgentsApp).container
    private val orchestrator = container.orchestrator
    private val speechOutput = container.speechOutput
    private val keyVault = container.keyVault
    private val registry = container.toolRegistry

    private var speechInput: SpeechInputManager? = null
    private var idCounter = 0L

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    init {
        _ui.update {
            it.copy(
                hasApiKey = keyVault.has(),
                apiKeyFromBuildConfig = keyVault.isFromBuildConfig(),
                resolvedModel = container.geminiClient.resolvedModelInfo(),
                tools = toolSummaries(),
                messages = restoreHistory()
            )
        }

        orchestrator.onEvent = { event -> handleOrchestratorEvent(event) }

        speechOutput.onSpeakingStarted = { agentId ->
            _ui.update { s ->
                s.copy(
                    phase = ConversationPhase.SPEAKING,
                    phaseAgent = AgentRegistry.byId(agentId)
                )
            }
        }
        speechOutput.onSpeakingEnded = {
            if (!orchestrator.isBusy) {
                _ui.update { it.copy(phase = ConversationPhase.IDLE, phaseAgent = null) }
                speechInput?.resume()
            }
        }
    }

    private fun handleOrchestratorEvent(event: OrchestratorEvent) {
        when (event) {
            is OrchestratorEvent.AgentReply -> addAgentMessage(event.agentId, event.text, event.artifacts)
            is OrchestratorEvent.Phase -> {
                _ui.update { s ->
                    s.copy(
                        phase = event.phase,
                        phaseAgent = event.agentId?.let { AgentRegistry.byId(it) }
                    )
                }
                when (event.phase) {
                    ConversationPhase.THINKING, ConversationPhase.SPEAKING -> speechInput?.pause()
                    ConversationPhase.IDLE -> {
                        if (!speechOutputReadyBusy()) speechInput?.resume()
                    }
                    else -> Unit
                }
            }
            is OrchestratorEvent.ToolUsed -> {
                val name = event.toolName
                addSystemNote(appString(R.string.note_tool_used).format(name))
            }
            is OrchestratorEvent.ToolForged -> {
                val name = event.toolName
                if (name.isNotBlank()) {
                    addSystemNote(appString(R.string.note_tool_forged).format(name))
                    _ui.update { it.copy(tools = toolSummaries()) }
                }
            }
            is OrchestratorEvent.Error -> addSystemNote("⚠️ " + event.userMessage)
            is OrchestratorEvent.Note -> {
                if (event.text == "busy") {
                    addSystemNote(appString(R.string.note_still_working))
                }
            }
        }
    }

    private fun speechOutputReadyBusy(): Boolean = !orchestrator.isBusy

    // ── User actions ────────────────────────────────────────────────────────

    fun onInputChanged(text: String) = _ui.update { it.copy(inputText = text) }

    fun onSend() {
        val text = _ui.value.inputText.trim()
        if (text.isEmpty()) return
        if (orchestrator.isBusy) {
            addSystemNote(appString(R.string.note_still_working))
            return
        }
        _ui.update { it.copy(inputText = "") }
        stopListening()

        // Routing decision — explicit mentions hand over fully and immediately.
        val decision = AgentRouter.decide(text, _ui.value.mode, _ui.value.activeAgent)
        _ui.update { it.copy(activeAgent = decision.newActiveAgent) }
        val emotion = EmotionAnalyzer.analyze(text)
        _ui.update { it.copy(emotion = emotion.emotion) }

        addUserMessage(text)
        orchestrator.submit(text, decision, emotion)
    }

    fun onModeChanged(mode: AgentMode) {
        _ui.update {
            it.copy(
                mode = mode,
                activeAgent = when (mode) {
                    AgentMode.IRFAN -> AgentRegistry.IRFAN
                    AgentMode.ELENA -> AgentRegistry.ELENA
                    else -> it.activeAgent
                }
            )
        }
    }

    fun onMicPermissionResult(granted: Boolean) {
        _ui.update { it.copy(micPermissionGranted = granted) }
        if (granted) ensureSpeechInput()
    }

    fun onMicToggled() {
        if (_ui.value.listening) {
            stopListening()
        } else {
            ensureSpeechInput()
            speechInput?.start()
            _ui.update {
                it.copy(
                    listening = true,
                    phase = ConversationPhase.LISTENING,
                    partial = ""
                )
            }
        }
    }

    private fun stopListening() {
        speechInput?.stop()
        _ui.update {
            it.copy(
                listening = false,
                partial = "",
                phase = if (it.phase == ConversationPhase.LISTENING) ConversationPhase.IDLE else it.phase
            )
        }
    }

    fun stopVoice() = speechOutput.stop()

    fun saveApiKey(key: String) {
        keyVault.saveRuntimeKey(key)
        _ui.update {
            it.copy(hasApiKey = keyVault.has(), apiKeyFromBuildConfig = keyVault.isFromBuildConfig())
        }
    }

    fun saveModel(model: String) {
        keyVault.saveModelOverride(model)
        _ui.update { it.copy(resolvedModel = container.geminiClient.resolvedModelInfo()) }
    }

    fun clearConversation() {
        container.memory.clear()
        _ui.update { it.copy(messages = emptyList()) }
        addSystemNote(appString(R.string.clear_done))
    }

    fun deleteTool(name: String) {
        registry.unregister(name)
        registry.toolForge.delete(name)
        _ui.update { it.copy(tools = toolSummaries()) }
    }

    fun speechStateMessage(code: String) {
        val msg = when (code) {
            "speech_unavailable" -> appString(R.string.speech_unavailable)
            "permission" -> appString(R.string.mic_permission_rationale)
            "network" -> null // transient — recognition auto-restarts
            else -> null
        }
        if (msg != null) {
            addSystemNote(msg)
            if (code == "speech_unavailable" || code == "permission") stopListening()
        }
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private fun ensureSpeechInput() {
        if (speechInput != null) return
        speechInput = SpeechInputManager(
            getApplication(),
            onPartial = { partial -> _ui.update { it.copy(partial = partial) } },
            onFinal = { text ->
                _ui.update { it.copy(inputText = text) }
                onSend()
            },
            onStateMessage = { code -> speechStateMessage(code) }
        )
        _ui.update { it.copy(speechAvailable = speechInput?.isAvailable() == true) }
    }

    private fun addUserMessage(text: String) {
        _ui.update { s ->
            s.copy(messages = s.messages + UiMessage(nextId(), UiRole.USER, null, text))
        }
    }

    private fun addAgentMessage(agentId: String, text: String, artifacts: List<com.arenaai.duagents.data.Artifact>) {
        _ui.update { s ->
            s.copy(
                messages = s.messages + UiMessage(
                    nextId(), UiRole.AGENT, agentId, text, artifacts
                )
            )
        }
    }

    private fun addSystemNote(text: String) {
        _ui.update { s ->
            s.copy(messages = s.messages + UiMessage(nextId(), UiRole.SYSTEM, null, text))
        }
    }

    private fun nextId(): Long = ++idCounter

    private fun toolSummaries(): List<ToolSummary> =
        registry.all().map { ToolSummary(it.name, it.description, registry.isForged(it.name)) }

    private fun restoreHistory(): List<UiMessage> =
        container.memory.load().mapNotNull { turn ->
            val text = turn.plainText()
            if (text.isBlank()) return@mapNotNull null
            when {
                turn.role == "user" -> UiMessage(
                    nextId(), UiRole.USER, null, text, emptyList(), turn.timestamp
                )
                else -> {
                    val agent = AgentRegistry.byId(turn.agentId ?: "irfan")
                    UiMessage(
                        nextId(), UiRole.AGENT, agent.id, text,
                        TextProcessing.parseArtifacts(text), turn.timestamp
                    )
                }
            }
        }

    private fun appString(res: Int): String = getApplication<Application>().getString(res)

    override fun onCleared() {
        speechInput?.destroy()
        speechOutput.shutdown()
        super.onCleared()
    }
}

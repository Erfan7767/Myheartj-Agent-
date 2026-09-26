package com.arenaai.duagents.core

import android.content.Context
import com.arenaai.duagents.network.GeminiClient
import com.arenaai.duagents.orchestration.ConversationOrchestrator
import com.arenaai.duagents.tools.ToolRegistry
import com.arenaai.duagents.voice.SpeechOutputManager

/**
 * Manual dependency graph — one instance per process.
 */
class AppContainer(context: Context) {

    val keyVault = KeyVault(context)
    val memory = ConversationMemory(context)
    val speechOutput = SpeechOutputManager(context)
    val toolRegistry = ToolRegistry(context)
    val geminiClient = GeminiClient(keyVault)
    val orchestrator =
        ConversationOrchestrator(geminiClient, toolRegistry, memory, speechOutput)
}

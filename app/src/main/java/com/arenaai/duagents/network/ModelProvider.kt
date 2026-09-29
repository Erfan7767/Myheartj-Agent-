package com.arenaai.duagents.network

import com.arenaai.duagents.data.Turn

/**
 * Vendor-neutral model contract.
 *
 * Agent/orchestration code depends on this interface instead of a specific model vendor.
 */
interface ModelProvider {
    val id: String
    val displayName: String

    fun resolvedModelInfo(): String

    suspend fun generate(
        systemInstruction: String,
        turns: List<Turn>,
        toolDeclarations: List<ToolDeclaration>
    ): GeminiOutcome
}

/** Adapter for the currently implemented Google Gemini transport. */
class GeminiModelProvider(
    private val client: GeminiClient
) : ModelProvider {
    override val id: String = "gemini"
    override val displayName: String = "Google Gemini"

    override fun resolvedModelInfo(): String = client.resolvedModelInfo()

    override suspend fun generate(
        systemInstruction: String,
        turns: List<Turn>,
        toolDeclarations: List<ToolDeclaration>
    ): GeminiOutcome = client.generate(systemInstruction, turns, toolDeclarations)
}

/** Small selection contract so ModelRouter stays JVM-testable without Android Context. */
interface ModelProviderSelector {
    fun providerOverride(): String?
}

/**
 * Central provider router.
 *
 * Arena Agent Mode is intentionally not impersonated here: Arena's current public Agent Mode
 * documentation describes a web service and its dedicated orchestrator, but does not document
 * a public Android/API contract for third-party apps. An official Arena/API-backed provider can
 * therefore be registered here later without changing the Agent Core.
 */
class ModelRouter(
    private val selector: ModelProviderSelector,
    providers: List<ModelProvider>,
    private val defaultProviderId: String = "gemini"
) {
    private val providersById = providers.associateBy { it.id }

    fun availableProviders(): List<ModelProvider> = providersById.values.toList()

    fun activeProviderId(): String {
        val requested = selector.providerOverride()?.trim().orEmpty()
        return if (requested.isNotBlank() && providersById.containsKey(requested)) {
            requested
        } else {
            defaultProviderId
        }
    }

    fun activeProvider(): ModelProvider =
        providersById[activeProviderId()]
            ?: error("No model provider is registered for the selected provider.")

    fun resolvedModelInfo(): String =
        activeProvider().displayName + " / " + activeProvider().resolvedModelInfo()

    suspend fun generate(
        systemInstruction: String,
        turns: List<Turn>,
        toolDeclarations: List<ToolDeclaration>
    ): GeminiOutcome =
        activeProvider().generate(systemInstruction, turns, toolDeclarations)
}

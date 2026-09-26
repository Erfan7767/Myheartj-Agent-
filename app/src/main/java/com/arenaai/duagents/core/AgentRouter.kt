package com.arenaai.duagents.core

import com.arenaai.duagents.data.AgentMode
import java.util.Locale

data class RouteDecision(
    val participants: List<AgentPersona>,
    val collaborative: Boolean,
    val newActiveAgent: AgentPersona
)

/**
 * Engineering decision: routing is deterministic and offline-capable (keyword + normalization
 * based) — the user's explicit words always win. "Auto" keeps the current active agent and
 * hands over fully and immediately when the other agent is named.
 */
object AgentRouter {

    private val bothRegex = Regex(
        "(both of you|you both|together|work together|كلتاكما|كلاكما|اثنتاكما|معا\\s*بعض|معاً|سوي\\s*بعض)",
        RegexOption.IGNORE_CASE
    )

    fun normalizeArabic(s: String): String =
        s.replace(Regex("[\\u064B-\\u0652\\u0640]"), "")

    fun mentions(text: String): List<AgentPersona> {
        val n = normalizeArabic(text.lowercase(Locale.ROOT))
        return AgentRegistry.all.filter { persona ->
            persona.matchKeywords.any { n.contains(it.lowercase(Locale.ROOT)) }
        }
    }

    fun decide(text: String, mode: AgentMode, active: AgentPersona): RouteDecision {
        return when (mode) {
            AgentMode.IRFAN -> RouteDecision(listOf(AgentRegistry.IRFAN), false, AgentRegistry.IRFAN)
            AgentMode.ELENA -> RouteDecision(listOf(AgentRegistry.ELENA), false, AgentRegistry.ELENA)
            AgentMode.COLLAB ->
                RouteDecision(AgentRegistry.all, true, active)
            AgentMode.AUTO -> {
                val mentioned = mentions(text)
                val wantsBoth = mentioned.size >= 2 ||
                        bothRegex.containsMatchIn(normalizeArabic(text))
                when {
                    wantsBoth -> RouteDecision(AgentRegistry.all, true, active)
                    mentioned.firstOrNull()?.id == "elena" ->
                        RouteDecision(listOf(AgentRegistry.ELENA), false, AgentRegistry.ELENA)
                    mentioned.isNotEmpty() ->
                        RouteDecision(listOf(AgentRegistry.IRFAN), false, AgentRegistry.IRFAN)
                    else -> RouteDecision(listOf(active), false, active)
                }
            }
        }
    }
}

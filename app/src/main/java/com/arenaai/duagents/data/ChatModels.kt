package com.arenaai.duagents.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Core conversation model shared by the network layer, memory store, orchestrator and UI.
 */
sealed class Part {
    data class Text(val text: String) : Part()
    data class FunctionCall(val name: String, val args: JSONObject) : Part()
    data class FunctionResponse(val name: String, val response: JSONObject) : Part()
}

data class Turn(
    val role: String, // "user" | "model"
    val agentId: String? = null,
    val parts: List<Part>,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun plainText(): String =
        parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("role", role)
        agentId?.let { o.put("agentId", it) }
        o.put("timestamp", timestamp)
        val arr = JSONArray()
        for (p in parts) {
            when (p) {
                is Part.Text -> arr.put(
                    JSONObject().put("type", "text").put("text", p.text)
                )
                is Part.FunctionCall -> arr.put(
                    JSONObject().put("type", "functionCall")
                        .put("name", p.name).put("args", p.args)
                )
                is Part.FunctionResponse -> arr.put(
                    JSONObject().put("type", "functionResponse")
                        .put("name", p.name).put("response", p.response)
                )
            }
        }
        o.put("parts", arr)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Turn {
            val parts = ArrayList<Part>()
            val arr = o.optJSONArray("parts") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val po = arr.optJSONObject(i) ?: continue
                when (po.optString("type")) {
                    "functionCall" -> parts.add(
                        Part.FunctionCall(
                            po.optString("name"),
                            po.optJSONObject("args") ?: JSONObject()
                        )
                    )
                    "functionResponse" -> parts.add(
                        Part.FunctionResponse(
                            po.optString("name"),
                            po.optJSONObject("response") ?: JSONObject()
                        )
                    )
                    else -> parts.add(Part.Text(po.optString("text")))
                }
            }
            return Turn(
                role = o.optString("role", "user"),
                agentId = if (o.has("agentId")) o.optString("agentId") else null,
                parts = parts,
                timestamp = o.optLong("timestamp", System.currentTimeMillis())
            )
        }
    }
}

/** A rendered code/structured artifact extracted from an agent reply (never read aloud). */
data class Artifact(val language: String, val title: String, val code: String)

enum class UiRole { USER, AGENT, SYSTEM }

data class UiMessage(
    val id: Long,
    val role: UiRole,
    val agentId: String? = null,
    val text: String,
    val artifacts: List<Artifact> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

enum class AgentMode { AUTO, IRFAN, ELENA, COLLAB }

enum class ConversationPhase { IDLE, LISTENING, THINKING, SPEAKING }

/** Structured events emitted by the orchestrator for the UI. */
sealed class OrchestratorEvent {
    data class AgentReply(val agentId: String, val text: String, val artifacts: List<Artifact>) :
        OrchestratorEvent()

    data class Phase(val phase: ConversationPhase, val agentId: String? = null) :
        OrchestratorEvent()

    data class ToolUsed(val toolName: String) : OrchestratorEvent()
    data class ToolForged(val toolName: String) : OrchestratorEvent()
    data class Error(val userMessage: String) : OrchestratorEvent()
    data class Note(val text: String) : OrchestratorEvent()
}

object TextProcessing {

    /** Extract fenced code blocks as artifacts; keeps original text untouched for display. */
    fun parseArtifacts(text: String): List<Artifact> {
        val result = ArrayList<Artifact>()
        val regex = Regex("```([A-Za-z0-9_+#.-]*)\\s*\\n([\\s\\S]*?)```")
        for (m in regex.findAll(text)) {
            val lang = m.groupValues[1].ifBlank { "text" }
            var code = m.groupValues[2].trimEnd('\n')
            if (code.isBlank()) continue
            if (code.length > 20000) code = code.take(20000) + "\n… (truncated)"
            val firstLine = code.lineSequence().firstOrNull()?.trim().orEmpty()
            val title = when {
                firstLine.length in 4..60 -> firstLine
                else -> lang
            }
            result.add(Artifact(lang, title, code))
        }
        return result
    }

    /** Speech-safe text: fenced code is referenced, markdown symbols are stripped. */
    fun sanitizeForSpeech(text: String): String {
        var t = Regex("```[A-Za-z0-9_+#.-]*\\s*\\n[\\s\\S]*?```")
            .replace(text, " (code artifact) ")
        t = Regex("\\[([^\\]]+)]\\(([^)]+)\\)").replace(t, "$1")
        t = t.replace("**", "").replace("__", "").replace("`", "")
        t = Regex("^#{1,6}\\s*", RegexOption.MULTILINE).replace(t, "")
        t = Regex("^\\s*[-*]{1}\\s+", RegexOption.MULTILINE).replace(t, "")
        t = t.replace("*", "")
        t = Regex("\\s+").replace(t, " ").trim()
        return t.ifBlank { "(code artifact)" }
    }

    /** Groups sanitized text into natural TTS lines so playback starts with near-zero delay. */
    fun splitToSpokenLines(sanitized: String): List<String> {
        val sentences = Regex("(?<=[.!?؟…])\\s+").split(sanitized)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val merged = ArrayList<String>()
        for (s in sentences) {
            val last = merged.lastOrNull()
            if (last != null && (last.length + s.length) <= 260) {
                merged[merged.size - 1] = "$last $s"
            } else if (last != null && last.length < 8) {
                merged[merged.size - 1] = "$last $s"
            } else {
                merged.add(s)
            }
        }
        if (merged.isEmpty() && sanitized.isNotBlank()) merged.add(sanitized)
        return merged
    }

    private val labelRegex =
        Regex("^\\s*(IRFAN|ELENA|\\u0639\\u0631\\u0641\\u0627\\u0646|\\u0625\\u064A\\u0644\\u064A\\u0646\\u0627)\\s*[:\\uFF1A\\u2014-]\\s*", RegexOption.IGNORE_CASE)

    /** Strips the 'IRFAN:' / 'ELENA:' collaboration label — the UI already shows the author. */
    fun stripAgentLabel(text: String): String = labelRegex.replace(text, "").trim()
}

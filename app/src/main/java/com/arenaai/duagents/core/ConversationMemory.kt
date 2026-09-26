package com.arenaai.duagents.core

import android.content.Context
import com.arenaai.duagents.data.Part
import com.arenaai.duagents.data.Turn
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistent conversation memory (JSON file in app-private storage).
 * Engineering decision: plain org.json + File — zero extra dependencies, fully inspectable,
 * and the last N turns are replayed to the model as long-term conversational context.
 */
class ConversationMemory(context: Context) {

    private val file = File(context.filesDir, "conversation_memory.json")

    @Synchronized
    fun load(): List<Turn> {
        if (!file.exists()) return emptyList()
        return try {
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("turns") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { Turn.fromJson(it) }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun append(turn: Turn) = appendAll(listOf(turn))

    @Synchronized
    fun appendAll(turns: List<Turn>) {
        if (turns.isEmpty()) return
        val all = (load() + turns).takeLast(MAX_TURNS)
        persist(all)
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun persist(turns: List<Turn>) {
        val arr = JSONArray()
        turns.forEach { arr.put(it.toJson()) }
        file.parentFile?.mkdirs()
        file.writeText(JSONObject().put("turns", arr).toString())
    }

    private companion object {
        const val MAX_TURNS = 80
    }
}

/** Convenience for building text-only turns. */
fun textTurn(role: String, agentId: String? = null, vararg texts: String): Turn =
    Turn(role, agentId, texts.map { Part.Text(it) })

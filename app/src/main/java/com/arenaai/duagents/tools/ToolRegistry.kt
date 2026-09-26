package com.arenaai.duagents.tools

import android.content.Context
import com.arenaai.duagents.network.ToolDeclaration
import org.json.JSONObject

/**
 * Live registry of every capability the agents can call: built-ins + self-forged tools.
 * A dispatch miss returns an actionable hint that steers the agents to ToolForge —
 * this is what makes the "no tool exists → build one" loop actually close.
 */
class ToolRegistry(context: Context) {

    private val tools = LinkedHashMap<String, AgentTool>()
    private val forge = ToolForge(context)

    val toolForge: ToolForge get() = forge

    init {
        // Built-in tools (real executors only — no stubs).
        register(TimeTool())
        register(CalculatorTool())
        register(DeviceInfoTool(context.applicationContext))
        register(WebFetchTool())
        // Self-extension meta-tools.
        register(forge.CreateToolMetaTool(this))
        register(forge.ListToolsMetaTool(this))
        register(forge.DeleteToolMetaTool())
        // Every tool previously forged by the agents in earlier sessions.
        forge.loadForgedInto(this)
    }

    @Synchronized
    fun register(tool: AgentTool) {
        tools[tool.name] = tool
    }

    @Synchronized
    fun unregister(name: String) {
        tools.remove(name)
    }

    @Synchronized
    fun all(): List<AgentTool> = tools.values.toList()

    fun declarations(): List<ToolDeclaration> = all().map { it.toDeclaration() }

    fun isForged(name: String): Boolean = forge.isForged(name)

    fun forgedNames(): List<String> = forge.forgedNames()

    suspend fun dispatch(name: String, args: JSONObject): JSONObject {
        val tool = tools[name]
            ?: return JSONObject()
                .put(
                    "error",
                    "Unknown tool '$name'. No such tool is registered."
                )
                .put(
                    "hint",
                    "If no existing tool fits this task, do NOT refuse it — call create_tool " +
                            "to build the missing capability now, then call the new tool."
                )
        return try {
            tool.execute(args)
        } catch (e: Exception) {
            JSONObject().put("error", "Tool '$name' execution failed: ${e.message}")
        }
    }
}

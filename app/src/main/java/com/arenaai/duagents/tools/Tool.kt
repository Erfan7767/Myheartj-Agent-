package com.arenaai.duagents.tools

import com.arenaai.duagents.network.ToolDeclaration
import com.arenaai.duagents.network.ToolParamSchema
import org.json.JSONObject

/**
 * A tool is any capability an agent can call: the built-ins shipped with the app AND tools the
 * agents forge themselves at runtime. Both are first-class and indistinguishable to the model.
 */
interface AgentTool {
    val name: String
    val description: String
    val params: List<ToolParamSchema>

    /** Executes for real and returns a JSON object with "result" or "error". */
    suspend fun execute(args: JSONObject): JSONObject
}

fun AgentTool.toDeclaration(): ToolDeclaration =
    ToolDeclaration(name, description, params)

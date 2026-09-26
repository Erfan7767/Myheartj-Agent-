package com.arenaai.duagents.tools

import android.content.Context
import com.arenaai.duagents.network.ToolParamSchema
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.regex.Pattern

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * TOOLFORGE — the real Dynamic Tool Creation / Self-Extending Capability engine.
 *
 * When Irfan or Elena needs a capability that does not exist, they call the
 * create_tool meta-tool. ToolForge VALIDATES the definition, PERSISTS it to
 * app-private storage (survives restarts), REGISTERS it into the live tool
 * registry, and it becomes immediately callable — a genuine self-extension
 * mechanism, not a fixed pre-limited toolset.
 *
 * Engineering decision: forged tools are expressed as a validated declarative
 * DSL (http/template/math/json_extract/regex_extract/text_transform/constant)
 * executed by DslProgramRunner. This gives real, safe, sandboxed dynamic
 * capability creation on Android — arbitrary runtime code loading is neither
 * possible nor safe on a stock device, and a DSL pipeline covers the realistic
 * space of tools (API calls + transformation pipelines) with zero security hole.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class ToolForge(private val context: Context) {

    private val dir: File get() = File(context.filesDir, "forged_tools").apply { mkdirs() }

    val ALLOWED_STEP_TYPES = setOf(
        "http", "template", "math", "json_extract", "regex_extract", "text_transform", "constant"
    )

    fun isForged(name: String): Boolean = File(dir, "$name.json").exists()

    fun forgedNames(): List<String> =
        dir.listFiles()?.filter { it.extension == "json" }?.map { it.nameWithoutExtension }
            .orEmpty().sorted()

    /** Loads every persisted forged tool into the live registry at app start. */
    fun loadForgedInto(registry: ToolRegistry) {
        for (f in dir.listFiles()?.filter { it.extension == "json" }.orEmpty()) {
            try {
                val spec = parseSpec(JSONObject(f.readText()))
                registry.register(ForgedTool(spec))
            } catch (_: Exception) {
                // A single corrupted definition never blocks the rest of the system.
            }
        }
    }

    /** Entry point of the create_tool meta-tool: validate → persist → register. */
    @Throws(IllegalArgumentException::class)
    fun create(registry: ToolRegistry, definition: JSONObject): JSONObject {
        val spec = parseSpec(definition)
        validate(spec)
        File(dir, "${spec.name}.json").writeText(spec.toStorageJson().toString())
        registry.register(ForgedTool(spec))
        return JSONObject()
            .put(
                "result",
                "Tool '${spec.name}' was validated, permanently saved and registered. " +
                        "It is callable right now with params: " +
                        spec.params.joinToString(", ") { it.name } + "."
            )
    }

    fun delete(name: String): Boolean = File(dir, "$name.json").delete()

    // ── Parsing & validation ────────────────────────────────────────────────

    data class DslSpec(
        val name: String,
        val description: String,
        val params: List<ToolParamSchema>,
        val steps: List<Pair<String, Map<String, String>>>,
        val returns: String
    ) {
        fun toStorageJson(): JSONObject {
            val paramsArr = JSONArray()
            params.forEach {
                paramsArr.put(
                    JSONObject().put("name", it.name).put("description", it.description)
                        .put("required", it.required)
                )
            }
            val stepsArr = JSONArray()
            steps.forEach { (type, fields) ->
                val fo = JSONObject().put("type", type)
                fields.forEach { (k, v) -> fo.put(k, v) }
                stepsArr.put(fo)
            }
            return JSONObject()
                .put("name", name)
                .put("description", description)
                .put("params", paramsArr)
                .put("steps", stepsArr)
                .put("returns", returns)
        }
    }

    @Throws(IllegalArgumentException::class)
    fun parseSpec(definition: JSONObject): DslSpec {
        val name = definition.optString("name", "").trim()
        if (!Pattern.matches("^[a-z][a-z0-9_]{2,40}$", name)) {
            throw IllegalArgumentException(
                "Tool name '$name' is invalid — use lowercase snake_case, 3-41 chars."
            )
        }
        val description = definition.optString("description", "").trim()
        if (description.length < 5) {
            throw IllegalArgumentException("Tool description is missing or too short.")
        }

        val params = ArrayList<ToolParamSchema>()
        val paramsArr = definition.optJSONArray("params")
        if (paramsArr != null) {
            for (i in 0 until paramsArr.length()) {
                val po = paramsArr.optJSONObject(i) ?: continue
                val pn = po.optString("name", "").trim()
                if (!Pattern.matches("^[a-z][a-z0-9_]{0,30}$", pn)) {
                    throw IllegalArgumentException("Param name '$pn' is invalid.")
                }
                params.add(
                    ToolParamSchema(
                        pn,
                        po.optString("description", pn).ifBlank { pn },
                        po.optBoolean("required", true)
                    )
                )
            }
        }
        if (params.isEmpty()) params.add(ToolParamSchema("input", "Free text input", false))

        val steps = ArrayList<Pair<String, Map<String, String>>>()
        val stepsArr = definition.optJSONArray("steps")
            ?: throw IllegalArgumentException("Tool has no 'steps' array.")
        if (stepsArr.length() == 0) throw IllegalArgumentException("Tool has zero steps.")
        if (stepsArr.length() > 12) throw IllegalArgumentException("Too many steps (max 12).")
        val metaKeys = setOf("type")
        for (i in 0 until stepsArr.length()) {
            val so = stepsArr.optJSONObject(i)
                ?: throw IllegalArgumentException("Step ${i + 1} is not an object.")
            val type = so.optString("type", "").trim().lowercase()
            if (type !in ALLOWED_STEP_TYPES) {
                throw IllegalArgumentException(
                    "Step ${i + 1} has unknown type '$type'. Allowed: $ALLOWED_STEP_TYPES"
                )
            }
            val fields = HashMap<String, String>()
            for (key in so.keys()) {
                if (key in metaKeys) continue
                val v = so.opt(key)
                fields[key] = when (v) {
                    null, JSONObject.NULL -> ""
                    is JSONObject, is JSONArray -> v.toString()
                    else -> v.toString()
                }
            }
            steps.add(type to fields)
        }

        val returns = definition.optString("returns", "").trim()
        if (returns.length > 8000) throw IllegalArgumentException("'returns' template too long.")

        return DslSpec(name, description, params, steps, returns)
    }

    @Throws(IllegalArgumentException::class)
    private fun validate(spec: DslSpec) {
        for ((index, step) in spec.steps.withIndex()) {
            val (type, fields) = step
            when (type) {
                "http" -> {
                    val url = fields["url"] ?: ""
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        throw IllegalArgumentException(
                            "Step ${index + 1}: http URL must start with http(s)://"
                        )
                    }
                }
                "json_extract" -> if (fields["path"].isNullOrBlank()) {
                    throw IllegalArgumentException("Step ${index + 1}: json_extract needs 'path'")
                }
                "regex_extract" -> {
                    try {
                        Pattern.compile(fields["pattern"].orEmpty(), Pattern.DOTALL)
                    } catch (e: Exception) {
                        throw IllegalArgumentException(
                            "Step ${index + 1}: invalid regex — ${e.message}"
                        )
                    }
                }
            }
        }
    }

    // ── The forged tool itself ──────────────────────────────────────────────

    inner class ForgedTool(private val spec: DslSpec) : AgentTool {
        override val name = spec.name
        override val description = "[forged] ${spec.description}"
        override val params = spec.params

        override suspend fun execute(args: JSONObject): JSONObject =
            DslProgramRunner.run(spec.steps, spec.returns, spec.params.map { it.name }, args)
    }

    // ── Meta-tools exposed to the agents (they make self-creation callable) ──

    inner class CreateToolMetaTool(private val registry: ToolRegistry) : AgentTool {
        override val name = "create_tool"
        override val description =
            "FORGE A BRAND-NEW TOOL when no existing tool fits the task. Provide 'definition' as a JSON object (or a JSON string) with: name (lowercase_snake_case), description, params [{name, description, required}], steps [{type: http|template|math|json_extract|regex_extract|text_transform|constant, ...fields, save_as}], returns (template). http fields: url, method(GET/POST), headers, body. json_extract: source, path (e.g. a.b[0].c). math: expression. regex_extract: source, pattern. text_transform: source, op(upper|lower|trim|replace|json_escape), find, replace_with. constant: value. The tool is validated, permanently saved and immediately callable."
        override val params = listOf(
            ToolParamSchema("definition", "The full tool definition JSON object", true)
        )

        override suspend fun execute(args: JSONObject): JSONObject {
            val def: JSONObject = when {
                args.has("definition") && args.opt("definition") is JSONObject ->
                    args.getJSONObject("definition")
                args.has("definition") && args.opt("definition") is String ->
                    JSONObject(args.getString("definition"))
                args.has("name") -> args // definition given inline
                else -> return JSONObject().put(
                    "error",
                    "No 'definition' provided. Pass the complete tool definition JSON."
                )
            }
            return try {
                val result = create(registry, def)
                result.put("_forged", def.optString("name", ""))
            } catch (e: IllegalArgumentException) {
                JSONObject().put("error", "Tool rejected: ${e.message}")
            }
        }
    }

    inner class ListToolsMetaTool(private val registry: ToolRegistry) : AgentTool {
        override val name = "list_tools"
        override val description =
            "Lists every tool currently registered (built-in + forged), with names and descriptions. Use it to check capabilities before building a new tool."
        override val params: List<ToolParamSchema> = emptyList()

        override suspend fun execute(args: JSONObject): JSONObject {
            val arr = JSONArray()
            registry.all().forEach {
                arr.put(JSONObject().put("name", it.name).put("description", it.description))
            }
            return JSONObject().put("result", arr)
        }
    }

    inner class DeleteToolMetaTool : AgentTool {
        override val name = "delete_tool"
        override val description =
            "Deletes a previously forged tool by name (built-in tools cannot be deleted)."
        override val params = listOf(
            ToolParamSchema("name", "Name of the forged tool to delete", true)
        )

        override suspend fun execute(args: JSONObject): JSONObject {
            val target = args.optString("name", args.optString("input", "")).trim()
            return if (isForged(target) && delete(target)) {
                JSONObject().put("result", "Forged tool '$target' deleted.")
            } else {
                JSONObject().put(
                    "error",
                    "No forged tool named '$target' exists (built-in tools are permanent)."
                )
            }
        }
    }
}

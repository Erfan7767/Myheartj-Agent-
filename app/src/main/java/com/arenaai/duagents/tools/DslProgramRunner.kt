package com.arenaai.duagents.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Executes ToolForge DSL programs for real: each step runs through a real executor
 * (real HTTP call, real math evaluation, real JSON traversal, real regex) and variables
 * flow between steps through an interpolation engine — this is the actual runtime behind
 * every dynamically created tool.
 */
object DslProgramRunner {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val placeholderRegex = Regex("\\{\\{\\s*([A-Za-z0-9_]+)\\s*\\}\\}")

    suspend fun run(
        steps: List<Pair<String, Map<String, String>>>,
        returns: String,
        paramNames: List<String>,
        args: JSONObject
    ): JSONObject = withContext(Dispatchers.IO) {
        val vars = HashMap<String, String>()
        for (p in paramNames) {
            vars[p] = if (args.has(p)) stringOf(args.opt(p)) else ""
        }
        if (!args.has("input") && paramNames.isEmpty()) vars["input"] = ""

        var lastSaved = paramNames.firstOrNull() ?: "input"
        steps.forEachIndexed { index, (type, fields) ->
            val output = executeStep(index, type, fields, vars)
            val saveAs = (fields["save_as"] ?: "step_$index").ifBlank { "step_$index" }
            vars[saveAs] = output
            lastSaved = saveAs
        }
        val returnsTemplate = returns.ifBlank { "{{${lastSaved}}}" }
        JSONObject().put("result", interpolate(returnsTemplate, vars))
    }

    private fun executeStep(
        index: Int,
        type: String,
        fields: Map<String, String>,
        vars: HashMap<String, String>
    ): String {
        try {
            when (type) {
                "http" -> {
                    val url = interpolate(require(fields, "url", index), vars)
                    val method = (fields["method"] ?: "GET").uppercase().trim()
                    val builder = Request.Builder().url(url)
                    fields["headers"]?.takeIf { it.isNotBlank() }?.let { headerBlock ->
                        headerBlock.lineSequence().forEach { line ->
                            val idx = line.indexOf(':')
                            if (idx > 0) {
                                builder.header(line.substring(0, idx).trim(), line.substring(idx + 1).trim())
                            }
                        }
                    }
                    when (method) {
                        "POST" -> builder.post(
                            interpolate(fields["body"] ?: "", vars)
                                .toRequestBody("application/json; charset=utf-8".toMediaType())
                        )
                        "PUT" -> builder.put(
                            interpolate(fields["body"] ?: "", vars)
                                .toRequestBody("application/json; charset=utf-8".toMediaType())
                        )
                        "DELETE" -> builder.delete()
                        else -> builder.get()
                    }
                    httpClient.newCall(builder.build()).execute().use { response ->
                        val bodyText = response.body?.string().orEmpty()
                        val isHtml = response.header("Content-Type").orEmpty()
                            .contains("html", ignoreCase = true)
                        val text = if (isHtml) stripHtml(bodyText) else bodyText
                        val capped = if (text.length > 20000) text.take(20000) else text
                        return "HTTP ${response.code}\n$capped"
                    }
                }
                "template" -> return interpolate(require(fields, "template", index), vars)
                "math" -> {
                    val expr = interpolate(require(fields, "expression", index), vars)
                    return CalculatorEngine.evaluateToText(expr)
                }
                "json_extract" -> {
                    val source = vars[fields["source"]] ?: ""
                    val path = require(fields, "path", index)
                    val parsed = parseJsonLoose(source)
                    val value = walkJson(parsed, path, 0)
                    return if (value == null || value == JSONObject.NULL) "" else value.toString()
                }
                "regex_extract" -> {
                    val source = vars[fields["source"]] ?: ""
                    val pattern = Pattern.compile(
                        require(fields, "pattern", index), Pattern.DOTALL
                    )
                    val m = pattern.matcher(source)
                    return if (m.find()) {
                        if (m.groupCount() >= 1) m.group(1).orEmpty() else m.group()
                    } else ""
                }
                "text_transform" -> {
                    val source = vars[fields["source"]] ?: ""
                    return when ((fields["op"] ?: "trim").lowercase()) {
                        "upper", "uppercase" -> source.uppercase()
                        "lower", "lowercase" -> source.lowercase()
                        "trim" -> source.trim()
                        "replace" -> source.replace(
                            fields["find"] ?: "", fields["replace_with"] ?: ""
                        )
                        "json_escape" -> JSONObject.quote(source).removeSurrounding("\"")
                        else -> throw IllegalArgumentException("Unknown text_transform op '${fields["op"]}'")
                    }
                }
                "constant" -> return fields["value"] ?: ""
                else -> throw IllegalArgumentException("Unknown step type '$type'")
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Step ${index + 1} ($type) failed: ${e.message}", e)
        }
    }

    private fun require(fields: Map<String, String>, key: String, step: Int): String {
        val v = fields[key]
        if (v.isNullOrBlank()) {
            throw IllegalArgumentException("Step ${step + 1} is missing required field '$key'")
        }
        return v
    }

    fun interpolate(template: String, vars: Map<String, String>): String {
        val out = placeholderRegex.replace(template) { m ->
            vars[m.groupValues[1]] ?: ""
        }
        return if (out.length > 60000) out.take(60000) else out
    }

    private fun parseJsonLoose(text: String): Any {
        val t = text.trim()
        if (t.isEmpty()) throw IllegalArgumentException("Empty JSON source")
        return if (t.startsWith("[")) JSONArray(t) else JSONObject(t)
    }

    /** Path grammar: dot-separated keys with optional [index] accessors, e.g. a.b[0].c */
    private fun walkJson(current: Any?, path: String, depth: Int): Any? {
        if (depth > 32) throw IllegalArgumentException("JSON path too deep")
        if (path.isBlank()) return current
        val dot = path.indexOf('.')
        val head = if (dot == -1) path else path.substring(0, dot)
        val tail = if (dot == -1) "" else path.substring(dot + 1)

        val keyPart = head.substringBefore('[')
        var next: Any? = when (current) {
            is JSONObject -> if (keyPart.isEmpty()) current else {
                if (!current.has(keyPart)) {
                    throw IllegalArgumentException("JSON key '$keyPart' not found")
                }
                current.opt(keyPart)
            }
            is JSONArray -> if (keyPart.isEmpty()) current else {
                throw IllegalArgumentException("Expected array index but found '$head'")
            }
            else -> throw IllegalArgumentException("Cannot navigate into ${current?.javaClass?.simpleName}")
        }
        // Handle chained [i] accessors.
        var rest = head.removePrefix(keyPart)
        while (rest.startsWith("[")) {
            val close = rest.indexOf(']')
            if (close < 0) throw IllegalArgumentException("Unclosed '[' in path")
            val idx = rest.substring(1, close).trim().toIntOrNull()
                ?: throw IllegalArgumentException("Bad array index in '$head'")
            next = when (next) {
                is JSONArray -> {
                    val arr = next as JSONArray
                    if (idx < 0 || idx >= arr.length()) {
                        throw IllegalArgumentException("Index $idx out of bounds")
                    }
                    arr.opt(idx)
                }
                else -> throw IllegalArgumentException("Value is not an array at [$idx]")
            }
            rest = rest.substring(close + 1)
        }
        return walkJson(next, tail, depth + 1)
    }

    private fun stripHtml(html: String): String {
        var t = Regex("(?is)<(script|style)[\\s\\S]*?</\\1>").replace(html, " ")
        t = Regex("(?s)<[^>]+>").replace(t, " ")
        return Regex("\\s+").replace(t, " ").trim()
    }

    private fun stringOf(v: Any?): String = when (v) {
        null -> ""
        JSONObject.NULL -> ""
        is JSONObject, is JSONArray -> v.toString()
        else -> v.toString()
    }
}

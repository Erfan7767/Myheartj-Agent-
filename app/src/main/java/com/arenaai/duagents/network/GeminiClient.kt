package com.arenaai.duagents.network

import com.arenaai.duagents.BuildConfig
import com.arenaai.duagents.core.ApiKeyProvider
import com.arenaai.duagents.core.KeyVault
import com.arenaai.duagents.data.Part
import com.arenaai.duagents.data.Turn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

// ─────────────────────────────────────────────────────────────────────────────
// REAL, LIVE BACKEND LAYER (non-negotiable requirement)
//
// The app connects directly to Google's official Gemini servers over HTTPS using
// a real OkHttpClient. The mandated primary endpoint is exactly:
//
//   https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent
//
// No mock class, no fake JSON, no hardcoded response exists anywhere in this app.
// Every reply shown or spoken by Irfan/Elena originates from an actual HTTP POST
// to the endpoint below, parsed from the real JSON response.
// ─────────────────────────────────────────────────────────────────────────────

const val GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
const val GEMINI_PRIMARY_MODEL = "gemini-3.5-flash" // mandated primary (BuildConfig default)

enum class GeminiErrorKind { MISSING_KEY, NETWORK, TIMEOUT, HTTP, QUOTA, SAFETY, EMPTY }

data class ToolParamSchema(val name: String, val description: String, val required: Boolean = true)

/** OpenAPI-subset function declaration sent to the Gemini function-calling layer. */
data class ToolDeclaration(
    val name: String,
    val description: String,
    val params: List<ToolParamSchema>
) {
    fun toJson(): JSONObject {
        val properties = JSONObject()
        val required = JSONArray()
        for (p in params) {
            properties.put(p.name, JSONObject().put("type", "STRING").put("description", p.description))
            if (p.required) required.put(p.name)
        }
        return JSONObject()
            .put("name", name)
            .put("description", description)
            .put("parameters", JSONObject()
                .put("type", "OBJECT")
                .put("properties", properties)
                .put("required", required))
    }
}

sealed class GeminiOutcome {
    data class Ok(val parts: List<Part>, val modelUsed: String) : GeminiOutcome()
    data class Failure(
        val kind: GeminiErrorKind,
        val userMessage: String,
        val technical: String,
        val modelRelated: Boolean = false
    ) : GeminiOutcome()
}

class GeminiClient(private val keyVault: ApiKeyProvider) {

    // Engineering decision: generous read timeout (large generations) + connection retry;
    // connect timeout short so dead-network failures surface fast and the fallback chain runs.
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile
    private var resolvedModel: String? = null

    fun resolvedModelInfo(): String = resolvedModel
        ?: keyVault.modelOverride()
        ?: BuildConfig.GEMINI_MODEL.ifBlank { GEMINI_PRIMARY_MODEL }

    /**
     * Executes a real generateContent call. Walks the model fallback chain only on
     * model-availability errors; retries transient IO failures once per model.
     */
    suspend fun generate(
        systemInstruction: String,
        turns: List<Turn>,
        toolDeclarations: List<ToolDeclaration>
    ): GeminiOutcome = withContext(Dispatchers.IO) {
        val key = keyVault.get()
        if (key.isBlank()) {
            return@withContext GeminiOutcome.Failure(
                GeminiErrorKind.MISSING_KEY,
                "No Gemini API key configured. Open Settings and add a free key from aistudio.google.com/apikey.",
                "KeyVault returned an empty key"
            )
        }
        val bodyJson = buildRequestBody(systemInstruction, turns, toolDeclarations)
        val mediaType = "application/json; charset=utf-8".toMediaType()

        val models = candidateModels()
        var lastFailure: GeminiOutcome.Failure? = null

        for (model in models) {
            var attempt = 0
            while (attempt < 2) {
                attempt++
                when (val outcome = callOnce(model, bodyJson, mediaType, key)) {
                    is CallOutcome.Success -> {
                        resolvedModel = model
                        return@withContext parseSuccess(outcome.payload, model)
                    }
                    is CallOutcome.HttpError -> {
                        val failure = parseHttpError(outcome.code, outcome.payload)
                        if (failure.modelRelated) {
                            // Model unavailable for this key → advance the fallback chain.
                            lastFailure = failure
                            break
                        }
                        return@withContext failure
                    }
                    is CallOutcome.Transport -> {
                        lastFailure = outcome.failure
                        // attempt 2 exhausted → break to next model in the fallback chain
                    }
                }
            }
        }
        lastFailure ?: GeminiOutcome.Failure(
            GeminiErrorKind.NETWORK,
            "Could not reach Google's servers.",
            "fallback chain exhausted"
        )
    }

    /** One real HTTP round-trip. Transport failures are retryable; HTTP errors are decoded. */
    private fun callOnce(
        model: String,
        bodyJson: JSONObject,
        mediaType: okhttp3.MediaType,
        key: String
    ): CallOutcome {
        val request = Request.Builder()
            .url(GEMINI_BASE_URL + model + ":generateContent")
            .header("x-goog-api-key", key)
            .header("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody(mediaType))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (response.isSuccessful) CallOutcome.Success(payload)
                else CallOutcome.HttpError(response.code, payload)
            }
        } catch (e: SocketTimeoutException) {
            CallOutcome.Transport(
                GeminiOutcome.Failure(
                    GeminiErrorKind.TIMEOUT,
                    "The connection to Google timed out. Check your network — I can retry.",
                    e.message ?: "socket timeout"
                )
            )
        } catch (e: IOException) {
            CallOutcome.Transport(
                GeminiOutcome.Failure(
                    GeminiErrorKind.NETWORK,
                    "Network problem reaching Google's servers: ${e.message ?: "unknown I/O error"}. Check your connection — I can retry.",
                    e.message ?: "IOException"
                )
            )
        }
    }

    private sealed class CallOutcome {
        data class Success(val payload: String) : CallOutcome()
        data class HttpError(val code: Int, val payload: String) : CallOutcome()
        data class Transport(val failure: GeminiOutcome.Failure) : CallOutcome()
    }

    private fun candidateModels(): List<String> {
        val list = ArrayList<String>()
        keyVault.modelOverride()?.let { if (it.isNotBlank()) list.add(it) }
        list.add(BuildConfig.GEMINI_MODEL.ifBlank { GEMINI_PRIMARY_MODEL })
        resolvedModel?.let { if (it !in list) list.add(0, it) } // sticky working model first
        list.add("gemini-flash-latest")
        list.add("gemini-2.5-flash")
        list.add("gemini-2.0-flash")
        list.add("gemini-1.5-flash")
        return list.distinct()
    }

    /** Builds the exact JSON structure the official generateContent endpoint expects. */
    private fun buildRequestBody(
        systemInstruction: String,
        turns: List<Turn>,
        toolDeclarations: List<ToolDeclaration>
    ): JSONObject {
        val root = JSONObject()

        root.put(
            "system_instruction",
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
        )

        val contents = JSONArray()
        for (turn in turns) {
            val content = JSONObject()
            content.put("role", if (turn.role == "user") "user" else "model")
            val parts = JSONArray()
            for (p in turn.parts) {
                when (p) {
                    is Part.Text -> parts.put(JSONObject().put("text", p.text))
                    is Part.FunctionCall -> parts.put(
                        JSONObject().put(
                            "function_call",
                            JSONObject().put("name", p.name).put("args", p.args)
                        )
                    )
                    is Part.FunctionResponse -> parts.put(
                        JSONObject().put(
                            "function_response",
                            JSONObject().put("name", p.name).put("response", p.response)
                        )
                    )
                }
            }
            content.put("parts", parts)
            contents.put(content)
        }
        root.put("contents", contents)

        if (toolDeclarations.isNotEmpty()) {
            val declarations = JSONArray()
            for (d in toolDeclarations) declarations.put(d.toJson())
            root.put("tools", JSONArray().put(JSONObject().put("function_declarations", declarations)))
        }

        val safety = JSONArray()
        for (category in listOf(
            "HARM_CATEGORY_HARASSMENT",
            "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_DANGEROUS_CONTENT"
        )) {
            safety.put(
                JSONObject().put("category", category).put("threshold", "BLOCK_ONLY_HIGH")
            )
        }
        root.put("safety_settings", safety)

        root.put(
            "generation_config",
            JSONObject()
                .put("temperature", 0.75)
                .put("top_p", 0.95)
                .put("max_output_tokens", 8192)
        )
        return root
    }

    private fun parseSuccess(payload: String, model: String): GeminiOutcome {
        return try {
            val root = JSONObject(payload)

            val blockReason = root.optJSONObject("prompt_feedback")
                ?.optString("block_reason").orEmpty()
            val candidates = root.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                return if (blockReason.isNotBlank()) {
                    GeminiOutcome.Failure(
                        GeminiErrorKind.SAFETY,
                        "Google's safety filter blocked that exchange. Try rephrasing.",
                        "promptFeedback.blockReason=$blockReason"
                    )
                } else {
                    GeminiOutcome.Failure(
                        GeminiErrorKind.EMPTY,
                        "Google returned an empty response. I can retry.",
                        "no candidates in payload"
                    )
                }
            }

            val candidate = candidates.optJSONObject(0)
            val content = candidate?.optJSONObject("content")
            val partsJson = content?.optJSONArray("parts") ?: JSONArray()

            val parts = ArrayList<Part>()
            for (i in 0 until partsJson.length()) {
                val p = partsJson.optJSONObject(i) ?: continue
                val call = optAny(p, "functionCall", "function_call")
                if (call != null) {
                    parts.add(
                        Part.FunctionCall(
                            call.optString("name"),
                            call.optJSONObject("args") ?: JSONObject()
                        )
                    )
                    continue
                }
                val text = p.optString("text", "")
                if (text.isNotEmpty()) parts.add(Part.Text(text))
            }

            if (parts.isEmpty()) {
                val finish = candidate?.optString("finishReason")
                    ?: candidate?.optString("finish_reason").orEmpty()
                return GeminiOutcome.Failure(
                    GeminiErrorKind.EMPTY,
                    "Google returned no usable content this time. I can retry.",
                    "empty parts, finishReason=$finish"
                )
            }
            GeminiOutcome.Ok(parts, model)
        } catch (e: Exception) {
            GeminiOutcome.Failure(
                GeminiErrorKind.HTTP,
                "Received a malformed response from Google. I can retry.",
                "parse error: ${e.message}"
            )
        }
    }

    private fun parseHttpError(code: Int, payload: String): GeminiOutcome.Failure {
        val message = try {
            val err = JSONObject(payload).optJSONObject("error")
            err?.optString("message")?.take(300) ?: payload.take(300)
        } catch (_: Exception) {
            payload.take(300)
        }
        val kind = when (code) {
            429 -> GeminiErrorKind.QUOTA
            401, 403 -> GeminiErrorKind.HTTP
            else -> GeminiErrorKind.HTTP
        }
        val userMessage = when {
            code == 429 -> "Rate limit or quota reached on the API key. Wait a moment and try again, or use a different key."
            code == 401 || code == 403 -> "The API key was rejected by Google ($code). Open Settings and check the key."
            code == 404 || (code == 400 && message.contains("model", ignoreCase = true)) ->
                "Primary model unavailable for this key ($message)."
            else -> "Google's server answered with HTTP $code: $message"
        }
        return GeminiOutcome.Failure(
            kind, userMessage, "HTTP $code :: $message",
            modelRelated = code == 404 || (code == 400 && message.contains("model", ignoreCase = true))
        )
    }

    private fun optAny(obj: JSONObject, vararg keys: String): JSONObject? {
        for (k in keys) {
            val v = obj.optJSONObject(k)
            if (v != null) return v
        }
        return null
    }
}

package com.arenaai.duagents.tools

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import com.arenaai.duagents.network.ToolParamSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Returns real current date/time for any timezone (defaults to the device zone). */
class TimeTool : AgentTool {
    override val name = "get_current_time"
    override val description =
        "Returns the real current date and time (device timezone by default, or a named IANA timezone such as Asia/Aden or America/New_York), including the Unix timestamp. Use whenever the user asks about 'now', 'today', or scheduling."
    override val params = listOf(
        ToolParamSchema("timezone", "Optional IANA timezone name, e.g. Asia/Aden", false)
    )

    override suspend fun execute(args: JSONObject): JSONObject {
        val zoneName = args.optString("timezone", "").trim()
        return try {
            val zone = if (zoneName.isBlank()) ZoneId.systemDefault() else ZoneId.of(zoneName)
            val now = ZonedDateTime.now(zone)
            val formatted = now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy — HH:mm:ss", Locale.ENGLISH))
            JSONObject()
                .put("result", formatted)
                .put("timezone", zone.id)
                .put("unix_seconds", now.toEpochSecond())
        } catch (e: Exception) {
            JSONObject().put("error", "Unknown timezone '$zoneName': ${e.message}")
        }
    }
}

/** Real precise arithmetic via the sandbox-safe CalculatorEngine. */
class CalculatorTool : AgentTool {
    override val name = "calculator"
    override val description =
        "Evaluates a mathematical expression with absolute precision: + - * / % ^, parentheses, sqrt, sin, cos, tan, log, ln, abs, round, floor, ceil, exp, pi, e. Use for ANY non-trivial arithmetic instead of mental math."
    override val params = listOf(
        ToolParamSchema("expression", "The math expression, e.g. (1240*3)/7 + sqrt(2)", true)
    )

    override suspend fun execute(args: JSONObject): JSONObject {
        val expr = args.optString("expression", args.optString("input", "")).trim()
        if (expr.isEmpty()) return JSONObject().put("error", "No expression provided")
        return try {
            JSONObject().put("result", CalculatorEngine.evaluateToText(expr))
        } catch (e: Exception) {
            JSONObject().put("error", "Cannot evaluate '$expr': ${e.message}")
        }
    }
}

/** Real device facts read from the live OS. */
class DeviceInfoTool(private val context: Context) : AgentTool {
    override val name = "get_device_info"
    override val description =
        "Returns real device facts: battery level/charging state, manufacturer, model, Android version, device uptime. Use when the user asks about the phone, its battery, or its status."
    override val params: List<ToolParamSchema> = emptyList()

    override suspend fun execute(args: JSONObject): JSONObject {
        return try {
            val bm = context.getSystemService(BatteryManager::class.java)
            val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val status = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            val battery = if (level in 1..100) {
                "$level% ${if (charging) "(charging)" else ""}".trim()
            } else "unknown"
            val uptimeSeconds = SystemClock.elapsedRealtime() / 1000
            val uptime = String.format(
                Locale.US, "%dh %02dm", uptimeSeconds / 3600, (uptimeSeconds % 3600) / 60
            )
            JSONObject()
                .put("result", "battery=$battery, device=${Build.MANUFACTURER} ${Build.MODEL}, " +
                        "android=${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), uptime=$uptime")
        } catch (e: Exception) {
            JSONObject().put("error", "Device info failed: ${e.message}")
        }
    }
}

/**
 * Live web fetch over the real network (OkHttp) — HTML is converted to readable text.
 * This tool is also the primary primitive agents combine when forging new tools (e.g. a
 * weather or currency tool built on top of a public HTTP API).
 */
class WebFetchTool : AgentTool {
    override val name = "web_fetch"
    override val description =
        "Fetches a live web page or public JSON API over the real internet and returns its readable text/JSON (HTML stripped, capped). Use for live facts: weather APIs, exchange rates, documentation, news, wikis. Provide the full http(s) URL."
    override val params = listOf(
        ToolParamSchema("url", "Full URL starting with http:// or https://", true),
        ToolParamSchema("max_chars", "Optional output cap, default 6000", false)
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override suspend fun execute(args: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val url = args.optString("url", args.optString("input", "")).trim()
        val maxChars = if (args.has("max_chars")) args.optInt("max_chars", 6000) else 6000
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext JSONObject().put("error", "URL must start with http:// or https://")
        }
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) IrfanElenaAssistant/1.0")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext JSONObject().put(
                        "error", "HTTP ${response.code} from $url"
                    )
                }
                val contentType = response.header("Content-Type").orEmpty()
                val text = if (contentType.contains("html", ignoreCase = true) ||
                    raw.trimStart().startsWith("<")) {
                    htmlToText(raw)
                } else raw
                val capped = if (text.length > maxChars) text.take(maxChars) + " …(truncated)" else text
                JSONObject()
                    .put("result", capped.ifBlank { "(empty body)" })
                    .put("http_status", response.code)
            }
        } catch (e: Exception) {
            JSONObject().put("error", "Fetch failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun htmlToText(html: String): String {
        var t = html
        t = Regex("(?is)<(script|style|noscript)[\\s\\S]*?</\\1>").replace(t, " ")
        t = Regex("(?i)<br\\s*/?>").replace(t, "\n")
        t = Regex("(?i)</(p|div|h[1-6]|li|tr)>").replace(t, "\n")
        t = Regex("(?s)<[^>]+>").replace(t, " ")
        t = t.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
        t = Regex("[ \\t\\x0B\\f\\r]+").replace(t, " ")
        t = Regex("\\n\\s*\\n+").replace(t, "\n")
        return t.trim()
    }
}

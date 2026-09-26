package com.arenaai.duagents.core

import android.content.Context
import com.arenaai.duagents.BuildConfig

/**
 * Abstraction over key storage — kept minimal so the network layer is testable on the JVM
 * against the LIVE Google endpoint (engineering decision: testability without Android Context).
 */
interface ApiKeyProvider {
    fun get(): String
    fun modelOverride(): String?
}

/**
 * Engineering decision: the API key has two real, functional sources —
 *  1. BuildConfig (injected at build time from the git-ignored local.properties) — preferred.
 *  2. A key entered in-app at runtime, stored in a private MODE_PRIVATE preferences file.
 * No secret is ever hardcoded in version control.
 */
class KeyVault(context: Context) : ApiKeyProvider {

    private val prefs =
        context.getSharedPreferences("duagents_secure_prefs", Context.MODE_PRIVATE)

    override fun get(): String {
        val fromBuild = BuildConfig.GEMINI_API_KEY
        if (fromBuild.isNotBlank()) return fromBuild.trim()
        return prefs.getString(KEY, null)?.trim().orEmpty()
    }

    fun has(): Boolean = get().isNotBlank()

    fun isFromBuildConfig(): Boolean = BuildConfig.GEMINI_API_KEY.isNotBlank()

    fun saveRuntimeKey(key: String) {
        prefs.edit().putString(KEY, key.trim()).apply()
    }

    override fun modelOverride(): String? {
        val v = prefs.getString(MODEL, null)?.trim().orEmpty()
        return v.ifBlank { null }
    }

    fun saveModelOverride(model: String) {
        prefs.edit().putString(MODEL, model.trim()).apply()
    }

    private companion object {
        const val KEY = "gemini_api_key"
        const val MODEL = "gemini_model_override"
    }
}

package com.dk.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * नवंबर 2026 (मालिक): DK लाइव में Z.ai / NVIDIA का विकल्प. आवाज़ Gemini Live ही बनाता है; "गहरी सोच" (deep_think)
 * वाला text जवाब - Settings → AI दिमाग → DK लाइव में Z.ai / NVIDIA की key डली हो तो पहले वहीं से (तेज़, Gemini की
 * Live limit बचती है). न चले / key नहीं = पहले जैसा Gemini ([BrainManager.think]).
 */
object LiveTextBrain {
    /** इतनी देर में जवाब नहीं = Gemini पर (बातचीत रुके नहीं) */
    const val TIMEOUT_MS = 20_000L

    const val SYSTEM = "You help a live voice assistant. Think carefully, then give only the final answer, short and clear, " +
        "in the same language and script as the question. No markdown, no lists unless needed."

    /** किस कंपनी से (पहली जिसकी key डली है). [key] = DK लाइव में उस कंपनी की key. Pure - JUnit से जाँच */
    fun pick(key: (String) -> String): String? = ApiScopes.LIVE_TEXT.firstOrNull { key(it).isNotBlank() }

    suspend fun answer(c: Context, question: String): String? {
        if (question.isBlank()) return null
        val id = pick { Prefs.scopeKey(c, ApiScopes.LIVE, it) } ?: return null
        val p = Providers.byId(id) ?: return null
        val client = OpenAiCompatClient(p.name, Prefs.scopeBase(c, ApiScopes.LIVE, id), Prefs.scopeKey(c, ApiScopes.LIVE, id),
            Prefs.scopeModel(c, ApiScopes.LIVE, id)).apply { fast = true; backups = Providers.fallbackModels(id) }
        return try {
            withTimeoutOrNull(TIMEOUT_MS) { withContext(Dispatchers.IO) { client.complete(SYSTEM, emptyList(), question, null) } }
                ?.trim()?.takeIf { it.isNotBlank() }
                ?.also { DKLog.d("deep_think: ${client.label} से जवाब") }
        } catch (e: Exception) {
            DKLog.d("deep_think: ${p.name} नहीं चला (${e.message?.take(80)}) - Gemini से"); null
        }
    }
}

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

    /**
     * नवंबर 2026 (मालिक): सिर्फ़ पहली नहीं - जिन-जिन की key डली है, सब बारी-बारी (Z.ai → NVIDIA → Groq).
     * हर API अपने फ्री मॉडल पहले आज़माती है ([Providers.fallbackModels]), सब खत्म = अगली API, फिर Gemini. Pure.
     */
    fun order(key: (String) -> String): List<String> = (ApiScopes.LIVE_TEXT + "groq").distinct().filter { key(it).isNotBlank() }

    suspend fun answer(c: Context, question: String): String? {
        if (question.isBlank()) return null
        val ids = order { Prefs.scopeKey(c, ApiScopes.LIVE, it) }
        if (ids.isEmpty()) return null
        // सब मिलाकर [TIMEOUT_MS] - बातचीत ज़्यादा न रुके
        return withTimeoutOrNull(TIMEOUT_MS) {
            for (id in ids) {
                val p = Providers.byId(id) ?: continue
                val base = Prefs.scopeBase(c, ApiScopes.LIVE, id); val model = Prefs.scopeModel(c, ApiScopes.LIVE, id)
                if (base.isBlank() || model.isBlank()) continue
                val client = OpenAiCompatClient(p.name, base, Prefs.scopeKey(c, ApiScopes.LIVE, id), model)
                    .apply { fast = true; backups = Providers.fallbackModels(id) }
                try {
                    val r = withContext(Dispatchers.IO) { client.complete(SYSTEM, emptyList(), question, null) }.trim()
                    if (r.isNotBlank()) { DKLog.d("deep_think: ${client.label} से जवाब"); return@withTimeoutOrNull r }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Exception) {
                    DKLog.d("deep_think: ${p.name} के सारे मॉडल नहीं चले (${e.message?.take(80)}) - अगली API")
                }
            }
            null
        } ?: run { DKLog.d("deep_think: Z.ai/NVIDIA/Groq नहीं चले - Gemini से"); null }
    }
}

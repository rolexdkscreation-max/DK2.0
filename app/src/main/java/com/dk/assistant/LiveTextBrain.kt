package com.dk.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    /** [deadline] = पूरे deep_think tool का आख़िरी पल ([ThinkTime]) - हर कोशिश ज़्यादा से ज़्यादा [ThinkTime.STEP_MS] */
    suspend fun answer(c: Context, question: String, deadline: Long = System.currentTimeMillis() + ThinkTime.TOTAL_MS): String? {
        if (question.isBlank()) return null
        // 20 नवंबर 2026 (मालिक): चिप में चुना मॉडल पहले; न चले = बताकर पहले जैसा रास्ता
        // 20 नवंबर 2026 (लॉग: 41 s रुकावट): पहले 20 s + blocking call नहीं रुकती थी -> अब [ThinkTime.within] (8 s में अगला)
        try { ThinkTime.within(ThinkTime.stepMs(deadline, System.currentTimeMillis())) { BrainManager(c).chipAsk(SYSTEM, question) } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { DKLog.d("deep_think: चिप वाला मॉडल नहीं चला (${e.message?.take(60)})"); null }
            ?.let { DKLog.d("deep_think: चिप के चुने मॉडल से जवाब"); return it }
        val ids = order { Prefs.scopeKey(c, ApiScopes.LIVE, it) }
        if (ids.isEmpty()) return null
        // हर API को ज़्यादा से ज़्यादा 8 s, सब मिलाकर पूरे tool का बचा समय - बातचीत ज़्यादा न रुके
        return run {
            for (id in ids) {
                val p = Providers.byId(id) ?: continue
                val base = Prefs.scopeBase(c, ApiScopes.LIVE, id); val model = Prefs.scopeModel(c, ApiScopes.LIVE, id)
                if (base.isBlank() || model.isBlank()) continue
                val client = OpenAiCompatClient(p.name, base, Prefs.scopeKey(c, ApiScopes.LIVE, id), model)
                    .apply { fast = true; backups = Providers.fallbackModels(id) }
                val ms = ThinkTime.stepMs(deadline, System.currentTimeMillis())
                if (ms <= 0L) { DKLog.d("deep_think: ${ThinkTime.TOTAL_MS / 1000} s पूरे - आगे की API नहीं"); break }
                try {
                    val r = ThinkTime.within(ms) { withContext(Dispatchers.IO) { client.complete(SYSTEM, emptyList(), question, null) } }?.trim()
                    if (r == null) { DKLog.d("deep_think: ${p.name} ने ${ms / 1000} s में जवाब नहीं दिया - अगली API"); continue }
                    if (r.isNotBlank()) { DKLog.d("deep_think: ${client.label} से जवाब"); return@run r }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Exception) {
                    DKLog.d("deep_think: ${p.name} के सारे मॉडल नहीं चले (${e.message?.take(80)}) - अगली API")
                }
            }
            null
        } ?: run { DKLog.d("deep_think: Z.ai/NVIDIA/Groq नहीं चले - Gemini से"); null }
    }
}

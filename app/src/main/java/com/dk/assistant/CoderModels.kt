package com.dk.assistant

import java.util.Locale

/**
 * 15 नवंबर 2026 (मालिक): "कोडिंग एजेंट की 5 Gemini keys की limit खत्म हो तो Groq, फिर OpenRouter, फिर Z.ai, फिर NVIDIA पर चले -
 * हर कंपनी के सिर्फ़ वे मॉडल जो कोडिंग के लिए सबसे अच्छे हों. सिर्फ़ Gemini खत्म होते ही काम न रुके."
 *
 * जड़: बाकी कंपनियों के बैकअप मॉडल पुराने तय नाम थे (जैसे NVIDIA के llama-3.3-70b / qwen3-coder-480b - अब उसकी सूची में नहीं = 404),
 * कोडिंग के हिसाब से चुने नहीं थे, और OpenRouter पर "openrouter/free" (कोई भी मॉडल) सबसे पहले.
 * अब: कंपनी की ताज़ा मॉडल सूची (Settings / होम / कोडिंग शुरू होते ही पीछे से) में से कोडिंग वाले पहले ([rank]);
 * सूची न हो तो जाने-माने ([KNOWN], 15 नवंबर 2026 की सूचियों से). OpenRouter = सिर्फ़ ":free" (पैसे न कटें), Z.ai = सिर्फ़ फ्री "flash".
 * Pure (JUnit).
 */
object CoderModels {
    /** कोडिंग एजेंट में कंपनियों का क्रम (मालिक का नियम): Gemini → Groq → OpenRouter → Z.ai → NVIDIA */
    val ORDER = listOf("gemini", "groq", "openrouter", "zai", "nvidia")

    /** चुनी कंपनी सबसे पहले, बाकी [ORDER] में */
    fun order(list: List<String>): List<String> {
        if (list.isEmpty()) return list
        val rest = list.drop(1).sortedBy { ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } }
        return (listOf(list.first()) + rest).distinct()
    }

    /** ताज़ा सूची न हो तब (console.groq.com/docs/models, openrouter.ai/api/v1/models, integrate.api.nvidia.com/v1/models - 15 नवंबर 2026) */
    val KNOWN: Map<String, List<String>> = mapOf(
        "groq" to listOf("openai/gpt-oss-120b", "qwen/qwen3.8-27b", "minimaxai/minimax-m2.7", "llama-3.3-70b-versatile", "openai/gpt-oss-20b"),
        "openrouter" to listOf("cohere/north-mini-code:free", "recursive/flint-v1.0:free", "particle-ai/particle-1:free",
            "nvidia/nemotron-3-ultra-550b-a55b:free", "google/gemma-4-31b-it:free", "openrouter/free"),
        "zai" to listOf("glm-4.7-flash", "glm-4.5-flash"),
        "nvidia" to listOf("moonshotai/kimi-k2.6", "z-ai/glm-5.3-flash", "deepseek-ai/deepseek-v4.1-flash", "minimaxai/minimax-m3.1-flash",
            "nvidia/nemotron-3-ultra-550b-a55b", "nvidia/nemotron-3-super-120b-a12b", "openai/gpt-oss-20b"),
    )

    /** कोडिंग / बातचीत के काम का नहीं */
    private val NOT_TEXT = listOf("whisper", "tts", "guard", "safeguard", "playai", "orpheus", "distil", "embed", "image", "audio",
        "transcribe", "compound", "rerank", "reward", "parse", "ocr", "retriever", "nemoretriever", "clip", "vila", "neva", "kosmos",
        "deplot", "paligemma", "fuyu", "riva", "translate", "pii", "safety", "content-safety", "nemoguard", "-vl-", "vision")

    private fun low(m: String) = m.trim().lowercase(Locale.ROOT)

    /** मॉडल के अरब parameter (नाम में "120b", "a12b" नहीं - कुल) - न मिले = 0 */
    fun sizeB(m: String): Double = Regex("""(?<![a-z])(\d+(?:\.\d+)?)b(?![a-z])""").findAll(low(m).replace(Regex("""-a\d+(?:\.\d+)?b"""), ""))
        .mapNotNull { it.groupValues[1].toDoubleOrNull() }.maxOrNull() ?: 0.0

    /** नाम का नया संस्करण (जैसे glm-5.3 > glm-4.7) */
    fun version(m: String): Double = Regex("""(\d+(?:\.\d+)?)""").find(low(m).substringAfter('/').replace(Regex("""\d+(?:\.\d+)?b"""), ""))
        ?.value?.toDoubleOrNull() ?: 0.0

    /** यह कंपनी का मॉडल कोडिंग एजेंट में चल सकता है? */
    fun usable(id: String, m: String): Boolean {
        val l = low(m)
        if (l.isBlank() || NOT_TEXT.any { l.contains(it) }) return false
        return when (id) {
            "openrouter" -> l.endsWith(":free") || l == "openrouter/free"     // पैसे न कटें
            "zai" -> l.contains("flash")                                        // Z.ai के फ्री सिर्फ़ flash
            else -> true
        }
    }

    /** कोडिंग के लिए कितना अच्छा (बड़ा = पहले) */
    fun score(m: String): Int {
        val l = low(m)
        var s = when {
            l == "openrouter/free" -> 5                                                     // कोई भी फ्री - सबसे आख़िर
            Regex("coder|devstral|codestral|-code\\b|code:|north-mini-code|flint|particle").containsMatchIn(l) -> 100
            Regex("kimi-k2|glm-5|glm-4\\.[6-9]|deepseek-v[3-9]|deepseek-r|minimax-m[2-9]|gpt-oss-120b|qwen3|nemotron-3-ultra|nemotron-ultra|qwen-3")
                .containsMatchIn(l) -> 85
            Regex("nemotron|gpt-oss|llama-4|llama-3\\.3-70b|mistral-large|gemma-4|glm-4").containsMatchIn(l) -> 65
            else -> 40
        }
        val b = sizeB(l)
        if (b in 0.1..9.9) s -= 45 else if (b in 10.0..31.9) s -= 12
        if (l.contains("starcoder") || l.contains("coder-6.7b") || l.contains("coder-1.3b")) s -= 30   // पुराने छोटे coder
        return s
    }

    /** कोडिंग वाले पहले; बराबर हों तो नया संस्करण, फिर बड़ा मॉडल */
    fun rank(id: String, models: List<String>): List<String> =
        models.map { it.trim() }.filter { usable(id, it) }.distinct()
            .sortedWith(compareByDescending<String> { score(it) }.thenByDescending { version(it) }.thenByDescending { sizeB(it) })

    /**
     * कोडिंग एजेंट में इस कंपनी का मॉडल-क्रम (पहला = मुख्य, बाकी = उसी key के बैकअप).
     * [cached] = कंपनी की ताज़ा सूची (खाली = [KNOWN]). [chosen] + [userChose] = Settings में खुद चुना मॉडल सबसे पहले.
     */
    fun chain(id: String, chosen: String, userChose: Boolean, cached: List<String>, max: Int = ApiScopes.BACKUP_MAX + 1): List<String> {
        val pool = cached.filter { it.isNotBlank() }.ifEmpty { KNOWN[id].orEmpty() }
        val ranked = rank(id, pool)
        val first = if (userChose && chosen.isNotBlank()) listOf(chosen.trim()) else emptyList()
        val out = (first + ranked).distinct().take(max)
        return out.ifEmpty { listOf(chosen.trim()).filter { it.isNotBlank() } }
    }
}

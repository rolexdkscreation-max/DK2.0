package com.dk.assistant.dkai

import kotlin.math.ln

/**
 * 28 नवंबर 2026 (मालिक: "मॉडल सेक्शन में नाम आना चाहिए DK AI, उसके बाद मॉडल का नाम... जेमिनी की जगह DK AI"):
 * सर्वर के मॉडल का दिखने वाला नाम = "DK AI" + असली मॉडल का नाम, कंपनी का नाम हटाकर (gemini-3.8-flash = "DK AI 3.8 Flash").
 * क्रम = जितना ताकतवर उतना ऊपर ([power]). असली id अंदर ही रहती है - सिर्फ़ नाम बदलता है. Pure (JUnit से जाँच).
 */
object DkAiNames {
    const val BRAND = "DK AI"

    /** कंपनी / परिवार के नाम - दिखने वाले नाम से हटते हैं */
    private val BRANDS = setOf(
        "gemini", "gemma", "google", "gpt", "openai", "meta", "llama", "mistral", "mistralai", "claude", "anthropic",
        "deepseek", "moonshotai", "moonshot", "kimi", "zai", "z", "ai", "glm", "nvidia", "nemotron", "minimaxai", "minimax",
        "grok", "xai", "x", "qwen", "alibaba", "command", "cohere", "cf", "openrouter", "microsoft", "phi", "ibm", "granite",
    )

    /** बेकार शब्द (नाम में कुछ नहीं जोड़ते) */
    private val NOISE = setOf("free", "latest", "instruct", "it", "chat", "preview", "auto", "exp", "experimental", "hf")

    /** एक जैसे नाम आएँ तो दूसरे के आगे यह (कंपनी का नाम नहीं) */
    private val TAG = mapOf(
        "groq" to "Turbo", "cloudflare" to "Edge", "openrouter" to "Nova", "nvidia" to "Prime", "mistral" to "Swift",
        "cohere" to "Atlas", "zai" to "Zen", "oneai" to "One", "gemini" to "Core", "gemini-lite" to "Lite",
        "deepseek" to "Deep", "openai" to "Astra", "anthropic" to "Sage",
    )

    /** नाम खाली बचे (जैसे openrouter/free) तो यह */
    private val EMPTY_NAME = mapOf("openrouter" to "Nova", "cloudflare" to "Edge", "oneai" to "One")

    /** कंपनी की शुरुआती ताकत (मॉडल के शब्द इसमें जुड़ते/घटते हैं) */
    private val BASE = mapOf(
        "anthropic" to 96.0, "openai" to 95.0, "deepseek" to 92.0, "gemini" to 88.0, "cohere" to 82.0, "oneai" to 80.0,
        "groq" to 78.0, "cloudflare" to 77.0, "nvidia" to 74.0, "zai" to 72.0, "mistral" to 70.0, "gemini-lite" to 66.0,
        "openrouter" to 65.0,
    )

    private val YEAR_TAIL = Regex("-\\d{2}-\\d{4}$")
    private val SIZE = Regex("^(\\d+(?:\\.\\d+)?)b$")
    private val ACTIVE = Regex("^a\\d+b$")
    private val NUM = Regex("^\\d+(?:\\.\\d+)?$")

    /** "DK AI 3.8 Flash" जैसा नाम */
    fun label(provider: String, model: String): String {
        val core = words(model).joinToString(" ") { pretty(it) }
        val name = core.ifBlank { EMPTY_NAME[provider] ?: "Core" }
        return "$BRAND $name"
    }

    /** मॉडल के बचे शब्द (कंपनी / बेकार शब्द हटाकर; "5", "5" = "5.5") */
    internal fun words(model: String): List<String> {
        var m = model.trim().lowercase().substringAfterLast('/').substringBefore(':')
        m = m.replace(YEAR_TAIL, "")
        val raw = m.split('-', '_', ' ').filter { it.isNotBlank() }
        val kept = raw.filter { it !in BRANDS && it !in NOISE && !ACTIVE.matches(it) && !(it.length == 1 && it[0].isLetter()) }
        val out = mutableListOf<String>()
        for (w in kept) {
            val prev = out.lastOrNull()
            // "minimax-m3-1" = M3.1, "sonnet-5-5" = 5.5 (वर्ज़न के टुकड़े जोड़ो)
            if (prev != null && NUM.matches(w) && !w.contains('.') && w.length <= 2 && prev.last().isDigit() && !prev.contains('.') &&
                !SIZE.matches(prev)) {
                out[out.lastIndex] = "$prev.$w"
            } else out.add(w)
        }
        return out
    }

    private fun pretty(w: String): String = when {
        SIZE.matches(w) -> w.dropLast(1) + "B"
        w.any { it.isDigit() } -> w.replaceFirstChar { it.uppercaseChar() }
        w.length <= 3 && w !in setOf("pro", "max", "air", "one") -> w.uppercase()
        else -> w.replaceFirstChar { it.uppercaseChar() }
    }

    /** ताकत का अंदाज़ा (बड़ा = ऊपर): कंपनी + मॉडल के शब्द (ultra / pro / बड़ा आकार ऊपर, lite / mini नीचे) */
    fun power(provider: String, model: String): Double {
        val t = model.lowercase().substringAfterLast('/').split('-', '_', ':', ' ', '.').filter { it.isNotBlank() }.toSet()
        fun has(vararg w: String) = w.any { it in t }
        var p = BASE[provider] ?: 70.0
        if (has("ultra", "opus")) p += 8
        if (has("pro")) p += 6
        if (has("plus", "max")) p += 4
        if (has("sonnet", "astra")) p += 3
        if (has("flash", "fast", "turbo")) p -= 4
        if (has("lite", "mini", "small", "nano", "tiny")) p -= 10
        val size = t.mapNotNull { SIZE.find(it)?.groupValues?.get(1)?.toDoubleOrNull() }.maxOrNull()
        if (size != null && size > 16) p += minOf(10.0, ln(size) / ln(2.0) - 4)
        return p
    }

    /** एक पंक्ति: असली (कड़ी, मॉडल) + दिखने वाला नाम + ताकत का दर्जा */
    data class Row(val provider: String, val model: String, val label: String, val tier: String)

    /** पूरी सूची: सबसे ताकतवर ऊपर, नाम दोहराए नहीं (दोहराव = आगे छोटा टैग) */
    fun rank(list: List<Pair<String, String>>): List<Row> {
        val sorted = list.distinct().sortedWith(compareByDescending<Pair<String, String>> { power(it.first, it.second) }
            .thenBy { label(it.first, it.second) })
        val used = mutableSetOf<String>()
        return sorted.mapIndexed { i, (p, m) ->
            var name = label(p, m)
            if (!used.add(name.lowercase())) {
                name = "$name ${TAG[p] ?: "Plus"}"
                var n = 2
                while (!used.add(name.lowercase())) { name = "${label(p, m)} ${n++}" }
            }
            Row(p, m, name, tier(i, sorted.size))
        }
    }

    /** ऊपर से नीचे: सबसे ताकतवर → तेज़ */
    fun tier(i: Int, n: Int): String {
        if (n <= 1 || i == 0) return "सबसे ताकतवर"
        val f = i.toDouble() / n
        return when {
            f < 0.34 -> "बहुत ताकतवर"
            f < 0.67 -> "ताकतवर + तेज़"
            else -> "तेज़ जवाब"
        }
    }

    // ---- चिप में सेव id: "कड़ी|मॉडल" (असली नाम अंदर)
    fun encode(provider: String, model: String): String = "$provider|$model"
    fun decode(id: String): Pair<String, String>? {
        val p = id.substringBefore('|', "").trim(); val m = id.substringAfter('|', "").trim()
        return if (p.isBlank() || m.isBlank()) null else p to m
    }
}

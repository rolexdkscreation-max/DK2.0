package com.dk.assistant

/**
 * नवंबर 2026 (मालिक): मॉडल चुनते समय सिर्फ़ "gemini-3.8-flash" जैसा id नहीं - Gemini ऐप जैसा साफ़ नाम + छोटा विवरण:
 *   3.5 Flash-Lite — सबसे तेज़ जवाब,  3.8 Flash — हर काम में मदद,  3.1 Pro — गहरी सोच,  4 Argon — मुश्किल काम.
 * कंपनी की API नाम दे (Gemini displayName, OpenRouter name) तो वही; नहीं तो id से बनाया नाम. Pure Kotlin (JUnit से जाँच).
 */
object ModelNames {

    /** API से आया नाम ([apiName]) हो तो उसे साफ़ करके, नहीं तो id से */
    fun label(id: String, apiName: String? = null): String {
        val api = apiName?.trim().orEmpty()
        if (api.isNotBlank() && !api.equals(id, true)) {
            // "Gemini 3.8 Flash" -> "3.8 Flash" (ऊपर पहले से "Google Gemini" लिखा है); "OpenAI: GPT-4o" -> "GPT-4o"
            val clean = api.removePrefix("Gemini ").substringAfter(": ").trim()
            if (clean.isNotBlank()) return clean
        }
        return fromId(id)
    }

    /** "gemini-3.5-flash-lite" -> "3.5 Flash-Lite", "gemini-3.1-pro-preview" -> "3.1 Pro (Preview)", "openai/gpt-oss-120b" -> "GPT-OSS 120B" */
    fun fromId(id: String): String {
        if (id == "openrouter/free") return "अपने-आप फ्री मॉडल"
        var s = id.trim().removePrefix("models/").substringAfterLast('/')
        val free = s.endsWith(":free"); s = s.removeSuffix(":free")
        val preview = Regex("-(preview|exp|experimental)(-[0-9-]+)?$").find(s)
        if (preview != null) s = s.substring(0, preview.range.first)
        s = s.removePrefix("gemini-")
        s = s.replace("flash-lite", "flash~lite")
        val words = s.split('-', '_', ' ').filter { it.isNotBlank() }.map { w ->
            when {
                w == "flash~lite" -> "Flash-Lite"
                w.equals("gpt", true) || w.equals("oss", true) || w.equals("llm", true) -> w.uppercase()
                Regex("^[0-9]+[bkm]$", RegexOption.IGNORE_CASE).matches(w) -> w.uppercase()
                w.first().isDigit() -> w
                else -> w.replaceFirstChar { it.uppercase() }
            }
        }
        val name = words.joinToString(" ").replace("GPT OSS", "GPT-OSS").ifBlank { id }
        return name + (if (preview != null) " (Preview)" else "") + (if (free) " • फ्री" else "")
    }

    /** छोटा विवरण - नाम से अंदाज़ा (हिंदी, साथ में English) */
    fun desc(id: String): String {
        val m = id.lowercase()
        return when {
            m == "openrouter/free" -> "जो फ्री मॉडल खाली हो, वही अपने-आप"
            m.contains("flash-lite") || m.contains("-lite") || m.contains("nano") || m.contains("instant") -> "सबसे तेज़ जवाब (Fastest answers)"
            m.contains("argon") || m.contains("ultra") || m.contains("opus") || m.contains("deep-think") -> "मुश्किल काम (Complex tasks)"
            m.contains("pro") || m.contains("thinking") || m.contains("reason") || m.contains("-r1") ||
                Regex("(^|/)o[1-9]").containsMatchIn(m) || m.contains("magistral") || m.contains("qwq") -> "गहरी सोच (Advanced reasoning)"
            m.contains("coder") || m.contains("codestral") || m.contains("devstral") || m.contains("code") -> "कोडिंग के लिए (Coding)"
            m.contains("flash") || m.contains("sonnet") || m.contains("gpt-5") || m.contains("gpt-4") -> "हर काम में मदद (All-around help)"
            m.contains("mini") || m.contains("small") || m.contains("haiku") || m.contains("gemma") ||
                Regex("[^0-9]([1-9]|1[0-9])b").containsMatchIn(m) -> "हल्का और तेज़ (Light and quick)"
            m.contains("gpt-oss") || m.contains("70b") || m.contains("120b") || m.contains("large") -> "बड़ा और समझदार (Smart)"
            else -> "आम बातचीत (General)"
        }
    }

    /** Prefs में सेव करने का तरीका: "id\tनाम" हर लाइन */
    fun encode(names: Map<String, String>): String =
        names.entries.filter { it.value.isNotBlank() }.joinToString("\n") { it.key + "\t" + it.value.replace('\n', ' ').replace('\t', ' ') }

    fun decode(raw: String): Map<String, String> =
        raw.split('\n').mapNotNull { l -> val i = l.indexOf('\t'); if (i <= 0) null else l.substring(0, i) to l.substring(i + 1) }.toMap()
}

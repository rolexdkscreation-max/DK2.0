package com.dk.assistant

/**
 * आवाज़ से DK कंप्यूटर: "डीके कंप्यूटर, ... करो" या "रिसर्च करके PDF बनाओ" जैसा काम पहचानना,
 * हाँ/नहीं (इजाज़त) और काम पूरा होने पर बोलने लायक छोटा जवाब. सब pure - unit tests में जाँचा जाता है.
 */
object ComputerVoice {
    /** null = DK कंप्यूटर का काम नहीं; open = सिर्फ़ स्क्रीन खोलनी है */
    data class Cmd(val task: String, val open: Boolean = false)

    private val NAMES = listOf("डीके कंप्यूटर", "डी के कंप्यूटर", "डीके कम्प्यूटर", "dk कंप्यूटर", "dk computer", "डीके computer",
        "कंप्यूटर से", "कम्प्यूटर से", "कंप्यूटर को", "कम्प्यूटर को", "computer se", "computer ko", "कंप्यूटर एजेंट", "computer agent")
    private val OPEN = listOf("खोलो", "खोल दो", "खोलें", "ओपन करो", "ओपन", "open", "open karo", "चालू करो", "दिखाओ")
    private val RESEARCH = listOf("रिसर्च", "research", "खोज", "सर्च", "search", "पता कर", "जानकारी", "इकट्ठा", "तुलना", "compare")
    private val OUTPUT = listOf("pdf", "पीडीएफ", "फ़ाइल", "फाइल", "file", "रिपोर्ट", "report", "टेबल", "table", "csv", "एक्सेल", "excel",
        "डॉक्यूमेंट", "document", "शीट", "sheet", "लिस्ट बना", "नोट्स")
    private val MAKE = listOf("बनाओ", "बना दो", "बनाकर", "बना के", "बनाके", "तैयार कर", "लिख दो", "लिखकर", "सेव कर", "bana", "banao", "make", "create")

    fun parse(goal: String): Cmd? {
        val low = goal.lowercase().trim()
        NAMES.firstOrNull { low.contains(it) }?.let { k ->
            val rest = goal.substring((low.indexOf(k) + k.length).coerceAtMost(goal.length))
                .trim(' ', ',', '.', '!', '।', ':', '-', '?').removePrefix("से ").removePrefix("को ").removePrefix("ने ").trim()
            val r = rest.lowercase().trim(' ', '.', '!', '।')
            return when {
                r.isEmpty() || r in OPEN -> Cmd("", open = true)
                rest.length < 4 -> Cmd("", open = true)
                else -> Cmd(rest)
            }
        }
        // बिना नाम लिए: "रिसर्च करके PDF बनाओ" जैसा = खोज + फ़ाइल/रिपोर्ट + बनाओ
        if (RESEARCH.any { low.contains(it) } && OUTPUT.any { low.contains(it) } && MAKE.any { low.contains(it) }) return Cmd(goal.trim())
        return null
    }

    private val YES = setOf("हाँ", "हां", "हा", "हाँ जी", "हां जी", "जी हाँ", "हाँ करो", "हां करो", "हाँ कर दो", "हां कर दो", "कर दो", "भेज दो",
        "हाँ भेज दो", "हां भेज दो", "ठीक है कर दो", "yes", "haan", "ha", "ok कर दो", "इजाज़त है", "अनुमति है")
    private val NO = setOf("नहीं", "नही", "ना", "मत करो", "मत भेजो", "नहीं करना", "रहने दो", "no", "nahi", "cancel", "रद्द करो")

    /** इजाज़त का जवाब: true = हाँ, false = नहीं, null = यह जवाब नहीं है */
    fun yesNo(said: String): Boolean? {
        val b = said.lowercase().trim(' ', '.', '!', '।', ',', '?')
        return when (b) { in YES -> true; in NO -> false; else -> null }
    }

    /** काम पूरा होने पर बोलने लायक: markdown/links हटाकर 1-3 वाक्य + फ़ाइलें कहाँ हैं */
    fun spoken(text: String, files: Int, question: Boolean = false, max: Int = 280): String {
        val clean = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("|") && !it.startsWith("```") && !it.startsWith("---") }
            .map {
                it.replace(Regex("\\[([^\\]]+)]\\((https?://[^)]+)\\)"), "$1").replace(Regex("https?://\\S+"), "")
                    .replace(Regex("[*_`#>]+"), "").replace(Regex("^\\s*[-•]\\s*"), "").replace(Regex("\\s+"), " ").trim()
            }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        var s = if (clean.length <= max) clean else {
            val cut = clean.take(max)
            val end = maxOf(cut.lastIndexOf('।'), cut.lastIndexOf(". "), cut.lastIndexOf('?'), cut.lastIndexOf('!'))
            if (end >= max / 3) cut.substring(0, end + 1) else cut.substringBeforeLast(' ') + "…"
        }
        if (s.isBlank()) s = if (question) "DK कंप्यूटर को आपसे कुछ जानकारी चाहिए।" else "DK कंप्यूटर ने काम पूरा कर दिया।"
        if (files > 0 && !question) s += " $files फ़ाइल बनी है, DK कंप्यूटर स्क्रीन में देखें।"
        if (question) s += " जवाब देने के लिए बोलिए: डीके कंप्यूटर, और फिर अपना जवाब।"
        return s.trim()
    }
}

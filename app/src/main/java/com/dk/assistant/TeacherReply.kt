package com.dk.assistant

/**
 * 24 नवंबर 2026 (मालिक: "टीचर मोड में टाइप / स्केच करके पूछा तो जवाब में ब्रैकेट, 'response' लिखा, फिर जवाब, फिर आख़िर में
 * ब्रैकेट - यह सब नहीं; और आवाज़ रोबोट जैसी (फ़ोन की TTS) - DK की ऑफ़लाइन आवाज़ से बोले").
 *
 * जड़: टीचर स्क्रीन `BrainManager.teach` का कच्चा जवाब सीधे बोर्ड पर लिखती और बोलती थी. कुछ मॉडल सादा जवाब
 * `{"response": "..."}` / ```json``` / `[RESPONSE]…[/RESPONSE]` / `<response>…</response>` में लपेटते हैं - बाकी ऐप
 * यह [ReplyShape] से खोलता है, टीचर नहीं खोलती थी. [WRITE] / [DRAW] टैग बोर्ड के लिए रहते हैं.
 * Android के बिना (JVM test).
 */
object TeacherReply {
    private const val WRAP = "response|reply|answer|output|result|assistant|उत्तर|जवाब"
    private val TAG = Regex("""\[\s*/?\s*($WRAP)\s*]|<\s*/?\s*($WRAP)\s*>|\(\s*/?\s*($WRAP)\s*\)""", RegexOption.IGNORE_CASE)
    /** पहली लाइन की शुरुआत का "Response:" / "**Answer:**" (सिर्फ़ अंग्रेज़ी लेबल - "उत्तर:" बोर्ड पर अपना काम करता है) */
    private val LABEL = Regex("""^\s*[*_#]*\s*(response|reply|answer|output|assistant)\s*[*_]*\s*[:：-]\s*[*_]*\s*""", RegexOption.IGNORE_CASE)
    private val WRITE = Regex("""\[/?WRITE]""", RegexOption.IGNORE_CASE)

    /** मॉडल का कच्चा जवाब -> साफ़ जवाब ([WRITE] / [DRAW] बचे रहते हैं) */
    fun clean(raw: String): String {
        var t = ReplyShape.unwrap(raw.trim())
        t = t.replace(TAG, " ")
        t = ReplyShape.unwrap(t.trim())                         // `[RESPONSE]{"response": "..."}[/RESPONSE]` जैसा दोहरा लपेट
        t = t.replace(LABEL, "")
        // पूरा जवाब एक ही जोड़ी उद्धरण / कोष्ठक में लिपटा = वह हटाओ
        t = t.trim()
        for ((a, b) in listOf("\"" to "\"", "“" to "”")) {
            val n = t.count { it.toString() == a }
            if (t.length > 2 && t.startsWith(a) && t.endsWith(b) && n == (if (a == b) 2 else 1)) t = t.substring(a.length, t.length - b.length).trim()
        }
        return t.replace(Regex("[ \\t]{2,}"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    /**
     * बोलने वाला text: [WRITE] के बाहर वाला समझाना (वह खाली हो तो [WRITE] वाले बिंदु भी), चित्र की लिखाई कभी नहीं,
     * markdown / बचे टैग / कोष्ठक-निशान नहीं.
     */
    fun spoken(clean: String): String {
        val write = Regex("""\[WRITE](.*?)\[/WRITE]""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        var s = TeacherDraw.strip(clean.replace(write, " ")).trim()
        if (s.isBlank()) s = TeacherDraw.strip(clean).replace(WRITE, " ")
        s = s.replace(WRITE, " ")
            .replace(Regex("""\[[A-Z_/ ]{2,20}]"""), " ")          // बचा कोई [TAG]
            .replace(Regex("[*#`_{}]"), "")
            .replace(Regex("""^\s*[-•]\s*""", RegexOption.MULTILINE), "")
        return s.replace(Regex("[ \\t]+"), " ").replace(Regex("\\s*\\n\\s*"), "\n").trim()
    }
}

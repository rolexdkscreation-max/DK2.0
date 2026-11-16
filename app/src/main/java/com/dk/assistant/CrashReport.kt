package com.dk.assistant

/**
 * 16 नवंबर 2026 (मालिक: "क्रैश रिपोर्ट मेरी मर्ज़ी से चालू करो").
 *
 * - DK अपने-आप कभी कुछ नहीं भेजता. crash फ़ोन में ही रहता है (last_crash.txt / crash_history.txt).
 * - Settings → गड़बड़ी रिपोर्ट → "रिपोर्ट भेजें" दबाने पर ही: छिपाई हुई (key / token / नंबर / OTP «छिपाया»)
 *   रिपोर्ट GitHub का नया-issue पन्ना खोलती है - वहाँ आप पढ़कर खुद "Submit" दबाएँ, या "शेयर करें" से जिसे चाहें.
 * - "अगली बार पूछो" ([Prefs] crash_ask, शुरू से बंद) चालू हो तो ही DK बंद होने के बाद अगली बार खुलते समय पूछता है.
 * Pure (JVM test: CrashReportTest).
 */
object CrashReport {
    const val MAX_URL_TEXT = 5000
    const val ASK_KEY = "crash_ask"
    const val ASKED_KEY = "crash_asked_at"

    /** भेजने लायक़ लेख: version + Android + छिपाया हुआ stack (बातचीत / key कुछ नहीं) */
    fun body(crash: String, version: String, sdk: Int, model: String): String = buildString {
        append("DK version: ").append(version).append('\n')
        append("Android SDK: ").append(sdk).append("  •  फ़ोन: ").append(model.take(40)).append('\n')
        append("\n```\n").append(com.dk.assistant.inspector.InspectorCore.redact(crash.trim()).take(MAX_URL_TEXT)).append("\n```\n")
        append("\n(यह रिपोर्ट DK ने मालिक के कहने पर बनाई - API key / token / फ़ोन नंबर / OTP छिपा दिए गए)")
    }

    fun title(crash: String): String {
        val first = crash.lines().drop(1).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return "Crash: " + com.dk.assistant.inspector.InspectorCore.redact(first).take(90).ifBlank { "DK बंद हुआ" }
    }

    fun issueUrl(repo: String, title: String, body: String): String {
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        return "https://github.com/$repo/issues/new?labels=crash&title=${enc(title)}&body=${enc(body.take(MAX_URL_TEXT + 400))}"
    }

    /** अगली बार खुलते समय पूछें? सिर्फ़ जब मालिक ने चालू किया हो और यह crash पहले पूछा न गया हो */
    fun shouldAsk(askOn: Boolean, crashAt: Long, askedAt: Long): Boolean = askOn && crashAt > 0 && crashAt > askedAt
}

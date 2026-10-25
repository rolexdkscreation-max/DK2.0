package com.dk.assistant

/**
 * Gemini मॉडल चुनने के नियम (Android पर निर्भर नहीं - unit test हो सकते हैं).
 *
 * Google docs (अक्टूबर 2026): 2.5 वाले मॉडल अब सिर्फ़ उन्हीं users को मिलते हैं जो पहले से इस्तेमाल कर रहे थे।
 * नए users को 404 "This model ... is no longer available to new users" मिलता है।
 * नए projects के लिए: gemini-3.5-flash-lite या gemini-3.8-flash।
 */
object GeminiModels {
    /** Coding/लंबे काम के लिए सबसे अच्छा Flash (software engineering + agents) */
    const val CODER = "gemini-3.8-flash"

    /** 1.x / 2.x = पुराने, नए users के लिए बंद */
    fun isLegacy(model: String): Boolean = Regex("^(models/)?gemini-[12]\\.").containsMatchIn(model.trim())

    /** चुना हुआ मॉडल पहले (भले पुराना हो - पुराने users के लिए चलता है), फिर backups, पर पुराने backups नहीं */
    fun order(chosen: String, backups: List<String>): List<String> =
        (listOf(chosen.trim()) + backups.map { it.trim() }.filterNot { isLegacy(it) }).filter { it.isNotBlank() }.distinct()

    /** Cooldown की चाबी: यह मॉडल इस key पर 404 "no longer available" दे चुका (phone में याद, 7 दिन) */
    fun goneKey(model: String, keyTail: String = "") = "gemini-gone:$keyTail|" + model.trim().removePrefix("models/")
    const val GONE_MS = 7 * 24 * 3_600_000L

    /**
     * जड़ (log 07:51:29 / 07:53:02 / 07:53:17): Settings में gemini-2.5-pro चुना था - हर कदम पर पहले वही, हर बार 404.
     * बंद हो चुके मॉडल क्रम से हटाओ (सब बंद हों तो पूरा क्रम, ताकि असली गलती दिखे).
     */
    fun orderSkippingGone(chosen: String, backups: List<String>, gone: (String) -> Boolean): List<String> {
        val all = order(chosen, backups)
        return all.filterNot(gone).ifEmpty { all }
    }

    /** Settings का चुना मॉडल बंद हो चुका -> नया coder मॉडल (null = बदलना नहीं) */
    fun replacementFor(chosen: String, gone: (String) -> Boolean): String? =
        if (chosen.isNotBlank() && chosen.trim() != CODER && gone(chosen.trim())) CODER else null

    /** मॉडल हटाया/बंद (अगला मॉडल आज़माओ, पर यह असली वजह नहीं) */
    fun isGone(code: Int, msg: String): Boolean =
        code == 404 || msg.contains("no longer available", true) || msg.contains("not found for API version", true)

    /** Google का \"retryDelay\": \"37s\" या \"Please retry in 37.2s\" -> सेकंड (0 = पता नहीं) */
    fun retryDelaySec(raw: String): Int {
        Regex("\"retryDelay\"\\s*:\\s*\"(\\d+)(?:\\.\\d+)?s\"").find(raw)?.let { return it.groupValues[1].toInt() }
        Regex("retry in (\\d+)(?:\\.\\d+)?\\s*s", RegexOption.IGNORE_CASE).find(raw)?.let { return it.groupValues[1].toInt() + 1 }
        return 0
    }

    /** एक बार रुककर उसी मॉडल पर दोबारा? (429 limit / 503 भीड़) - सेकंड, 0 = नहीं */
    fun waitBeforeRetry(code: Int, retryAfter: Int, maxSec: Int = 45): Int = when {
        code == 429 && retryAfter in 1..maxSec -> retryAfter
        code == 503 || code == 500 -> retryAfter.coerceIn(3, maxSec)
        else -> 0
    }

    /** सारे मॉडल fail: सबसे काम की गलती दिखाओ - "2.5 बंद है" वाली नहीं, जो असली वजह छिपा देती है */
    fun <T> best(errors: List<Pair<String, T>>, code: (T) -> Int, msg: (T) -> String): Pair<String, T>? =
        errors.firstOrNull { !isGone(code(it.second), msg(it.second)) } ?: errors.firstOrNull()

    /** आसान हिंदी वजह */
    fun hindi(code: Int, msg: String): String = when {
        code == 429 -> "Gemini की free limit (quota) अभी खत्म है (429)। थोड़ी देर बाद फिर कोशिश करें, या Settings → कोडिंग एजेंट में दूसरी key/मॉडल चुनें।"
        code == 503 || code == 500 -> "Gemini के server पर अभी भीड़ है ($code)। थोड़ी देर बाद फिर कोशिश करें।"
        isGone(code, msg) -> "यह Gemini मॉडल अब नए users के लिए बंद है। Settings में $CODER या gemini-3.5-flash-lite चुनें।"
        code == 400 && msg.contains("token", true) -> "बात बहुत लंबी हो गई (400)। कोडिंग स्क्रीन पर \"नया\" दबाकर फिर कोशिश करें।"
        else -> msg.take(200)
    }
}

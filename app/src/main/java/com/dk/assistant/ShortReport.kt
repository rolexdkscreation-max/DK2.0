package com.dk.assistant

/**
 * यूज़र को दिखने वाली छोटी रिपोर्ट - सिर्फ़ इतना:
 *   1. काम हुआ या नहीं
 *   2. API चली या नहीं
 *   3. नहीं हुआ तो वजह (एक लाइन)
 *   4. हल (एक लाइन)
 *
 * अंदर की बातें (API/मॉडल बदला, retry, request छोटी की, कौन-सा एजेंट क्या कर रहा था)
 * यहाँ कभी नहीं आतीं - वे सिर्फ़ डिबग लॉग में जाती हैं।
 * Pure Kotlin - कोई Android चीज़ नहीं, ताकि JUnit से जाँच हो सके।
 */
object ShortReport {

    enum class Cause { LIMIT, KEY, TOKEN, NETWORK, CODE, UNCLEAR, TIMEOUT, STOPPED, OTHER,
        /** GitHub पर checks अभी चल रहे - गलती नहीं, बस इंतज़ार */ WAITING }

    data class Why(val cause: Cause, val reason: String, val fix: String) {
        /** गलती API की तरफ़ से है? (तब "API: ❌") */
        val apiFault: Boolean get() = cause == Cause.LIMIT || cause == Cause.KEY || cause == Cause.NETWORK
    }

    private const val GO_ON = "फिर \"आगे करो\" बोलें - काम वहीं से शुरू होगा जहाँ रुका था"
    private const val KEY_PLACE = "Settings → AI दिमाग → कोडिंग एजेंट"

    /** कच्ची गलती (exception/लॉग) से वजह और हल - सीधी भाषा में। */
    fun why(raw: String): Why {
        val t = raw.trim()
        val l = t.lowercase()
        fun has(vararg w: String) = w.any { l.contains(it.lowercase()) }
        return when {
            t.isBlank() -> Why(Cause.OTHER, "पता नहीं चला कि काम क्यों रुका", "\"आगे करो\" बोलें - काम वहीं से फिर चलेगा")
            has("रोक दिया", "रोका गया", "cancel", "रद्द") ->
                Why(Cause.STOPPED, "काम बीच में रोका गया", "\"आगे करो\" बोलें - काम वहीं से शुरू होगा जहाँ रुका था")
            has("तय समय", "में पूरा नहीं हुआ") ->
                Why(Cause.TIMEOUT, "काम तय समय में पूरा नहीं हुआ", "\"आगे करो\" बोलें - जो हो चुका वह दोबारा नहीं होगा")
            // "Build/tests अभी चल रहे हैं" = कोड की गलती नहीं (पहले इसे "कोड की जाँच पास नहीं हुई" दिखाया जाता था)
            isWaiting(l) ->
                Why(Cause.WAITING, "GitHub पर checks (build/tests) अभी चल रहे हैं — यह कोड की गलती नहीं",
                    "checks पूरे होने दें (Emulator वाला check ~10-15 मिनट लेता है), फिर \"merge करो\" या \"रिपो चेक करो\" बोलें")
            has("github") && has("401", "403", "token", "इजाज़त", "permission", "developer settings", "bad credentials", "not found", "नहीं मिला") ->
                Why(Cause.TOKEN, "GitHub token गलत है या उसकी इजाज़त कम है",
                    "Settings में नया GitHub token डालें (Contents, Pull requests, Actions: Read and write), $GO_ON")
            has("429", "quota", "limit", "लिमिट", "कोटा", "resource_exhausted", "rate limit") ->
                Why(Cause.LIMIT, "API key की limit खत्म हो गई", "$KEY_PLACE में नई API key डालें, $GO_ON")
            has("401", "403", "api key", "api_key", "ai key", "key नहीं", "key गलत", "unauthorized", "invalid key", "permission_denied") ->
                Why(Cause.KEY, "API key गलत है या काम नहीं कर रही", "$KEY_PLACE में सही API key डालें, $GO_ON")
            has("network", "नेटवर्क", "इंटरनेट", "unable to resolve", "timeout", "timed out", "socket", "connection",
                " 500", " 502", " 503", " 504", "unavailable", "overloaded") ->
                Why(Cause.NETWORK, "इंटरनेट या AI server में अभी दिक्कत है", "इंटरनेट चालू रखें, थोड़ी देर बाद \"आगे करो\" बोलें")
            has("प्लान नहीं बना", "समझ नहीं", "साफ़ नहीं") ->
                Why(Cause.UNCLEAR, "एजेंट तय नहीं कर पाया कि ठीक-ठीक क्या बदलना है",
                    "काम थोड़ा और साफ़ बताएँ (कौन-सी स्क्रीन/बटन, क्या होना चाहिए), फिर भेजें")
            has("fail", "फ़ेल", "error", "गलती", "compile", "build", "test", "unresolved", "exception", "अटक") ->
                Why(Cause.CODE, codeReason(t), "\"आगे करो\" बोलें - एजेंट इसी गलती से आगे ठीक करेगा, पुराने कदम दोबारा नहीं")
            else -> Why(Cause.OTHER, firstLine(t), "\"आगे करो\" बोलें - काम वहीं से फिर चलेगा")
        }
    }

    /**
     * पूरी छोटी रिपोर्ट।
     * @param ok true = हो गया, false = नहीं हुआ, null = पूरा नहीं हुआ (रुका/जाँच बाकी)
     * @param problem कच्ची गलती/वजह (ok=true हो तो अनदेखी)
     * @param prUrl PR link (GitHub मोड)
     * @param github false = नॉर्मल मोड (कोड चैट में)
     */
    fun text(ok: Boolean?, problem: String = "", prUrl: String = "", github: Boolean = true): String = buildString {
        if (ok == true) {
            append("✅ काम हो गया\nAPI: ✅ ठीक चली")
            if (github && prUrl.isNotBlank()) {
                append("\nPR: ").append(prUrl)
                append("\nआगे: \"हाँ, merge करो\" बोलें तो merge करके नई APK बन जाएगी")
            } else if (!github) append("\nकोड नीचे फ़ाइल-नाम के साथ है")
            return@buildString
        }
        val w = why(problem)
        append(if (ok == null || w.cause == Cause.WAITING) "⏳ काम पूरा नहीं हुआ" else "❌ काम नहीं हुआ")
        append("\nAPI: ").append(if (w.apiFault) "❌ नहीं चली" else "✅ ठीक चली")
        append("\nवजह: ").append(w.reason)
        append("\nहल: ").append(w.fix)
        if (github && prUrl.isNotBlank()) append("\nPR: ").append(prUrl)
    }

    // ------------------------------------------------------------------ मदद

    private val waitWords = listOf("अभी चल रहे", "अभी चल रहा", "पूरे होने दें", "पूरे होने तक", "checks चल रहे", "check चल रहा",
        "still running", "in_progress", "in progress", "queued")
    private val failWords = listOf("fail", "फ़ेल", "फेल", "error", "e: ", "exception", "unresolved", "गलती")

    /** checks सिर्फ़ चल रहे हैं (कहीं fail/गलती नहीं) */
    fun isWaiting(lower: String): Boolean = waitWords.any { lower.contains(it) } && failWords.none { lower.contains(it) }

    private val noise = listOf(
        Regex("""\((आज़माए|दूसरे API|tried)[^)]*\)""", RegexOption.IGNORE_CASE),
        Regex("""(?i)\b(gemini|groq|openrouter|nvidia|deepseek|qwen|llama|mistral|gpt)[\w./:-]*"""),
    )

    /** पहली काम की लाइन - API/मॉडल के नाम हटाकर, 140 अक्षर तक। */
    fun firstLine(raw: String): String {
        var s = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
        noise.forEach { s = s.replace(it, "") }
        s = s.replace(Regex("""\s{2,}"""), " ").trim(' ', '-', ':', '—')
        return if (s.length > 140) s.take(137) + "…" else s.ifBlank { "पता नहीं चला कि काम क्यों रुका" }
    }

    private val errLine = Regex("""([\w-]+\.(?:kt|kts|java|xml|js|ts|gradle))(?::(\d+))?(?::\d+)?\s*[:\-–]?\s*(.*)""")

    /** कोड की गलती: "फ़ाइल:लाइन - बात" जितना छोटा हो सके। */
    fun codeReason(raw: String): String {
        val line = raw.lineSequence().map { it.trim() }
            .firstOrNull { (it.startsWith("e:") || it.contains("error", true)) && errLine.containsMatchIn(it) }
        if (line != null) {
            val m = errLine.find(line)!!
            val where = m.groupValues[1] + (m.groupValues[2].takeIf { it.isNotBlank() }?.let { ":$it" } ?: "")
            val what = m.groupValues[3].trim().take(90)
            return "कोड में गलती बची है - $where" + (if (what.isNotBlank()) " ($what)" else "")
        }
        return "कोड की जाँच पास नहीं हुई - " + firstLine(raw)
    }
}

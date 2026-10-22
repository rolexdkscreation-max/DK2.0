package com.dk.assistant

/**
 * ऑटो-रिट्राई और "जड़ ढूँढो → ठीक करो → फिर टेस्ट" चक्र के नियम (Android पर निर्भर नहीं - unit test हो सकते हैं).
 *
 * - API कॉल / build / test का कदम अस्थायी वजह (नेटवर्क, server 5xx, GitHub runner) से fail हो
 *   तो अपने-आप 2 बार और (कुल 3) कोशिश; तब भी fail हो तभी user को रिपोर्ट.
 * - असली code वाली गलती पर retry नहीं - बिल्डर पहले जड़ (root cause) ढूँढता है, फिर ठीक करता है, फिर दोबारा test.
 */
object Retry {
    /** कुल कोशिशें (1 + 2 ऑटो-रिट्राई) */
    const val ATTEMPTS = 3

    /** n-वीं दोबारा कोशिश से पहले रुकना: 1s, 2s, 4s … (ज़्यादा से ज़्यादा 8s) */
    fun backoffMs(retry: Int): Long = (1000L shl (retry - 1).coerceIn(0, 3))

    /**
     * यह गलती अस्थायी है (दोबारा कोशिश से ठीक हो सकती है)?
     * code: HTTP code, 0/-1 = नेटवर्क (timeout, connection reset, DNS).
     * 4xx (key गलत, इजाज़त नहीं, नहीं मिला, गलत request) पर दोबारा कोशिश बेकार - तुरंत रिपोर्ट.
     */
    fun isTransient(code: Int, msg: String = ""): Boolean = when (code) {
        0, -1, 408, 500, 502, 503, 504 -> true
        // GitHub की "secondary rate limit" कुछ सेकंड की होती है
        403 -> msg.contains("secondary rate limit", true)
        else -> false
    }

    /** नेटवर्क वाली Java गलती (OkHttp) */
    fun isNetwork(e: Throwable): Boolean = e is java.net.SocketTimeoutException || e is java.net.UnknownHostException ||
        e is java.net.ConnectException || e is java.net.SocketException || e is javax.net.ssl.SSLException ||
        (e is java.io.IOException && (e.message.orEmpty().contains("timeout", true) || e.message.orEmpty().contains("reset", true)))

    private val infraWords = listOf(
        "Connection reset", "Read timed out", "connect timed out", "Could not GET", "Could not HEAD",
        "502 Bad Gateway", "503 Service", "504 Gateway", "received a shutdown signal", "lost communication with the server",
        "ECONNRESET", "ETIMEDOUT", "Temporary failure in name resolution", "No space left on device",
        "Unable to download", "The operation was canceled"
    )
    private val codeWords = listOf("e: ", "error:", "Unresolved reference", "AssertionError", " FAILED", "Compilation error", "expected:<")

    /**
     * GitHub Actions का build/test GitHub की अपनी दिक्कत (नेटवर्क, runner बंद) से fail हुआ, code से नहीं?
     * तब code नहीं बदलना - वही job दोबारा चलानी है.
     */
    fun isInfraFailure(log: String): Boolean {
        if (log.isBlank()) return false
        val lines = log.lines()
        if (lines.any { l -> codeWords.any { l.contains(it) } && infraWords.none { l.contains(it, true) } }) return false
        return infraWords.any { log.contains(it, true) }
    }
}

/**
 * Test/build fail → जड़ ढूँढो → ठीक करो → फिर test. तब तक दोहराओ जब तक पास न हो,
 * पर अंतहीन नहीं: हर बार गलती बदल रही है (आगे बढ़ रहे हैं) तो चलते रहो;
 * वही गलती ठीक करने के बाद भी लगातार लौटे (अटक गए) या सुरक्षा-सीमा पूरी हो तो रुककर user को रिपोर्ट.
 */
object FixLoop {
    /** सुरक्षा-सीमा: इतने fix-राउंड के बाद भी fail = user को रिपोर्ट (API खर्च/limit बचाने को) */
    const val HARD_CAP = 8
    /** एक ही गलती इतनी बार लगातार = अटक गए */
    const val SAME_LIMIT = 3

    enum class Next { FIX_AGAIN, STOP_STUCK, STOP_CAP }

    /** Build log की पहचान: पहली असली गलतियाँ, line/column नंबर और समय हटाकर (ताकि "वही गलती" पहचानी जाए) */
    fun signature(log: String): String = log.lines().map { it.trim() }
        .filter { it.startsWith("e: ") || it.contains("error:", true) || it.contains("FAILED") || it.contains("AssertionError") || it.contains("Unresolved reference") }
        .take(3)
        .joinToString(" | ") { it.replace(Regex(":\\d+(:\\d+)?"), "").replace(Regex("\\(\\d+, ?\\d+\\)"), "").replace(Regex("\\d+(\\.\\d+)?\\s*(s|ms)\\b"), "") }
        .ifBlank { log.lines().lastOrNull { it.isNotBlank() }.orEmpty().trim().take(160) }

    /** history = अब तक के हर fail की signature (सबसे नई आखिर में) */
    fun next(history: List<String>, cap: Int = HARD_CAP): Next {
        if (history.size >= cap) return Next.STOP_CAP
        if (history.size >= SAME_LIMIT && history.takeLast(SAME_LIMIT).distinct().size == 1 && history.last().isNotBlank()) return Next.STOP_STUCK
        return Next.FIX_AGAIN
    }
}

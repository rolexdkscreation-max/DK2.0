package com.dk.assistant

/**
 * मालिक (09 नवंबर 2026): "अपडेट सेक्शन में डाउनलोड की जगह 504 लिखा आता है, नया अपडेट नहीं हो पाता. भविष्य में कभी गड़बड़ न हो."
 *
 * जड़: 504 = "Gateway Timeout" - GitHub के बीच वाले सर्वर (github.com -> release-assets.githubusercontent.com CDN)
 * या मोबाइल नेटवर्क के proxy ने समय पर जवाब नहीं दिया. यह थोड़ी देर की गड़बड़ है, APK ठीक है.
 * पहले DK एक ही बार कोशिश करता था, फिर "Download fail 504" लिखकर रुक जाता था.
 *
 * अब ([Updater.startDownload] + [Updater.latest]):
 *  1. ऐसी अस्थायी गलती (5xx / 408 / 429 / टूटा connection / timeout) पर खुद दोबारा, बढ़ते इंतज़ार के साथ, जहाँ रुका वहीं से (Range).
 *  2. दो रास्ते बारी-बारी से: पहले GitHub API का asset पता, फिर github.com का सीधा पता. (09 नवंबर की जाँच:
 *     github.com/.../releases/download/v637/DK.apk बार-बार "504 Gateway Time-out", उसी समय API वाला 200 / Range पर 206.)
 *  3. 416 (आधी फ़ाइल गड़बड़) = आधी फ़ाइल हटाकर शुरू से.
 *  4. पूरी फ़ाइल APK है या नहीं (शुरू में "PK") जाँच - गलती वाला वेब-पेज APK बनकर नहीं बचता.
 *  5. सब कोशिशें हारें = इंटरनेट आने पर 10 मिनट बाद अपने-आप फिर ([UpdateJob.scheduleResume]).
 *  6. स्क्रीन पर कोड नहीं, साफ़ हिंदी वजह ([friendly]).
 * (Android import नहीं -> JVM test)
 */
object UpdateRetry {
    /** एक बार "डाउनलोड करें" पर ज़्यादा से ज़्यादा इतनी कोशिशें (फिर अपने-आप बाद में) */
    const val MAX_TRIES = 6
    /** चेक (नया version है?) की कोशिशें */
    const val CHECK_TRIES = 3

    /** 0 = connection/timeout (कोड नहीं मिला) */
    fun retryable(code: Int): Boolean =
        code == 0 || code == 408 || code == 425 || code == 429 || code in 500..599

    /** n-वीं दोबारा कोशिश (1 से) से पहले इंतज़ार: 2s, 4s, 8s, 15s, 30s, 30s… */
    fun backoffMs(n: Int): Long = when {
        n <= 1 -> 2_000L
        n == 2 -> 4_000L
        n == 3 -> 8_000L
        n == 4 -> 15_000L
        else -> 30_000L
    }

    /** चेक में छोटा इंतज़ार (यूज़र स्क्रीन देख रहा है): 1.5s, 4s */
    fun checkBackoffMs(n: Int): Long = if (n <= 1) 1_500L else 4_000L

    /** इस कोशिश (0 से) में कौन सा पता - बारी-बारी, खाली/दोहराए पते हटाकर */
    fun pick(urls: List<String>, attempt: Int): String {
        val u = urls.filter { it.isNotBlank() }.distinct()
        if (u.isEmpty()) return ""
        return u[Math.floorMod(attempt, u.size)]
    }

    /** कोशिशों का क्रम: API asset पता (हो तो) पहले, फिर github.com का सीधा पता */
    fun order(direct: String, api: String): List<String> =
        listOf(api.takeIf { isApiAsset(it) }.orEmpty(), direct).filter { it.isNotBlank() }.distinct()

    /** API asset पता चाहिए तो यह header (वरना JSON आता है) */
    fun isApiAsset(url: String): Boolean = url.startsWith("https://api.github.com/") && url.contains("/releases/assets/")

    /** फ़ाइल APK (zip) है? शुरू के 4 byte "PK\u0003\u0004" */
    fun looksLikeApk(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() && head[2] == 3.toByte() && head[3] == 4.toByte()

    /** यूज़र को दिखने वाली वजह (कोड अकेला नहीं) */
    fun friendly(code: Int): String = when {
        code == 504 || code == 502 || code == 503 || code == 500 ->
            "GitHub का सर्वर अभी धीमा है ($code) - DK खुद दोबारा कोशिश करता है"
        code == 429 || code == 403 -> "GitHub ने थोड़ी देर के लिए रोका ($code) - DK कुछ देर बाद खुद फिर कोशिश करेगा"
        code == 404 -> "यह अपडेट फ़ाइल GitHub पर अभी नहीं मिली - थोड़ी देर बाद फिर चेक करें"
        code == 408 || code == 0 -> "इंटरनेट धीमा/टूटा - DK खुद दोबारा कोशिश करता है"
        code > 0 -> "GitHub ने मना किया ($code)"
        else -> "इंटरनेट नहीं मिला"
    }

    /** डाउनलोड चलते समय स्क्रीन पर: "GitHub का सर्वर धीमा (504) - दोबारा कोशिश 2/6" */
    fun retryNote(code: Int, nextTry: Int): String =
        (if (code > 0) "GitHub का सर्वर धीमा ($code)" else "इंटरनेट रुका") + " - दोबारा कोशिश $nextTry/$MAX_TRIES"
}

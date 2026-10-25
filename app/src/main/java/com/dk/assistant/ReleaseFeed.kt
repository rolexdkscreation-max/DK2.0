package com.dk.assistant

/**
 * v3.64: अपडेट-चेक का दूसरा रास्ता (Android import नहीं -> JVM test).
 *
 * समस्या: api.github.com बिना लॉगिन एक इंटरनेट पते (IP) से घंटे में सिर्फ़ 60 बार जवाब देता है. मोबाइल नेटवर्क
 * (Jio/Airtel) पर हज़ारों लोग एक ही IP बाँटते हैं, इसलिए GitHub "403 rate limit" लौटा देता था और ऐप
 * "चेक नहीं हो पाया: GitHub 403. इंटरनेट देखें" दिखाता था - नया release होते हुए भी.
 *
 * हल: API न मिले तो github.com/<repo>/releases.atom (साधारण वेब पेज वाला feed, API की सीमा नहीं) से सबसे नया
 * release + notes, और APK का सीधा पता github.com/<repo>/releases/download/<tag>/DK.apk.
 */
object ReleaseFeed {
    const val APK_NAME = "DK.apk"

    data class Entry(val tag: String, val title: String, val body: String, val page: String)

    fun feedUrl(repo: String) = "https://github.com/$repo/releases.atom"
    fun apkUrl(repo: String, tag: String) = "https://github.com/$repo/releases/download/$tag/$APK_NAME"

    /** feed का पहला (सबसे नया) release. गड़बड़ feed = null */
    fun parseAtom(xml: String): Entry? {
        val start = xml.indexOf("<entry")
        if (start < 0) return null
        val end = xml.indexOf("</entry>", start).takeIf { it > 0 } ?: return null
        val e = xml.substring(start, end)
        val page = Regex("<link[^>]*href=\"([^\"]+)\"").find(e)?.groupValues?.get(1)?.let(::unescape).orEmpty()
        val tag = page.substringAfter("/releases/tag/", "").substringBefore('"').trim()
            .ifBlank { Regex("<id>[^<]*/([^/<]+)</id>").find(e)?.groupValues?.get(1).orEmpty() }
        if (tag.isBlank() || tag.none { it.isDigit() }) return null
        val title = Regex("<title>([\\s\\S]*?)</title>").find(e)?.groupValues?.get(1)?.let(::unescape)?.trim().orEmpty()
        val content = Regex("<content[^>]*>([\\s\\S]*?)</content>").find(e)?.groupValues?.get(1).orEmpty()
        return Entry(tag, title.ifBlank { tag }, htmlToText(unescape(content)), page)
    }

    /** release का HTML -> सादा लेख (TYPE:/VERSION: लाइनें अलग-अलग लाइन पर ही रहें) */
    fun htmlToText(html: String): String {
        var s = html.replace("\r", "")
        s = s.replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|h[1-6]|li|div|ul|ol|pre|blockquote)>"), "\n")
            .replace(Regex("(?i)<li[^>]*>"), "• ")
            .replace(Regex("(?i)<hr[^>]*>"), "\n---\n")
            .replace(Regex("<[^>]+>"), "")
        s = unescape(s)
        return s.lines().map { it.trim() }.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    fun unescape(s: String): String = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&apos;", "'").replace("&nbsp;", " ").replace("&amp;", "&")

    /** API का यह जवाब मिलने पर दूसरा रास्ता (feed) आज़माओ */
    fun shouldFallback(code: Int): Boolean = code == 401 || code == 403 || code == 429 || code >= 500

    /** यूज़र को दिखने वाली साफ़ वजह (पहले सिर्फ़ "GitHub 403. इंटरनेट देखें") */
    fun friendly(code: Int): String = when {
        code == 403 || code == 429 -> "GitHub ने अभी थोड़ी देर के लिए रोक दिया (एक घंटे में बहुत बार पूछा गया). कुछ देर बाद फिर चेक करें"
        code == 401 -> "GitHub token गलत/पुराना है"
        code == 404 -> "GitHub पर release नहीं मिला"
        code >= 500 -> "GitHub अभी जवाब नहीं दे रहा ($code). थोड़ी देर बाद फिर चेक करें"
        code > 0 -> "GitHub $code"
        else -> "इंटरनेट नहीं मिला"
    }
}

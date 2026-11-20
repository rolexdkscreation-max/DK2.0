package com.dk.assistant

import android.content.Context

/**
 * 20 नवंबर 2026 (मालिक, लॉग: "Gemini key गलत है" - key #2 पर हर बार पहले कोशिश): जो Gemini key गलत निकली (400/401/403),
 * उसे याद रखो (फ़ोन पर, key नहीं - सिर्फ़ उसका hash) और 24 घंटे उस पर पहले कोशिश मत करो - Round-Robin की शुरुआत,
 * अगली बैकअप key और दौड़ (race) की सूची, तीनों में वह सबसे पीछे. Key बदली (नया hash) या फिर चली = अपने-आप भूलो.
 */
object LiveBadKeys {
    const val TTL_MS = 24 * 60 * 60 * 1000L
    private const val FILE = "dk_live_bad_keys"

    private fun name(key: String) = "h_" + key.trim().hashCode()

    fun mark(c: Context, key: String) {
        if (key.isBlank() || LiveDkServer.isServer(key)) return
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putLong(name(key), System.currentTimeMillis()).apply()
    }

    fun clear(c: Context, key: String) {
        val p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (p.contains(name(key))) p.edit().remove(name(key)).apply()
    }

    fun bad(c: Context, key: String): Boolean =
        isBad(c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getLong(name(key), 0L), System.currentTimeMillis())

    /** गलत निकलने का समय [at] (0 = कभी नहीं) - अभी भी छोड़ें? Pure */
    fun isBad(at: Long, now: Long): Boolean = at > 0L && now - at in 0 until TTL_MS

    /** यह गलती key की ही है (limit नहीं)? Pure */
    fun badKeyError(code: Int, reason: String): Boolean {
        val r = reason.lowercase()
        if (r.contains("quota") || r.contains("exhausted") || code == 429) return false
        return code == 401 || code == 403 || r.contains("api key") || r.contains("api_key") || r.contains("key गलत")
    }

    /** [start] से घूमकर पहली ठीक key; सब गलत = [start] (पहले जैसा). Pure */
    fun firstGood(start: Int, size: Int, bad: (Int) -> Boolean): Int {
        if (size <= 0) return start
        val s = start.coerceIn(0, size - 1)
        return ((s until size) + (0 until s)).firstOrNull { !bad(it) } ?: s
    }

    /** [plain] = [LiveKeys.next] का अगला; वह गलत हो तो उसके आगे की पहली ठीक key; आगे कोई ठीक नहीं = [plain] (आख़िरी कोशिश). Pure */
    fun after(plain: Int, size: Int, start: Int, bad: (Int) -> Boolean): Int {
        var n = plain
        while (n >= 0 && bad(n)) n = LiveKeys.next(n, size, start)
        return if (n >= 0) n else plain
    }

    /** दौड़ की सूची: गलत keys की जोड़ियाँ सबसे पीछे (हटाई नहीं), बाकी क्रम वैसा. Pure */
    fun <T> last(queue: List<T>, bad: (T) -> Boolean): List<T> {
        val (b, g) = queue.partition(bad)
        return g + b
    }
}

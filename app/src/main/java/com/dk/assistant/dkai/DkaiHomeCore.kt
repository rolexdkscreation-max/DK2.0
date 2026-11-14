package com.dk.assistant.dkai

/**
 * 14 नवंबर 2026 (मालिक: "Perplexity जैसा नीचे Ask anything डिब्बा"): होम के डिब्बे का हिसाब - Android के बिना (JVM टेस्ट).
 *  [+] 5 तक फोटो / फ़ाइल • [🔍 | 🖥] खोज-चैट या DK Computer • [Model ▾] • [🎤] लिखवाना (✓ = भेजो) • [〰] सीधी आवाज़ / ↑ भेजें
 */
object DkaiHomeCore {
    /** एक बार में कितनी फोटो / फ़ाइल */
    const val MAX_ATTACH = 5

    enum class InputAction { VOICE, SEND }

    /** दाईं ओर का नीला बटन: कुछ लिखा / जुड़ा हो = भेजें, नहीं तो आवाज़ से बात. लिखवाते समय भी भेजें (✓ जैसा) */
    fun inputAction(hasText: Boolean, hasFiles: Boolean, dictating: Boolean): InputAction =
        if (hasText || hasFiles || dictating) InputAction.SEND else InputAction.VOICE

    enum class SendRoute { CHAT, COMPUTER, COMPUTER_FILES }

    /** फोटो / फ़ाइल जुड़ी = हमेशा DK Computer (वही पढ़ / देख सकता है); 🖥 चुना = DK Computer; नहीं तो चैट */
    fun sendRoute(computer: Boolean, files: Int): SendRoute = when {
        files > 0 -> SendRoute.COMPUTER_FILES
        computer -> SendRoute.COMPUTER
        else -> SendRoute.CHAT
    }

    /** पहले से लिखा + बोला हुआ (बीच में एक जगह) */
    fun joinDictation(base: String, spoken: String): String {
        val b = base.trimEnd(); val s = spoken.trim()
        return when { b.isEmpty() -> s; s.isEmpty() -> b; else -> "$b $s" }
    }

    /** नई चुनी जोड़ो - दोहराव नहीं, [max] से ज़्यादा नहीं. वापस: (रखी, छोड़ी गई गिनती) */
    fun <T> addUpTo(cur: List<T>, add: List<T>, max: Int = MAX_ATTACH): Pair<List<T>, Int> {
        val out = ArrayList(cur.distinct())
        var over = 0
        for (x in add) { if (x in out) continue; if (out.size >= max) over++ else out.add(x) }
        return out to over
    }
}

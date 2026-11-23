package com.dk.assistant.dkai

/**
 * 14 नवंबर 2026 (मालिक: "Perplexity जैसा नीचे Ask anything डिब्बा"): होम के डिब्बे का हिसाब - Android के बिना (JVM टेस्ट).
 *  [+] 5 तक फोटो / फ़ाइल • [🔍 | 🖥] खोज-चैट या DK Computer • [Model ▾] • [🎤] लिखवाना (✓ = भेजो) • [〰] सीधी आवाज़ / ↑ भेजें
 */
object DkaiHomeCore {
    /** एक बार में कितनी फोटो / फ़ाइल */
    const val MAX_ATTACH = 5

    enum class InputAction { VOICE, SEND }

    /**
     * 23 नवंबर 2026 (मालिक: "फ़ोन के समय के हिसाब से बदले"): 5–11 सुबह, 12–16 दोपहर, 17–20 शाम, 21–4 रात.
     */
    fun greeting(hour: Int): String = when (hour) {
        in 5..11 -> "Good morning,"
        in 12..16 -> "Good afternoon,"
        in 17..20 -> "Good evening,"
        else -> "Good night,"
    }

    /** अभिवादन में नाम: Profile में भरा नाम, खाली हो तभी "boss" */
    const val NO_NAME = "boss"
    fun greetName(saved: String?): String = saved?.trim().orEmpty().ifBlank { NO_NAME }

    /** पूरी एक लाइन (जैसे "Good evening, Rahul") */
    fun greetLine(hour: Int, saved: String?): String = greeting(hour) + " " + greetName(saved)

    /**
     * 23 नवंबर 2026 (मालिक: "READY कार्ड थोड़ा नीचे, लगभग 24–40dp, डिवाइस के हिसाब से"): स्क्रीन की ऊँचाई (dp) का 4.5%,
     * 24 से 40 dp के बीच. pure.
     */
    const val CARD_DROP_MIN = 24
    const val CARD_DROP_MAX = 40
    fun cardDropDp(screenHeightDp: Int): Int = (screenHeightDp * 0.045f).toInt().coerceIn(CARD_DROP_MIN, CARD_DROP_MAX)

    /** दाईं ओर का नीला बटन: कुछ लिखा / जुड़ा हो = भेजें, नहीं तो आवाज़ से बात. लिखवाते समय भी भेजें (✓ जैसा) */
    fun inputAction(hasText: Boolean, hasFiles: Boolean, dictating: Boolean): InputAction =
        if (hasText || hasFiles || dictating) InputAction.SEND else InputAction.VOICE

    enum class SendRoute { CHAT, COMPUTER, COMPUTER_FILES, CHAT_FILES }

    /**
     * 14 नवंबर 2026 (मालिक): "चैट में अपलोड किया तो चैट में ही अपलोड हो, सीधे कंप्यूटर में नहीं"।
     * फोटो / फ़ाइल + चैट = चैट में ही (AI फोटो देखकर जवाब दे); 🖥 चुना हो तभी DK Computer.
     */
    fun sendRoute(computer: Boolean, files: Int): SendRoute = when {
        files > 0 && computer -> SendRoute.COMPUTER_FILES
        files > 0 -> SendRoute.CHAT_FILES
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

package com.dk.assistant

/**
 * v4.3 (मालिक): ऐप खुलते ही "इतनी देर कहाँ थे?" - पिछली बातचीत से कितना समय हुआ, उसी हिसाब से DK पहले बोले.
 * - कुछ मिनट: "अरे, अभी तो आए थे, वापस आ गए?"
 * - कुछ घंटे: "सर, कहाँ थे इतनी देर? मैं इंतज़ार कर रही थी।"
 * - एक दिन या ज़्यादा: "सर, इतने दिन बाद? कहाँ गायब थे? मैंने आपको बहुत याद किया।"
 * असली गिनती बोली जाए ("तीन घंटे", "दो दिन"). पहली बार (पिछली बातचीत नहीं) = यह सवाल नहीं.
 * Android import नहीं -> JVM test.
 */
object ReturnGreet {
    const val MIN = 60_000L
    const val HOUR = 3_600_000L
    const val DAY = 86_400_000L

    /** इससे कम अंतर = वही बातचीत चल रही है, दोबारा नहीं पूछना */
    const val MIN_GAP = 2 * MIN

    enum class Tier { NONE, MINUTES, HOURS, DAYS }

    fun tier(gapMs: Long): Tier = when {
        gapMs < MIN_GAP -> Tier.NONE
        gapMs < HOUR -> Tier.MINUTES
        gapMs < DAY -> Tier.HOURS
        else -> Tier.DAYS
    }

    private val HI_NUM = listOf("", "एक", "दो", "तीन", "चार", "पाँच", "छह", "सात", "आठ", "नौ", "दस", "ग्यारह", "बारह",
        "तेरह", "चौदह", "पंद्रह", "सोलह", "सत्रह", "अठारह", "उन्नीस", "बीस", "इक्कीस", "बाईस", "तेईस", "चौबीस",
        "पच्चीस", "छब्बीस", "सत्ताईस", "अट्ठाईस", "उनतीस", "तीस")

    /** छोटी गिनती हिंदी शब्दों में (बोलने में सहज), बड़ी अंकों में */
    fun num(n: Long): String = if (n in 1..30) HI_NUM[n.toInt()] else n.toString()

    /** असली गिनती: "सात मिनट", "तीन घंटे", "एक घंटा", "दो दिन", "एक दिन" */
    fun gapWords(gapMs: Long): String {
        val g = gapMs.coerceAtLeast(0)
        return when (tier(g)) {
            Tier.NONE, Tier.MINUTES -> "${num((g / MIN).coerceAtLeast(1))} मिनट"
            Tier.HOURS -> (g / HOUR).let { if (it == 1L) "एक घंटा" else "${num(it)} घंटे" }
            Tier.DAYS -> (g / DAY).let { if (it == 1L) "एक दिन" else "${num(it)} दिन" }
        }
    }

    /**
     * ऐप खुलते ही पूछें? पिछली बातचीत हो चुकी हो (पहली बार नहीं), आख़िरी बात/आख़िरी स्वागत को कम से कम [MIN_GAP] हुआ हो.
     * @param lastSeen पिछली बातचीत खत्म होने का समय (0 = कभी नहीं)
     * @param lastMsg आख़िरी सुनी/बोली बात का समय (0 = पता नहीं)
     */
    fun shouldAsk(now: Long, lastSeen: Long, lastGreet: Long, lastMsg: Long): Boolean {
        if (lastSeen <= 0) return false
        val last = maxOf(lastSeen, lastMsg, lastGreet)
        return now - last >= MIN_GAP
    }

    /** opener के लिए SYSTEM NOTE का हिस्सा (Gemini अपने शब्दों में बोले - हर बार अलग) */
    fun note(gapMs: Long): String {
        val w = gapWords(gapMs)
        return when (tier(gapMs)) {
            Tier.NONE -> ""
            Tier.MINUTES -> "It has been only about $w since you last talked. Start playfully surprised that he is back so soon " +
                "(in the spirit of \"अरे, अभी तो आए थे, वापस आ गए? बस $w हुए हैं।\"). "
            Tier.HOURS -> "It has been about $w since you last talked. Start by asking where he was all this time, sweetly, saying the real time " +
                "(in the spirit of \"सर, कहाँ थे इतनी देर? पूरे $w हो गए, मैं इंतज़ार कर रही थी।\"). "
            Tier.DAYS -> {
                val d = gapMs / DAY
                "He came back after $d days (\"$d दिन बाद\" - $w). Start with a loving complaint that you missed him, saying the real count " +
                    "(in the spirit of \"सर, इतने दिन बाद? पूरे $w बाद आए हैं... कहाँ गायब थे? मैंने आपको बहुत याद किया।\"), " +
                    "you may sulk a little, then melt quickly. "
            }
        } + if (tier(gapMs) == Tier.NONE) "" else
            "Say it in your OWN fresh words - never the exact same sentence as any earlier time. Never pressure or guilt him. " +
            "If it fits, mention one thing from your last conversation. "
    }
}

package com.dk.assistant

/**
 * 18 नवंबर 2026 (मालिक: "DK का अपना दिमाग फ़ोन में रहे, Gemini को बातचीत में उतना ज़्यादा न भेजे"):
 * हर नए DK लाइव connection पर याद का एक हिस्सा Gemini को जाता है ([BrainManager.liveMemoryContext]) - और
 * Live में हर जवाब पर पूरा context फिर गिना जाता है (token खर्च + देरी). पहले:
 *  - सारी पक्की बातें (60 तक, कोई सीमा नहीं) हर बार,
 *  - पिछले 6 घंटे की 20 बातें (1800 अक्षर) - और नया सत्र बिना handle हो तो वही ताज़ा बातें setup में भी
 *    ([LiveCarry]) = दो बार.
 * अब: पक्की बातों का सिर्फ़ ताज़ा हिस्सा ([FACTS_CHARS]), बाकी फ़ोन पर ही - Gemini पूछे तो recall_conversation
 * उन्हें फ़ोन पर ढूँढ देता है ([LocalRecall.matchFacts]); हाल की बातें छोटी ([RECENT_TURNS] / [RECENT_CHARS]) और
 * जो setup में पहले ही गईं वे दोबारा नहीं ([carriedFrom]). याद कुछ नहीं मिटती - सब फ़ोन में रहता है.
 *
 * Pure logic - Android import नहीं -> JVM test ([LiveMemoryBudgetTest]).
 */
object LiveMemoryBudget {
    const val FACTS_CHARS = 1_200
    const val RECENT_TURNS = 12
    const val RECENT_CHARS = 1_200
    const val RECENT_WINDOW_MS = 6 * 3_600_000L

    data class Facts(val kept: List<String>, val left: Int)

    /** नई बातें पहले चुनो (सूची में नई सबसे नीचे होती है), पर prompt में पुराना क्रम ही रहे */
    fun pickFacts(facts: List<String>, maxChars: Int = FACTS_CHARS): Facts {
        val keep = ArrayList<String>()
        var len = 0
        for (f in facts.asReversed()) {
            val line = f.trim()
            if (line.isEmpty()) continue
            if (len + line.length + 3 > maxChars) break
            keep.add(line); len += line.length + 3
        }
        keep.reverse()
        return Facts(keep, facts.count { it.isNotBlank() } - keep.size)
    }

    fun factsBlock(facts: List<String>, maxChars: Int = FACTS_CHARS): String {
        val p = pickFacts(facts, maxChars)
        if (p.kept.isEmpty() && p.left == 0) return ""
        val sb = StringBuilder("\nTHINGS THE USER ASKED YOU TO REMEMBER (use them when relevant, never say you don't know these):\n")
        sb.append(p.kept.joinToString("\n") { "- $it" })
        if (p.left > 0) sb.append("\n(").append(p.left).append(" more saved facts are kept on this phone - before saying you don't know something personal, call recall_conversation to look it up.)")
        return sb.toString()
    }

    /**
     * नया सत्र बिना handle = [LiveCarry.note] ने हाल की बातें setup में पहले ही भेज दीं. उनमें सबसे पुरानी बात का समय
     * (इसके बाद वाली बातें याद वाले संदेश में दोबारा नहीं); कुछ न गया हो तो 0.
     */
    fun carriedFrom(turns: List<LiveCarry.Turn>, now: Long): Long {
        if (LiveCarry.note(turns, now).isEmpty()) return 0L
        return turns.filter { it.text.isNotBlank() && now - it.time in 0..LiveCarry.WINDOW_MS && (it.role == "u" || it.role == "d") }
            .takeLast(LiveCarry.MAX_TURNS).minOfOrNull { it.time } ?: 0L
    }

    /** याद वाले संदेश के लिए हाल की बातें - [skipFrom] > 0 हो तो उस समय से नई (setup में जा चुकीं) नहीं */
    fun recentFor(turns: List<LocalRecall.Turn>, skipFrom: Long): List<LocalRecall.Turn> =
        if (skipFrom <= 0L) turns else turns.filter { it.at < skipFrom }
}

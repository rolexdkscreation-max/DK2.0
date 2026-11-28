package com.dk.assistant

/**
 * 27 नवंबर 2026 (मालिक: "DK एक ही सवाल का जवाब 3-4 बार देता है, मेरी बात सही से नहीं सुनता,
 * जो पहले बोला वही दोहराता रहता है"). जड़ के तीन कारण, तीनों का हिसाब यहाँ (Android import नहीं -> unit test):
 *
 * 1. बचाव में दोबारा भेजी बात: Gemini ने सुना पर 3.5s में जवाब शुरू नहीं किया (Google Search जैसे काम में
 *    अक्सर 4-8s लगते हैं) -> फ़ोन वही बात text / आवाज़ में दोबारा भेजता है -> Gemini दोनों का जवाब देता है।
 *    [onResent] के बाद एक जवाब पूरा हो गया और आपने कुछ नया नहीं बोला = अगला जवाब दोहराव ([dupReply])।
 * 2. पिछला जवाब फिर से शुरू: नया जवाब पिछले किसी जवाब जैसा ही शुरू हो (पहले [CHECK_WORDS] शब्द) और आपने
 *    "फिर से / दोबारा" नहीं कहा = वहीं रोको ([isRepeat])।
 * 3. अपनी ही आवाज़ की गूँज: DK का बोलना खत्म होते ही उसी की बात (स्पीकर से) Gemini को "आपकी बात" लगती है
 *    और वह उस पर जवाब देने लगता है ([isEcho])।
 */
class LiveRepeat(private val keepMs: Long = KEEP_MS, private val max: Int = MAX_KEEP) {
    private class Reply(val words: List<String>, val user: String, val at: Long)
    private val recent = ArrayDeque<Reply>()
    @Volatile private var resentAt = 0L
    @Volatile private var doneAfterResend = 0L
    @Volatile private var cut = false

    /** पिछला जवाब खत्म - अगले जवाब की शुरुआत फिर से जाँचो */
    @Synchronized fun onReplyStart() { cut = false }

    /** फ़ोन ने आपकी बात (text / आवाज़) Gemini को दोबारा भेजी */
    @Synchronized fun onResent(now: Long) { resentAt = now; doneAfterResend = 0L }

    /** आपकी नई बात / नया SYSTEM NOTE / tool का जवाब = अगला जवाब दोहराव नहीं */
    @Synchronized fun onInput() { resentAt = 0L; doneAfterResend = 0L }

    /** जवाब पूरा हुआ - याद रखो (अगला जवाब इसी जैसा शुरू हो तो दोहराव) */
    @Synchronized fun onReplyDone(reply: String, now: Long, user: String = "") {
        if (resentAt > 0L && now - resentAt < RESEND_WINDOW_MS && doneAfterResend == 0L && reply.isNotBlank()) doneAfterResend = now
        val w = words(reply)
        if (w.size < CHECK_WORDS) return
        recent.addLast(Reply(w, user, now))
        while (recent.size > max) recent.removeFirst()
    }

    /** दोबारा भेजी बात का जवाब आ चुका, उसके बाद आपने कुछ नया नहीं कहा ([userSpokeAt] = आपकी आख़िरी असली आवाज़) - यह दूसरा जवाब उसी बात का है (एक बार ही true) */
    @Synchronized fun dupReply(now: Long, userSpokeAt: Long = 0L): Boolean {
        val dup = doneAfterResend > 0L && now - doneAfterResend < DUP_WINDOW_MS && userSpokeAt <= doneAfterResend
        if (dup || (resentAt > 0L && now - resentAt > RESEND_WINDOW_MS)) { resentAt = 0L; doneAfterResend = 0L }
        return dup
    }

    /** अभी बन रहा जवाब ([soFar]) पिछले किसी जवाब की ही दोहराई है? (आपने [user] में दोबारा नहीं माँगा) */
    @Synchronized fun isRepeat(soFar: String, user: String, now: Long): Boolean {
        if (cut || asksAgain(user)) return false
        val a = words(soFar)
        if (a.size < CHECK_WORDS) return false
        while (recent.isNotEmpty() && now - recent.first().at > keepMs) recent.removeFirst()
        val head = a.take(CHECK_WORDS)
        // आपने वही सवाल फिर पूछा (समय, मौसम...) = वही जवाब ठीक है; नई बात पर पुराना जवाब = दोहराव
        val hit = recent.any { same(head, it.words) && !sameAsk(user, it.user) }
        cut = true                     // हर जवाब की शुरुआत एक ही बार जाँचते हैं
        return hit
    }

    companion object {
        const val CHECK_WORDS = 8
        const val KEEP_MS = 180_000L
        const val MAX_KEEP = 4
        const val RESEND_WINDOW_MS = 45_000L
        const val DUP_WINDOW_MS = 25_000L
        const val ECHO_MS = 6_000L
        private val AGAIN = Regex(
            "फिर\\s*से|फिरसे|दोबारा|दुबारा|एक\\s*बार\\s*और|क्या\\s*(कहा|बोला|बोले|कहे)|सुनाई\\s*नहीं|समझ\\s*नहीं|" +
                "repeat|again|phir\\s*se|dobara|dubara|kya\\s*(kaha|bola)|samajh\\s*nahi|پھر\\s*سے|دوبارہ",
            RegexOption.IGNORE_CASE,
        )

        const val RESCUE_KEEP_MS = 20_000L

        /** बचाव में भेजा text [RESCUE_KEEP_MS] के अंदर का हो तो वही (सत्र टूटा = नए connection पर दोबारा), वरना "" */
        fun rescueFor(text: String, at: Long, now: Long): String =
            if (at > 0L && now - at in 0..RESCUE_KEEP_MS) text.trim() else ""

        /** Live को भेजा संदेश नई बात है? (text / tool का जवाब / clientContent - आवाज़ के टुकड़े नहीं) */
        fun isInput(m: String): Boolean =
            m.startsWith("{\"realtimeInput\":{\"text\"") || m.startsWith("{\"toolResponse\"") || m.startsWith("{\"clientContent\"")

        fun asksAgain(user: String): Boolean = AGAIN.containsMatchIn(user)

        private val SPLIT = Regex("[^\\p{L}\\p{M}\\p{N}]+")
        private val LETTER = Regex("[\\p{L}\\p{M}]")
        private val DIGIT = Regex("\\p{N}")

        /** शब्द (अंक भी - "5 बजकर 10" और "5 बजकर 20" अलग जवाब हैं) */
        fun words(t: String): List<String> =
            t.lowercase().split(SPLIT).filter { LETTER.findAll(it).count() >= 2 || DIGIT.containsMatchIn(it) }

        /** दोनों बार आपने लगभग वही पूछा? (खाली = पता नहीं -> नहीं) */
        internal fun sameAsk(now: String, before: String): Boolean {
            if (BargeGate.tokens(now).isEmpty() || BargeGate.tokens(before).isEmpty()) return false
            return BargeGate.isEcho(now, before) && BargeGate.isEcho(before, now)
        }

        /** पहले 8 शब्दों में से 6 उसी जगह मिले (अंक सब वही), या ये 8 शब्द पिछले जवाब में ज्यों-के-त्यों हों */
        internal fun same(head: List<String>, prev: List<String>): Boolean {
            if (prev.size < head.size) return false
            // अंक अलग (समय / गिनती / दाम) = अलग जवाब
            if (head.indices.any { head[it] != prev[it] && (DIGIT.containsMatchIn(head[it]) || DIGIT.containsMatchIn(prev[it])) }) return false
            val pos = head.indices.count { head[it] == prev[it] }
            if (pos * 4 >= head.size * 3) return true
            return (" " + prev.joinToString(" ") + " ").contains(" " + head.joinToString(" ") + " ")
        }

        /**
         * DK अभी-अभी ([msSinceDk] ms पहले) चुप हुआ और Gemini ने जो "आपकी बात" सुनी वह DK के पिछले जवाब की ही है
         * (कम से कम 4 शब्द - "हाँ / ठीक है" जैसी छोटी असली बात कभी नहीं रोकते) = गूँज
         */
        fun isEcho(heard: String, lastDk: String, msSinceDk: Long): Boolean {
            if (msSinceDk < 0 || msSinceDk > ECHO_MS || asksAgain(heard)) return false
            if (BargeGate.tokens(heard).size < 4) return false
            return BargeGate.isEcho(heard, lastDk)
        }
    }
}

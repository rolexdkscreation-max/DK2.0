package com.dk.assistant

/**
 * नवंबर 2026 (मालिक: "कोडिंग एजेंट 1 घंटे से ऊपर, फिर भी काम नहीं हुआ - इतना स्लो क्यों?").
 *
 * जड़ (रिपो की जाँच, feature/dk-control-center-feature-api-1111-1932: branch बनी, 2.5 घंटे तक एक भी commit नहीं):
 * टीम हर कदम पर एक के बाद एक कई AI कॉल करती है - कोडर (ढूँढ/पढ़ + लिखना) -> रिव्यूअर (AI) -> गलती हो तो फिर दोनों.
 * हर कॉल में बड़ी फ़ाइलें + पूरा spec + टीम की याद (Gemini पर 500k अक्षर तक) जाती है = हर जवाब में देर,
 * और free key की हर-मिनट वाली limit (429) जल्दी खत्म होकर 12 मिनट तक का इंतज़ार. ऊपर से टीम की याद बदलते ही
 * मैनेजर एक और AI कॉल (सारांश) करता था. push सब चरण पूरे होने के बाद ही - इसलिए GitHub पर कुछ दिखता भी नहीं.
 *
 * इलाज (गुणवत्ता की पक्की जाँचें वैसी ही):
 *  1. [quickPass]: छोटा, साफ़ FIND/REPLACE बदलाव जो बिना-AI वाली सारी पक्की जाँच (FIND एक ही जगह, syntax, नाम सच में मौजूद,
 *     कोई शर्त उल्टी/सुरक्षा हटी नहीं) पास कर चुका = AI रिव्यू की अलग कॉल नहीं. बड़ा/नई फ़ाइल/पूरी फ़ाइल/शक वाला = पहले जैसा AI रिव्यू.
 *     आख़िर में build + सारे tests + QA वैसे ही चलते हैं.
 *  2. [briefByAi]: टीम की याद का AI सारांश ज़्यादा से ज़्यादा हर [BRIEF_AI_EVERY_MS] में एक बार; बीच में बिना-AI वाला ताज़ा छोटा रूप.
 *  3. [Clock]: हर AI कॉल का समय गिनो - धीमी कॉल पर स्क्रीन पर बताओ, आख़िर में "समय कहाँ गया" (अगली बार वजह साफ़).
 * Pure Kotlin -> JUnit.
 */
object CoderSpeed {
    /** इससे छोटा (बदली लाइनें, FIND+REPLACE दोनों तरफ़ की) = छोटा बदलाव */
    const val QUICK_LINES = 24
    /** एक कदम में इतने FIND/REPLACE तक */
    const val QUICK_PAIRS = 2
    /** मैनेजर का AI सारांश इतने समय में एक बार से ज़्यादा नहीं */
    const val BRIEF_AI_EVERY_MS = 10 * 60_000L
    /** इससे लंबी AI कॉल = स्क्रीन पर बताओ */
    const val SLOW_CALL_MS = 60_000L

    /**
     * AI रिव्यू छोड़ा जा सकता है? (सिर्फ़ तब जब बिना-AI वाली जाँच [Seven.check] + नाम-जाँच पहले ही पास हो चुकी हो)
     * pairs = (FIND, REPLACE); fullFile = पूरी फ़ाइल/नई फ़ाइल; unsureNames = जिन नामों का पक्का सबूत नहीं मिला.
     */
    fun quickPass(pairs: List<Pair<String, String>>, fullFile: Boolean, fileExists: Boolean, unsureNames: Int,
                  flips: Int, droppedGuards: Int, attempt: Int): Boolean {
        if (fullFile || !fileExists) return false            // नई फ़ाइल / पूरी दोबारा लिखी = AI रिव्यू ज़रूरी
        if (attempt > 1) return false                        // एक बार वापस आ चुका = अब AI ध्यान से देखे
        if (unsureNames > 0 || flips > 0 || droppedGuards > 0) return false
        if (pairs.isEmpty() || pairs.size > QUICK_PAIRS) return false
        val lines = pairs.sumOf { (f, r) -> changed(f, r) }
        return lines in 1..QUICK_LINES
    }

    /** FIND -> REPLACE में सच में बदली लाइनें (एक जैसी लाइनें नहीं गिनीं) */
    fun changed(find: String, replace: String): Int {
        val a = find.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val b = replace.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val common = a.toMutableList()
        var same = 0
        for (l in b) if (common.remove(l)) same++
        return (a.size - same) + (b.size - same)
    }

    /** AI से नया सारांश बनवाना है? (नहीं = बिना-AI वाला छोटा रूप) */
    fun briefByAi(cacheBlank: Boolean, lastAiAt: Long, now: Long): Boolean =
        cacheBlank || lastAiAt <= 0L || now - lastAiAt >= BRIEF_AI_EVERY_MS

    /** एक काम का समय-हिसाब (thread-safe नहीं चाहिए: टीम एक के बाद एक चलती है) */
    class Clock(private val now: () -> Long = System::currentTimeMillis) {
        val startedAt = now()
        var calls = 0; private set
        var aiMs = 0L; private set
        var slowest = 0L; private set
        var skippedReviews = 0; private set
        var skippedBriefs = 0; private set

        fun start(): Long = now()
        /** @return कॉल का समय (ms) */
        fun done(t0: Long): Long {
            val d = (now() - t0).coerceAtLeast(0)
            calls++; aiMs += d; if (d > slowest) slowest = d
            return d
        }
        fun reviewSkipped() { skippedReviews++ }
        fun briefSkipped() { skippedBriefs++ }

        /** आख़िर की एक लाइन: कुल समय, AI में कितना, कितनी कॉल, कितनी बचीं */
        fun report(): String {
            val total = (now() - startedAt).coerceAtLeast(0)
            val other = (total - aiMs).coerceAtLeast(0)
            return "⏱ कुल ${mins(total)}: AI के जवाब ${mins(aiMs)} ($calls कॉल, सबसे लंबी ${secs(slowest)}), " +
                "build/tests/GitHub/limit का इंतज़ार ${mins(other)}" +
                (if (skippedReviews + skippedBriefs > 0) " • बचाई कॉल: ${skippedReviews + skippedBriefs} (छोटे साफ़ बदलाव का AI रिव्यू $skippedReviews, सारांश $skippedBriefs)" else "")
        }
    }

    fun secs(ms: Long) = "${(ms + 500) / 1000}s"
    fun mins(ms: Long): String {
        val s = (ms + 500) / 1000
        return if (s < 60) "${s}s" else "${s / 60} मिनट ${s % 60}s"
    }

    /** धीमी कॉल की लाइन (स्क्रीन पर) - null = तेज़ थी */
    fun slowNote(who: String, ms: Long): String? =
        if (ms < SLOW_CALL_MS) null else "⏳ $who का AI जवाब ${secs(ms)} में आया (बड़ा अनुरोध / API धीमी या limit पर रुकी)"
}

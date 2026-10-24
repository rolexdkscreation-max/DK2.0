package com.dk.assistant

/**
 * Live शुरू होने की देरी + STT देरी के छोटे फ़ैसले (Android पर निर्भर नहीं -> JVM unit test).
 *
 * जड़ (log 07:51:36): Live चालू करने पर wake word वाला loop hear() में अटका रहता था. वहाँ से आई
 * background की बात ("अल्लाह लिमे कालिका...") Live command मान ली जाती थी और Gemini Live जुड़ता ही नहीं था.
 */
object LiveStart {
    /** Live चालू हुआ और पुराना (wake word वाला) loop चल रहा है -> उसे रोककर नया loop (Gemini Live तुरंत) */
    fun restartLoop(loopActive: Boolean, wasLive: Boolean): Boolean = loopActive && !wasLive

    /**
     * hear() शुरू होते समय Live बंद था, खत्म होने तक चालू हो गया = यह बात Live से पहले की (wake mode की) है.
     * इसे Live command मत मानो - Gemini Live खुद सुनेगा.
     */
    fun staleHeard(liveBefore: Boolean, liveNow: Boolean): Boolean = !liveBefore && liveNow
}

/**
 * जड़ (log 07:52:58, STT 27878 ms): Groq का callTimeout 22s था, उसके बाद ही Gemini STT. Caption के पास
 * text पहले से था, पर तभी लिया जाता था जब STT पूरा fail हो.
 */
object SttTiming {
    /** इतनी देर में STT न आए और caption के पास साफ़ शब्द हों -> caption का text (सामान्य STT 0.4-2.7s) */
    const val CAPTION_FALLBACK_MS = 3_000L

    /** Groq Whisper का कुल समय: 4s + हर 5s आवाज़ पर 1s, ज़्यादा से ज़्यादा 9s (पहले 22s) */
    fun groqTimeoutMs(audioMs: Long): Long = (4_000L + audioMs.coerceAtLeast(0) / 5).coerceIn(4_000L, 9_000L)

    /** Gemini STT (backup) का कुल समय: Groq से 3s ज़्यादा, ज़्यादा से ज़्यादा 12s */
    fun geminiTimeoutMs(audioMs: Long): Long = (groqTimeoutMs(audioMs) + 3_000L).coerceAtMost(12_000L)

    private val WS = Regex("\\s+")
    private val LETTER = Regex("\\p{L}")
    private fun words(t: String) = t.split(WS).filter { w -> LETTER.findAll(w).count() >= 2 }

    /** caption का text STT की जगह लेने लायक? (कम से कम 2 असली शब्द) */
    fun captionUsable(t: String): Boolean = words(t.trim()).size >= 2

    /** "DK" के आम caption रूप (Settings वाले wake words के अलावा) */
    val WAKE_VARIANTS = listOf("डीके", "दीके", "डी के", "दी के", "डिके", "दिके", "डी.के", "dk", "d k", "d.k", "deke", "dee kay")

    /**
     * Wake mode: caption ने साफ़ बात (3+ शब्द) सुनी और उसमें DK का नाम नहीं -> Groq को मत भेजो
     * (हर background बात 25s तक Groq पर जाती थी = देरी + limit खर्च). Caption खाली/छोटा = STT तय करे.
     */
    fun skipWakeStt(caption: String, wakeWords: List<String>): Boolean {
        val low = caption.lowercase().trim()
        if (words(low).size < 3) return false
        val flat = low.replace(" ", "").replace(".", "")
        val all = (wakeWords + WAKE_VARIANTS).map { it.lowercase().trim() }.filter { it.isNotEmpty() }
        return all.none { w -> low.contains(w) || flat.contains(w.replace(" ", "").replace(".", "")) }
    }
}

/**
 * जड़ (log 07:51:50): Live models की list हर app-start पर network से (8s timeout) - connect से पहले 3-4s.
 * Cache सिर्फ़ memory में था. अब phone में save: पुरानी list से तुरंत जुड़ो, नई list पीछे से.
 */
object LiveModelCache {
    /** इतनी पुरानी list = ताज़ा, दोबारा मत माँगो */
    const val FRESH_MS = 3_600_000L
    /** इससे पुरानी list भरोसे लायक नहीं - रुककर नई माँगो */
    const val MAX_MS = 7 * 24 * 3_600_000L

    fun encode(keyHash: Int, at: Long, models: List<String>): String = "$keyHash|$at|" + models.joinToString(",")

    fun decode(s: String): Triple<Int, Long, List<String>>? {
        val p = s.split('|')
        if (p.size != 3) return null
        val h = p[0].toIntOrNull() ?: return null
        val at = p[1].toLongOrNull() ?: return null
        val list = p[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return if (list.isEmpty()) null else Triple(h, at, list)
    }

    /** "use" = तुरंत इस्तेमाल, "refresh" = इस्तेमाल + पीछे से नई, "fetch" = अभी network से */
    fun plan(cache: Triple<Int, Long, List<String>>?, keyHash: Int, now: Long): String = when {
        cache == null || cache.first != keyHash || cache.third.isEmpty() -> "fetch"
        now - cache.second < 0 || now - cache.second > MAX_MS -> "fetch"
        now - cache.second < FRESH_MS -> "use"
        else -> "refresh"
    }
}

package com.dk.assistant.voice

/**
 * नवंबर 2026 (मालिक): फ़ोन लॉक / अनलॉक और "DK किधर हो" - बिना इंटरनेट, बिना API, बिना Gemini Live.
 * मालिक हर कमांड का पूरा वाक्य 5 बार बोलकर सिखाता है ("हे डीके, फोन अनलॉक कर दो"). सुनते समय पूरी बात की
 * आवाज़-बनावट (MFCC, [WakeMatch.features]) सिखाए नमूनों से DTW पर मिलाई जाती है - text बनता ही नहीं, ~20-60 ms.
 *
 * pure logic (Android नहीं) -> JVM test ([VoiceCmdMatchTest]). Android वाला हिस्सा [VoiceCmds].
 *
 * लॉक / अनलॉक के वाक्य बहुत मिलते-जुलते ("लॉक" बनाम "अनलॉक") -> [pick] तभी कमांड देता है जब सबसे पास वाला
 * बाकियों से साफ़ आगे हो ([MARGIN]); पक्का न हो = कुछ नहीं (पुराना इंटरनेट वाला रास्ता बैकअप).
 */
object VoiceCmdMatch {
    /** @param ownerOnly true = सिर्फ़ मालिक की आवाज़ पर (आवाज़-पहचान ज़रूरी) */
    enum class Cmd(val id: String, val hi: String, val say: String, val ownerOnly: Boolean) {
        LOCK("lock", "फ़ोन लॉक", "हे डीके, फोन लॉक कर दो", true),
        UNLOCK("unlock", "फ़ोन अनलॉक", "हे डीके, फोन अनलॉक कर दो", true),
        FIND("find", "DK किधर हो (फ़ोन बजाओ)", "हे डीके, किधर हो तुम", false),
        FOUND("found", "मिल गया (बजना बंद)", "हे डीके, मिल गया", false);

        companion object { fun of(id: String?): Cmd? = values().firstOrNull { it.id == id } }
    }

    const val TAKES = 5
    /** पूरा वाक्य: "हे डीके, मिल गया" (~0.8 s) से "हे डीके, फोन अनलॉक कर दो" (~2.5 s) तक; धीमा बोलें तो 5 s */
    const val MIN_MS = 500
    const val MAX_MS = 5_000
    /** सिखाते समय: बोले हुए फ़्रेम (10 ms) */
    const val MIN_FRAMES = 40
    const val MAX_FRAMES = 480
    /** सबसे पास वाला बाकियों से कम से कम इतना आगे (अनुपात) - वरना पक्का नहीं */
    const val MARGIN = 0.85f
    /** "Hey DK" और कमांड के बीच रुककर बोला (दो टुकड़े) - पिछला टुकड़ा इतनी देर तक साथ जोड़कर भी देखो */
    const val JOIN_GAP_MS = 1_500L

    /** एक कमांड के सिखाए नमूने. पूरी बात बनाम पूरा नमूना (बंद DTW - "लॉक" के आगे की बात भी गिनी जाए) */
    class Model(val templates: List<Array<FloatArray>>, val base: Float) {
        fun threshold(s: WakeMatch.Sense) = base * s.factor

        /** (सबसे पास, दूसरा सबसे पास) */
        fun distances(f: Array<FloatArray>): Pair<Float, Float> {
            if (f.isEmpty()) return Float.MAX_VALUE to Float.MAX_VALUE
            val d = templates.map { WakeMatch.dtw(it, f) }.sorted()
            return d.first() to (d.getOrNull(1) ?: d.first())
        }

        /** सीमा के अंदर = दूरी/सीमा (कम = पक्का), वरना null */
        fun ratio(f: Array<FloatArray>, s: WakeMatch.Sense): Float? {
            val (a, b) = distances(f)
            val th = threshold(s)
            return if (a <= th && b <= th * 1.15f) a / th else null
        }

        /** सीमा से बाहर भी - सिर्फ़ तुलना के लिए */
        fun rawRatio(f: Array<FloatArray>, s: WakeMatch.Sense): Float = distances(f).first / threshold(s)

        fun toJson(): String = WakeMatch.Model(templates, base).toJson()

        companion object {
            fun fromJson(s: String?): Model? = WakeMatch.Model.fromJson(s)?.let { Model(it.templates, it.base) }

            /** हर नमूने की बाकी से सबसे कम दूरी (leave-one-out) -> उनमें सबसे बड़ी = base */
            fun build(templates: List<Array<FloatArray>>): Model? {
                val ts = templates.filter { it.isNotEmpty() }
                if (ts.size < 3) return null
                val loo = ts.indices.map { i -> ts.indices.filter { it != i }.minOf { WakeMatch.dtw(ts[i], ts[it]) } }
                if (loo.any { !it.isFinite() || it == Float.MAX_VALUE || it <= 0f }) return null
                return Model(ts, loo.max())
            }
        }
    }

    /** सीखते समय: बाकियों से बहुत अलग नमूना (गलत वाक्य / शोर) -> index, या -1 */
    fun outlier(templates: List<Array<FloatArray>>): Int {
        if (templates.size < 3) return -1
        val loo = templates.indices.map { i -> templates.indices.filter { it != i }.minOf { WakeMatch.dtw(templates[i], templates[it]) } }
        val sorted = loo.sorted(); val median = sorted[sorted.size / 2]
        val worst = loo.indices.maxBy { loo[it] }
        return if (loo[worst] > median * 1.6f || loo[worst] == Float.MAX_VALUE) worst else -1
    }

    class Pick(val cmd: Cmd, val ratio: Float)

    /**
     * सुनी पूरी बात [f] किस कमांड की है. पक्का न हो (दो कमांड पास-पास) = null.
     * @param models सिर्फ़ चालू कमांड
     */
    fun pick(models: Map<Cmd, Model>, f: Array<FloatArray>, s: WakeMatch.Sense): Pick? {
        if (f.isEmpty() || models.isEmpty()) return null
        val raw = models.mapValues { (_, m) -> m.rawRatio(f, s) }
        val best = models.entries.mapNotNull { (c, m) -> m.ratio(f, s)?.let { Pick(c, it) } }.minByOrNull { it.ratio } ?: return null
        val rival = raw.filterKeys { it != best.cmd }.values.minOrNull()
        if (rival != null && best.ratio > rival * MARGIN) return null
        return best
    }

    /** दो कमांड के नमूने आपस में इतने पास कि DK गड़बड़ा सकता है (सिखाते समय चेतावनी) */
    fun confusable(a: Model, b: Model): Boolean {
        val cross = a.templates.minOf { x -> b.templates.minOf { y -> WakeMatch.dtw(x, y) } }
        return cross <= maxOf(a.base, b.base) * WakeMatch.Sense.NORMAL.factor
    }

    // ------------------------------------------------------------------ text वाला बैकअप (इंटरनेट से लिखी बात)

    private val FIND = listOf(
        "किधर हो", "किधर हैं", "किधर है तू", "कहाँ हो", "कहां हो", "कहा हो", "कहाँ हैं आप", "कहां हैं आप",
        "kidhar ho", "kidhar hai tu", "kahan ho", "kaha ho", "where are you", "मुझे नहीं दिख रहे", "तुम नहीं दिख रहे",
        // नवंबर 2026 (मालिक): "DK नहीं दिख रहे हो" - नाम हटने के बाद सिर्फ़ यही बचता है
        "नहीं दिख रहे", "नही दिख रहे", "nahi dikh rahe",
    )
    private val FOUND = listOf("मिल गया", "मिल गए", "मिल गई", "मिल गये", "बंद करो", "बस करो", "रुक जाओ", "mil gaya", "stop")

    private fun norm(t: String) = t.lowercase().replace("\u093C", "").replace('ं', 'ँ').replace(Regex("[,.!?।\\s]+"), " ").trim()

    /** "DK किधर हो" / "कहाँ हो तुम" (DK को कही बात - नाम पहले ही हटा/जाँचा) */
    fun findText(text: String): Boolean {
        val t = norm(text)
        if (t.isEmpty() || t.length > 60) return false
        return FIND.any { t.contains(norm(it)) }
    }

    /** बजते समय: "मिल गया" / "बंद करो" */
    fun foundText(text: String): Boolean {
        val t = norm(text)
        if (t.isEmpty() || t.length > 40) return false
        return FOUND.any { t.contains(norm(it)) }
    }
}

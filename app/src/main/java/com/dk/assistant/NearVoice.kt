package com.dk.assistant

/**
 * v4.7 (मालिक: "दूर की आवाज़ न सुने, फ़ोन के पास धीरे भी बोलूँ या ज़ोर से - सुन ले").
 * पहले दूर की आवाज़ की रोक सिर्फ़ "सिर्फ़ मेरी आवाज़" (आवाज़ सिखाना) के साथ चलती थी - वह हट गया तो रोक भी बंद थी.
 * अब बिना सिखाए: आपकी असली (शब्दों वाली) बातों का स्तर अपने आप सीखो (आख़िरी [keep] का मध्य मान) और
 * उससे [FAR_FACTOR] गुना (−14 dB) से धीमी आवाज़ पर "बोलना शुरू" नहीं.
 * हिसाब: पास धीरे बोलना ≈ −10 dB (पास होती है), 3-4 मीटर दूर आम आवाज़ ≈ −20 dB (रुकती है).
 * Android import नहीं -> unit test.
 */
class NearVoice(private val keep: Int = 15) {
    private val levels = ArrayDeque<Double>()

    /** एक पूरी बात का स्तर (बोलते टुकड़ों की RMS का मध्य मान) */
    @Synchronized fun learn(level: Double) {
        if (level <= 0 || !level.isFinite()) return
        levels.addLast(level.coerceAtMost(MAX_LEVEL))
        while (levels.size > keep) levels.removeFirst()
    }

    /** आपकी आम पास वाली आवाज़ (0 = अभी नहीं पता) */
    @get:Synchronized val level: Double
        get() = if (levels.size < MIN_SAMPLES) 0.0 else levels.sorted()[levels.size / 2]

    /** इससे धीमी आवाज़ = दूर की (0 = रोक बंद) */
    fun floor(): Double = (level * FAR_FACTOR).coerceAtMost(MAX_FLOOR)

    @Synchronized fun save(): String = levels.joinToString(",") { it.toLong().toString() }

    @Synchronized fun load(s: String) {
        levels.clear()
        s.split(',').mapNotNull { it.trim().toDoubleOrNull() }.filter { it > 0 }.takeLast(keep).forEach { levels.addLast(it.coerceAtMost(MAX_LEVEL)) }
    }

    companion object {
        const val FAR_FACTOR = 0.2
        const val MIN_SAMPLES = 3
        const val MAX_LEVEL = 12_000.0
        const val MAX_FLOOR = 1_200.0

        /** एक बात के टुकड़ों से उसका स्तर: बोलने वाले (शोर से साफ़ ऊपर) टुकड़ों का मध्य मान; बहुत कम हों तो 0 */
        fun utterLevel(rms: List<Double>, noise: Double): Double {
            val s = rms.filter { it > maxOf(noise * 2.0, 150.0) }.sorted()
            return if (s.size < 8) 0.0 else s[s.size / 2]
        }
    }
}

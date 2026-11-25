package com.dk.assistant

/**
 * 25 नवंबर 2026 (मालिक: "वीडियो चल रहा है तो DK मेरी बात नहीं सुनता - वीडियो भी चले और मेरी बात भी सुने").
 * जड़: वीडियो/गाना बजते समय [PhoneAudioGuard] हर आवाज़ रोकता था, और सिर्फ़ तब छोड़ता जब caption में DK का नाम आए.
 * caption बंद/धीमा हो या आप नाम न लें = आपकी बात भी "फ़ोन की आवाज़" मानकर अनसुनी.
 * अब दूसरा रास्ता: फ़ोन के पास से बोली आपकी आवाज़ speaker से आती वीडियो की आवाज़ से साफ़ ज़्यादा तेज़ होती है.
 * वीडियो चलते समय माइक का आम स्तर ([Floor]) नापो; नई आवाज़ उससे [RATIO] गुना (+8 dB) ऊपर, इंसानी (VAD) और
 * आपके सीखे पास वाले स्तर ([NearVoice]) के करीब = आपकी बात, नाम के बिना भी. वीडियो रुकता नहीं.
 * "सिर्फ़ DK बोलने पर सुनो" चालू हो तो पहले जैसा (नाम ज़रूरी). Android import नहीं -> JVM test.
 */
object VideoTalk {
    /** वीडियो के आम स्तर से इतना गुना तेज़ = पास से बोली आवाज़ */
    const val RATIO = 2.5
    /** इतने टुकड़े (40 ms) सुनकर फ़ैसला (~0.5 s) */
    const val NEED_CHUNKS = 12
    /** इंसानी आवाज़ की औसत संभावना (VAD) कम से कम */
    const val MIN_VAD = 0.55f
    /** आपके सीखे पास वाले स्तर का कम से कम इतना हिस्सा */
    const val NEAR_PART = 0.45
    /** माइक का स्तर इससे कम = चुप/शोर, फ़ैसले लायक नहीं */
    const val MIN_RMS = 300.0

    /** वीडियो बजते समय माइक का आम स्तर: आख़िरी ~6 s के टुकड़ों का मध्य मान */
    class Floor(private val keep: Int = 150) {
        private val v = ArrayDeque<Double>()
        @Synchronized fun add(rms: Double) {
            if (!rms.isFinite() || rms < 0) return
            v.addLast(rms); while (v.size > keep) v.removeFirst()
        }
        @Synchronized fun level(): Double = if (v.size < 25) 0.0 else v.sorted()[v.size / 2]
        @Synchronized fun clear() = v.clear()
    }

    /**
     * रोकी हुई नई आवाज़ आपकी है? [held] = उसके टुकड़ों की RMS, [base] = आवाज़ शुरू होने से पहले वीडियो का स्तर,
     * [near] = आपकी सीखी पास वाली आवाज़ (0 = अभी नहीं पता), [vadAvg] = औसत VAD (-1 = VAD नहीं).
     */
    fun isUser(held: List<Double>, base: Double, near: Double, vadAvg: Float): Boolean {
        if (held.size < NEED_CHUNKS || base <= 0) return false
        val med = held.sorted()[held.size / 2]
        if (med < MIN_RMS || med < base * RATIO) return false
        if (vadAvg >= 0f && vadAvg < MIN_VAD) return false
        return near <= 0 || med >= near * NEAR_PART
    }
}

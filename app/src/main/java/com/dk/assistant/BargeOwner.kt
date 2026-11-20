package com.dk.assistant

/**
 * 20 नवंबर 2026 (मालिक, लॉग: "DK नहीं रुका: इंसानी आवाज़ (VAD 1.00) पर rms=5263 < सीमा 14015 (गूँज=5378)"):
 * जड़ - DK बोलते समय "यूज़र है?" की सीमा ([BargeDetector.threshold]) गूँज और DK की अपनी आवाज़ से बनती है; speaker पर
 * आपकी आवाज़ (5263) DK की गूँज (5378) जितनी ही आती है = सिर्फ़ आवाज़ के ज़ोर (rms) से आप और गूँज अलग नहीं होते.
 * पक्का फ़र्क आवाज़ पहचान (वॉइस गार्डियन) देती है: DK की अपनी (बनाई) आवाज़ मालिक से कभी नहीं मिलती.
 * अब: VAD > [VAD_P] और इंसानी आवाज़ [NEAR_MS] तक = उसी आवाज़ का मिलान (अलग thread). मालिक निकला = [FRESH_MS] तक सीमा
 * गूँज के हिसाब से नीची ([threshold]). रोकना फिर भी पहले जैसा पक्के शब्दों पर ([BargeGate]) - DK अपनी आवाज़ से नहीं रुकता.
 * Android import नहीं - JVM test.
 */
object BargeOwner {
    /** इससे पक्की इंसानी आवाज़ पर ही मिलान */
    const val VAD_P = 0.9f
    /** इतनी देर लगातार इंसानी आवाज़ (मिलान को कम से कम ~0.5 s आवाज़ चाहिए) */
    const val NEAR_MS = 600L
    /** एक मिलान के बाद अगला इतनी देर बाद (CPU / आवाज़ बनाना धीमा न हो) */
    const val RETRY_MS = 1_500L
    /** मालिक मिला = इतनी देर तक नीची सीमा */
    const val FRESH_MS = 2_500L
    /** मिलान के लिए इतने chunk (40 ms) तक की आवाज़ */
    const val MAX_CHUNKS = 25
    /** मालिक की पक्की आवाज़ पर सीमा: गूँज x0.9 / शोर x2 / न्यूनतम x0.5 */
    const val ECHO_X = 0.9
    const val AMBIENT_X = 2.0
    const val MIN_X = 0.5

    /** अभी मिलान करें? (पहचान चालू, पक्की आवाज़, काफ़ी देर, पिछला चल नहीं रहा, थोड़ी देर हो चुकी) */
    fun wantCheck(on: Boolean, prob: Float, nearMs: Long, busy: Boolean, sinceTryMs: Long): Boolean =
        on && prob >= VAD_P && nearMs >= NEAR_MS && !busy && sinceTryMs >= RETRY_MS

    fun matched(score: Float?, th: Float): Boolean = score != null && score >= th

    /** मालिक का मिलान अभी ताज़ा है? */
    fun fresh(at: Long, now: Long): Boolean = at > 0L && now - at in 0..FRESH_MS

    /** मालिक की पक्की आवाज़ वाली सीमा (गूँज के हिसाब से) */
    fun threshold(echo: Double, ambient: Double, minRms: Double): Double =
        maxOf(echo * ECHO_X, ambient * AMBIENT_X, minRms * MIN_X)
}

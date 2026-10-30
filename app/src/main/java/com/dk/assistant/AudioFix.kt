package com.dk.assistant

import android.media.audiofx.LoudnessEnhancer

/**
 * v3.6: speaker पर DK लाइव की आवाज़ (बिना Bluetooth) - owner: "फ़ोन की आवाज़ जितनी नहीं, कम लगती है".
 *  - माइक "कॉल वाले" तरीके (गूँज हटाना) में खुला हो तो कई फ़ोन speaker थोड़ा धीमा कर देते हैं + Gemini की आवाज़
 *    खुद थोड़ी धीमी आती है -> DK की अपनी आवाज़ पर +6 dB (LoudnessEnhancer, तेज़ हिस्से दबाकर - फटती नहीं).
 *  - Bluetooth headset पर boost नहीं (कान के पास, कॉल वाली आवाज़ पहले से ठीक).
 *  - पीछे शोर पर DK पहले 30% हो जाता था (हर पंखे/TV पर आवाज़ गिरती लगती थी) -> 60%.
 */
object SpeakerBoost {
    const val GAIN_MB = 600
    const val SUSPECT_VOLUME = 0.6f

    fun gainFor(bluetooth: Boolean): Int = if (bluetooth) 0 else GAIN_MB

    fun attach(session: Int, bluetooth: Boolean): LoudnessEnhancer? {
        val g = gainFor(bluetooth)
        if (g <= 0 || session <= 0) return null
        return try { LoudnessEnhancer(session).apply { setTargetGain(g); enabled = true } }
        catch (e: Throwable) { DKLog.d("आवाज़ boost नहीं लगा: ${e.message}"); null }
    }

    fun release(e: LoudnessEnhancer?) { try { e?.enabled = false; e?.release() } catch (_: Throwable) {} }
}

/** v3.6: कॉल पर DK घंटी धीमी करता है (आपकी बात सुनने को) - कॉल ख़त्म/DK बंद हो जाए तो हर हाल में वापस */
object RingGuard {
    const val CHECK_MS = 15_000L
    /** घंटी अभी बज रही / बात चल रही = नहीं; वरना वापस */
    fun shouldRestore(callActive: Boolean): Boolean = !callActive
}

/** v3.6: पीछे का गाना/वीडियो कब धीमा - सिर्फ़ जब सच में कुछ बज रहा हो, और ज़्यादा से ज़्यादा 60s (भूल-चूक में) */
object DuckRule {
    const val MAX_HOLD_MS = 60_000L
    fun shouldDuck(musicActive: Boolean): Boolean = musicActive
}

/** v3.6: DK लाइव का "बोलना शुरू" दरवाज़ा (इससे धीमी आवाज़ = Gemini को खामोशी जाती थी, वह सुनता ही नहीं था) */
object LiveHear {
    // v4.2 (मालिक: "एकदम धीरे से भी बोलूँ तो सुन ले") - दरवाज़ा और नीचे; शोर को बोलना मानने से steadyReset + server VAD + caption बचाते हैं
    const val START_FACTOR = 1.7      // पहले 2.0x (उससे पहले 2.5x) शोर
    const val LOUD_FACTOR = 1.5       // तेज़ शोर/गाने में (पहले 1.7x)
    const val START_MIN = 150.0       // पहले 220 (उससे पहले 300) RMS
    /** बोलते समय धीमे होते शब्द (वाक्य का आख़िरी हिस्सा) को चुप्पी न मानो - पहले 1.6x / 220 */
    const val KEEP_FACTOR = 1.4
    const val KEEP_MIN = 140.0
    /** धीमी आवाज़ को Gemini तक तेज़ करना: पहले max 6 गुना, शोर 450 तक */
    const val BOOST_MAX = 10.0
    const val BOOST_NOISE_CAP = 700.0
    /** v4.7: बात के अपने स्तर का इतना गुना (−18 dB) से धीमा = बात खत्म (माइक का शोर "बोलना" न लगे) */
    const val TAIL_FACTOR = 0.12
    /** दूर/धीमी आवाज़ की रोक सिर्फ़ "सिर्फ़ मालिक की आवाज़" के साथ */
    fun farFloor(ownerOn: Boolean, floor: Double): Double = if (ownerOn) floor else 0.0
}

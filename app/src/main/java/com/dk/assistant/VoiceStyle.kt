package com.dk.assistant

/**
 * 18 नवंबर 2026 (मालिक: "सेटिंग में डालो - ज़ोर से बोलने वाले, धीरे बोलने वाले, या दूर रहकर धीरे बोलें फिर भी DK सुन ले"):
 * Settings → सुनना (माइक) → "आप कैसे बोलते हैं". Android import नहीं = पूरा unit test.
 *
 *  - [NORMAL] आम: जैसा पहले चलता था (हर गुणक 1.0).
 *  - [SOFT] धीरे बोलता हूँ: "बोलना शुरू" का दरवाज़ा नीचे, माइक की आवाज़ ज़्यादा तेज़, बीच में टोकना आसान, ठहराव थोड़ा लंबा.
 *  - [LOUD] ज़ोर से बोलता हूँ: दरवाज़ा ऊपर (पंखा / TV / गूँज कम पकड़े), DK गलती से कम रुके, माइक कम तेज़.
 *  - [FAR] दूर से / धीरे-धीरे: सबसे संवेदनशील, वाक्यों के बीच लंबे ठहराव पर भी बात खत्म नहीं, "दूर की आवाज़ अनसुनी" अपने-आप बंद.
 */
enum class VoiceStyle(
    val key: String,
    val label: String,
    /** "बोलना शुरू / अभी बोल रहा" वाले दरवाज़े का गुणक ([TurnDetector.styleScale]) */
    val startScale: Double,
    /** DK बोलते समय टोकने की सीमा का गुणक ([BargeDetector.scale] / [SpeakerBarge.scale]) */
    val bargeScale: Double,
    /** "बात खत्म" के लिए इतनी ज़्यादा चुप्पी (ms) ([TurnDetector.endExtraMs]) */
    val endExtraMs: Int,
    /** धीमी आवाज़ को तेज़ करने की सीमा का गुणक ([MicBoost.gainScale]) */
    val gainScale: Double,
    /** false = "दूर की आवाज़ अनसुनी" इस अंदाज़ में बंद */
    val farFilterOk: Boolean,
) {
    NORMAL("normal", "आम (डिफ़ॉल्ट) — जैसा अभी चलता है", 1.0, 1.0, 0, 1.0, true),
    SOFT("soft", "धीरे बोलता हूँ — धीमी आवाज़ भी पकड़े", 0.75, 0.8, 150, 1.5, true),
    LOUD("loud", "ज़ोर से बोलता हूँ — शोर/गूँज पर कम ध्यान, गलती से कम रुके", 1.3, 1.25, 0, 0.6, true),
    FAR("far", "दूर से / धीरे-धीरे बोलता हूँ — सबसे संवेदनशील, लंबे ठहराव पर भी इंतज़ार", 0.6, 0.7, 350, 1.8, false);

    companion object {
        const val PREF = "voice_style"

        fun of(key: String?): VoiceStyle = entries.firstOrNull { it.key == key } ?: NORMAL

        /** हर बार चलने वाली छोटी log लाइन */
        fun describe(s: VoiceStyle): String =
            "आवाज़ का अंदाज़: ${s.key} (दरवाज़ा x${s.startScale}, टोकना x${s.bargeScale}, ठहराव +${s.endExtraMs} ms, माइक x${s.gainScale})"
    }
}

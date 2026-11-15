package com.dk.assistant.voice

import com.dk.assistant.plus.models.ModelCatalog

/**
 * 15 नवंबर 2026 (मालिक, हिस्सा 2): वॉइस गार्डियन के pure नियम - JVM test ([VoiceGuardianTest]).
 * मॉडल APK में नहीं - [ModelCatalog] के RedimNet (ONNX) इंटरनेट से, filesDir में.
 */
object VgRules {
    /** दो विकल्प: हल्का (कम CPU) और सटीक */
    val MODELS: List<ModelCatalog.Asset> = listOf(ModelCatalog.REDIMNET_LIGHT, ModelCatalog.REDIMNET_HEAVY)
    val DEFAULT_MODEL = ModelCatalog.REDIMNET_LIGHT.id

    fun model(id: String?): ModelCatalog.Asset = MODELS.firstOrNull { it.id == id } ?: ModelCatalog.REDIMNET_LIGHT

    fun label(a: ModelCatalog.Asset): String = when (a.id) {
        ModelCatalog.REDIMNET_LIGHT.id -> "हल्का (कम CPU) — RedimNet-B2, ${a.mb}, ${a.license}"
        else -> "सटीक (ज़्यादा CPU) — RedimNet2-B3, ${a.mb}, ${a.license}"
    }

    /** सीखने के 6 वाक्य (हर एक ~3 सेकंड) */
    val PHRASES = listOf(
        "डीके, आज मौसम कैसा रहेगा, मुझे ज़रा बताओ।",
        "मेरे फ़ोन पर व्हाट्सऐप खोलो और नया मैसेज दिखाओ।",
        "कल सुबह सात बजे का अलार्म लगा देना।",
        "आज की ताज़ा ख़बरें सुनाओ, आराम से बोलना।",
        "मम्मी को फ़ोन लगाओ और स्पीकर चालू कर दो।",
        "मैं इस फ़ोन का मालिक हूँ, मेरी आवाज़ अच्छे से पहचान लो।",
    )

    /** RedimNet: एक ही आदमी ~0.70-0.78, अलग आदमी ≤ 0.30 (हमारी जाँच) -> बीच में 0.50 */
    const val TH_DEFAULT = 0.50f
    const val TH_MIN = 0.30f
    const val TH_MAX = 0.80f

    fun clampTh(t: Float): Float = if (!t.isFinite()) TH_DEFAULT else t.coerceIn(TH_MIN, TH_MAX)

    /** slider 0..100 <-> सीमा */
    fun thFromSlider(p: Int): Float = clampTh(TH_MIN + (TH_MAX - TH_MIN) * p.coerceIn(0, 100) / 100f)
    fun sliderFromTh(t: Float): Int = (((clampTh(t) - TH_MIN) / (TH_MAX - TH_MIN)) * 100).toInt().coerceIn(0, 100)

    fun pct(score: Float): Int = (score.coerceIn(0f, 1f) * 100).toInt()

    fun testLine(score: Float?, th: Float): String = when {
        score == null -> "आवाज़ साफ़ नहीं आई — फिर से 3 सेकंड बोलिए"
        score >= th -> "मिलान ${pct(score)}% (सीमा ${pct(th)}%) — ✓ यह आपकी आवाज़ है"
        else -> "मिलान ${pct(score)}% (सीमा ${pct(th)}%) — ✗ आपकी आवाज़ नहीं मानी"
    }

    /** सीखते समय: हर वाक्य कम से कम इतना बोला गया हो, और सब आपस में इतने मिलें */
    const val MIN_SPEECH_MS = 1200
    const val MIN_CONSISTENCY = 0.45f

    // ------------------------------------------------------------ Away मोड: पहली बार चेतावनी, दूसरी बार लॉक
    enum class Away { NONE, WARN, LOCK }
    /** इतनी देर में दूसरी बार अनजान आवाज़ = लॉक; उसके बाद गिनती फिर से */
    const val AWAY_WINDOW_MS = 10 * 60_000L

    class AwayState {
        var strikes = 0; private set
        var firstAt = 0L; private set
        fun onStranger(now: Long): Away {
            if (strikes > 0 && now - firstAt > AWAY_WINDOW_MS) strikes = 0
            if (strikes == 0) firstAt = now
            strikes++
            return if (strikes >= 2) { strikes = 0; Away.LOCK } else Away.WARN
        }
        fun reset() { strikes = 0; firstAt = 0L }
    }

    const val WARN_SPEECH = "चेतावनी: यह फ़ोन किसी और का है। आप मालिक नहीं हैं। दोबारा कोशिश की तो फ़ोन लॉक हो जाएगा।"
    const val LOCK_SPEECH = "अनजान आवाज़ दोबारा आई — फ़ोन लॉक कर रही हूँ।"
}

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

    // ------------------------------------------------------------ 16 नवंबर 2026 (मालिक): 15 सैंपल वाली पक्की पहचान
    /** पुराने 6 के बाद 9 और: अलग-अलग अंदाज़ (तेज़, धीरे, ऊँचा, Hinglish, आदेश) - ताकि हर तरह की आपकी आवाज़ सीखे */
    val PHRASES_EXTRA = listOf(
        "डीके, यूट्यूब पर नया गाना लगाओ और आवाज़ थोड़ी बढ़ा दो।",
        "भाई को मैसेज भेजो कि मैं दस मिनट में पहुँच रहा हूँ।",
        "आज मेरा कौन-कौन सा काम बाकी है, एक-एक करके बताओ।",
        "धीरे से बोल रहा हूँ — डीके, स्क्रीन की रोशनी कम कर दो।",
        "जल्दी से कैमरा खोलो, एक फ़ोटो लेनी है।",
        "डीके, Instagram खोलो और मेरी last post दिखाओ।",
        "पास वाली दवाई की दुकान कितनी दूर है, रास्ता बताओ।",
        "ज़ोर से बोल रहा हूँ — डीके, अभी के अभी गाना बंद करो।",
        "एक, दो, तीन, चार, पाँच — यह मेरी असली आवाज़ है, इसे याद रखो।",
    )
    /** पूरी सीख: 6 + 9 = 15 वाक्य */
    val PHRASES_15: List<String> = PHRASES + PHRASES_EXTRA
    /** "5 और सैंपल जोड़ो" एक बार में इतने; कुल इससे ज़्यादा नहीं (पुराने हट जाते हैं) */
    const val ADD_MORE = 5
    const val MAX_TEMPLATES = 30
    /** सबसे ज़्यादा मिलने वाले इतने सैंपल का औसत */
    const val TOP_K = 3

    /**
     * कई सैंपल से मिलान: max(औसत-पहचान से मिलान, सबसे मिलते TOP_K सैंपल का औसत).
     * कंप्यूटर पर नापा (LibriSpeech 40 लोग, 15 सैंपल, 1.5 सेकंड): अकेले औसत से गलती (EER) करीब आधी.
     */
    fun multiScore(centroid: FloatArray?, templates: List<FloatArray>, e: FloatArray): Float {
        val c = centroid?.let { VoicePrint.cosine(it, e) } ?: Float.NEGATIVE_INFINITY
        if (templates.isEmpty()) return if (c.isFinite()) c else Float.NaN
        val top = templates.map { VoicePrint.cosine(it, e) }.sortedDescending().take(TOP_K)
        val k = top.average().toFloat()
        return maxOf(c, k)
    }

    /** हर सैंपल को बाकी सैंपल से मिलाकर (leave-one-out) - आपकी आवाज़ आपस में कितनी मिलती है */
    fun selfScores(templates: List<FloatArray>): List<Float> {
        if (templates.size < 3) return emptyList()
        return templates.indices.map { i ->
            val rest = templates.filterIndexed { j, _ -> j != i }
            multiScore(VoicePrint.mean(rest), rest, templates[i])
        }
    }

    /** सुझाई सीमा के लिए: छोटे (1 सेकंड) टुकड़े पर score लंबे वाक्य से इतना कम आता है */
    const val CAL_DROP = 0.30f
    const val CAL_MIN = 0.35f
    const val CAL_MAX = 0.55f

    /**
     * आपकी आवाज़ के हिसाब से सीमा: आपस में मिलान का निचला हिस्सा (20%) - CAL_DROP, CAL_MIN..CAL_MAX में.
     * null = सैंपल कम (3 से कम) - तब पुरानी सीमा ही.
     */
    fun calibrate(templates: List<FloatArray>): Float? {
        val s = selfScores(templates).filter { it.isFinite() }.sorted()
        if (s.isEmpty()) return null
        val low = s[(s.size * 0.2).toInt().coerceIn(0, s.size - 1)]
        return (low - CAL_DROP).coerceIn(CAL_MIN, CAL_MAX)
    }

    /** नए सैंपल जोड़ो: कुल MAX_TEMPLATES से ज़्यादा हों तो सबसे पुराने हटाओ */
    fun addTemplates(old: List<FloatArray>, new: List<FloatArray>): List<FloatArray> = (old + new).takeLast(MAX_TEMPLATES)

    fun encodeAll(list: List<FloatArray>): String = list.joinToString(";") { VoicePrint.encode(it) }
    fun decodeAll(s: String?): List<FloatArray> =
        if (s.isNullOrBlank()) emptyList() else s.split(";").mapNotNull { VoicePrint.decode(it) }.filter { it.size == VoicePrint.DIM }

    /**
     * सीखने की आवाज़ से आगे-पीछे की चुप्पी काटो (20 ms टुकड़े; सबसे तेज़ टुकड़े के 10% से धीमे = चुप्पी).
     * चुप्पी पहचान को कमज़ोर करती है. बहुत कम बचे (0.5 सेकंड से कम) तो जैसी थी वैसी.
     */
    fun trimSilence(pcm: ByteArray, rate: Int = 16000): ByteArray {
        val frame = rate / 50 * 2
        val n = pcm.size / frame
        if (n < 3) return pcm
        val lv = DoubleArray(n) { f ->
            var s = 0.0
            for (i in 0 until frame / 2) { val o = f * frame + 2 * i; val v = ((pcm[o].toInt() and 0xff) or (pcm[o + 1].toInt() shl 8)).toShort().toDouble(); s += v * v }
            kotlin.math.sqrt(s / (frame / 2))
        }
        val peak = lv.maxOrNull() ?: return pcm
        if (peak <= 0) return pcm
        val first = lv.indexOfFirst { it >= peak * 0.1 }
        val last = lv.indexOfLast { it >= peak * 0.1 }
        val a = maxOf(0, first - 2); val b = minOf(n, last + 3)
        if ((b - a) * 20 < 500) return pcm
        return pcm.copyOfRange(a * frame, b * frame)
    }

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

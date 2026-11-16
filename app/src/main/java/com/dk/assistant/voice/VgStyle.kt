package com.dk.assistant.voice

/**
 * 16 नवंबर 2026 (मालिक): "आवाज़ सिखाते समय धीरे बोलो, ज़ोर से बोलो, उत्साह से बोलो... और लिखा हुआ ही बोलना ज़रूरी नहीं -
 * कुछ भी बोल सकता है". पहचान वाला मॉडल (RedimNet) शब्द नहीं, आवाज़ पहचानता है - इसलिए लिखा वाक्य सिर्फ़ सुझाव है.
 * हर सैंपल के साथ एक अंदाज़ (धीरे / ज़ोर से / ...) ताकि हर तरह की आपकी आवाज़ याद रहे. pure नियम - JVM test ([VgStyleTest]).
 */
object VgStyle {
    class Style(val id: String, val hi: String)

    /** बोलने के अंदाज़ - बारी-बारी से हर सैंपल पर */
    val STYLES = listOf(
        Style("normal", "अपनी आम आवाज़ में"),
        Style("slow", "थोड़ा धीरे-धीरे (स्लो)"),
        Style("loud", "ज़ोर से"),
        Style("excited", "उत्साह से (जोश में)"),
        Style("fast", "थोड़ा जल्दी-जल्दी"),
        Style("soft", "धीमी / हल्की आवाज़ में"),
        Style("far", "फ़ोन थोड़ा दूर रखकर (हाथ भर)"),
        Style("whisper", "बहुत धीमे, लगभग फुसफुसाकर"),
    )

    /** "हे डीके" वाले छोटे सैंपल - वेक वर्ड के बाद आवाज़-मिलान इन्हीं जितने छोटे टुकड़े पर होता है */
    val WAKE_SAY = listOf("हे डीके", "हेलो डीके", "हे डीके", "हेलो डीके")
    /** छोटे सैंपल के अंदाज़ (फुसफुसाना नहीं - वेक वर्ड वैसे भी फुसफुसाहट पर कम जागता है) */
    val WAKE_STYLES = listOf("normal", "loud", "slow", "excited")
    /** छोटा सैंपल कम से कम इतना (ms) - "हे डीके" ~0.6-1 सेकंड */
    const val WAKE_MIN_SPEECH_MS = 400

    class Prompt(val style: Style, val suggestion: String, val short: Boolean)

    fun style(id: String): Style = STYLES.firstOrNull { it.id == id } ?: STYLES.first()

    /** हर वाक्य-सुझाव के साथ अगला अंदाज़ (बारी-बारी). [start] = अलग-अलग बार अलग शुरुआत */
    fun plan(suggestions: List<String>, start: Int = 0): List<Prompt> =
        suggestions.mapIndexed { i, s -> Prompt(STYLES[Math.floorMod(start + i, STYLES.size)], s, short = false) }

    /** "हे डीके" वाले छोटे सैंपल */
    fun wakePlan(): List<Prompt> = WAKE_SAY.mapIndexed { i, s -> Prompt(style(WAKE_STYLES[i % WAKE_STYLES.size]), s, short = true) }

    /** स्क्रीन पर: अंदाज़ बड़ा, वाक्य सिर्फ़ सुझाव */
    fun line(i: Int, n: Int, p: Prompt): String =
        if (p.short) "(${i + 1}/$n) ${p.style.hi} बोलिए: \"${p.suggestion}\""
        else "(${i + 1}/$n) ${p.style.hi} बोलिए — कुछ भी बोल सकते हैं (2-3 सेकंड).\nसुझाव: \"${p.suggestion}\""

    /** फुसफुसाहट/बहुत दूर की आवाज़ आपकी आम आवाज़ से कम मिलती है - इसलिए इनके लिए मिलान की छूट थोड़ी ज़्यादा */
    fun minConsistency(p: Prompt): Float = when (p.style.id) { "whisper", "far" -> VgRules.MIN_CONSISTENCY - 0.10f; else -> VgRules.MIN_CONSISTENCY }

    /** सैंपल रखें? (आवाज़ के औसत से कितना मिलता है) - कम मिला = शायद शोर / कोई और बोला */
    fun keep(p: Prompt, cosToMean: Float): Boolean = cosToMean.isFinite() && cosToMean >= minConsistency(p)

    /** कितने सैंपल साफ़ आने ज़रूरी (2/3) */
    fun need(n: Int): Int = (n * 2 + 2) / 3

    /** छोटा "हे डीके" लंबे वाक्यों के औसत से स्वाभाविक रूप से कम मिलता है - रखने की सीमा कम */
    const val SHORT_MIN_COS = 0.25f
    fun keepShort(cosToMean: Float): Boolean = cosToMean.isFinite() && cosToMean >= SHORT_MIN_COS

    // ------------------------------------------------------------ वेक वर्ड वाला आवाज़-मिलान
    /** हर छोटे सैंपल को बाकी सब (वाक्य + दूसरे छोटे) से मिलाओ - जैसे असली "हे डीके" पर मिलेगा */
    fun wakeSelf(print: FloatArray, sentences: List<FloatArray>, shorts: List<FloatArray>): List<Float> =
        shorts.indices.map { i -> VgRules.multiScore(print, sentences + shorts.filterIndexed { j, _ -> j != i }, shorts[i]) }

    const val WAKE_TH_DROP = 0.10f
    const val WAKE_TH_MIN = 0.30f
    const val WAKE_TH_MAX = 0.50f

    /**
     * "हे डीके" के छोटे सैंपल आपकी बाकी आवाज़ से कितने मिलते हैं ([scores]) -> वेक वर्ड वाली मालिक-सीमा.
     * निचला 25% - [WAKE_TH_DROP], [WAKE_TH_MIN]..[WAKE_TH_MAX]. null = सैंपल नहीं (पुरानी सीमा ही).
     */
    fun wakeTh(scores: List<Float>): Float? {
        val s = scores.filter { it.isFinite() }.sorted()
        if (s.isEmpty()) return null
        val low = s[(s.size * 0.25).toInt().coerceIn(0, s.size - 1)]
        return (low - WAKE_TH_DROP).coerceIn(WAKE_TH_MIN, WAKE_TH_MAX)
    }
}

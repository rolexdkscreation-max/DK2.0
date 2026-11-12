package com.dk.assistant.voice.engine

/**
 * नवंबर 2026 (मालिक): "Supertonic चालू करके बात करता हूँ, फिर भी लगता है आवाज़ सीधे Gemini से आ रही है. जहाँ आवाज़ है वहाँ
 * दिखना चाहिए कि अभी कौन-सा TTS / कौन-सी आवाज़ बोल रही है."
 *
 * जो सच में बजा उसी से (तय किया हुआ नहीं): बँटवारे का टुकड़ा बजा = वही इंजन; Live की अपनी आवाज़ का टुकड़ा बजा =
 * "Gemini Live (सीधी आवाज़)" + कारण (Supertonic तैयार नहीं / चुप रहा / बीच में बचाव…). बैकअप मोड = Gemini TTS.
 * DK लाइव स्क्रीन पर (जुड़ाव वाली लाइन के नीचे) और Settings → Supertonic card में दिखता है. Pure हिस्सा JVM test में.
 */
object VoiceNow {
    /** स्क्रीन वाली लाइन (खाली = अभी कुछ नहीं बोला) */
    @Volatile var label = ""; private set
    @Volatile var at = 0L; private set
    @Volatile var engineId = ""; private set
    /** connection पर तय: कौन बोलेगा और क्यों (Live की सीधी आवाज़ का कारण) */
    @Volatile var planWhy = ""; private set
    @Volatile var planTitle = ""; private set

    /** स्क्रीन ताज़ा करने वाला (DKState.refresh) - test में खाली */
    @Volatile var onChange: () -> Unit = {}

    fun plan(title: String, why: String) { planTitle = title; planWhy = why }

    /** एक टुकड़ा सच में बजा */
    fun played(id: String, title: String, voice: String = "", why: String = "", now: Long = System.currentTimeMillis()) {
        val l = line(title, voice, why)
        at = now; engineId = id
        if (l != label) { label = l; try { onChange() } catch (_: Throwable) {} }
    }

    fun reset() { label = ""; at = 0; engineId = ""; planWhy = ""; planTitle = "" }

    /** pure: "आवाज़: Supertonic (हिंदी, ऑफ़लाइन) • मीरा" / "आवाज़: Gemini Live (सीधी आवाज़) • Kore — Supertonic अभी तैयार नहीं" */
    fun line(title: String, voice: String = "", why: String = ""): String = buildString {
        append("आवाज़: ").append(title.trim().ifEmpty { "—" })
        if (voice.isNotBlank()) append(" • ").append(voice.trim())
        if (why.isNotBlank()) append(" — ").append(why.trim())
    }

    /** स्क्रीन पर: बहुत पुराना (10 मिनट) = नहीं */
    fun shown(now: Long = System.currentTimeMillis()): String = if (label.isNotBlank() && now - at < 10 * 60_000L) label else ""

    const val LIVE_TITLE = "Gemini Live (सीधी आवाज़)"
}

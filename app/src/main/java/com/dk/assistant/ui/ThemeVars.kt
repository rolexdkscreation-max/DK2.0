package com.dk.assistant.ui

/**
 * 15 नवंबर 2026 (मालिक: "light mode में भी dark mode वाला ही design - सिर्फ़ background, कार्ड और text बदले"):
 * पूरे ऐप के रंगों का एक ही स्रोत (theme variables).
 *
 * नियम (ThemeVarsTest जाँचता है):
 *  - Light और Dark में सिर्फ़ तीन चीज़ें अलग: मुख्य पृष्ठभूमि [bg], कार्ड [card] और लिखावट [text] (और लिखावट के हल्के रूप
 *    [sub] / [gray] / [hint], जो text ही हैं). कार्ड से बनी सतहें (pill, field, nav, चुनी पंक्ति, हल्का किनारा) कार्ड के ही
 *    सादे (बिना रंग वाले) रूप हैं - ताकि dark कार्ड पर गहरी लिखावट न आए.
 *  - बाकी सब साझा: accent, icon, icon box, चमक, लाल/हरा/पीला, सूचना-पट्टी - दोनों मोड में एक ही मान.
 *  - Light में कोई pastel / gradient / रंगीन-कांच कार्ड नहीं - हर कार्ड ठोस.
 * नई स्क्रीन बस यहीं (या DkaiTheme / PlainLook, जो यहीं से पढ़ते हैं) के रंग लें - नियम अपने-आप लागू.
 * Android import नहीं - JVM test से जाँच.
 */
object ThemeVars {
    /** हर मोड में अलग होने वाले रंग */
    data class Mode(
        val bg: Int, val card: Int, val text: Int,
        val sub: Int, val gray: Int, val hint: Int,
        val pill: Int, val field: Int, val nav: Int, val sel: Int, val border: Int,
        /** छूने पर हल्की चमक (ripple) - उजले पर गहरी, अँधेरे पर सफ़ेद */
        val press: Int
    )

    val DARK = Mode(
        bg = 0xFF0B101D.toInt(), card = 0xFF161E31.toInt(), text = 0xFFFFFFFF.toInt(),
        sub = 0xFFCBD5E1.toInt(), gray = 0xFF94A3B8.toInt(), hint = 0xFF64748B.toInt(),
        pill = 0xFF1F2940.toInt(), field = 0xFF0B1324.toInt(), nav = 0xFF0D1324.toInt(), sel = 0xFF1E2A47.toInt(),
        border = 0xFF26324D.toInt(), press = 0x33FFFFFF
    )

    /** साफ़ बहुत हल्का धूसर पृष्ठभूमि + सफ़ेद कार्ड + गहरी लिखावट. सब सादे धूसर - कोई नीला/गुलाबी झलक नहीं */
    val LIGHT = Mode(
        bg = 0xFFF5F6F8.toInt(), card = 0xFFFFFFFF.toInt(), text = 0xFF0F172A.toInt(),
        sub = 0xFF334155.toInt(), gray = 0xFF475569.toInt(), hint = 0xFF64748B.toInt(),
        pill = 0xFFF1F3F6.toInt(), field = 0xFFF1F3F6.toInt(), nav = 0xFFFFFFFF.toInt(), sel = 0xFFE8EBF0.toInt(),
        border = 0xFFE2E5EA.toInt(), press = 0x220F172A
    )

    /** 23 नवंबर 2026 (Soft Clay): असली रंग अब चुनी Clay थीम से ([ClayTheme]); DARK / LIGHT पुराने आधार-रंग (tests / पुराना हिसाब) */
    @Suppress("UNUSED_PARAMETER")
    fun mode(light: Boolean): Mode = ClayTheme.t.mode()

    // ------------------------------------------------------------------ साझा (दोनों मोड में एक जैसे)
    const val BLUE = 0xFF3B82F6.toInt()
    const val CYAN = 0xFF00E5FF.toInt()
    const val VIOLET = 0xFFA855F7.toInt()
    const val GREEN = 0xFF22C55E.toInt()
    const val AMBER = 0xFFF59E0B.toInt()
    const val RED = 0xFFEF4444.toInt()
    const val PINK = 0xFFF472B6.toInt()
    const val ON = 0xFFFFFFFF.toInt()
    const val RED_BG = 0xFF1E1218.toInt()
    const val ALERT_BG = 0xFF2A1F0A.toInt()
    const val ALERT_TEXT = 0xFFF59E0B.toInt()
    const val UPDATE_BG = 0xFF064E3B.toInt()
    const val UPDATE_TEXT = 0xFF6EE7B7.toInt()
    /** "सोच रहा हूँ…" / THINKING वाला चमकता रंग */
    const val THINK = 0xFF22D3EE.toInt()

    /** pure: रंग पारदर्शिता के साथ बिना रंग-झलक का (r≈g≈b) धूसर/सफ़ेद है? - light सतहें ऐसी ही हों */
    fun neutral(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        return maxOf(r, g, b) - minOf(r, g, b) <= 0x14
    }

    /** pure: WCAG contrast (1..21) */
    fun contrast(a: Int, b: Int): Double = NeonTheme.contrast(a, b)
}

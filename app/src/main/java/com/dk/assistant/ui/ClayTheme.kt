package com.dk.assistant.ui

import android.content.Context

/**
 * 23 नवंबर 2026 (मालिक: "पूरे ऐप का UI Soft 3D Clay (Neumorphism) में, 8 थीम"):
 * पूरे ऐप के रंगों का एक ही स्रोत. हर थीम में सिर्फ़ तीन रंग-टोकन - [Theme.bg] (पृष्ठभूमि), [Theme.surface] (कार्ड),
 * [Theme.accent] (उभरता रंग). बाकी सब (लिखावट, हल्की लिखावट, धँसा खाना, दोनों परछाइयाँ, accent का ढलान, accent पर लिखावट)
 * इन्हीं तीन से गणित से बनते हैं - कहीं रंग-कोड सीधे नहीं लिखा जाता, सब यहीं से पढ़ा जाता है.
 *
 * सिर्फ़ दिखावट. यहाँ की कोई भी चीज़ AI, आवाज़, API key, login या data को नहीं छूती.
 * चुनाव "dk_prefs" में [K_THEME] नाम से सेव (ऐप दोबारा खोलने पर भी वही थीम).
 * Android रंग API नहीं - शुद्ध Int गणित, ताकि JVM test ([ClayThemeTest]) सीधे जाँच सके.
 */
object ClayTheme {
    const val K_THEME = "clay_theme"
    /** लाइट से डार्क पर लौटते समय पिछली गहरी थीम (Settings का "लाइट मोड" स्विच) */
    const val K_LAST_DARK = "clay_last_dark"

    /** एक थीम = सिर्फ़ तीन रंग + नाम/विवरण */
    data class Theme(val id: String, val name: String, val desc: String, val bg: Int, val surface: Int, val accent: Int) {
        /** पृष्ठभूमि उजली = लाइट थीम (लिखावट गहरी) */
        val light: Boolean get() = NeonTheme.luminance(bg) > 0.5
    }

    // ------------------------------------------------------------------ 8 थीम (सिर्फ़ bg / surface / accent)
    val CLAY_DAY = Theme("clay_day", "Clay Day", "हल्का नीला-सफ़ेद, चमकदार नीला — दिन के लिए हल्की थीम",
        0xFFE4EBF5.toInt(), 0xFFEDF2F9.toInt(), 0xFF2F7BFF.toInt())
    val MIDNIGHT = Theme("midnight", "Midnight", "लगभग काला-नीला, चमकदार नीला — गहरी और साफ़",
        0xFF10151F.toInt(), 0xFF171E2B.toInt(), 0xFF3D8BFF.toInt())
    val NOCTURNE = Theme("nocturne", "Nocturne", "गहरा indigo, violet accent — थोड़ी गर्माहट",
        0xFF17142B.toInt(), 0xFF201C3A.toInt(), 0xFF9D6BFF.toInt())
    val EMBER = Theme("ember", "Ember", "कोयले जैसा काला, amber accent — रात में आसान पढ़ाई",
        0xFF121110.toInt(), 0xFF1B1917.toInt(), 0xFFFFA62B.toInt())
    val ABYSS = Theme("abyss", "Abyss", "गहरा teal, cyan accent — ठंडा और साफ़ लुक",
        0xFF0B1A1D.toInt(), 0xFF112529.toInt(), 0xFF22D3EE.toInt())
    // 23 नवंबर 2026 (मालिक): "Clay Navy" - गहरा नीला (navy) बैकग्राउंड, चमकदार neon नीला accent, उभरे 3D clay टाइल; सूची में सबसे ऊपर
    val NAVY = Theme("gehra_navy", "Clay Navy", "गहरा नीला बैकग्राउंड, चमकदार neon नीला accent, उभरे 3D clay टाइल",
        0xFF0B1433.toInt(), 0xFF111C42.toInt(), 0xFF4D7CFF.toInt())
    val OBSIDIAN = Theme("obsidian", "Obsidian", "न्यूट्रल ग्रे, ठंडा accent — सबसे सरल रूप",
        0xFF1C1D20.toInt(), 0xFF24262A.toInt(), 0xFF7FB2FF.toInt())
    val ROSEWOOD = Theme("rosewood", "Rosewood", "गहरा plum, rose accent — सबसे नरम",
        0xFF231623.toInt(), 0xFF2E1D2E.toInt(), 0xFFF472A0.toInt())

    val ALL: List<Theme> = listOf(NAVY, CLAY_DAY, MIDNIGHT, NOCTURNE, EMBER, ABYSS, OBSIDIAN, ROSEWOOD)
    val DEFAULT = MIDNIGHT

    fun of(id: String?): Theme = ALL.firstOrNull { it.id == id } ?: DEFAULT

    // ------------------------------------------------------------------ अभी की थीम
    @Volatile var current: Theme = DEFAULT
        private set
    /** हर बदलाव पर +1 - खुली स्क्रीनें लौटते ही इसी से पहचानती हैं कि दोबारा बनना है */
    @Volatile var version = 0
        private set
    @Volatile private var cached: Tokens = tokens(DEFAULT)

    fun set(t: Theme) { if (t != current) { current = t; cached = tokens(t); version++ } }

    fun load(c: Context) {
        val sp = c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE)
        val saved = sp.getString(K_THEME, null)
        // पहली बार: पुरानी Light/Dark पसंद से (लाइट = Clay Day)
        val t = if (saved != null) of(saved) else if (sp.getString("dkai_theme", "dark") == "light") CLAY_DAY else DEFAULT
        set(t)
    }

    fun save(c: Context, t: Theme) {
        val e = c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE).edit().putString(K_THEME, t.id)
        if (!t.light) e.putString(K_LAST_DARK, t.id)
        // पुरानी Light/Dark पसंद भी साथ में (जो स्क्रीनें उसे पढ़ती हैं, वे भी सही रहें)
        e.putString("dkai_theme", if (t.light) "light" else "dark")
        e.apply()
        set(t)
    }

    /** Settings का पुराना "लाइट मोड" स्विच: चालू = Clay Day, बंद = पिछली गहरी थीम */
    fun forLight(c: Context, on: Boolean): Theme =
        if (on) CLAY_DAY else of(c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE).getString(K_LAST_DARK, DEFAULT.id)).let { if (it.light) DEFAULT else it }

    val t: Tokens get() = cached

    // ------------------------------------------------------------------ बने हुए रंग (सब तीन टोकन से)
    data class Tokens(
        val theme: Theme,
        val bg: Int, val surface: Int, val accent: Int,
        /** लिखावट: मुख्य / दूसरी / हल्की / hint */
        val text: Int, val sub: Int, val gray: Int, val hint: Int,
        /** धँसे खाने (text box, usage बार) का रंग */
        val well: Int,
        /** छोटा चिप / पिल (कार्ड से ज़रा अलग) */
        val chip: Int,
        /** हल्की चमक वाली परछाईं (ऊपर-बाएँ) और नरम छाया (नीचे-दाएँ) */
        val shadowLight: Int, val shadowDark: Int,
        /** accent का चमकदार ढलान (ऊपर -> नीचे) और उस पर लिखावट */
        val accentTop: Int, val accentBottom: Int, val onAccent: Int,
        /** accent की बाहरी चमक (पारदर्शी) */
        val glow: Int,
        /** पतला किनारा (बहुत हल्का) और छूने की चमक */
        val rim: Int, val press: Int,
        /** चुनी पंक्ति का हल्का accent भराव */
        val sel: Int
    ) {
        val light get() = theme.light
        /** पुराने ThemeVars.Mode वाले नाम - पुरानी स्क्रीनें बिना बदले इसी से पढ़ती हैं */
        private val modeV by lazy { ThemeVars.Mode(bg = bg, card = surface, text = text, sub = sub, gray = gray, hint = hint,
            pill = chip, field = well, nav = surface, sel = sel, border = rim, press = press) }
        fun mode(): ThemeVars.Mode = modeV
    }

    /** पूरे ऐप का एक उभार-माप: उभरी परछाईं कितनी दूर/फैली ([ClayDrawable]) - आकार नहीं बदलता */
    const val LIFT = 1.45f
    /** धँसे (input / दबा) हिस्से की अंदर वाली परछाईं कितनी गहरी */
    const val SINK = 1.35f

    // const: `cached = tokens(DEFAULT)` ऊपर पहले चलता है - साधारण val तब 0 होता (Midnight लिखावट काली दिखती थी)
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()

    fun tokens(th: Theme): Tokens {
        val light = th.light
        val m = NeonTheme::mix
        val text = if (light) m(th.bg, BLACK, 0.80f).let { m(it, th.accent, 0.10f) } else m(th.surface, WHITE, 0.93f)
        val sub = m(text, th.surface, 0.22f)
        val gray = m(text, th.surface, 0.34f)
        val hint = m(text, th.surface, 0.44f)
        val well = if (light) m(th.bg, BLACK, 0.045f) else m(th.bg, BLACK, 0.28f)
        val chip = if (light) m(th.surface, th.bg, 0.4f) else m(th.surface, WHITE, 0.035f)
        // 23 नवंबर 2026 (मालिक: "उभार काफ़ी ज़्यादा, लाइट थीम में भी साफ़"): छाया गहरी, चमक तेज़
        val shL = if (light) WHITE else NeonTheme.alpha(m(th.surface, WHITE, 0.30f), 0x90)
        val shD = if (light) NeonTheme.alpha(m(th.bg, m(BLACK, th.accent, 0.15f), 0.58f), 0xD8) else NeonTheme.alpha(m(th.bg, BLACK, 0.88f), 0xFF)
        val aTop = m(th.accent, WHITE, 0.18f)
        val aBot = m(th.accent, BLACK, 0.18f)
        val aMid = m(aTop, aBot, 0.5f)
        val on = if (NeonTheme.contrast(WHITE, aMid) >= 3.0) WHITE else m(th.bg, BLACK, 0.85f).let { if (light) m(BLACK, th.accent, 0.1f) else it }
        val glow = NeonTheme.alpha(th.accent, if (light) 0x55 else 0x66)
        val rim = NeonTheme.alpha(if (light) WHITE else m(th.surface, WHITE, 0.10f), if (light) 0xCC else 0xFF)
        val press = NeonTheme.alpha(text, 0x1E)
        val sel = m(th.surface, th.accent, if (light) 0.14f else 0.20f)
        return Tokens(th, th.bg, th.surface, th.accent, text, sub, gray, hint, well, chip, shL, shD, aTop, aBot, on, glow, rim, press, sel)
    }

    /** पॉपअप / शीट के पीछे का काला पर्दा ([f] = 0..1 गहराई) - हर थीम में एक जैसा */
    fun scrim(f: Float): Int = NeonTheme.alphaF(BLACK, f.coerceIn(0f, 1f))

    /** pure: रंग किसी थीम की किसी सतह (bg/surface/chip/well) जैसा है? - पुराने डिब्बे पहचानने के लिए */
    fun isSurface(argb: Int, k: Tokens = t): Boolean = argb == k.surface || argb == k.chip || argb == k.bg || argb == k.sel
}

package com.dk.assistant.ui

import android.content.Context

/**
 * DK 2.0 "Premium Neon Glassmorphism" design tokens - poore ऐप का एक ही रंग/काँच/चमक/एनिमेशन स्रोत.
 *
 * सिर्फ़ दिखावट. यहाँ की कोई भी सेटिंग AI, आवाज़, API key, prompt या किसी काम को नहीं छूती.
 * यूज़र की पसंद (Settings → दिखावट) "dk_prefs" में ui_* नाम से सेव होती है; बाकी सेटिंग्स से बिल्कुल अलग.
 *
 * रंग का हिसाब शुद्ध Int गणित से (android.graphics.Color नहीं) - इसलिए JUnit में सीधे जाँच होती है.
 */
object NeonTheme {

    // ------------------------------------------------------------------ पसंद (Prefs keys)
    const val K_THEME = "ui_theme"
    const val K_ACCENT = "ui_accent"
    const val K_GLASS = "ui_glass"
    const val K_GLOW = "ui_glow"
    const val K_MOTION = "ui_motion"
    val KEYS = listOf(K_THEME, K_ACCENT, K_GLASS, K_GLOW, K_MOTION)

    const val THEME_DARK = "dark"
    const val THEME_AMOLED = "amoled"
    const val MOTION_STANDARD = "standard"
    const val MOTION_REDUCED = "reduced"

    /** मुख्य (accent) रंग: नाम, चमकदार रंग, और उसका दूसरा (gradient) साथी */
    enum class Accent(val key: String, val label: String, val main: Int, val partner: Int) {
        BLUE("blue", "नियॉन नीला", 0xFF3D8BFF.toInt(), 0xFF8B5CF6.toInt()),
        CYAN("cyan", "सियान", 0xFF22D3EE.toInt(), 0xFF3D8BFF.toInt()),
        VIOLET("violet", "वायलेट", 0xFF8B5CF6.toInt(), 0xFF3D8BFF.toInt()),
        PURPLE("purple", "पर्पल", 0xFFA855F7.toInt(), 0xFFE040FB.toInt()),
        MAGENTA("magenta", "मैजेंटा", 0xFFE040FB.toInt(), 0xFF8B5CF6.toInt());

        companion object {
            fun of(k: String?) = values().firstOrNull { it.key == k } ?: BLUE
        }
    }

    val THEMES = listOf(THEME_DARK to "डार्क नेवी (शुरुआती)", THEME_AMOLED to "AMOLED काला (बैटरी बचत)")
    val MOTIONS = listOf(MOTION_STANDARD to "सामान्य एनिमेशन", MOTION_REDUCED to "कम एनिमेशन (Reduced motion)")

    const val DEF_GLASS = 55
    const val DEF_GLOW = 60

    data class Look(
        val theme: String = THEME_DARK,
        val accent: Accent = Accent.BLUE,
        /** 0 = लगभग ठोस काँच, 100 = सबसे पारदर्शी */
        val glass: Int = DEF_GLASS,
        /** 0 = बिना चमक, 100 = सबसे तेज़ नियॉन चमक */
        val glow: Int = DEF_GLOW,
        val motion: String = MOTION_STANDARD
    ) {
        val glassF get() = glass / 100f
        val glowF get() = glow / 100f
    }

    /** पुरानी/गलत value आए तो भी सुरक्षित (क्रैश नहीं, शुरुआती value) */
    fun sanitize(theme: String?, accent: String?, glass: Int, glow: Int, motion: String?) = Look(
        theme = if (theme == THEME_AMOLED) THEME_AMOLED else THEME_DARK,
        accent = Accent.of(accent),
        glass = glass.coerceIn(0, 100),
        glow = glow.coerceIn(0, 100),
        motion = if (motion == MOTION_REDUCED) MOTION_REDUCED else MOTION_STANDARD
    )

    @Volatile var look = Look()
        private set
    /** हर बदलाव पर +1: काँच/पृष्ठभूमि अगली बार बनते समय नया रूप ले लेते हैं */
    @Volatile var version = 0
        private set
    /** फ़ोन की सेटिंग में एनिमेशन बंद (Animator duration scale = 0) */
    @Volatile var systemNoAnim = false

    fun set(l: Look) { if (l != look) { look = l; version++ } }

    fun load(c: Context) {
        val sp = c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE)
        set(sanitize(sp.getString(K_THEME, null), sp.getString(K_ACCENT, null),
            sp.getInt(K_GLASS, DEF_GLASS), sp.getInt(K_GLOW, DEF_GLOW), sp.getString(K_MOTION, null)))
        systemNoAnim = try {
            android.provider.Settings.Global.getFloat(c.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (_: Exception) { false }
    }

    fun save(c: Context, l: Look) {
        c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE).edit()
            .putString(K_THEME, l.theme).putString(K_ACCENT, l.accent.key)
            .putInt(K_GLASS, l.glass).putInt(K_GLOW, l.glow).putString(K_MOTION, l.motion).apply()
        set(l)
    }

    /** सिर्फ़ दिखावट शुरुआती जैसी - बातचीत, यादें, key, आवाज़ कुछ नहीं मिटता */
    fun resetAppearance(c: Context) {
        val e = c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE).edit()
        KEYS.forEach { e.remove(it) }
        e.apply()
        set(Look())
    }

    fun reducedMotion(l: Look = look, systemOff: Boolean = systemNoAnim) = systemOff || l.motion == MOTION_REDUCED

    // ------------------------------------------------------------------ रंग गणित
    fun alpha(c: Int, a: Int) = (a.coerceIn(0, 255) shl 24) or (c and 0x00FFFFFF)
    fun alphaF(c: Int, f: Float) = alpha(c, (f * 255).toInt())

    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(sh: Int) = (((a ushr sh) and 0xFF) + (((b ushr sh) and 0xFF) - ((a ushr sh) and 0xFF)) * k).toInt().coerceIn(0, 255)
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** WCAG relative luminance (0..1) */
    fun luminance(c: Int): Double {
        fun lin(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
        return 0.2126 * lin((c shr 16) and 0xFF) + 0.7152 * lin((c shr 8) and 0xFF) + 0.0722 * lin(c and 0xFF)
    }

    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a); val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** पारदर्शी रंग को पीछे के ठोस रंग पर रखकर असल दिखने वाला रंग */
    fun over(top: Int, bottom: Int): Int {
        val a = ((top ushr 24) and 0xFF) / 255f
        return mix(bottom or (0xFF shl 24), top or (0xFF shl 24), a)
    }

    // ------------------------------------------------------------------ tokens
    const val VIOLET = 0xFF8B5CF6.toInt()
    const val MAGENTA = 0xFFE040FB.toInt()
    const val CYAN = 0xFF22D3EE.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()
    const val TEXT2 = 0xD9E6ECFF.toInt()
    const val TEXT3 = 0xA6C4CCE6.toInt()

    /** पृष्ठभूमि: ऊपर, बीच, नीचे (गहरा नेवी / मिडनाइट; AMOLED = लगभग काला) */
    fun bg(l: Look = look): IntArray = if (l.theme == THEME_AMOLED)
        intArrayOf(0xFF000000.toInt(), 0xFF02030A.toInt(), 0xFF060414.toInt())
    else intArrayOf(0xFF050A1F.toInt(), 0xFF0A1033.toInt(), 0xFF120A33.toInt())

    /** काँच का आधार रंग (नेवी) - पारदर्शिता यूज़र की पसंद से; सबसे पारदर्शी पर भी सफ़ेद text पढ़ने लायक */
    fun glassAlpha(l: Look = look, base: Float = 1f): Int {
        val a = 0xE0 - (0xE0 - 0x7A) * l.glassF          // 224 .. 122
        return (a * base).toInt().coerceIn(0x50, 0xF5)
    }
    fun glassTop(l: Look = look, base: Float = 1f) = alpha(mix(0xFF1B2A6E.toInt(), l.accent.main, 0.10f), glassAlpha(l, base))
    fun glassBottom(l: Look = look, base: Float = 1f) = alpha(mix(0xFF0E1440.toInt(), l.accent.partner, 0.08f), glassAlpha(l, base) + 0x0A)
    fun glassPressTop(l: Look = look) = alpha(mix(0xFF26398C.toInt(), l.accent.main, 0.25f), glassAlpha(l) + 0x14)
    fun glassPressBottom(l: Look = look) = alpha(mix(0xFF141C52.toInt(), l.accent.partner, 0.2f), glassAlpha(l) + 0x18)

    /** मुख्य बटन / अपना संदेश: accent से partner तक नियॉन gradient (हमेशा लगभग ठोस - text साफ़) */
    // navy में 46% मिलाकर: सबसे हल्के (सियान) पर भी सफ़ेद अक्षर का contrast 4.5:1 से ऊपर (NeonThemeTest)
    fun accentTop(l: Look = look) = alpha(mix(l.accent.main, 0xFF0A0F3A.toInt(), 0.46f), 0xF0)
    fun accentBottom(l: Look = look) = alpha(mix(l.accent.partner, 0xFF0A0F3A.toInt(), 0.52f), 0xF5)

    /** पतला चमकदार किनारा (accent) और उसके बाहर की नियॉन चमक */
    fun rim(l: Look = look) = mix(0xFFFFFFFF.toInt(), l.accent.main, 0.55f)
    fun glow(l: Look = look) = l.accent.main
    fun glowAlpha(l: Look = look, max: Int = 0x46) = (max * l.glowF).toInt()

    /** पृष्ठभूमि की धीमी रोशनी (accent, violet, magenta, cyan) और उनकी चमक */
    fun auroraColors(l: Look = look) = intArrayOf(l.accent.main, VIOLET, MAGENTA, if (l.accent == Accent.CYAN) 0xFF3D8BFF.toInt() else CYAN)
    fun auroraAlpha(l: Look = look, base: Int): Int = (base * (0.35f + 0.9f * l.glowF)).toInt().coerceIn(0, 140)
}

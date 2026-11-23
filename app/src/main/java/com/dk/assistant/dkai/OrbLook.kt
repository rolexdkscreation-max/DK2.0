package com.dk.assistant.dkai

import android.content.Context
import android.graphics.Bitmap

/**
 * 23 नवंबर 2026 (मालिक): होम के ORB / कैरेक्टर का साइज़ (छोटा-बड़ा) और ORB के अपने रंग - एक रंग, या 2 से 5 रंग मिलाकर.
 * कोई रंग न चुना = पहले जैसे (orb के अपने / थीम के रंग). चुनाव "dk_prefs" में सेव - ऐप बंद होने के बाद भी.
 *
 * रंग कहाँ लगते हैं: चलता ORB (होम, पूरी आवाज़ वाली स्क्रीन), ऑर्ब वाले आर्टवर्क (रंग बदलकर), और आर्टवर्क के पीछे की
 * रोशनी व बहती वेव. कैरेक्टर (लड़की) की तस्वीर का रंग नहीं बदलता - वह हर हाल में असली दिखती है.
 */
object OrbLook {
    const val P_SIZE = "home_size"
    const val P_COLORS = "orb_colors"
    const val MIN_SIZE = 60
    const val MAX_SIZE = 140
    const val DEF_SIZE = 100
    const val MAX_COLORS = 5

    /** चुनने के लिए तैयार रंग (टैप करके जोड़ें) */
    val SWATCHES: List<Int> = listOf(
        0xFF00E5FF, 0xFF3B82F6, 0xFF1D4ED8, 0xFF6366F1, 0xFFA855F7, 0xFFD946EF,
        0xFFEC4899, 0xFFF43F5E, 0xFFEF4444, 0xFFF97316, 0xFFFACC15, 0xFFFDE68A,
        0xFF84CC16, 0xFF22C55E, 0xFF10B981, 0xFF14B8A6, 0xFF67E8F9, 0xFFFFFFFF,
    ).map { it.toInt() }

    /** एक टैप वाले मेल (नाम to रंग) - एक रंग से पाँच रंग तक */
    val PRESETS: List<Pair<String, List<Int>>> = listOf(
        "सिर्फ़ नीला" to listOf(0xFF3B82F6),
        "नीला-बैंगनी" to listOf(0xFF00E5FF, 0xFFA855F7),
        "सूर्यास्त" to listOf(0xFFFACC15, 0xFFF97316, 0xFFEC4899),
        "हरा-फ़िरोज़ी" to listOf(0xFF84CC16, 0xFF10B981, 0xFF22D3EE),
        "आग" to listOf(0xFFFDE68A, 0xFFFACC15, 0xFFF97316, 0xFFEF4444),
        "इंद्रधनुष" to listOf(0xFFEF4444, 0xFFFACC15, 0xFF22C55E, 0xFF3B82F6, 0xFFA855F7),
    ).map { (n, l) -> n to l.map { it.toInt() } }

    private fun prefs(c: Context) = c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- साइज़
    fun clampSize(v: Int): Int = v.coerceIn(MIN_SIZE, MAX_SIZE)
    fun size(c: Context): Int = try { clampSize(prefs(c).getInt(P_SIZE, DEF_SIZE)) } catch (_: Exception) { DEF_SIZE }
    fun setSize(c: Context, v: Int) { prefs(c).edit().putInt(P_SIZE, clampSize(v)).apply() }
    /** pure: साइज़ (%) -> गुणा */
    fun factor(size: Int): Float = clampSize(size) / 100f

    // ---------------------------------------------------------------- रंग
    /** pure: "#RRGGBB,#RRGGBB" -> रंग (ग़लत टुकड़े छोड़े, ज़्यादा से ज़्यादा 5) */
    fun parse(s: String?): List<Int> = s.orEmpty().split(',').mapNotNull { p ->
        val h = p.trim().removePrefix("#")
        if (h.length != 6) null else h.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
    }.take(MAX_COLORS)

    /** pure: रंग -> सेव वाला टेक्स्ट */
    fun encode(cols: List<Int>): String = cols.take(MAX_COLORS).joinToString(",") { String.format(java.util.Locale.US, "#%06X", it and 0xFFFFFF) }

    @Volatile private var memRaw: String? = "\u0000"
    @Volatile private var memCols: List<Int> = emptyList()

    /** चुने रंग (खाली = अपने / थीम के रंग). हर फ़्रेम बुलाया जा सकता है - टेक्स्ट वही तो दोबारा नहीं पढ़ता */
    fun colors(c: Context): List<Int> {
        val raw = try { prefs(c).getString(P_COLORS, "") } catch (_: Exception) { "" }
        if (raw != memRaw) { memCols = parse(raw); memRaw = raw }
        return memCols
    }
    fun setColors(c: Context, cols: List<Int>) { prefs(c).edit().putString(P_COLORS, encode(cols)).apply() }
    fun clearColors(c: Context) { prefs(c).edit().remove(P_COLORS).apply() }

    /** pure: दो रंग मिलाना (k = 0 -> a, 1 -> b) */
    fun mix(a: Int, b: Int, k: Float): Int = FluidOrbView.mix(a, b, k)

    /**
     * pure: orb के 3 रंग (शुरू, बीच, आख़िर). 1 रंग = उसी के हल्के-गहरे रूप, 2 = बीच में मिला हुआ, 3+ = पहला / बीच / आख़िरी.
     * खाली = null (orb के अपने रंग).
     */
    fun palette3(cols: List<Int>): IntArray? = when (cols.size) {
        0 -> null
        1 -> intArrayOf(mix(cols[0], 0xFFFFFFFF.toInt(), 0.35f), cols[0], mix(cols[0], 0xFF000000.toInt(), 0.3f))
        2 -> intArrayOf(cols[0], mix(cols[0], cols[1], 0.5f), cols[1])
        else -> intArrayOf(cols[0], cols[cols.size / 2], cols.last())
    }

    /** pure: orb की देह का पूरा मेल - 1 रंग = हल्का-गहरा, बाकी = सारे चुने रंग बारी-बारी (4-5 रंग भी दिखें) */
    fun bodyColors(cols: List<Int>): IntArray? = when (cols.size) {
        0 -> null
        1 -> palette3(cols)
        else -> cols.toIntArray()
    }

    /** pure: रंगों की पट्टी में [t] (0..1) पर रंग */
    fun at(cols: List<Int>, t: Float): Int {
        if (cols.isEmpty()) return 0
        if (cols.size == 1) return cols[0]
        val x = t.coerceIn(0f, 1f) * (cols.size - 1)
        val i = x.toInt().coerceAtMost(cols.size - 2)
        return mix(cols[i], cols[i + 1], x - i)
    }

    /**
     * pure: ऑर्ब आर्टवर्क का एक पिक्सल नए रंग में - रोशनी (brightness) वही रहती है, सिर्फ़ रंग बदलता है:
     * अँधेरा अँधेरा ही, चमक चुने रंग में, सबसे तेज़ चमक सफ़ेद की ओर (ताकि ऑर्ब की चमक बनी रहे).
     * [pos] = ऑर्ब के बीच के चारों ओर जगह (0..1, घेरे में) - कई रंग हों तो ऑर्ब के घेरे पर बारी-बारी दिखें.
     * बहुत गहरे हिस्से (तस्वीर का पीछे वाला गहरा नीला) लगभग वैसे ही - ताकि मंच से मिला रहे, रंगीन डिब्बा न बने.
     */
    fun recolorPixel(argb: Int, cols: List<Int>, pos: Float): Int {
        if (cols.isEmpty()) return argb
        val a = argb ushr 24
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        val l = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
        val ring = if (cols.size > 1) cols + cols[0] else cols           // घेरे में घूमते रंग: आख़िर से पहले पर बिना जोड़ के
        val col = at(ring, pos.coerceIn(0f, 1f))
        var out = mix(0xFF000000.toInt(), col, (l * 1.7f).coerceIn(0f, 1f))
        val hi = ((l - 0.72f) / 0.28f).coerceIn(0f, 1f)
        if (hi > 0f) out = mix(out, 0xFFFFFFFF.toInt(), hi * 0.85f)
        val keep = 1f - ((l - 0.08f) / 0.22f).coerceIn(0f, 1f)          // गहरा = असली रंग
        if (keep > 0f) out = mix(out, argb or 0xFF000000.toInt(), keep)
        return (a shl 24) or (out and 0xFFFFFF)
    }

    /** pure: (x, y) की बीच (cx, cy) के चारों ओर जगह, 0..1 (ऊपर से घड़ी की दिशा) */
    fun angle01(x: Float, y: Float, cx: Float, cy: Float): Float {
        val t = (kotlin.math.atan2((y - cy).toDouble(), (x - cx).toDouble()) / (2 * Math.PI) + 0.25).toFloat()
        return t - kotlin.math.floor(t)
    }

    /** ऑर्ब वाले आर्टवर्क की रंग बदली नई कॉपी (असली bitmap नहीं बदलता). रंग न चुने = वही bitmap */
    fun recolor(src: Bitmap, cols: List<Int>, cxK: Float = 0.5f, cyK: Float = 0.5f): Bitmap {
        if (cols.isEmpty()) return src
        return try {
            val w = src.width; val h = src.height
            val px = IntArray(w * h)
            src.getPixels(px, 0, w, 0, 0, w, h)
            val cx = w * cxK; val cy = h * cyK
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) px[row + x] = recolorPixel(px[row + x], cols, angle01(x.toFloat(), y.toFloat(), cx, cy))
            }
            Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        } catch (e: Throwable) { com.dk.assistant.Quiet.note(e); src }
    }

    /** होम दोबारा बनाने की पहचान - साइज़ या रंग बदले तो */
    fun sig(c: Context): String = "${size(c)}|${encode(colors(c))}"
}

package com.dk.assistant.dkai

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.dk.assistant.ui.NeonTheme
import kotlin.math.cos
import kotlin.math.sin

/**
 * 27 नवंबर 2026 (मालिक): हर थीम में कैरेक्टर का हल्का color-grade - कैरेक्टर का अपना रूप वही, बस थीम की ओर झुका.
 * (CSS वाले --character-hue-shift / --character-saturate / --character-tint का Android रूप: एक [android.graphics.ColorMatrix].)
 *  - Clay Navy / Midnight: neon नीला, लगभग कोई shift नहीं
 *  - Nocturne: violet की ओर · Ember: amber / orange · Abyss: teal / cyan · Obsidian: थोड़ा फीका, ठंडा ग्रे-नीला
 *  - Rosewood: rose / plum · Clay Day: हल्का-नरम, contrast थोड़ा कम
 *  - कोई नई / अपनी थीम: उसी के accent से अपने आप (बिना नए कोड के)
 * सब बदलाव छोटे (hue ≤ 10°, saturate 0.85..1.06, tint 8-12%) - चेहरा / त्वचा प्राकृतिक.
 * tint = soft-light जैसा: हर रंग-चैनल accent की ओर अनुपात में (काला काला रहता, सफ़ेद लगभग सफ़ेद).
 */
object CharacterGrade {
    data class Grade(val hue: Float, val saturate: Float, val contrast: Float, val tint: Int, val tintK: Float)

    const val MAX_HUE = 10f
    const val TINT_MIN = 0.08f
    const val TINT_MAX = 0.12f
    /** पीछे की रोशनी की तीव्रता 20-30% */
    const val GLOW_MIN = 0.20f
    const val GLOW_MAX = 0.30f
    /** रोशनी का साइज़ ज़्यादा से ज़्यादा 2% बड़ा */
    const val GLOW_SCALE = 0.02f

    fun glowAlpha(pulse: Float): Float = GLOW_MIN + (GLOW_MAX - GLOW_MIN) * pulse.coerceIn(0f, 1f)
    fun glowScale(pulse: Float): Float = 1f + GLOW_SCALE * pulse.coerceIn(0f, 1f)

    /** pure: थीम (id + उसके रंग) से grade. [light] = हल्की थीम */
    fun of(id: String, bg: Int, accent: Int, light: Boolean): Grade {
        val g = when (id) {
            "gehra_navy", "midnight" -> Grade(0f, 1.03f, 1f, accent, 0.08f)
            "nocturne" -> Grade(7f, 1.02f, 1f, accent, 0.10f)
            "ember" -> Grade(4f, 1.04f, 1f, accent, 0.11f)
            "abyss" -> Grade(-7f, 1.02f, 1f, accent, 0.10f)
            "obsidian" -> Grade(-2f, 0.88f, 0.98f, NeonTheme.mix(accent, 0xFF8A94A6.toInt(), 0.5f), 0.08f)
            "rosewood" -> Grade(-5f, 1.0f, 1f, accent, 0.11f)
            "clay_day" -> Grade(0f, 0.97f, 0.94f, NeonTheme.mix(accent, 0xFFFFFFFF.toInt(), 0.45f), 0.08f)
            else -> Grade(0f, if (light) 0.97f else 1.02f, if (light) 0.95f else 1f, if (light) NeonTheme.mix(accent, 0xFFFFFFFF.toInt(), 0.45f) else accent, 0.09f)
        }
        return g.copy(hue = g.hue.coerceIn(-MAX_HUE, MAX_HUE), tintK = g.tintK.coerceIn(TINT_MIN, TINT_MAX))
    }

    /** pure: grade का 4x5 ColorMatrix (saturate → hue-rotate → contrast → soft-light tint) */
    fun matrix(g: Grade): FloatArray {
        var m = identity()
        m = concat(saturation(g.saturate), m)
        m = concat(hueRotate(g.hue), m)
        m = concat(contrast(g.contrast), m)
        m = concat(tint(g.tint, g.tintK), m)
        return m
    }

    /** pure: एक रंग पर matrix (टेस्ट / जाँच के लिए) */
    fun apply(m: FloatArray, c: Int): Int {
        val r = ((c ushr 16) and 255).toFloat(); val gg = ((c ushr 8) and 255).toFloat(); val b = (c and 255).toFloat(); val a = ((c ushr 24) and 255).toFloat()
        fun row(i: Int) = (m[i * 5] * r + m[i * 5 + 1] * gg + m[i * 5 + 2] * b + m[i * 5 + 3] * a + m[i * 5 + 4]).toInt().coerceIn(0, 255)
        return (row(3) shl 24) or (row(0) shl 16) or (row(1) shl 8) or row(2)
    }

    private fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)

    /** a के बाद b: out = b · a */
    private fun concat(b: FloatArray, a: FloatArray): FloatArray {
        val o = FloatArray(20)
        for (i in 0 until 4) {
            for (j in 0 until 5) {
                var v = 0f
                for (k in 0 until 4) v += b[i * 5 + k] * a[k * 5 + j]
                if (j == 4) v += b[i * 5 + 4]
                o[i * 5 + j] = v
            }
        }
        return o
    }

    private fun saturation(s: Float): FloatArray {
        val r = 0.213f * (1 - s); val g = 0.715f * (1 - s); val b = 0.072f * (1 - s)
        return floatArrayOf(r + s, g, b, 0f, 0f, r, g + s, b, 0f, 0f, r, g, b + s, 0f, 0f, 0f, 0f, 0f, 1f, 0f)
    }

    private fun hueRotate(deg: Float): FloatArray {
        val a = Math.toRadians(deg.toDouble()); val c = cos(a).toFloat(); val s = sin(a).toFloat()
        return floatArrayOf(
            0.213f + c * 0.787f - s * 0.213f, 0.715f - c * 0.715f - s * 0.715f, 0.072f - c * 0.072f + s * 0.928f, 0f, 0f,
            0.213f - c * 0.213f + s * 0.143f, 0.715f + c * 0.285f + s * 0.140f, 0.072f - c * 0.072f - s * 0.283f, 0f, 0f,
            0.213f - c * 0.213f - s * 0.787f, 0.715f - c * 0.715f + s * 0.715f, 0.072f + c * 0.928f + s * 0.072f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f)
    }

    private fun contrast(k: Float): FloatArray {
        val o = 127.5f * (1 - k)
        return floatArrayOf(k, 0f, 0f, 0f, o, 0f, k, 0f, 0f, o, 0f, 0f, k, 0f, o, 0f, 0f, 0f, 1f, 0f)
    }

    /** soft-light जैसा: चैनल × (1 + k·(2·accent − 1)) */
    private fun tint(c: Int, k: Float): FloatArray {
        fun f(sh: Int) = 1f + k * (2f * ((c ushr sh) and 255) / 255f - 1f)
        return floatArrayOf(f(16), 0f, 0f, 0f, 0f, 0f, f(8), 0f, 0f, 0f, 0f, 0f, f(0), 0f, 0f, 0f, 0f, 0f, 1f, 0f)
    }
}

/**
 * 27 नवंबर 2026 (मालिक): होम का "character stage" - ऊपर की पट्टी (status + अभिवादन) से नीचे की बार तक की पूरी खाली जगह.
 * एक ही component जिससे हर कैरेक्टर / ऑर्ब / अवतार (आज के और आगे जुड़ने वाले) गुज़रता है:
 *  - इसकी ऊँचाई / ऊपर / नीचे कभी नहीं बदलते - लिखावट कितनी भी हो, हालत कोई भी हो (READY / LISTENING / SPEAKING / RESEARCHING)
 *  - कैरेक्टर की परत ([character]) हमेशा पूरी जगह; लाइव संदेश बॉक्स ([overlay]) layout में नहीं - ऊपर तैरती परत, नीचे टिकी,
 *    सबसे बड़ी ऊँचाई तय ([boxMaxPx], ~4 लाइन / स्क्रीन का 22%), ज़्यादा बात = अंदर स्क्रॉल (नई लाइन दिखे, ऊपर-नीचे नरम फेड)
 *  - [reservePx] = बॉक्स की सबसे बड़ी ऊँचाई + नीचे की जगह. कैरेक्टर का चेहरा इसके ऊपर ही रहता है ([CharacterArt.place] floor)
 * यहाँ कोई animation नहीं - रोशनी / वेव सिर्फ़ कैरेक्टर की परत के अंदर (transform / alpha), layout कभी नहीं बदलता.
 */
class CharacterStage(c: Context) : FrameLayout(c) {
    var character: View? = null
        private set
    var overlay: View? = null
        private set
    /** संदेश बॉक्स की सबसे बड़ी ऊँचाई (px) */
    var boxMaxPx = 0
    /** बॉक्स और stage के नीचे के बीच की जगह (px) */
    var gapPx = 0
    val reservePx: Int get() = boxMaxPx + gapPx

    init { clipChildren = true; clipToPadding = true }

    fun setCharacter(v: View, lp: LayoutParams) { character = v; addView(v, 0, lp) }

    fun setOverlay(v: View, lp: LayoutParams) {
        overlay = v
        lp.gravity = Gravity.BOTTOM
        lp.bottomMargin = gapPx
        addView(v, lp)
        v.translationZ = 1f     // कैरेक्टर के ऊपर
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // बॉक्स अपनी सबसे बड़ी ऊँचाई से कभी नहीं बढ़ता (अंदर स्क्रॉल)
        val o = overlay ?: return
        if (boxMaxPx > 0 && o.visibility != View.GONE && o.measuredHeight > boxMaxPx) {
            o.measure(MeasureSpec.makeMeasureSpec(o.measuredWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(boxMaxPx, MeasureSpec.EXACTLY))
        }
    }

    companion object {
        /** बॉक्स की सबसे बड़ी ऊँचाई = स्क्रीन का 22% */
        const val BOX_SCREEN_K = 0.22f
        const val BOX_LINES = 4

        /** pure: बॉक्स की सबसे बड़ी ऊँचाई - [lines] लाइन + ऊपर का हिस्सा ([chromePx]), पर स्क्रीन के 22% से ज़्यादा नहीं */
        fun boxMax(screenH: Int, lineH: Int, chromePx: Int, lines: Int = BOX_LINES): Int =
            minOf((screenH * BOX_SCREEN_K).toInt(), chromePx + lineH * lines).coerceAtLeast(chromePx + lineH)

        /** pure: कैरेक्टर के चेहरे की सबसे नीची जगह (stage के ऊपर से px) */
        fun faceFloor(stageH: Int, reserve: Int): Float = (stageH - reserve).toFloat().coerceAtLeast(stageH * 0.35f)
    }
}

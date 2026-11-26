package com.dk.assistant.dkai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.dk.assistant.ui.NeonTheme
import kotlin.math.sqrt

/**
 * 26 नवंबर 2026 (मालिक: "कैरेक्टर ऐप का जीवित हिस्सा लगे, चिपकाई तस्वीर नहीं"): Dynamic Full-Bleed Character Environment.
 *
 * हर कैरेक्टर तस्वीर (ऐप के अंदर वाली, इंटरनेट वाली, आगे जुड़ने वाली - महिला / पुरुष / रोबोट / anime / अपनी) एक ही नियम से:
 *  1. [analyze] - तस्वीर किस तरह की है: पारदर्शी (PNG / WebP alpha), काले पीछे वाली रोशनी (emissive), सादे एक-रंग
 *     पीछे वाली (keyed), या पूरी पृष्ठभूमि वाली आर्टवर्क (full art). साथ में आकार (चौकोर / लंबी / चौड़ी) और गोल आर्टवर्क.
 *     किसी एक कैरेक्टर के रूप पर कोई नियम नहीं - सिर्फ़ तस्वीर के किनारों के पिक्सल से.
 *  2. [prepare] - एक बार (पीछे के thread पर, cache में): सादा पीछा पारदर्शी (keyed / emissive) + चारों किनारे नरम alpha
 *     (कभी सीधी किनार नहीं) + 16px का छोटा धुंधला रूप (पीछे के माहौल के लिए). हर frame कुछ नया नहीं बनता.
 *  3. [place] - अनुपात कभी नहीं बदलता (width / height एक ही पैमाना). न-कटने वाला हिस्सा (subject) पूरा अंदर;
 *     नीचे से कटा bust नीचे टिके (संदेश कार्ड में घुलता), गोल आर्टवर्क पूरा गोल बीच में.
 *  4. [CharacterEnvironment] - परतें: थीम का माहौल (gradient) → तस्वीर का धुंधला, थीम-रंगा रूप → आवाज़ के साथ रोशनी →
 *     असली कैरेक्टर (कभी धुंधला / खिंचा नहीं) → धीमे कण → ऊपर-नीचे थीम के पीछे में घुलना.
 * रंग हमेशा अभी की थीम ([com.dk.assistant.ui.ClayTheme.t]: bg / accent / light) से - Light / Dark / Black / Blue / Purple /
 * कोई भी नई थीम, बिना अलग कोड के.
 */
object CharacterArt {
    enum class Kind { TRANSPARENT, EMISSIVE, KEYED, FULL_ART }
    enum class Shape { SQUARE, PORTRAIT, LANDSCAPE }

    /** [key] = सादे पीछे का रंग (keyed / emissive), [circular] = गोल आर्टवर्क (पूरा गोला कभी न कटे) */
    data class Profile(val kind: Kind, val shape: Shape, val circular: Boolean, val key: Int = 0)

    /** जाँच के लिए तस्वीर इतनी छोटी (48x48) - पूरी तस्वीर कभी नहीं पढ़ी जाती */
    const val SAMPLE = 48
    /** तैयार (पारदर्शी किनारे वाली) तस्वीर की सबसे लंबी भुजा - फ़ोन की चौड़ाई जितनी, memory सीमित */
    const val MAX_SIDE = 1152
    /** किनारे का नरम alpha (तस्वीर के हर तरफ़ का हिस्सा) */
    const val FEATHER = 0.12f
    /** पीछे के धुंधले माहौल की छोटी तस्वीर (लंबी भुजा) - इतनी छोटी कि GPU blur की ज़रूरत ही नहीं */
    const val TINY = 16
    /** चौड़ी आर्टवर्क में बीच का कितना हिस्सा हमेशा दिखे */
    const val WIDE_KEEP = 0.70f

    // ------------------------------------------------------------------ pure (JVM test)
    fun shapeOf(w: Int, h: Int): Shape {
        if (w <= 0 || h <= 0) return Shape.SQUARE
        val r = w.toFloat() / h
        return when { r > 1.15f -> Shape.LANDSCAPE; r < 0.87f -> Shape.PORTRAIT; else -> Shape.SQUARE }
    }

    private fun ch(c: Int, s: Int) = ((c ushr s) and 255) / 255f
    fun alphaOf(c: Int) = ch(c, 24)

    /** दो रंगों की दूरी (0..~1.7) */
    fun dist(a: Int, b: Int): Float {
        val r = ch(a, 16) - ch(b, 16); val g = ch(a, 8) - ch(b, 8); val bl = ch(a, 0) - ch(b, 0)
        return sqrt(r * r + g * g + bl * bl)
    }

    fun luma(c: Int): Float = 0.2126f * ch(c, 16) + 0.7152f * ch(c, 8) + 0.0722f * ch(c, 0)

    fun smooth(a: Float, b: Float, x: Float): Float { val k = ((x - a) / (b - a)).coerceIn(0f, 1f); return k * k * (3 - 2 * k) }

    /**
     * pure: छोटे नमूने (w x h, ARGB) से तस्वीर का प्रकार. [srcW] / [srcH] = असली साइज़ (आकार के लिए).
     *  - किनारे के 25%+ पिक्सल पारदर्शी = TRANSPARENT
     *  - किनारे का 50%+ एक ही रंग, और अंदर कुछ अलग (कैरेक्टर) = KEYED (वह रंग लगभग काला हो तो EMISSIVE)
     *  - नहीं तो FULL_ART (पूरी तस्वीर, किनारे तक चित्र)
     */
    fun classify(px: IntArray, w: Int, h: Int, srcW: Int = w, srcH: Int = h): Profile {
        val shape = shapeOf(srcW, srcH)
        if (w < 4 || h < 4 || px.size < w * h) return Profile(Kind.FULL_ART, shape, false)
        val border = ArrayList<Int>(4 * (w + h))
        for (x in 0 until w) for (y in intArrayOf(0, 1, h - 2, h - 1)) border.add(px[y * w + x])
        for (y in 2 until h - 2) for (x in intArrayOf(0, 1, w - 2, w - 1)) border.add(px[y * w + x])
        val transparent = border.count { alphaOf(it) < 0.16f }.toFloat() / border.size
        fun at(u: Float, v: Float) = px[((v * (h - 1)).toInt().coerceIn(0, h - 1)) * w + (u * (w - 1)).toInt().coerceIn(0, w - 1)]
        val corners = listOf(at(0.04f, 0.04f), at(0.96f, 0.04f), at(0.04f, 0.96f), at(0.96f, 0.96f))
        // गोल आर्टवर्क: कोने "पीछा", पर बीच से 0.42 की दूरी वाला घेरा ज़्यादातर चित्र
        fun ring(isContent: (Int) -> Boolean): Float {
            var n = 0; var k = 0
            for (i in 0 until 32) {
                val a = i * (Math.PI * 2 / 32)
                val p = at(0.5f + 0.40f * kotlin.math.cos(a).toFloat(), 0.5f + 0.40f * kotlin.math.sin(a).toFloat())
                n++; if (isContent(p)) k++
            }
            return k.toFloat() / n
        }
        if (transparent >= 0.25f) {
            val isC: (Int) -> Boolean = { alphaOf(it) > 0.6f }
            val circ = shape == Shape.SQUARE && corners.none(isC) && ring(isC) >= 0.7f && isC(at(0.5f, 0.5f))
            return Profile(Kind.TRANSPARENT, shape, circ)
        }
        // सादे पीछे का रंग = किनारे का median
        fun med(s: Int): Int { val v = border.map { (it ushr s) and 255 }.sorted(); return v[v.size / 2] }
        val key = (0xFF shl 24) or (med(16) shl 16) or (med(8) shl 8) or med(0)
        val near = border.count { dist(it, key) < 0.13f }.toFloat() / border.size
        var content = 0; var inner = 0
        for (y in h / 5 until h - h / 5) for (x in w / 5 until w - w / 5) { inner++; if (dist(px[y * w + x], key) > 0.2f) content++ }
        val hasContent = inner > 0 && content.toFloat() / inner >= 0.04f
        if (near >= 0.5f && hasContent) {
            val isC: (Int) -> Boolean = { dist(it, key) > 0.2f }
            val circ = shape == Shape.SQUARE && corners.none(isC) && ring(isC) >= 0.7f
            val maxc = maxOf(ch(key, 16), ch(key, 8), ch(key, 0))
            return Profile(if (maxc < 0.06f) Kind.EMISSIVE else Kind.KEYED, shape, circ, key)
        }
        return Profile(Kind.FULL_ART, shape, false)
    }

    /**
     * pure: एक पिक्सल का नया रूप (ARGB, non-premultiplied). [u] / [v] = तस्वीर में जगह (0..1), [core] = चेहरे / शरीर का
     * वह अंडाकार जो keyed में कभी पारदर्शी नहीं होता (बाल / कपड़े पीछे जैसे रंग के हों तब भी) - cx, cy, rx, ry.
     */
    fun pixel(c: Int, p: Profile, u: Float, v: Float, core: FloatArray?, feather: Boolean = true): Int {
        var a = alphaOf(c)
        var r = ch(c, 16); var g = ch(c, 8); var b = ch(c, 0)
        when (p.kind) {
            Kind.EMISSIVE -> {
                // काला = रोशनी नहीं: चमक ही alpha, रंग वही (काले पर रखने पर हूबहू पहले जैसा)
                val m = maxOf(r, g, b)
                val k = (m * 1.25f - 0.02f).coerceIn(0f, 1f)
                if (k > 0.001f) { r = (r / k).coerceAtMost(1f); g = (g / k).coerceAtMost(1f); b = (b / k).coerceAtMost(1f) }
                a *= k
            }
            Kind.KEYED -> {
                val kr = ch(p.key, 16); val kg = ch(p.key, 8); val kb = ch(p.key, 0)
                var k = smooth(0.10f, 0.32f, dist(c, p.key))
                val kl = luma(p.key)
                if (kl < 0.5f) k = maxOf(k, smooth(kl + 0.05f, kl + 0.35f, luma(c)))       // गहरा पीछा: चमकीला हिस्सा पूरा रहे
                if (core != null) {
                    val fx = (u - core[0]) / core[2]; val fy = (v - core[1]) / core[3]
                    k = maxOf(k, 1f - smooth(0.55f, 1f, fx * fx + fy * fy))
                }
                // पीछे के रंग की परत हटाओ (किनारों पर गहरा / सफ़ेद धुआँ न रहे)
                if (k in 0.001f..0.999f) {
                    r = ((r - kr * (1 - k)) / k).coerceIn(0f, 1f); g = ((g - kg * (1 - k)) / k).coerceIn(0f, 1f); b = ((b - kb * (1 - k)) / k).coerceIn(0f, 1f)
                }
                a *= k
            }
            else -> {}
        }
        // चारों किनारे नरम (कोई सीधी किनार / आयत कभी नहीं)
        if (feather) a *= smooth(0f, FEATHER, u) * smooth(0f, FEATHER, 1f - u) * smooth(0f, FEATHER, v) * smooth(0f, FEATHER, 1f - v)
        fun i(f: Float) = (f * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (i(a) shl 24) or (i(r) shl 16) or (i(g) shl 8) or i(b)
    }

    /** pure: subject (बायाँ, ऊपर, दायाँ, नीचे) से चेहरे / शरीर का न-मिटने वाला अंडाकार */
    fun coreOf(subject: List<Float>): FloatArray {
        val l = subject.getOrElse(0) { 0f }; val t = subject.getOrElse(1) { 0f }
        val r = subject.getOrElse(2) { 1f }; val b = subject.getOrElse(3) { 1f }
        return floatArrayOf((l + r) / 2f, t + (b - t) * 0.45f, (r - l) * 0.34f, (b - t) * 0.42f)
    }

    /**
     * pure: तस्वीर कहाँ और कितनी बड़ी - **एक ही पैमाना** (कभी खिंचाव / दबाव नहीं).
     *  - FULL_ART: जितनी बड़ी हो सके (view भरे) पर subject पूरा अंदर ([HomeAvatarView.artScale]); बाकी जगह धुंधला माहौल
     *  - बाकी (पीछा पारदर्शी): subject view में पूरा, जितना बड़ा हो सके - माहौल पीछे से
     *  - गोल: पूरा गोला, बीच में. नीचे से कटा कैरेक्टर (subject नीचे तक) = नीचे टिका, ऊपर सिर के लिए जगह
     * [out] में dst; लौटाता है पैमाना.
     */
    fun place(w: Float, h: Float, bw: Float, bh: Float, subject: List<Float>, p: Profile, sizeK: Float, tablet: Boolean, out: RectF): Float {
        if (w <= 0f || h <= 0f || bw <= 0f || bh <= 0f) { out.setEmpty(); return 1f }
        val sl = subject.getOrElse(0) { 0f }; val st = subject.getOrElse(1) { 0f }
        val sr = subject.getOrElse(2) { 1f }; val sb = subject.getOrElse(3) { 1f }
        val sw = (sr - sl).coerceIn(0.05f, 1f); val sh = (sb - st).coerceIn(0.05f, 1f)
        var s = if (p.kind == Kind.FULL_ART) {
            HomeAvatarView.artScale(w, h, bw, bh, subject)
        } else {
            // पारदर्शी किनारे: subject पूरा, थोड़ी साँस की जगह; गोला = पूरा गोला (90%)
            val room = if (p.circular) 0.90f else 0.98f
            minOf(w * room / (bw * sw), h * room / (bh * sh))
        }
        // चौड़ी (landscape) आर्टवर्क (पूरी या सादे पीछे वाली), जिसका कोई अलग subject नहीं: बीच का 70% हिस्सा पूरा (चेहरा आम तौर पर बीच में),
        // दोनों किनारों से थोड़ा नियंत्रित कटाव - ऊपर-नीचे कम खाली जगह. अनुपात फिर भी वही
        val wideFull = p.kind != Kind.TRANSPARENT && p.shape == Shape.LANDSCAPE && sw >= 0.98f && sh >= 0.98f && !p.circular
        if (wideFull) s = maxOf(s, minOf(h * 0.98f / bh, w / (bw * WIDE_KEEP)))
        if (tablet) s = HomeAvatarView.tabletScale(w, h, bw, bh, s)
        s *= sizeK.coerceIn(OrbLook.MIN_SIZE / 100f, OrbLook.MAX_SIZE / 100f)
        val dw = bw * s; val dh = bh * s
        val cx = (sl + sr) / 2f
        var left = w / 2f - dw * cx
        val bottomCut = sb >= 0.97f && !p.circular && !wideFull
        var top = if (bottomCut) h - dh * sb else if (wideFull) (h - dh) * 0.58f else h / 2f - dh * (st + sb) / 2f
        if (p.kind == Kind.FULL_ART) {
            // पूरी आर्टवर्क: जहाँ तक subject न कटे, खाली पट्टी नहीं
            left = if (dw >= w) left.coerceIn(w - dw, 0f) else (w - dw) / 2f
            top = if (dh >= h) top.coerceIn(h - dh, 0f) else if (bottomCut) h - dh else (h - dh) * if (wideFull) 0.58f else 0.5f
        }
        out.set(left, top, left + dw, top + dh)
        return s
    }

    /**
     * pure: थीम से माहौल के रंग - [0] बीच का हल्का रंग, [1] पीछे की रोशनी, [2] कण. हर थीम (और आगे की नई) इसी से.
     * लाइट = सफ़ेद-नीली-cyan हल्की रोशनी (कोई गहरी परछाईं नहीं); डार्क = थीम का गहरा रंग + accent / cyan की रोशनी.
     */
    fun palette(bg: Int, accent: Int, light: Boolean): IntArray {
        val m = NeonTheme::mix
        val cyan = 0xFF22D3EE.toInt(); val white = 0xFFFFFFFF.toInt()
        return if (light) intArrayOf(
            m(bg, white, 0.55f),
            m(m(accent, cyan, 0.35f), white, 0.42f),
            m(accent, 0xFF000000.toInt(), 0.10f),
        ) else intArrayOf(
            m(bg, accent, 0.09f),
            m(accent, cyan, 0.30f),
            m(m(accent, cyan, 0.4f), white, 0.55f),
        )
    }

    /** pure: धुंधले माहौल का एक पिक्सल थीम में - तस्वीर की रोशनी थीम के रंग में फैले, अँधेरा = थीम का पीछा */
    fun tone(c: Int, p: Profile, bg: Int, glow: Int, light: Boolean): Int {
        val m = NeonTheme::mix
        if (p.kind == Kind.FULL_ART) {
            // पूरी आर्टवर्क: उसी के रंग (असली किनारे से बिना जोड़ के मिले), थीम की ओर झुके
            return m(c, bg, if (light) 0.50f else 0.38f)
        }
        val keyL = if (p.kind == Kind.TRANSPARENT) 0f else luma(p.key)
        val lift = ((luma(c) - keyL) * 2.2f).coerceIn(0f, 1f) * alphaOf(c).let { if (p.kind == Kind.TRANSPARENT) it else 1f }
        val src = m(c or (0xFF shl 24), glow, 0.5f).let { if (light) m(it, 0xFFFFFFFF.toInt(), 0.45f) else it }
        return m(bg, src, lift * (if (light) 0.55f else 0.62f))
    }

    // ------------------------------------------------------------------ फ़ोन
    /** एक तस्वीर की तैयारी - एक बार, फिर cache */
    class Prepared(val profile: Profile, val art: Bitmap, val tiny: Bitmap, val srcW: Int, val srcH: Int) {
        private var toneKey = 0L
        private var toned: Bitmap? = null

        /** थीम के रंग वाला धुंधला माहौल (16px से फ़िल्टर के साथ 64px - असली blur की ज़रूरत नहीं); थीम बदले तभी नया */
        fun backdrop(bg: Int, glow: Int, light: Boolean): Bitmap? {
            val k = (bg.toLong() shl 32) xor (glow.toLong() and 0xFFFFFFFFL) xor (if (light) 1L else 0L)
            toned?.let { if (k == toneKey && !it.isRecycled) return it }
            return try {
                val w = tiny.width; val h = tiny.height
                val px = IntArray(w * h); tiny.getPixels(px, 0, w, 0, 0, w, h)
                for (i in px.indices) px[i] = tone(px[i], profile, bg, glow, light)
                val t = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
                val up1 = Bitmap.createScaledBitmap(t, w * 2, h * 2, true)
                val up2 = Bitmap.createScaledBitmap(up1, w * 4, h * 4, true)
                if (up1 !== t) t.recycle()
                if (up2 !== up1) up1.recycle()
                toned = up2; toneKey = k
                up2
            } catch (e: Throwable) { com.dk.assistant.Quiet.note(e); null }
        }
    }

    private val cache = object : LruCache<String, Prepared>(3) {}
    private val busy = java.util.Collections.synchronizedSet(HashSet<String>())
    private val worker by lazy { java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "dk-char-env").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } } }
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun keyOf(b: Bitmap, subject: List<Float>): String =
        "${System.identityHashCode(b)}|${b.generationId}|${b.width}x${b.height}|${subject.joinToString(",")}"

    fun peek(b: Bitmap, subject: List<Float>): Prepared? = cache.get(keyOf(b, subject))

    /** छोटे नमूने से प्रकार (पूरी तस्वीर नहीं पढ़ते) */
    fun analyze(b: Bitmap): Profile {
        val sw = minOf(SAMPLE, b.width).coerceAtLeast(1); val sh = minOf(SAMPLE, b.height).coerceAtLeast(1)
        val s = Bitmap.createScaledBitmap(b, sw, sh, true)
        val px = IntArray(sw * sh); s.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (s !== b) s.recycle()
        return classify(px, sw, sh, b.width, b.height)
    }

    /** अभी (इसी thread पर) तैयार करो - पीछे वाले thread और टेस्ट के लिए */
    fun prepare(b: Bitmap, subject: List<Float>): Prepared? {
        val key = keyOf(b, subject)
        cache.get(key)?.let { return it }
        return try {
            val p = analyze(b)
            val long = maxOf(b.width, b.height)
            val src = if (long > MAX_SIDE) {
                val f = MAX_SIDE.toFloat() / long
                Bitmap.createScaledBitmap(b, maxOf(1, (b.width * f).toInt()), maxOf(1, (b.height * f).toInt()), true)
            } else b
            val w = src.width; val h = src.height
            val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
            val core = if (p.kind == Kind.KEYED) coreOf(subject) else null
            for (y in 0 until h) {
                val v = (y + 0.5f) / h
                val row = y * w
                for (x in 0 until w) px[row + x] = pixel(px[row + x], p, (x + 0.5f) / w, v, core)
            }
            val art = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
            if (src !== b) src.recycle()
            val tl = TINY.toFloat() / maxOf(b.width, b.height)
            val tiny = Bitmap.createScaledBitmap(b, maxOf(2, (b.width * tl).toInt()), maxOf(2, (b.height * tl).toInt()), true)
            Prepared(p, art, tiny, b.width, b.height).also { cache.put(key, it) }
        } catch (e: Throwable) { com.dk.assistant.Quiet.note(e); null }
    }

    /**
     * पीछे के thread पर तैयारी (UI कभी नहीं अटकता). तब तक [CharacterEnvironment] असली तस्वीर नरम किनारों के साथ दिखाता है -
     * खाली / टूटी जगह कभी नहीं. तैयार होते ही [done] (UI thread पर).
     */
    fun prepareAsync(b: Bitmap, subject: List<Float>, done: () -> Unit) {
        val key = keyOf(b, subject)
        if (cache.get(key) != null || !busy.add(key)) return
        try {
            worker.execute {
                try { prepare(b, subject) } finally { busy.remove(key); main.post { try { done() } catch (e: Exception) { com.dk.assistant.Quiet.note(e) } } }
            }
        } catch (e: Throwable) { busy.remove(key); com.dk.assistant.Quiet.note(e) }
    }
}

/**
 * माहौल की परतें बनाने वाला (हर view का अपना; Paint / shader पहले से, हर frame कोई बड़ी चीज़ नया नहीं).
 * कोई अलग परत (layer) या GPU blur नहीं - ऊपर-नीचे का घुलना थीम के पीछे वाले रंग की परत से (पीछा वही रंग है).
 */
class CharacterEnvironment {
    private val baseP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backP = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val artP = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dotP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgeP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tmp = RectF()
    private var baseKey = 0L
    private var edgeKey = 0L
    private var glowKey = 0L

    // धीमे कण (0..1 जगह) - पहले से बने, हर frame सिर्फ़ खिसकते
    private val rnd = java.util.Random(23L)
    private val pX = FloatArray(MAX_DOTS); private val pY = FloatArray(MAX_DOTS)
    private val pS = FloatArray(MAX_DOTS); private val pR = FloatArray(MAX_DOTS); private val pPh = FloatArray(MAX_DOTS)
    private var lastT = -1f

    init { for (i in 0 until MAX_DOTS) spawn(i, rnd.nextFloat()) }

    private fun spawn(i: Int, y: Float) {
        pX[i] = 0.04f + rnd.nextFloat() * 0.92f; pY[i] = y
        pS[i] = 0.008f + rnd.nextFloat() * 0.018f; pR[i] = 0.6f + rnd.nextFloat() * 1.3f; pPh[i] = rnd.nextFloat() * 6.28f
    }

    /** (1) थीम का माहौल: ऊपर-नीचे थीम का पीछा, बीच में हल्का रंग (पूरी चौड़ाई - कोई डिब्बा नहीं) */
    fun drawBase(cv: Canvas, w: Float, h: Float, bg: Int, mid: Int) {
        val k = (w.toLong() shl 40) xor (h.toLong() shl 20) xor (mid.toLong() * 31 + bg)
        if (k != baseKey) {
            baseKey = k
            baseP.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(NeonTheme.alpha(mid, 0), mid, mid, NeonTheme.alpha(mid, 0)),
                floatArrayOf(0f, 0.22f, 0.78f, 1f), Shader.TileMode.CLAMP)
        }
        cv.drawRect(0f, 0f, w, h, baseP)
    }

    /** (2) तस्वीर का धुंधला थीम-रंगा रूप - पूरी जगह ढके (अनुपात वही, बीच से), ताकि खाली जगह कभी न दिखे */
    fun drawBackdrop(cv: Canvas, w: Float, h: Float, back: Bitmap?, alpha: Float, anchor: RectF) {
        if (back == null || alpha <= 0.01f) return
        val bw = back.width.toFloat(); val bh = back.height.toFloat()
        // कैरेक्टर जितना बड़ा उतना ही माहौल, पर view से छोटा कभी नहीं
        val s = maxOf(anchor.width() / bw, anchor.height() / bh, w / bw, h / bh) * 1.08f
        val dw = bw * s; val dh = bh * s
        val cx = anchor.centerX().coerceIn(w - dw / 2f, dw / 2f); val cy = anchor.centerY().coerceIn(h - dh / 2f, dh / 2f)
        tmp.set(cx - dw / 2f, cy - dh / 2f, cx + dw / 2f, cy + dh / 2f)
        backP.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        cv.drawBitmap(back, null, tmp, backP)
    }

    /** (3) आवाज़ के साथ रोशनी - कैरेक्टर के पीछे गोल, कभी चौकोर नहीं */
    fun drawGlow(cv: Canvas, x: Float, y: Float, r: Float, color: Int, strength: Float) {
        if (r <= 1f) return
        val k = (x.toInt().toLong() shl 42) xor (y.toInt().toLong() shl 21) xor r.toInt().toLong() xor (color.toLong() shl 8)
        if (k != glowKey) {
            glowKey = k
            glowP.shader = RadialGradient(x, y, r, intArrayOf(color, NeonTheme.alpha(color, 0x55), NeonTheme.alpha(color, 0)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        }
        glowP.alpha = (strength.coerceIn(0f, 1f) * 255).toInt()
        cv.drawCircle(x, y, r, glowP)
    }

    /** (4) असली कैरेक्टर - सिर्फ़ एक drawBitmap (किनारे पहले से नरम); कभी धुंधला / खिंचा नहीं */
    fun drawArt(cv: Canvas, art: Bitmap, dst: RectF) { cv.drawBitmap(art, null, dst, artP) }

    /** (5) धीमे कण - माहौल के ऊपर, कुछ ही */
    fun drawDots(cv: Canvas, w: Float, h: Float, t: Float, color: Int, strength: Float, dp: Float, calm: Boolean) {
        val dt = if (lastT < 0f || calm) 0f else (t - lastT).coerceIn(0f, 0.05f)
        lastT = t
        for (i in 0 until MAX_DOTS) {
            pY[i] -= pS[i] * dt
            if (pY[i] < -0.03f) spawn(i, 1.03f)
            val y = pY[i]
            val edge = CharacterArt.smooth(0f, 0.2f, y) * CharacterArt.smooth(0f, 0.2f, 1f - y)
            val tw = 0.55f + 0.45f * kotlin.math.sin(t * 0.9f + pPh[i])
            dotP.color = NeonTheme.alpha(color, (strength * edge * tw * 170f).toInt().coerceIn(0, 255))
            cv.drawCircle(pX[i] * w + 6f * dp * kotlin.math.sin(t * 0.3f + pPh[i]), y * h, pR[i] * dp, dotP)
        }
    }

    /** (6) ऊपर (हेडर) और नीचे (संदेश कार्ड) की ओर थीम के पीछे में घुलना - वही रंग, इसलिए कोई किनार नहीं दिखती */
    fun drawEdges(cv: Canvas, w: Float, h: Float, bg: Int, top: Float, bottom: Float) {
        val k = (w.toLong() shl 40) xor (h.toLong() shl 20) xor bg.toLong() xor (top.toLong() shl 50) xor (bottom.toLong() shl 30)
        if (k != edgeKey) {
            edgeKey = k
            val tf = (top / h).coerceIn(0.01f, 0.45f); val bf = (bottom / h).coerceIn(0.01f, 0.45f)
            val o = NeonTheme.alpha(bg, 0xFF); val z = NeonTheme.alpha(bg, 0)
            edgeP.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(o, o, z, z, o, o),
                floatArrayOf(0f, 0.004f, tf, 1f - bf, 0.996f, 1f), Shader.TileMode.CLAMP)
        }
        cv.drawRect(0f, 0f, w, h, edgeP)
    }

    companion object { const val MAX_DOTS = 14 }
}

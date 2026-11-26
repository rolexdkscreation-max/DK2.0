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
 *     + अंडाकार (radial) फीकापन + नीचे की ओर फीका (कभी सीधी किनार नहीं). हर frame कुछ नया नहीं बनता.
 *     27 नवंबर 2026 (मालिक: "पीछे धुंधला चौकोर / बादल"): तस्वीर की धुंधली कॉपी अब कहीं नहीं बनती / बनती.
 *  3. [place] - अनुपात कभी नहीं बदलता (width / height एक ही पैमाना). न-कटने वाला हिस्सा (subject) पूरा अंदर;
 *     नीचे से कटा bust नीचे टिके (संदेश कार्ड में घुलता), गोल आर्टवर्क पूरा गोल बीच में.
 *  4. [CharacterEnvironment] - परतें: (पीछे ऐप का अपना थीम-रंग) → accent की नरम गोल रोशनी (20-30%) →
 *     असली कैरेक्टर (तेज़, कभी धुंधला / खिंचा / हिलता नहीं; थीम का हल्का color-grade [CharacterGrade]) → धीमे कण →
 *     चारों ओर थीम के पीछे में घुलना (नीचे सबसे ज़्यादा).
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
    /** अंडाकार फीकापन: बीच से इतने (0..1, कोने = 1) तक पूरा, फिर कोनों की ओर धीरे-धीरे पारदर्शी (CSS: black 55%, transparent 100%) */
    const val RADIAL_IN = 0.55f
    /** नीचे का फीकापन (तस्वीर की ऊँचाई का हिस्सा) - नीचे की पट्टी की ओर घुलना */
    const val BOTTOM_FADE = 0.22f
    /** सिर के ऊपर की जगह (stage की ऊँचाई का हिस्सा) - object-position: center top */
    const val HEADROOM = 0.035f
    /** subject का ऊपरी हिस्सा जिसमें चेहरा (0..1) - यह हिस्सा नीचे वाले संदेश बॉक्स के नीचे कभी नहीं जाता */
    const val FACE_K = 0.55f
    /** हल्की थीम वाली भराव परत ([Prepared.plain]) का अंडाकार */
    const val PLAIN_IN = 0.30f
    const val PLAIN_OUT = 0.78f
    /**
     * pure: किनारे का नरम हिस्सा हर दिशा में बराबर px जैसा दिखे - चौड़ी तस्वीर में ऊपर-नीचे (छोटी भुजा) का हिस्सा बड़ा.
     * [0] = बाएँ-दाएँ, [1] = ऊपर-नीचे (उस भुजा का हिस्सा), सबसे ज़्यादा 0.24
     */
    fun featherOf(w: Int, h: Int): FloatArray {
        if (w <= 0 || h <= 0) return floatArrayOf(FEATHER, FEATHER)
        val r = w.toFloat() / h
        return floatArrayOf((FEATHER / r).coerceIn(FEATHER, 0.24f).let { if (r > 1f) FEATHER else it },
            (FEATHER * r).coerceIn(FEATHER, 0.24f).let { if (r < 1f) FEATHER else it })
    }

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
    fun pixel(c: Int, p: Profile, u: Float, v: Float, core: FloatArray?, feather: Boolean = true, fu: Float = FEATHER, fv: Float = FEATHER,
              radial: Boolean = p.kind != Kind.TRANSPARENT): Int {
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
        if (feather) {
            a *= smooth(0f, fu, u) * smooth(0f, fu, 1f - u) * smooth(0f, fv, v) * smooth(0f, fv, 1f - v)
            // अंडाकार (radial) - पकी हुई पृष्ठभूमि वाली तस्वीर का कोई आयत कभी न दिखे; असली पारदर्शी तस्वीर को ज़रूरत नहीं
            if (radial) a *= 1f - smooth(RADIAL_IN, 1f, radius(u, v))
            // नीचे की ओर फीका (नीचे की पट्टी में घुले)
            a *= smooth(0f, BOTTOM_FADE, 1f - v)
        }
        fun i(f: Float) = (f * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (i(a) shl 24) or (i(r) shl 16) or (i(g) shl 8) or i(b)
    }

    /** pure: अंडाकार दूरी - बीच = 0, किनारे का बीच ≈ 0.71, कोना = 1 (CSS radial-gradient ellipse farthest-corner) */
    fun radius(u: Float, v: Float): Float {
        val dx = (u - 0.5f) * 2f; val dy = (v - 0.5f) * 2f
        return sqrt(dx * dx + dy * dy) / 1.4142135f
    }

    /** pure: subject (बायाँ, ऊपर, दायाँ, नीचे) से चेहरे / शरीर का न-मिटने वाला अंडाकार */
    fun coreOf(subject: List<Float>): FloatArray {
        val l = subject.getOrElse(0) { 0f }; val t = subject.getOrElse(1) { 0f }
        val r = subject.getOrElse(2) { 1f }; val b = subject.getOrElse(3) { 1f }
        return floatArrayOf((l + r) / 2f, t + (b - t) * 0.45f, (r - l) * 0.34f, (b - t) * 0.42f)
    }

    /**
     * pure: तस्वीर कहाँ और कितनी बड़ी - **एक ही पैमाना** (कभी खिंचाव / दबाव नहीं), object-fit: contain + object-position: center top.
     *  - FULL_ART: जितनी बड़ी हो सके (view भरे) पर subject पूरा अंदर ([HomeAvatarView.artScale])
     *  - बाकी (पीछा पारदर्शी): subject view में पूरा, जितना बड़ा हो सके. गोला = पूरा गोला (90%)
     *  - ऊपर से टिका: सिर के ऊपर [HEADROOM] जगह; [floor] (px, ऊपर से) = संदेश बॉक्स की सबसे ऊँची जगह - चेहरा ([FACE_K])
     *    उसके ऊपर ही रहता है, ज़रूरत हो तो तस्वीर (अनुपात वही) छोटी होती है. बॉक्स सिर्फ़ कंधे / कपड़े ढक सकता है.
     * [floor] तय (बॉक्स की सबसे बड़ी ऊँचाई से) - बॉक्स में लिखावट कितनी भी हो, कैरेक्टर की जगह / साइज़ वही.
     * [out] में dst; लौटाता है पैमाना.
     */
    fun place(w: Float, h: Float, bw: Float, bh: Float, subject: List<Float>, p: Profile, sizeK: Float, tablet: Boolean, out: RectF,
              floor: Float = h): Float {
        if (w <= 0f || h <= 0f || bw <= 0f || bh <= 0f) { out.setEmpty(); return 1f }
        val sl = subject.getOrElse(0) { 0f }; val st = subject.getOrElse(1) { 0f }
        val sr = subject.getOrElse(2) { 1f }; val sb = subject.getOrElse(3) { 1f }
        val sw = (sr - sl).coerceIn(0.05f, 1f); val sh = (sb - st).coerceIn(0.05f, 1f)
        val head = h * HEADROOM
        val fl = floor.coerceIn(h * 0.35f, h)
        var s = if (p.kind == Kind.FULL_ART) {
            HomeAvatarView.artScale(w, h, bw, bh, subject)
        } else {
            val room = if (p.circular) 0.90f else 0.98f
            minOf(w * room / (bw * sw), (h - head) * room / (bh * sh))
        }
        // चौड़ी आर्टवर्क (कोई अलग subject नहीं): बीच का 70% पूरा, दोनों किनारों से थोड़ा नियंत्रित कटाव - अनुपात वही
        val wideFull = p.kind != Kind.TRANSPARENT && p.shape == Shape.LANDSCAPE && sw >= 0.98f && sh >= 0.98f && !p.circular
        if (wideFull) s = maxOf(s, minOf(fl * 0.98f / bh, w / (bw * WIDE_KEEP)))
        if (tablet) s = HomeAvatarView.tabletScale(w, h, bw, bh, s)
        s *= sizeK.coerceIn(OrbLook.MIN_SIZE / 100f, OrbLook.MAX_SIZE / 100f)
        // गोला पूरा बॉक्स के ऊपर; बाकी: चेहरे वाला ऊपरी हिस्सा बॉक्स के ऊपर
        val faceK = if (p.circular) 1f else FACE_K
        val faceMax = (fl - head) / (bh * sh * faceK)
        if (s > faceMax) s = faceMax
        val dw = bw * s; val dh = bh * s
        val cx = (sl + sr) / 2f
        var left = w / 2f - dw * cx
        var top = if (p.circular) head + ((fl - head) - dh * sh) / 2f - dh * st else head - dh * st
        if (p.kind == Kind.FULL_ART) {
            left = if (dw >= w) left.coerceIn(w - dw, 0f) else (w - dw) / 2f
            if (dh >= h) top = top.coerceIn(h - dh, 0f)
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

    // ------------------------------------------------------------------ फ़ोन
    /** एक तस्वीर की तैयारी - एक बार, फिर cache */
    /**
     * [plain] = वही तस्वीर बिना रंग-चाबी (key) के, कसे अंडाकार में - हल्की थीम (Clay Day) में keyed तस्वीर के **नीचे** (normal blend).
     * वहाँ पीछे का रंग हटाने से गहरे कपड़े (पीछे जैसे रंग के) में छेद दिखते; यह परत उन्हें भरती है (सिर्फ़ KEYED में बनता).
     */
    class Prepared(val profile: Profile, val art: Bitmap, val srcW: Int, val srcH: Int, val plain: Bitmap? = null)

    private val cache = object : LruCache<String, Prepared>(3) {}
    private val waiting = HashMap<String, MutableList<() -> Unit>>()
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
            val plain = if (p.kind == Kind.KEYED) {
                val fp = p.copy(kind = Kind.FULL_ART); val fe = featherOf(w, h)
                val q = IntArray(w * h)
                for (y in 0 until h) { val v = (y + 0.5f) / h; val row = y * w
                    for (x in 0 until w) { val u = (x + 0.5f) / w
                        val c = pixel(px[row + x], fp, u, v, null, true, fe[0], fe[1])
                        // कसा हुआ अंडाकार: सिर्फ़ शरीर के बीच के गहरे हिस्से भरे, बाहर हल्की थीम में पूरी तरह घुले (कोई पट्टी / आयत नहीं)
                        val k = 1f - smooth(PLAIN_IN, PLAIN_OUT, radius(u, v))
                        q[row + x] = (((c ushr 24) * k).toInt() shl 24) or (c and 0xFFFFFF) } }
                Bitmap.createBitmap(q, w, h, Bitmap.Config.ARGB_8888)
            } else null
            val core = if (p.kind == Kind.KEYED) coreOf(subject) else null
            val fe = featherOf(w, h); val fu = fe[0]; val fv = fe[1]
            for (y in 0 until h) {
                val v = (y + 0.5f) / h
                val row = y * w
                for (x in 0 until w) px[row + x] = pixel(px[row + x], p, (x + 0.5f) / w, v, core, true, fu, fv)
            }
            val art = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
            if (src !== b) src.recycle()
            Prepared(p, art, b.width, b.height, plain).also { cache.put(key, it) }
        } catch (e: Throwable) { com.dk.assistant.Quiet.note(e); null }
    }

    /**
     * पीछे के thread पर तैयारी (UI कभी नहीं अटकता). तब तक [CharacterEnvironment] असली तस्वीर नरम किनारों के साथ दिखाता है -
     * खाली / टूटी जगह कभी नहीं. तैयार होते ही [done] (UI thread पर).
     */
    /**
     * पीछे के thread पर तैयारी, फिर [done] (main thread). एक ही तस्वीर कई view (जैसे activity दोबारा बनी) एक साथ माँगें
     * तो तैयारी एक ही बार, पर ख़बर हर view को - कोई view अधूरी (fallback) तस्वीर पर अटका न रहे.
     */
    fun prepareAsync(b: Bitmap, subject: List<Float>, done: () -> Unit) {
        val key = keyOf(b, subject)
        if (cache.get(key) != null) { main.post { safe(done) }; return }
        synchronized(waiting) {
            val list = waiting[key]
            if (list != null) { list.add(done); return }
            waiting[key] = mutableListOf(done)
        }
        try {
            worker.execute {
                try { prepare(b, subject) } finally {
                    val all = synchronized(waiting) { waiting.remove(key) }.orEmpty()
                    main.post { for (d in all) safe(d) }
                }
            }
        } catch (e: Throwable) {
            val all = synchronized(waiting) { waiting.remove(key) }.orEmpty()
            com.dk.assistant.Quiet.note(e)
            main.post { for (d in all) safe(d) }
        }
    }

    private fun safe(d: () -> Unit) { try { d() } catch (e: Exception) { com.dk.assistant.Quiet.note(e) } }
}

/**
 * माहौल की परतें बनाने वाला (हर view का अपना; Paint / shader पहले से, हर frame कोई बड़ी चीज़ नया नहीं).
 * 27 नवंबर 2026: कोई blur, कोई धुंधली कॉपी, कोई अलग परत (layer) नहीं. पीछे सिर्फ़ ऐप का अपना थीम-रंग (कोई जोड़ / seam नहीं) +
 * accent की नरम गोल रोशनी. कैरेक्टर हमेशा तेज़ (FILTER_BITMAP), अपनी जगह स्थिर.
 */
class CharacterEnvironment {
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val artP = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val screenX = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN)
    private val dotP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgeP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sideP = Paint(Paint.ANTI_ALIAS_FLAG)
    private var edgeKey = 0L
    private var sideKey = 0L
    private var glowKey = 0L
    private var gradeKey = ""
    private var gradeF: android.graphics.ColorFilter? = null

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

    /**
     * (1) कैरेक्टर के पीछे accent की नरम गोल रोशनी - सिर्फ़ radial gradient (तस्वीर की कोई कॉपी नहीं).
     * [pulse] 0..1: तीव्रता [CharacterGrade.GLOW_MIN]..[CharacterGrade.GLOW_MAX] (20-30%), साइज़ ज़्यादा से ज़्यादा [CharacterGrade.GLOW_SCALE] (2%).
     * shader एक बार (जगह / रंग बदले तभी); धड़कन सिर्फ़ alpha + canvas scale (GPU पर सस्ता).
     */
    fun drawGlow(cv: Canvas, x: Float, y: Float, r: Float, color: Int, pulse: Float) {
        if (r <= 1f) return
        val k = (x.toInt().toLong() shl 42) xor (y.toInt().toLong() shl 21) xor r.toInt().toLong() xor (color.toLong() shl 8)
        if (k != glowKey) {
            glowKey = k
            val o = NeonTheme.alpha(color, 0xFF)
            glowP.shader = RadialGradient(x, y, r, intArrayOf(o, NeonTheme.alpha(color, 0x70), NeonTheme.alpha(color, 0)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        }
        val p = pulse.coerceIn(0f, 1f)
        glowP.alpha = (CharacterGrade.glowAlpha(p) * 255f).toInt().coerceIn(0, 255)
        val sc = CharacterGrade.glowScale(p)
        cv.save()
        cv.scale(sc, sc, x, y)
        cv.drawCircle(x, y, r, glowP)
        cv.restore()
    }

    /**
     * (2) असली कैरेक्टर - सिर्फ़ एक drawBitmap, 100% तेज़ (high-quality filtering), कोई blur / scale animation नहीं.
     * [grade] = थीम का हल्का color-grade; [screen] = गहरी पकी पृष्ठभूमि + डार्क थीम: SCREEN blend (गहरा हिस्सा ऐप के पीछे में घुले).
     */
    fun drawArt(cv: Canvas, art: Bitmap, dst: RectF, grade: CharacterGrade.Grade? = null, screen: Boolean = false) {
        artP.colorFilter = grade?.let { filterOf(it) }
        artP.xfermode = if (screen) screenX else null
        cv.drawBitmap(art, null, dst, artP)
        artP.xfermode = null
    }

    /** grade का ColorFilter - एक बार प्रति थीम (हर frame नया नहीं) */
    fun filterOf(g: CharacterGrade.Grade): android.graphics.ColorFilter {
        val key = g.toString()
        gradeF?.let { if (key == gradeKey) return it }
        return android.graphics.ColorMatrixColorFilter(CharacterGrade.matrix(g)).also { gradeF = it; gradeKey = key }
    }

    /** (3) धीमे कण - माहौल के ऊपर, कुछ ही */
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

    /**
     * (4) कैरेक्टर के ऊपर थीम के पीछे वाले रंग का gradient: बीच पारदर्शी, ऊपर / बाएँ / दाएँ / (सबसे ज़्यादा) नीचे पूरा थीम-रंग.
     * वही रंग जो ऐप के पीछे - इसलिए कोई किनार / seam नहीं; नीचे नीचे की पट्टी में घुलता.
     */
    fun drawEdges(cv: Canvas, w: Float, h: Float, bg: Int, top: Float, bottom: Float, side: Float = 0f) {
        val k = (w.toLong() shl 40) xor (h.toLong() shl 20) xor bg.toLong() xor (top.toLong() shl 50) xor (bottom.toLong() shl 30)
        val o = NeonTheme.alpha(bg, 0xFF); val z = NeonTheme.alpha(bg, 0)
        if (k != edgeKey) {
            edgeKey = k
            val tf = (top / h).coerceIn(0.01f, 0.45f); val bf = (bottom / h).coerceIn(0.01f, 0.45f)
            edgeP.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(o, o, z, z, o, o),
                floatArrayOf(0f, 0.004f, tf, 1f - bf, 0.996f, 1f), Shader.TileMode.CLAMP)
        }
        cv.drawRect(0f, 0f, w, h, edgeP)
        if (side > 0.5f) {
            val sk = (w.toLong() shl 32) xor bg.toLong() xor (side.toLong() shl 12)
            if (sk != sideKey) {
                sideKey = sk
                val sf = (side / w).coerceIn(0.01f, 0.3f)
                sideP.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(o, o, z, z, o, o), floatArrayOf(0f, sf * 0.3f, sf, 1f - sf, 1f - sf * 0.3f, 1f), Shader.TileMode.CLAMP)
            }
            cv.drawRect(0f, 0f, w, h, sideP)
        }
    }

    companion object { const val MAX_DOTS = 14 }
}

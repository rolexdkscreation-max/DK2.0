package com.dk.assistant.dkai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.dk.assistant.R
import com.dk.assistant.ui.NeonTheme
import kotlin.math.exp
import kotlin.math.sin

/**
 * 26 नवंबर 2026 (मालिक): ethereal कैरेक्टर की पहचान - कौन सी तस्वीर, चेहरा / धड़ कहाँ (वहाँ लहर नहीं),
 * और तस्वीर के किस किनारे को काले में घुलाना है (दोनों कैरेक्टर एक ही तस्वीर से काटे गए - बीच वाला किनारा).
 * महिला और पुरुष हमेशा दो अलग पहचान - हर एक की अपनी तस्वीर, कभी बदलती नहीं.
 */
class SpiritIdentity(
    val key: String,
    val res: Int,
    val faceX: Float, val faceY: Float, val faceRx: Float, val faceRy: Float,
    val torsoX: Float,
    /** किनारा घुलना (तस्वीर की चौड़ाई / ऊँचाई का हिस्सा): बाएँ, ऊपर, दाएँ, नीचे */
    val fadeL: Float, val fadeT: Float, val fadeR: Float, val fadeB: Float,
) {
    companion object {
        const val FEMALE = "female"
        const val MALE = "male"
        val FEMALE_ID = SpiritIdentity(FEMALE, R.drawable.spirit_female, 0.62f, 0.33f, 0.10f, 0.14f, 0.62f,
            0.12f, 0.10f, 0.15f, 0.13f)
        val MALE_ID = SpiritIdentity(MALE, R.drawable.spirit_male, 0.42f, 0.33f, 0.10f, 0.14f, 0.42f,
            0.15f, 0.10f, 0.12f, 0.13f)
    }
}

/** असली आर्टवर्क एक बार decode (बिना scale, पूरे रंग ARGB_8888) - हर बार वही Bitmap, कोई नई कॉपी नहीं */
object SpiritArt {
    private val cache = HashMap<Int, Bitmap>()

    @Synchronized fun get(c: Context, res: Int): Bitmap? {
        cache[res]?.let { if (!it.isRecycled) return it }
        val o = BitmapFactory.Options().apply { inScaled = false; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val b = try { BitmapFactory.decodeResource(c.applicationContext.resources, res, o) } catch (e: Throwable) {
            com.dk.assistant.Quiet.note(e); null
        }
        if (b != null) cache[res] = b
        return b
    }

    private val litCache = HashMap<String, Bitmap>()

    /**
     * 26 नवंबर 2026: हल्की थीम के लिए वही तस्वीर, काला पीछा पारदर्शी ([CharacterArt.Kind.EMISSIVE] - चमक ही alpha) और
     * रोशनी थीम के गहरे accent की ओर थोड़ी झुकी, ताकि सफ़ेद पीछे पर भी साफ़ दिखे. पहले काला "मंच" (डिब्बा) बनता था - अब नहीं.
     * आकार / पिक्सल की जगह वही, एक बार बनता है (थीम का रंग बदले तभी नया).
     */
    @Synchronized fun lit(c: Context, res: Int, ink: Int): Bitmap? {
        val key = "$res|$ink"
        litCache[key]?.let { if (!it.isRecycled) return it }
        val b = get(c, res) ?: return null
        return try {
            val w = b.width; val h = b.height
            val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
            val p = CharacterArt.Profile(CharacterArt.Kind.EMISSIVE, CharacterArt.shapeOf(w, h), false, 0xFF000000.toInt())
            for (i in px.indices) {
                val q = CharacterArt.pixel(px[i], p, 0.5f, 0.5f, null, feather = false)
                // हल्के पीछे पर फीका न दिखे: धुँधले हिस्से भी थोड़े ठोस (1-(1-a)^2), रंग थीम की स्याही की ओर
                val a0 = (q ushr 24) / 255f
                val a1 = 1f - (1f - a0) * (1f - a0)
                px[i] = ((a1 * 255f).toInt().coerceIn(0, 255) shl 24) or (com.dk.assistant.ui.NeonTheme.mix(q, ink, 0.48f) and 0x00FFFFFF)
            }
            val out = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
            litCache.entries.removeAll { it.key.startsWith("$res|") }
            litCache[key] = out
            out
        } catch (e: Throwable) { com.dk.assistant.Quiet.note(e); null }
    }

    /** Settings की टाइल के लिए छोटा प्रीव्यू (उसी असली तस्वीर से, फ़िल्टर चालू) */
    fun thumb(c: Context, id: SpiritIdentity, h: Int): Bitmap? {
        val b = get(c, id.res) ?: return null
        val hh = h.coerceAtLeast(8)
        val w = (b.width.toFloat() * hh / b.height).toInt().coerceAtLeast(1)
        return try { Bitmap.createScaledBitmap(b, w, hh, true) } catch (e: Throwable) { com.dk.assistant.Quiet.note(e); null }
    }
}

/**
 * साझा animation इंजन (महिला और पुरुष दोनों यही). पूरी तस्वीर एक साथ हिलती है - बाल-बाल अलग नहीं, कुछ भी दोबारा नहीं बनता:
 * - लहर: [Canvas.drawBitmapMesh] की जाली, चेहरे और धड़ पर लगभग शून्य, बाल / धुएँ पर सबसे ज़्यादा, बहुत धीमी
 * - साँस: ऊपर के शरीर का बहुत छोटा scale / उठना
 * - aura: cyan-सफ़ेद नरम रोशनी, धीरे तेज़-मंद (कोई चमक-झपक नहीं)
 * - धुआँ: बाहरी हिस्सों में ऊपर और बगल को धीरे बहती लहर
 * - कण: कम, अलग-अलग धीमी रफ़्तार, कुछ तारे धीरे टिमटिमाते
 * काला पीछा SCREEN blend से ऐप के अँधेरे में घुल जाता - सिर्फ़ रोशनी दिखती. हर frame में कोई नई allocation नहीं.
 */
class SpiritEngine(val id: SpiritIdentity) {
    private val vCount = (COLS + 1) * (ROWS + 1)
    private val verts = FloatArray(vCount * 2)
    private val colors = IntArray(vCount)
    private val feather = FloatArray(vCount)
    private val warpW = FloatArray(vCount)
    private val smokeW = FloatArray(vCount)

    private val mesh = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) }
    private val auraP = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) }
    // हल्की थीम: SCREEN नहीं (सफ़ेद पर SCREEN = कुछ नहीं दिखता) - पारदर्शी तस्वीर सीधी, रोशनी / कण भी सीधे
    private val meshN = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val auraN = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotN = Paint(Paint.ANTI_ALIAS_FLAG)
    /** 26 नवंबर 2026: पूरी जगह का थीम वाला माहौल (कैरेक्टर के आसपास खाली अँधेरी / सफ़ेद जगह नहीं) */
    private val env = CharacterEnvironment()
    private var vw = 0f; private var vh = 0f

    val dst = RectF()
    private var gradFor = 0f
    private var auraCol = 0

    // कण (ऊपर बहते) और तारे (एक जगह टिमटिमाते) - तस्वीर के हिस्से के हिसाब से 0..1
    private val rnd = java.util.Random(if (id.key == SpiritIdentity.FEMALE) 7L else 11L)
    private val pX = FloatArray(MAX_PARTICLES); private val pY = FloatArray(MAX_PARTICLES)
    private val pS = FloatArray(MAX_PARTICLES); private val pR = FloatArray(MAX_PARTICLES); private val pPh = FloatArray(MAX_PARTICLES)
    private val sX = FloatArray(MAX_STARS); private val sY = FloatArray(MAX_STARS)
    private val sW = FloatArray(MAX_STARS); private val sPh = FloatArray(MAX_STARS); private val sR = FloatArray(MAX_STARS)

    /** धीरे-धीरे बदलने वाले मान */
    var presence = 0f; private set
    var presenceTarget = 1f
    var aura = SpiritVoiceBridge.aura(SpiritState.IDLE); private set
    private var energy = 0f       // THINKING में कण थोड़े तेज़
    private var voice = 0f        // बोलते समय TTS की आवाज़ (नरम)
    private var t = 0.0           // सेकंड
    private var lastColorsFor = -1f

    init {
        computeWeights()
        for (i in 0 until MAX_PARTICLES) spawn(i, rnd.nextFloat())
        for (i in 0 until MAX_STARS) {
            sX[i] = 0.06f + rnd.nextFloat() * 0.88f; sY[i] = 0.04f + rnd.nextFloat() * 0.7f
            sW[i] = 0.35f + rnd.nextFloat() * 0.6f; sPh[i] = rnd.nextFloat() * 6.28f; sR[i] = 0.6f + rnd.nextFloat() * 0.9f
        }
    }

    private fun spawn(i: Int, y: Float) {
        pX[i] = 0.05f + rnd.nextFloat() * 0.9f; pY[i] = y
        pS[i] = 0.006f + rnd.nextFloat() * 0.016f      // ऊँचाई का हिस्सा / सेकंड - बहुत धीमा, हर कण अलग
        pR[i] = 0.5f + rnd.nextFloat() * 1.1f; pPh[i] = rnd.nextFloat() * 6.28f
    }

    private fun smooth(a: Float, b: Float, x: Float): Float { val k = ((x - a) / (b - a)).coerceIn(0f, 1f); return k * k * (3 - 2 * k) }

    private fun computeWeights() {
        for (r in 0..ROWS) for (cI in 0..COLS) {
            val i = r * (COLS + 1) + cI
            val u = cI.toFloat() / COLS; val v = r.toFloat() / ROWS
            val fx = (u - id.faceX) / id.faceRx; val fy = (v - id.faceY) / id.faceRy
            val face = 1f - smooth(1.0f, 2.6f, fx * fx + fy * fy)          // चेहरा = 1 (स्थिर)
            val tx = kotlin.math.abs(u - id.torsoX) / 0.24f
            val torso = (1f - smooth(0.55f, 1.0f, tx)) * smooth(0.36f, 0.52f, v)
            val w = (1f - face) * (1f - 0.85f * torso)
            warpW[i] = w
            smokeW[i] = w * (0.35f + 0.65f * smooth(0.35f, 0.95f, v))
            // किनारे काले में घुलें (कटाई कभी न दिखे) - SCREEN में काला = कुछ नहीं
            val e = smooth(0f, id.fadeL, u) * smooth(0f, id.fadeT, v) * smooth(0f, id.fadeR, 1f - u) * smooth(0f, id.fadeB, 1f - v)
            feather[i] = e
        }
    }

    /** एक frame आगे: dt सेकंड. reduced = कम असर (लहर नहीं, कम कण) */
    fun step(dtIn: Float, state: SpiritState, amp: Float, reduced: Boolean) {
        val dt = dtIn.coerceIn(0f, 0.05f)
        t += dt
        val fade = 1f - exp(-dt / 0.16f)
        presence += (presenceTarget - presence) * fade
        if (kotlin.math.abs(presenceTarget - presence) < 0.004f) presence = presenceTarget
        // हर असर की अपनी धीमी रफ़्तार
        var target = SpiritVoiceBridge.aura(state)
        if (state == SpiritState.LISTENING) target += 0.05f * sin(t * 2.6).toFloat()           // नरम धड़कन
        val a = if (state == SpiritState.SPEAKING) (if (amp < 0f) 0.18f + 0.12f * sin(t * 3.1).toFloat() else amp) else 0f
        voice += (a - voice) * (1f - exp(-dt / 0.12f))
        target += 0.32f * voice
        aura += (target - aura) * (1f - exp(-dt / 0.55f))
        val en = if (state == SpiritState.THINKING) 1f else 0f
        energy += (en - energy) * (1f - exp(-dt / 0.9f))
        val n = if (reduced) REDUCED_PARTICLES else MAX_PARTICLES
        val mul = 1f + 1.2f * energy
        for (i in 0 until n) {
            pY[i] -= pS[i] * mul * dt
            if (pY[i] < -0.03f) spawn(i, 1.02f)
        }
    }

    /** animation बंद वाले फ़ोन पर: सीधे पूरा दिखो / छिपो */
    fun snap() { presence = presenceTarget }

    /** 27 नवंबर 2026 ([CharacterStage]): नीचे तैरते संदेश बॉक्स की सबसे बड़ी जगह (px) - चेहरा इसके ऊपर ही */
    var reserve = 0

    fun layout(vw: Int, vh: Int, bw: Int, bh: Int) {
        this.vw = vw.toFloat(); this.vh = vh.toFloat()
        if (vw <= 0 || vh <= 0 || bw <= 0 || bh <= 0) { dst.setEmpty(); return }
        var s = minOf(vw.toFloat() / bw, vh.toFloat() / bh)          // fit: पूरा सिर-बाल-शरीर, अनुपात वही, कभी न कटे
        // 27 नवंबर 2026: ऊपर से टिका (सिर के ऊपर थोड़ी जगह), चेहरा नीचे वाले बॉक्स के ऊपर - बॉक्स सिर्फ़ धड़ / धुआँ ढके
        val head = vh * CharacterArt.HEADROOM
        val floor = CharacterStage.faceFloor(vh, reserve)
        val faceBottom = (id.faceY + 0.10f).coerceIn(0.1f, 1f)
        s = minOf(s, (floor - head) / (bh * faceBottom), (vh - head) / bh)
        val w = bw * s; val h = bh * s
        val l = (vw - w) / 2f; val tp = head
        dst.set(l, tp, l + w, tp + h)
    }

    /**
     * [stage] = हल्की थीम: [lit] (काला पीछा पारदर्शी वाली तस्वीर) सीधे, बिना SCREEN और बिना काले मंच.
     * डार्क थीम: पहले जैसा SCREEN (काला पीछा ऐप के अँधेरे में घुलता). रोशनी / कण के रंग अभी की थीम के accent से.
     */
    fun draw(cv: Canvas, bmp: Bitmap?, reduced: Boolean, stage: Boolean, lit: Bitmap? = null) {
        if (bmp == null || dst.isEmpty || presence <= 0.001f) return
        val w = dst.width(); val h = dst.height()
        val k = com.dk.assistant.ui.ClayTheme.t
        val light = stage && lit != null
        val pal = CharacterArt.palette(k.bg, k.accent, k.light)
        // 27 नवंबर 2026: पीछे सिर्फ़ ऐप का अपना थीम-रंग (कोई अलग पट्टी / seam नहीं); थीम का हल्का color-grade
        val gf = env.filterOf(CharacterGrade.of(k.theme.id, k.bg, k.accent, k.light))
        mesh.colorFilter = gf; meshN.colorFilter = gf; glow.colorFilter = gf
        // पीछे की aura (तस्वीर के ऊपरी सीने / चेहरे के पीछे) - रंग थीम के accent से, cyan की ओर
        val ax = dst.left + id.faceX * w; val ay = dst.top + (id.faceY + 0.12f) * h; val ar = w * 0.62f
        // 26 नवंबर 2026: थीम की रोशनी की धड़कन - वही [CharacterMotion] जो होम के हर कैरेक्टर पर
        val lp = if (reduced) 0.5f else CharacterMotion.pulse(t.toFloat(), voice > 0.05f, voice)
        val pc = CharacterMotion.pulseColor(k.bg, k.accent, k.light)
        val inner = if (light) NeonTheme.mix(pal[1], pc, 0.35f) else NeonTheme.mix(0xFF8CE8F5.toInt(), pc, 0.30f)
        val gk = ax * 31f + ay * 7f + w + inner * 0.001f
        if (gradFor != gk || auraCol != inner) {
            gradFor = gk; auraCol = inner
            val g = RadialGradient(ax, ay, ar,
                intArrayOf(inner, NeonTheme.alpha(NeonTheme.mix(0xFF38BDF8.toInt(), k.accent, 0.4f), 0x55), 0x00000000), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            auraP.shader = g; auraN.shader = g
        }
        val breath = sin(t * 1.21).toFloat()          // ~5.2 सेकंड की साँस
        val aP = if (light) auraN else auraP
        aP.alpha = (presence * (if (light) 0.30f + 0.40f * aura else 0.10f + 0.34f * aura) * (0.8f + 0.4f * lp) * 255).toInt().coerceIn(0, 255)
        cv.drawCircle(ax, ay, ar, aP)          // गोल - किनारे पर कोई सीधी रेखा नहीं
        buildMesh(w, h, breath, reduced)
        val src = if (light) lit!! else bmp
        val mP = if (light) meshN else mesh
        mP.alpha = 255
        cv.drawBitmapMesh(src, COLS, ROWS, verts, 0, colors, 0, mP)
        // चमक बढ़ाने वाला दूसरा हल्का pass - उसी तस्वीर से (blur / नया रंग नहीं)
        val extra = ((aura - 0.32f) * 0.9f + voice * 0.22f).coerceIn(0f, 0.42f)
        if (extra > 0.01f) {
            val gP = if (light) meshN else glow
            gP.alpha = ((if (light) extra * 0.5f else extra) * 255).toInt()
            cv.drawBitmapMesh(src, COLS, ROWS, verts, 0, colors, 0, gP)
            meshN.alpha = 255
        }
        drawDots(w, h, cv, reduced, if (light) dotN else dot, if (light) pal[2] else NeonTheme.mix(0xFFE0FAFF.toInt(), pal[2], 0.35f))
        // 27 नवंबर 2026: चारों ओर (नीचे सबसे ज़्यादा) थीम के पीछे में घुलना - वही CharacterStage नियम
        if (vw > 0f && vh > 0f) env.drawEdges(cv, vw, vh, k.bg, vh * 0.08f, vh * 0.16f, vw * 0.08f)
    }

    private fun buildMesh(w: Float, h: Float, breath: Float, reduced: Boolean) {
        // 27 नवंबर 2026 (मालिक): कैरेक्टर का साइज़ / जगह कभी नहीं बदलती - साँस का scale / उठना हटा (सिर्फ़ बाल / धुएँ की हल्की लहर)
        val s = 1f
        val px = dst.centerX(); val py = dst.bottom
        val lift = 0f
        val aW = if (reduced) 0f else w * 0.0065f          // लहर: तस्वीर की चौड़ाई का ~0.65%
        val aS = if (reduced) 0f else w * 0.0095f          // धुआँ थोड़ा ज़्यादा
        val tt = t.toFloat()
        for (r in 0..ROWS) {
            val v = r.toFloat() / ROWS
            for (cI in 0..COLS) {
                val i = r * (COLS + 1) + cI
                val u = cI.toFloat() / COLS
                var x = dst.left + u * w; var y = dst.top + v * h
                // साँस: नीचे-बीच से बहुत छोटा फैलना + उठना (ऊपरी शरीर)
                x = px + (x - px) * s; y = py + (y - py) * s - lift * (1f - v * 0.6f)
                if (aW > 0f) {
                    val ww = warpW[i]
                    if (ww > 0.001f) {
                        val wave = sin(v * 6.1f + tt * 0.37f) * 0.62f + sin(u * 4.3f - tt * 0.23f + v * 1.9f) * 0.38f
                        x += ww * aW * wave
                        y += ww * aW * 0.45f * sin(u * 5.2f + tt * 0.29f)
                    }
                    val sw = smokeW[i]
                    if (sw > 0.001f) {
                        // phase ऊपर की ओर चलता = धुआँ धीरे ऊपर-बगल बहता
                        x += sw * aS * sin(v * 9.0f + tt * 0.52f + u * 2.0f)
                        y += sw * aS * 0.55f * sin(v * 7.0f + tt * 0.44f)
                    }
                }
                verts[i * 2] = x; verts[i * 2 + 1] = y
            }
        }
        if (lastColorsFor != presence) {
            lastColorsFor = presence
            for (i in 0 until vCount) {
                val g = (feather[i] * presence * 255f).toInt().coerceIn(0, 255)
                // alpha से घुलना: SCREEN (डार्क) और सीधी परत (हल्की थीम) दोनों में किनारा गायब
                colors[i] = (g shl 24) or 0xFFFFFF
            }
        }
    }

    private fun drawDots(w: Float, h: Float, cv: Canvas, reduced: Boolean, dot: Paint, tint: Int) {
        val d = w / 360f
        val n = if (reduced) REDUCED_PARTICLES else MAX_PARTICLES
        val br = presence * (0.75f + 0.35f * energy + 0.2f * voice)
        for (i in 0 until n) {
            val y = pY[i]
            val edge = smooth(-0.03f, 0.12f, y) * smooth(-0.02f, 0.18f, 1.02f - y)
            val x = pX[i] + 0.012f * sin(t * 0.31 + pPh[i]).toFloat()
            dot.color = (tint and 0xFFFFFF) or ((br * edge * 120f).toInt().coerceIn(0, 255) shl 24)
            cv.drawCircle(dst.left + x * w, dst.top + y * h, pR[i] * d * 1.4f, dot)
        }
        val ns = if (reduced) REDUCED_STARS else MAX_STARS
        for (i in 0 until ns) {
            val k = 0.5f + 0.5f * sin(t * sW[i] + sPh[i]).toFloat()
            dot.color = (NeonTheme.mix(tint, 0xFFFFFFFF.toInt(), 0.5f) and 0xFFFFFF) or ((presence * k * k * 170f).toInt().coerceIn(0, 255) shl 24)
            cv.drawCircle(dst.left + sX[i] * w, dst.top + sY[i] * h, sR[i] * d, dot)
        }
    }

    companion object {
        const val COLS = 24
        const val ROWS = 28
        const val MAX_PARTICLES = 22
        const val REDUCED_PARTICLES = 10
        const val MAX_STARS = 12
        const val REDUCED_STARS = 6
    }
}

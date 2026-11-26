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
    private val stageP = Paint(Paint.ANTI_ALIAS_FLAG)

    val dst = RectF()
    private var gradFor = 0f
    private var stageFor = 0f

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

    fun layout(vw: Int, vh: Int, bw: Int, bh: Int) {
        if (vw <= 0 || vh <= 0 || bw <= 0 || bh <= 0) { dst.setEmpty(); return }
        val s = minOf(vw.toFloat() / bw, vh.toFloat() / bh)          // fit: पूरा सिर-बाल-शरीर, अनुपात वही, कभी न कटे
        val w = bw * s; val h = bh * s
        val l = (vw - w) / 2f; val tp = (vh - h) / 2f
        dst.set(l, tp, l + w, tp + h)
    }

    fun draw(cv: Canvas, bmp: Bitmap?, reduced: Boolean, stage: Boolean) {
        if (bmp == null || dst.isEmpty || presence <= 0.001f) return
        val w = dst.width(); val h = dst.height()
        if (stage) drawStage(cv, w)
        // पीछे की aura (तस्वीर के ऊपरी सीने / चेहरे के पीछे)
        val ax = dst.left + id.faceX * w; val ay = dst.top + (id.faceY + 0.12f) * h; val ar = w * 0.62f
        val gk = ax * 31f + ay * 7f + w
        if (gradFor != gk) {
            gradFor = gk
            auraP.shader = RadialGradient(ax, ay, ar,
                intArrayOf(0xFF8CE8F5.toInt(), 0x5538BDF8, 0x00000000), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        }
        val breath = sin(t * 1.21).toFloat()          // ~5.2 सेकंड की साँस
        auraP.alpha = (presence * (0.10f + 0.34f * aura) * 255).toInt().coerceIn(0, 255)
        cv.drawCircle(ax, ay, ar, auraP)          // गोल - किनारे पर कोई सीधी रेखा नहीं
        buildMesh(w, h, breath, reduced)
        mesh.alpha = 255
        cv.drawBitmapMesh(bmp, COLS, ROWS, verts, 0, colors, 0, mesh)
        // चमक बढ़ाने वाला दूसरा हल्का pass - उसी तस्वीर से (blur / नया रंग नहीं)
        val extra = ((aura - 0.32f) * 0.9f + voice * 0.22f).coerceIn(0f, 0.42f)
        if (extra > 0.01f) { glow.alpha = (extra * 255).toInt(); cv.drawBitmapMesh(bmp, COLS, ROWS, verts, 0, colors, 0, glow) }
        drawDots(w, h, cv, reduced)
    }

    /** हल्की थीम में SCREEN का काला सफ़ेद पर नहीं घुलता - पीछे एक नरम अँधेरा मंच */
    private fun drawStage(cv: Canvas, w: Float) {
        val sk = dst.centerX() * 31f + dst.centerY() * 7f + w
        if (stageFor != sk) {
            stageFor = sk
            stageP.shader = RadialGradient(dst.centerX(), dst.centerY(), maxOf(dst.width(), dst.height()) * 0.62f,
                intArrayOf(0xFF05070D.toInt(), 0xF005070D.toInt(), 0x0005070D), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        }
        stageP.alpha = (presence * 255).toInt().coerceIn(0, 255)
        cv.drawRect(dst.left - w * 0.1f, dst.top - w * 0.1f, dst.right + w * 0.1f, dst.bottom + w * 0.1f, stageP)
    }

    private fun buildMesh(w: Float, h: Float, breath: Float, reduced: Boolean) {
        val s = 1f + 0.0045f * breath
        val px = dst.centerX(); val py = dst.bottom
        val lift = 0.0022f * h * breath
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
                colors[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
        }
    }

    private fun drawDots(w: Float, h: Float, cv: Canvas, reduced: Boolean) {
        val d = w / 360f
        val n = if (reduced) REDUCED_PARTICLES else MAX_PARTICLES
        val br = presence * (0.75f + 0.35f * energy + 0.2f * voice)
        for (i in 0 until n) {
            val y = pY[i]
            val edge = smooth(-0.03f, 0.12f, y) * smooth(-0.02f, 0.18f, 1.02f - y)
            val x = pX[i] + 0.012f * sin(t * 0.31 + pPh[i]).toFloat()
            dot.color = 0xE0FAFF or ((br * edge * 120f).toInt().coerceIn(0, 255) shl 24)
            cv.drawCircle(dst.left + x * w, dst.top + y * h, pR[i] * d * 1.4f, dot)
        }
        val ns = if (reduced) REDUCED_STARS else MAX_STARS
        for (i in 0 until ns) {
            val k = 0.5f + 0.5f * sin(t * sW[i] + sPh[i]).toFloat()
            dot.color = 0xFFFFFF or ((presence * k * k * 170f).toInt().coerceIn(0, 255) shl 24)
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

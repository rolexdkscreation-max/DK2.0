package com.dk.assistant.dkai

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout

/** कम असर वाला मोड: Settings का स्विच, बैटरी सेवर या कम RAM वाला फ़ोन */
object SpiritFx {
    const val PREF_REDUCED = "spirit_reduced"

    fun reducedPref(c: Context): Boolean = try {
        c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE).getBoolean(PREF_REDUCED, false)
    } catch (e: Exception) { com.dk.assistant.Quiet.note(e); false }

    fun setReduced(c: Context, on: Boolean) {
        c.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE).edit().putBoolean(PREF_REDUCED, on).apply()
    }

    fun reduced(c: Context): Boolean {
        if (reducedPref(c)) return true
        val pm = c.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        return pm?.isPowerSaveMode == true || am?.isLowRamDevice == true
    }

    /** फ़ोन में animation बंद (Developer options › animator scale 0) = कैरेक्टर स्थिर */
    fun still(): Boolean = !android.animation.ValueAnimator.areAnimatorsEnabled()
}

/**
 * 26 नवंबर 2026 (मालिक): ethereal कैरेक्टर का View - साझा [SpiritEngine], हालत [SpiritVoiceBridge] से.
 * दिखता नहीं (छिपा / स्क्रीन बंद / हटाया) = frame loop रुका. पारदर्शिता View alpha से नहीं (offscreen layer में
 * SCREEN blend टूटता) - इंजन की presence से. तस्वीर कभी नहीं बदलती; बस धीरे जीवित लगती है.
 */
open class SpiritCharacterView(c: Context, val identity: SpiritIdentity) : View(c) {
    val engine = SpiritEngine(identity)
    var bridge = SpiritVoiceBridge()
    private val bmp = SpiritArt.get(c, identity.res)
    private var reduced = SpiritFx.reduced(c)
    private var still = SpiritFx.still()
    private var visibleNow = false
    private var last = 0L
    private var fadeDone: (() -> Unit)? = null
    /** हल्की थीम: काला पीछा पारदर्शी वाली तस्वीर सीधे (26 नवंबर 2026 - अब काला मंच / डिब्बा नहीं) */
    var stage = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        engine.presenceTarget = 1f
    }

    override fun hasOverlappingRendering(): Boolean = false

    /** दिखाओ (धीरे उभरे) */
    fun fadeIn() { fadeDone = null; engine.presenceTarget = 1f; visibility = VISIBLE; refreshFx(); kick() }

    /** धीरे ओझल, फिर [done] */
    fun fadeOut(done: () -> Unit) {
        engine.presenceTarget = 0f
        fadeDone = done
        if (!visibleNow || still) { finishFade(); return }
        kick()
    }

    private fun finishFade() { val d = fadeDone; fadeDone = null; d?.invoke() }

    fun refreshFx() { reduced = SpiritFx.reduced(context); still = SpiritFx.still() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        engine.layout(w - paddingLeft - paddingRight, h - paddingTop - paddingBottom, bmp?.width ?: 0, bmp?.height ?: 0)
        engine.dst.offset(paddingLeft.toFloat(), paddingTop.toFloat())
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        visibleNow = isVisible
        if (isVisible) {
            refreshFx(); kick()
        } else {
            last = 0L
            if (fadeDone != null) finishFade()
        }
    }

    override fun onDetachedFromWindow() { visibleNow = false; last = 0L; super.onDetachedFromWindow() }

    private fun kick() { last = 0L; postInvalidateOnAnimation() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 0.016f else (now - last) / 1000f
        last = now
        val st = bridge.tick(System.currentTimeMillis())
        engine.step(dt, st, bridge.amplitude, reduced)
        if (still) engine.snap()
        val lit = if (stage) {
            val k = com.dk.assistant.ui.ClayTheme.t
            SpiritArt.lit(context, identity.res, com.dk.assistant.ui.NeonTheme.mix(k.accent, k.text, 0.45f))
        } else null
        engine.draw(canvas, bmp, reduced || still, stage, lit)
        if (fadeDone != null && engine.presence <= 0f) { post { finishFade() }; return }
        if (!visibleNow || still || !isAttachedToWindow) return
        if (reduced) postInvalidateDelayed(REDUCED_FRAME_MS) else postInvalidateOnAnimation()
    }

    companion object { const val REDUCED_FRAME_MS = 33L }
}

/** महिला कैरेक्टर - लंबे लहराते बाल (अपनी तस्वीर, अपनी पहचान) */
class FemaleCharacterScreen(c: Context) : SpiritCharacterView(c, SpiritIdentity.FEMALE_ID)

/** पुरुष कैरेक्टर - छोटे पीछे मुड़े बाल, चौड़े कंधे (अपनी तस्वीर, अपनी पहचान) */
class MaleCharacterScreen(c: Context) : SpiritCharacterView(c, SpiritIdentity.MALE_ID)

/**
 * एक समय में सिर्फ़ एक कैरेक्टर: बदलने पर पुराना धीरे ओझल, फिर नया धीरे उभरे - दोनों साथ कभी नहीं.
 */
class SpiritCharacterHost(c: Context) : FrameLayout(c) {
    private var female: FemaleCharacterScreen? = null
    private var male: MaleCharacterScreen? = null
    var current: String? = null; private set

    init { clipChildren = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun hasOverlappingRendering(): Boolean = false

    fun view(key: String): SpiritCharacterView = if (key == SpiritIdentity.MALE) {
        male ?: MaleCharacterScreen(context).also { male = it; it.visibility = GONE; addView(it, LayoutParams(-1, -1)) }
    } else {
        female ?: FemaleCharacterScreen(context).also { female = it; it.visibility = GONE; addView(it, LayoutParams(-1, -1)) }
    }

    /** [SpiritIdentity.FEMALE] / [SpiritIdentity.MALE] */
    fun show(key: String) {
        val k = if (key == SpiritIdentity.MALE) SpiritIdentity.MALE else SpiritIdentity.FEMALE
        val light = com.dk.assistant.ui.ClayTheme.t.light
        female?.stage = light; male?.stage = light
        if (k == current) { view(k).let { it.stage = light; if (it.visibility != VISIBLE) it.fadeIn() else it.refreshFx() }; return }
        val old = current?.let { view(it) }
        current = k
        val next = view(k).also { it.stage = light }
        if (old == null || old.visibility != VISIBLE) { old?.visibility = GONE; next.fadeIn(); return }
        old.fadeOut {
            old.visibility = GONE
            if (current == k) next.fadeIn()
        }
    }

    /** दोनों बंद (ORB / अवतार चुना) */
    fun hideAll() { female?.visibility = GONE; male?.visibility = GONE; current = null }
}

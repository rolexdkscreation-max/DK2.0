package com.dk.assistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import com.dk.assistant.backend.Plan

/**
 * 25 नवंबर 2026 (मालिक): प्रोफ़ाइल में असली नाम के आगे Instagram जैसा verified टिक - लाइसेंस के हिसाब से अलग रंग.
 * मालिक (Google लॉगिन, सर्वर ने माना) = सुनहरा, प्रीमियम = बैंगनी, बेसिक = नीला, मुफ़्त = कोई टिक नहीं.
 * DK किस नाम से बुलाए वाले खाने में कभी टिक नहीं. [tier] pure (JVM test).
 */
object VerifiedBadge {
    enum class Tier(val label: String, val hi: String, val color: Int) {
        OWNER("Owner", "मालिक (Boss)", 0xFFF5B301.toInt()),
        PREMIUM("Premium", "प्रीमियम", 0xFF8B5CF6.toInt()),
        BASIC("Basic", "बेसिक", 0xFF0095F6.toInt()),
    }

    /** owner = मालिक लॉगिन है; saved/until/now = याद रखा लाइसेंस और उसकी मियाद. null = टिक नहीं (मुफ़्त / मियाद ख़त्म) */
    fun tier(owner: Boolean, saved: Plan.Level, until: Long, now: Long): Tier? {
        if (owner || saved == Plan.Level.OWNER) return if (owner) Tier.OWNER else null
        return when (Plan.effective(saved, until, now)) {
            Plan.Level.PREMIUM -> Tier.PREMIUM
            Plan.Level.BASIC -> Tier.BASIC
            else -> null
        }
    }

    /** असली नाम लिखा हो और लाइसेंस हो तभी टिक */
    fun show(realName: String, t: Tier?): Boolean = t != null && realName.isNotBlank()

    fun of(c: Context, now: Long = System.currentTimeMillis()): Tier? = try {
        tier(com.dk.assistant.backend.BackendSession.owner(c), Plan.saved(c), Plan.savedUntil(c), now)
    } catch (_: Exception) { null }
}

/** लहरदार गोल (rosette) + बीच में सफ़ेद ✓ - डार्क और लाइट दोनों पर साफ़ (गहरा रंग, सफ़ेद निशान) */
class VerifiedTickDrawable(private val color: Int) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = this@VerifiedTickDrawable.color }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; this.color = 0xFFFFFFFF.toInt(); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()).toFloat()
        if (s <= 0f) return
        val cx = b.exactCenterX(); val cy = b.exactCenterY(); val r = s / 2f
        // 12 छोटे गोले + बीच का गोला = Instagram जैसा लहरदार किनारा
        canvas.drawCircle(cx, cy, r * 0.80f, fill)
        for (i in 0 until BUMPS) {
            val a = Math.PI * 2 * i / BUMPS
            canvas.drawCircle(cx + (r * 0.76f * Math.cos(a)).toFloat(), cy + (r * 0.76f * Math.sin(a)).toFloat(), r * 0.24f, fill)
        }
        tick.strokeWidth = s * 0.11f
        val l = cx - r; val t = cy - r
        path.reset()
        path.moveTo(l + s * 0.30f, t + s * 0.52f)
        path.lineTo(l + s * 0.44f, t + s * 0.65f)
        path.lineTo(l + s * 0.71f, t + s * 0.37f)
        canvas.drawPath(path, tick)
    }

    override fun setAlpha(alpha: Int) { fill.alpha = alpha; tick.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { fill.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object { const val BUMPS = 12 }
}

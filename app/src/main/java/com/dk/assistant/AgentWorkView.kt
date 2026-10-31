package com.dk.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * v5.1 (मालिक): कोडिंग टीम में अभी कौन सा एजेंट काम कर रहा है - नाम + animation.
 * ऊपर सारे एजेंट की पंक्ति (सब मैनेजर के नीचे), जो काम कर रहा है वह चमकता/धड़कता है,
 * उसके नीचे स्कैन करती रोशनी और "<icon> <नाम> काम कर रहा है…" (बिंदु बढ़ते हैं)।
 * Android-मुक्त हिस्सा (कौन सा एजेंट, लेबल) = [AgentWork] - JVM test होता है।
 */
object AgentWork {
    /** स्क्रीन पर टीम की पंक्ति का क्रम (v5.2: 7 एजेंट + समझाने वाला): मैनेजर सबसे पहले (सबका मालिक) */
    val ORDER: List<Seven.Member> = listOf(Seven.Member.MANAGER) + Seven.Member.values().filter { it != Seven.Member.MANAGER }

    /** टीम की लाइन "👔 मैनेजर: …" से सदस्य */
    fun parse(line: String): Seven.Member? = Seven.Member.values().firstOrNull { line.startsWith("${it.icon} ${it.hi}:") }

    fun label(a: Seven.Member?, tick: Int): String {
        val dots = ".".repeat(tick % 4)
        return if (a == null) "👔 मैनेजर काम बाँट रहा है$dots" else "${a.icon} ${a.hi} काम कर रहा है$dots"
    }

    /** मैनेजर के अलावा कोई एजेंट = "मैनेजर ने उसे काम सौंपा" */
    fun underManager(a: Seven.Member?): String = if (a == null || a == Seven.Member.MANAGER) "👔 मैनेजर" else "👔 मैनेजर → ${a.icon} ${a.hi}"
}

class AgentWorkView(c: Context) : View(c) {
    var active: Seven.Member? = null
        set(v) { field = v; invalidate() }
    var note: String = ""
        set(v) { field = v; invalidate() }
    private var start = System.currentTimeMillis()
    private val dp = resources.displayMetrics.density
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * resources.displayMetrics.density }
    private val emoji = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 14f * resources.displayMetrics.density }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00E676.toInt(); textSize = 13f * resources.displayMetrics.density }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99FFFFFF.toInt(); textSize = 11f * resources.displayMetrics.density }
    private val scan = Paint(Paint.ANTI_ALIAS_FLAG)

    /** चौड़ाई से लंबा = "…" पर काटो (लाइन बाहर न जाए) */
    private fun fit(s: String, p: Paint, max: Float): String {
        if (max <= 0 || p.measureText(s) <= max) return s
        val n = p.breakText(s, true, max - p.measureText("…"), null)
        return s.take(n.coerceAtLeast(0)) + "…"
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (78 * dp).toInt())
    }

    override fun onDraw(cv: Canvas) {
        val t = (System.currentTimeMillis() - start) / 1000f
        val n = AgentWork.ORDER.size
        val w = width.toFloat()
        val cell = w / n
        val r = minOf(cell * 0.40f, 14 * dp)
        val cy = 18 * dp
        AgentWork.ORDER.forEachIndexed { i, a ->
            val cx = cell * i + cell / 2
            val on = a == active
            val pulse = if (on) 1f + 0.18f * kotlin.math.sin(t * 6f) else 1f
            chip.color = if (on) 0x5500E676 else 0x22FFFFFF
            cv.drawCircle(cx, cy, r * pulse, chip)
            if (on) {
                ring.color = 0xFF00E676.toInt(); ring.alpha = (140 + 115 * kotlin.math.sin(t * 6f)).toInt().coerceIn(40, 255)
                cv.drawCircle(cx, cy, r * pulse + 3 * dp, ring)
            }
            emoji.alpha = if (on) 255 else 110
            cv.drawText(a.icon, cx, cy + emoji.textSize / 3, emoji)
        }
        // मैनेजर से काम कर रहे एजेंट तक चलती रोशनी (सब मैनेजर के नीचे)
        val idx = AgentWork.ORDER.indexOf(active)
        if (idx > 0) {
            val x0 = cell / 2; val x1 = cell * idx + cell / 2
            val p = (t * 1.2f) % 1f
            scan.color = 0x6600E676
            cv.drawRect(RectF(x0, cy + r + 4 * dp, x1, cy + r + 5.5f * dp), scan)
            scan.color = 0xFF00E676.toInt()
            cv.drawCircle(x0 + (x1 - x0) * p, cy + r + 4.75f * dp, 2.6f * dp, scan)
        }
        val tick = (t * 2.5f).toInt()
        cv.drawText(fit(AgentWork.label(active, tick), txt, w - 8 * dp), 4 * dp, 56 * dp, txt)
        val s = AgentWork.underManager(active) + if (note.isNotBlank()) " • " + note.lineSequence().first() else ""
        cv.drawText(fit(s, sub, w - 8 * dp), 4 * dp, 72 * dp, sub)
        if (isShown) postInvalidateDelayed(50)
    }
}

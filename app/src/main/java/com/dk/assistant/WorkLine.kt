package com.dk.assistant

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.dk.assistant.dkai.ComputerEyes

/**
 * 14 नवंबर 2026 (मालिक): DK Computer और कोडिंग एजेंट में - होम जैसा छोटा "कंप्यूटर का चित्र" और उसके बगल
 * एक लाइन में अभी का असली काम: "ये ढूँढ रहा हूँ", "PR चेक कर रहा हूँ", "CI टेस्टिंग चला रहा हूँ"…
 * सिर्फ़ असली कदम (s लाइन / अभी का कदम) से - अपने मन से कुछ नहीं.
 */
object WorkLine {
    const val MAX = 80
    const val IDLE = "तैयार — काम लिखिए"
    const val TICK_MS = 500L

    private val ROLE = Regex("^(कोऑर्डिनेटर|बिल्डर|रिव्यूअर|डिबगर|टेस्टर|डिप्लॉयमेंट|मैनेजर|प्लानर|सुझाव)\\s*:\\s*")
    // चित्र-चिह्न (emoji) और सजावट हटाओ - एक साफ़ लाइन
    private val EMOJI = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE0F}\\x{200D}\\x{2B00}-\\x{2BFF}]")

    /** कदम की लाइन -> स्क्रीन की एक छोटी हिंदी लाइन */
    fun of(raw: String?): String {
        var t = (raw ?: "").replace(EMOJI, "").replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        t = t.replace(ROLE, "").trim().trimStart('•', '-', '*', ':', ' ')
        if (t.isEmpty()) return ""
        val low = t.lowercase()
        val said = when {
            Regex("\\bpr\\b|pull request|merge").containsMatchIn(low) -> "हाँ, PR चेक कर रहा हूँ…"
            Regex("\\bci\\b|build|test|टेस्ट|बिल्ड(?!र)|apk|workflow").containsMatchIn(low) -> "हाँ, CI टेस्टिंग चला रहा हूँ…"
            Regex("push|commit").containsMatchIn(low) -> "GitHub पर बदलाव भेज रहा हूँ…"
            Regex("backup").containsMatchIn(low) -> "पहले backup ले रहा हूँ…"
            Regex("branch").containsMatchIn(low) -> "काम की branch बना रहा हूँ…"
            low.contains("जड़") -> "गलती की जड़ ढूँढ रहा हूँ…"
            low.startsWith("खोज") || low.contains("search") -> "ये ढूँढ रहा हूँ: " + t.substringAfter(':', t).trim()
            low.startsWith("पढ़ रहा") || low.contains("http") -> "पेज पढ़ रहा हूँ: " + host(t)
            else -> t
        }
        return if (said.length > MAX) said.take(MAX - 1).trimEnd() + "…" else said
    }

    /** लिंक से सिर्फ़ वेबसाइट का नाम */
    fun host(t: String): String {
        val u = Regex("https?://([^/\\s]+)").find(t)?.groupValues?.get(1) ?: return t.substringAfter(':', t).trim()
        return u.removePrefix("www.")
    }

    /** काम चल रहा = अभी का कदम (या आख़िरी s लाइन); रुका = "तैयार" */
    fun pick(busy: Boolean, now: String?, lastStep: String?): String {
        if (!busy) return IDLE
        return of(now).ifEmpty { of(lastStep) }.ifEmpty { "काम कर रहा हूँ…" }
    }
}

/** होम (DKAI) जैसी पट्टी: [कंप्यूटर की आँखें] + एक लाइन (हर आधे सेकंड ताज़ा) */
class WorkLineStrip(c: Context, private val state: () -> Pair<Boolean, String>) : LinearLayout(c) {
    private val dp = resources.displayMetrics.density
    private val eyes = ComputerEyes(c)
    private val line = TextView(c).apply {
        textSize = 13f; setTextColor(0xFFCBD5E1.toInt()); maxLines = 1; isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        UiLang.keep(this)
    }
    private val h = Handler(Looper.getMainLooper())
    private var last = ""
    private val tick = object : Runnable {
        override fun run() { refresh(); h.postDelayed(this, WorkLine.TICK_MS) }
    }

    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        val p = (10 * dp).toInt(); setPadding(p, (6 * dp).toInt(), p, (6 * dp).toInt())
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 14 * dp; setColor(PlainLook.CARD); setStroke(dp.toInt().coerceAtLeast(1), PlainLook.BORDER)
        }
        addView(eyes, LayoutParams((26 * dp).toInt(), (26 * dp).toInt()).apply { marginEnd = (10 * dp).toInt() })
        addView(line, LayoutParams(0, -2, 1f))
        contentDescription = "DK अभी क्या कर रहा है"
    }

    fun refresh() {
        val (busy, txt) = try { state() } catch (_: Exception) { false to "" }
        eyes.working = busy
        eyes.color = if (busy) 0xFF00E5FF.toInt() else 0xFF9CA3AF.toInt()
        val show = if (busy) txt.ifBlank { "काम कर रहा हूँ…" } else WorkLine.IDLE
        if (show != last) { last = show; line.text = show }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); h.removeCallbacks(tick); h.post(tick) }
    override fun onDetachedFromWindow() { h.removeCallbacks(tick); super.onDetachedFromWindow() }
}

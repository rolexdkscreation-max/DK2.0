package com.dk.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.sin

/**
 * नवंबर 2026 (मालिक): 4-स्क्रीन "Futuristic 3D Glass" रूप के साझा हिस्से - सिर्फ़ दिखावट.
 * कोई logic नहीं बदलता: हर हिस्सा असली डेटा (AI का नाम, काम की हालत, कोड) से भरता है.
 *
 * [Logic] = Android के बिना (unit test होता है); [FutureViews] = views बनाना.
 */
object FutureUi {
    /** संदेश का एक टुकड़ा: आम पाठ या ``` वाला कोड */
    data class Part(val code: Boolean, val text: String, val lang: String = "")

    data class Span(val start: Int, val end: Int, val kind: Kind)
    enum class Kind { KEYWORD, STRING, COMMENT, NUMBER }

    private val KEYWORDS = setOf(
        "fun", "val", "var", "class", "object", "interface", "if", "else", "when", "for", "while", "return",
        "import", "package", "private", "public", "internal", "override", "true", "false", "null", "def",
        "const", "let", "function", "static", "void", "new", "try", "catch", "finally", "throw", "suspend",
        "data", "enum", "async", "await", "from", "in", "is", "as", "this", "super", "lateinit", "int", "string"
    )

    fun hasCode(t: String): Boolean = t.contains("```")

    /** ``` बाड़ से पाठ और कोड अलग करो. अधूरा (बंद न हुआ) ``` = बाक़ी सब कोड */
    fun split(t: String): List<Part> {
        if (!hasCode(t)) return if (t.isBlank()) emptyList() else listOf(Part(false, t))
        val out = mutableListOf<Part>()
        var i = 0
        while (i < t.length) {
            val s = t.indexOf("```", i)
            if (s < 0) { t.substring(i).trim('\n').takeIf { it.isNotBlank() }?.let { out += Part(false, it) }; break }
            t.substring(i, s).trim('\n').takeIf { it.isNotBlank() }?.let { out += Part(false, it) }
            val nl = t.indexOf('\n', s + 3)
            val lang = if (nl < 0) "" else t.substring(s + 3, nl).trim().takeIf { it.length <= 20 && !it.contains(' ') }.orEmpty()
            val bodyStart = if (nl < 0) t.length else nl + 1
            val e = t.indexOf("```", bodyStart)
            val body = if (e < 0) t.substring(bodyStart) else t.substring(bodyStart, e)
            out += Part(true, body.trimEnd('\n', ' '), lang)
            i = if (e < 0) t.length else e + 3
        }
        return out
    }

    /** बाईं ओर लाइन नंबर (IDE जैसा). gutter की चौड़ाई सबसे बड़े नंबर जितनी */
    fun gutter(lineCount: Int): Int = lineCount.coerceAtLeast(1).toString().length

    /** एक लाइन में रंग: कीवर्ड / "पाठ" / // टिप्पणी / नंबर (सस्ता, regex-मुक्त) */
    fun spans(line: String): List<Span> {
        val out = mutableListOf<Span>()
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                (ch == '/' && i + 1 < line.length && line[i + 1] == '/') || (ch == '#' && line.substring(0, i).isBlank()) -> {
                    out += Span(i, line.length, Kind.COMMENT); return out
                }
                ch == '"' || ch == '\'' -> {
                    var j = i + 1
                    while (j < line.length && line[j] != ch) { if (line[j] == '\\') j++; j++ }
                    val end = (j + 1).coerceAtMost(line.length)
                    out += Span(i, end, Kind.STRING); i = end
                }
                ch.isLetter() || ch == '_' -> {
                    var j = i
                    while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '_')) j++
                    if (line.substring(i, j) in KEYWORDS) out += Span(i, j, Kind.KEYWORD)
                    i = j
                }
                ch.isDigit() -> {
                    var j = i
                    while (j < line.length && (line[j].isLetterOrDigit() || line[j] == '.')) j++
                    out += Span(i, j, Kind.NUMBER); i = j
                }
                else -> i++
            }
        }
        return out
    }

    /** टेलीमेट्री के लिए छोटा इंजन नाम: "OpenRouter • claude-sonnet" → "claude-sonnet" */
    fun engineTag(label: String?): String {
        val l = label?.trim().orEmpty()
        if (l.isEmpty()) return "AI नहीं"
        val last = l.split('•', '|', '/').map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: l
        return if (last.length > 28) last.take(27) + "…" else last
    }
}

/** Futuristic रूप के view (रंग, काँच, कोड पैनल, टेलीमेट्री) */
object FutureViews {
    const val CYAN = 0xFF22D3EE.toInt()
    const val VIOLET = 0xFFA855F7.toInt()
    const val GREEN = 0xFF34D399.toInt()
    const val AMBER = 0xFFFBBF24.toInt()
    const val CODE_BG = 0xFF0A0F1D.toInt()

    private fun dp(c: Context) = c.resources.displayMetrics.density

    /** चमकदार बड़ा शीर्षक (DK COMPUTER / DK CODING AGENT) */
    fun title(c: Context, text: String, gold: Boolean = false): TextView = TextView(c).apply {
        this.text = text; textSize = 18f; setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.12f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        setTextColor(Color.WHITE)
        setShadowLayer(14f, 0f, 0f, if (gold) 0x88E8B64C.toInt() else 0xAA22D3EE.toInt())
        if (gold) goldShader(this)
    }

    /** धातु जैसा सुनहरा पाठ (CONTROL CENTER) */
    fun goldShader(tv: TextView) {
        tv.post {
            val h = tv.textSize.coerceAtLeast(1f)
            tv.paint.shader = LinearGradient(0f, 0f, 0f, h,
                intArrayOf(0xFFFFF3C4.toInt(), 0xFFE8B64C.toInt(), 0xFF9C6B1E.toInt(), 0xFFFFE7A0.toInt()),
                floatArrayOf(0f, 0.45f, 0.7f, 1f), Shader.TileMode.CLAMP)
            tv.invalidate()
        }
    }

    /** टेलीमेट्री का छोटा निऑन टैग */
    fun chip(c: Context, text: String, color: Int): TextView = TextView(c).apply {
        val d = dp(c)
        this.text = text; textSize = 11f; setTextColor(color); maxLines = 1
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding((10 * d).toInt(), (4 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
        background = GradientDrawable().apply {
            cornerRadius = 99 * d
            setColor((color and 0x00FFFFFF) or 0x1F000000)
            setStroke((1 * d).toInt().coerceAtLeast(1), (color and 0x00FFFFFF) or 0x88000000.toInt())
        }
    }

    /** उपयोगकर्ता का बुलबुला: गहरा नीला → बैंगनी, 16dp कोने */
    fun userBubble(c: Context): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF1E1B4B.toInt(), 0xFF3B0764.toInt())
    ).apply {
        val d = dp(c); cornerRadius = 16 * d
        setStroke((1 * d).toInt().coerceAtLeast(1), 0x66A78BFA)
    }

    /** सेटिंग्स के रंगीन squircle आइकन का पीछे का हिस्सा */
    fun squircle(c: Context, color: Int): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, intArrayOf((color and 0x00FFFFFF) or 0x55000000, (color and 0x00FFFFFF) or 0x14000000)
    ).apply {
        val d = dp(c); cornerRadius = 14 * d
        setStroke((1 * d).toInt().coerceAtLeast(1), (color and 0x00FFFFFF) or 0x77000000)
    }

    /** IDE जैसा कोड पैनल: ऊपर भाषा + कॉपी, नीचे लाइन नंबर + रंगीन कोड */
    fun codePanel(c: Context, code: String, lang: String, onCopy: (String) -> Unit): LinearLayout {
        val d = dp(c)
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(CODE_BG); cornerRadius = 12 * d
                setStroke((1 * d).toInt().coerceAtLeast(1), 0x4422D3EE)
            }
            val p = (10 * d).toInt(); setPadding(p, (8 * d).toInt(), p, p)
        }
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(c).apply {
            text = lang.ifBlank { "code" }; textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(CYAN)
            UiLang.keep(this)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(chip(c, "कॉपी", CYAN).apply {
            isClickable = true; contentDescription = "कोड कॉपी करें"
            setOnClickListener { onCopy(code) }
        })
        box.addView(head)
        val hs = HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false }
        hs.addView(TextView(c).apply {
            text = highlighted(code); textSize = 12f; typeface = Typeface.MONOSPACE
            setTextColor(0xE6E2E8F0.toInt()); setTextIsSelectable(true); setLineSpacing(0f, 1.12f)
            setPadding(0, (6 * d).toInt(), 0, 0)
            UiLang.keep(this)
        })
        box.addView(hs)
        return box
    }

    fun highlighted(code: String): CharSequence {
        val lines = code.split('\n')
        val w = FutureUi.gutter(lines.size)
        val sb = SpannableStringBuilder()
        lines.forEachIndexed { n, line ->
            val g = (n + 1).toString().padStart(w) + "  "
            val gs = sb.length
            sb.append(g)
            sb.setSpan(ForegroundColorSpan(0x5594A3B8), gs, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val ls = sb.length
            sb.append(line)
            FutureUi.spans(line).forEach { s ->
                val col = when (s.kind) {
                    FutureUi.Kind.KEYWORD -> CYAN
                    FutureUi.Kind.STRING -> 0xFF86EFAC.toInt()
                    FutureUi.Kind.COMMENT -> 0x9964748B.toInt()
                    FutureUi.Kind.NUMBER -> 0xFFF0ABFC.toInt()
                }
                sb.setSpan(ForegroundColorSpan(col), ls + s.start, ls + s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (n < lines.size - 1) sb.append('\n')
        }
        return sb
    }

    /**
     * जवाब में ``` कोड हो तो: पाठ + कोड पैनल. [into] = DK का बुलबुला (उसी में जुड़ते हैं).
     * true = कोड मिला और जोड़ा; false = आम पाठ (कुछ नहीं बदला)
     */
    fun addCodeParts(into: LinearLayout, body: TextView, text: String, onCopy: (String) -> Unit): Boolean {
        if (!FutureUi.hasCode(text)) return false
        val parts = FutureUi.split(text)
        if (parts.none { it.code }) return false
        val c = into.context; val d = dp(c)
        val prose = parts.filter { !it.code }.joinToString("\n\n") { it.text }
        body.text = prose
        if (prose.isBlank()) body.visibility = View.GONE
        parts.filter { it.code }.forEach { p ->
            into.addView(codePanel(c, p.text, p.lang, onCopy), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * d).toInt() })
        }
        return true
    }

    /** सूची में DK का कोड वाला संदेश (पुरानी/लौटी बातचीत): काँच का बुलबुला + पाठ + कोड पैनल. कोड न हो = null */
    fun dkCodeBubble(c: Context, text: String, maxW: Int, color: Int, onCopy: (String) -> Unit): LinearLayout? {
        if (!FutureUi.hasCode(text)) return null
        val d = dp(c)
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.glass_bubble_dk)
            val ph = (14 * d).toInt(); val pv = (10 * d).toInt(); setPadding(ph, pv, ph, pv)
        }
        val body = TextView(c).apply { textSize = 15f; setTextColor(color); setTextIsSelectable(true); maxWidth = maxW; UiLang.keep(this) }
        box.addView(body)
        return if (addCodeParts(box, body, text, onCopy)) box else null
    }

    /** टेलीमेट्री पट्टी (Status • इंजन • …). वही टैग हों तो दोबारा नहीं बनती */
    class Telemetry(c: Context) {
        private val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val view = HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(row) }
        private var last: List<Pair<String, Int>> = emptyList()
        private val gap = (6 * c.resources.displayMetrics.density).toInt()

        fun set(items: List<Pair<String, Int>>) {
            if (items == last) return
            last = items
            row.removeAllViews()
            items.forEach { (t, col) ->
                row.addView(chip(row.context, t, col), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = gap })
            }
        }
    }
}

/**
 * होम की तरंग (cyan → violet). असली हालत से चलती है: DKState.mode + DKState.level (माइक).
 * चुप/IDLE हो तो फ़्रेम नहीं बनते (बस हर 300ms हालत देखती है) - बैटरी और 60-120 FPS सुरक्षित.
 */
class HoloWaveView @JvmOverloads constructor(ctx: Context, attrs: android.util.AttributeSet? = null) : View(ctx, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    private var amp = 0f
    private var shaderW = -1

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    private val idleCheck = Runnable { invalidate() }

    override fun onDetachedFromWindow() { removeCallbacks(idleCheck); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (shaderW != width) {
            shaderW = width
            paint.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(FutureViews.CYAN, 0xFF6366F1.toInt(), FutureViews.VIOLET), null, Shader.TileMode.CLAMP)
        }
        val mode = DKState.mode
        val target = when (mode) {
            Mode.LISTENING -> 0.25f + DKState.level.coerceIn(0f, 1f) * 0.75f
            Mode.SPEAKING -> 0.7f
            Mode.THINKING, Mode.WORKING -> 0.35f
            else -> if (DKState.live) 0.12f else 0.04f
        }
        amp += (target - amp) * 0.15f
        val t = SystemClock.uptimeMillis() / 1000f
        val mid = h / 2f
        val d = resources.displayMetrics.density
        for (k in 0 until 3) {
            path.reset()
            val ph = t * (2.2f + k * 0.7f) + k * 1.3f
            val a = amp * (h * 0.42f) * (1f - k * 0.28f)
            var x = 0f
            val step = 4f * d
            while (x <= w) {
                val env = sin(Math.PI.toFloat() * x / w)          // किनारों पर पतली
                val y = mid + a * env * sin(x / w * 12.5f + ph)
                if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                x += step
            }
            paint.strokeWidth = (2.2f - k * 0.6f) * d
            paint.alpha = 255 - k * 80
            c.drawPath(path, paint)
        }
        val active = mode != Mode.IDLE || DKState.live || amp > 0.06f
        if (active) postInvalidateOnAnimation() else { removeCallbacks(idleCheck); postDelayed(idleCheck, 300) }
    }
}

package com.dk.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ReplacementSpan
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 13 नवंबर 2026 (मालिक): जवाब के जिस वाक्य/बुलेट को स्रोत मिला, उसके ठीक अंत में छोटे गोल चिप (github, wikipedia…) -
 * टैप = पूरा link browser में (नया टैब). नीचे "स्रोत" सूची + Google का searchEntryPoint. पुराना UI और नया DKAI UI दोनों यही इस्तेमाल करते हैं.
 */
object GroundView {

    /** छोटा गोल चिप - अक्षर के रंग से ही बनता है, इसलिए गहरे और हल्के दोनों रंग में पढ़ने लायक */
    class PillSpan(private val fg: Int, private val sizePx: Float, private val padH: Float, private val padV: Float) : ReplacementSpan() {
        private fun p(base: Paint) = Paint(base).apply { textSize = sizePx; isFakeBoldText = false; isAntiAlias = true }
        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int =
            (p(paint).measureText(text, start, end) + padH * 2).toInt() + 1

        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val tp = p(paint)
            val w = tp.measureText(text, start, end) + padH * 2
            val fm = tp.fontMetrics
            val h = (fm.descent - fm.ascent) + padV * 2
            val cy = (top + bottom) / 2f
            val r = RectF(x, cy - h / 2f, x + w, cy + h / 2f)
            val bg = Paint().apply { isAntiAlias = true; color = (fg and 0x00FFFFFF) or 0x2E000000; style = Paint.Style.FILL }
            canvas.drawRoundRect(r, h / 2f, h / 2f, bg)
            val br = Paint().apply { isAntiAlias = true; color = (fg and 0x00FFFFFF) or 0x55000000; style = Paint.Style.STROKE; strokeWidth = 1f }
            canvas.drawRoundRect(r, h / 2f, h / 2f, br)
            tp.color = fg
            canvas.drawText(text, start, end, x + padH, cy - (fm.ascent + fm.descent) / 2f, tp)
        }
    }

    private class Open(private val url: String) : ClickableSpan() {
        override fun onClick(widget: View) = SourcesView.open(widget.context, url)
        override fun updateDrawState(ds: TextPaint) { /* चिप खुद रंग देता है - underline नहीं */ }
    }

    /** जवाब का text + चिप (स्रोत न हों = वही text, कुछ नहीं जुड़ता) */
    fun decorate(c: Context, text: CharSequence, g: GroundCite.G?, fg: Int): CharSequence {
        val s = text.toString()
        val pts = GroundCite.points(s, g)
        if (g == null || pts.isEmpty()) return text
        val dp = c.resources.displayMetrics.density
        val sp = c.resources.displayMetrics.scaledDensity
        val out = SpannableStringBuilder(text)
        for ((pos, idx) in pts.asReversed()) {
            val chips = GroundCite.chips(g, idx)
            if (chips.isEmpty()) continue
            val add = SpannableStringBuilder()
            for ((name, url) in chips) {
                add.append(" ")
                val st = add.length
                add.append(name)
                add.setSpan(PillSpan(fg, 10.5f * sp, 7 * dp, 1.5f * dp), st, add.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                add.setSpan(Open(url), st, add.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            out.insert(pos.coerceIn(0, out.length), add)
        }
        return out
    }

    /** चिप वाले text पर टैप काम करे */
    fun links(tv: TextView?) {
        val t = tv?.text as? Spanned ?: return
        if (t.getSpans(0, t.length, ClickableSpan::class.java).isNotEmpty()) tv.movementMethod = LinkMovementMethod.getInstance()
    }

    /** लाइव/जहाँ वाक्य की जगह न मिली: सारे स्रोत छोटे चिप की कतार में (लाइन भरे तो अगली लाइन) */
    fun chipRow(c: Context, g: GroundCite.G?, fg: Int): View? {
        val chips = GroundCite.allChips(g)
        if (chips.isEmpty()) return null
        val tv = TextView(c).apply { textSize = 12f; setTextColor(fg); setLineSpacing(0f, 1.35f); UiLang.keep(this) }
        tv.text = decorateAll(c, chips, fg)
        links(tv)
        return tv
    }

    private fun decorateAll(c: Context, chips: List<Pair<String, String>>, fg: Int): CharSequence {
        val dp = c.resources.displayMetrics.density
        val sp = c.resources.displayMetrics.scaledDensity
        val b = SpannableStringBuilder()
        for ((name, url) in chips) {
            if (b.isNotEmpty()) b.append(" ")
            val st = b.length; b.append(name)
            b.setSpan(PillSpan(fg, 10.5f * sp, 7 * dp, 1.5f * dp), st, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            b.setSpan(Open(url), st, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return b
    }

    /**
     * जवाब के सबसे नीचे "स्रोत": क्रम संख्या, पेज का शीर्षक, वेबसाइट, टैप करने लायक पूरा link (हर स्रोत एक बार).
     * [accent] = नंबर/link का रंग, [dim] = हल्का रंग.
     */
    fun list(c: Context, sources: List<Pair<String, String>>, fg: Int, dim: Int, accent: Int): View? {
        val uniq = ArrayList<Pair<String, String>>()
        for (s in sources) if (s.second.startsWith("http") && uniq.none { it.second == s.second || (s.first.isNotBlank() && it.first.equals(s.first, true)) }) uniq.add(s)
        if (uniq.isEmpty()) return null
        val dp = c.resources.displayMetrics.density
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        box.addView(TextView(c).apply {
            text = UiLang.s("स्रोत"); textSize = 12f; setTextColor(dim); setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (4 * dp).toInt())
        })
        uniq.forEachIndexed { i, (title, url) ->
            val site = Grounding.site(title, url)
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP
                isClickable = true; isFocusable = true
                setPadding(0, (3 * dp).toInt(), 0, (3 * dp).toInt())
                contentDescription = "स्रोत ${i + 1}: ${title.ifBlank { site }} ($site), खोलें"
                setOnClickListener { SourcesView.open(c, url) }
            }
            row.addView(TextView(c).apply { text = "${i + 1}."; textSize = 12f; setTextColor(accent); minWidth = (20 * dp).toInt() })
            val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(c).apply { text = Grounding.label(title.ifBlank { site }, 80); textSize = 13f; setTextColor(fg); maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END; UiLang.keep(this) })
            if (site.isNotBlank()) col.addView(TextView(c).apply { text = site; textSize = 11f; setTextColor(dim); maxLines = 1; UiLang.keep(this) })
            col.addView(TextView(c).apply { text = url; textSize = 10.5f; setTextColor(accent); maxLines = 1; paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; UiLang.keep(this) })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            box.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        return box
    }

    /** Google की शर्त: searchEntryPoint.renderedContent (Google Search सुझाव) जवाब के नीचे - टैप = browser में */
    fun entry(c: Context, html: String): View? {
        if (html.isBlank()) return null
        return try {
            android.webkit.WebView(c).apply {
                setBackgroundColor(0)
                settings.javaScriptEnabled = false
                isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: android.webkit.WebView, r: android.webkit.WebResourceRequest): Boolean {
                        SourcesView.open(c, r.url.toString()); return true
                    }
                }
                loadDataWithBaseURL("https://www.google.com/",
                    "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head>" +
                        "<body style=\"margin:0;padding:0;background:transparent\">$html</body></html>", "text/html", "utf-8", null)
            }
        } catch (e: Exception) { DKLog.d("Google खोज सुझाव नहीं दिखा: ${e.message}"); null }
    }

    /**
     * जवाब के नीचे का पूरा हिस्सा: (लाइव/जगह न मिली = चिप कतार) + "स्रोत" सूची + Google searchEntryPoint.
     * कोई स्रोत नहीं = null (कुछ नहीं दिखता).
     */
    fun footer(c: Context, text: String, sources: List<Pair<String, String>>, g: GroundCite.G?, fg: Int, dim: Int, accent: Int, forceRow: Boolean = false): View? {
        val all = if (g != null) (GroundCite.unique(g) + sources) else sources
        if (all.isEmpty()) return null
        val dp = c.resources.displayMetrics.density
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        if (g != null && (forceRow || GroundCite.points(text, g).isEmpty())) chipRow(c, g, fg)?.let {
            col.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (6 * dp).toInt() })
        }
        list(c, all, fg, dim, accent)?.let { col.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        g?.entryHtml?.let { h -> entry(c, h)?.let { col.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * dp).toInt() }) } }
        return if (col.childCount == 0) null else col
    }
}

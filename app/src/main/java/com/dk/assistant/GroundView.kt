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

    /**
     * जवाब का text + चिप. 13 नवंबर 2026: सब प्रदाता (Tavily / Exa / Google) एक जैसे - जवाब के [n] निशान ([WebCite]) की जगह
     * वाक्य के अंत में वेबसाइट के छोटे नाम वाले गोल चिप; टैप = उसी खबर का पूरा link (नया टैब / नया task).
     * खोज नहीं हुई (g = null) = वही text, कोई चिप नहीं; बिना स्रोत वाले [n] भी हटते हैं (नकली लिंक कभी नहीं).
     */
    fun decorate(c: Context, text: CharSequence, g: GroundCite.G?, fg: Int): CharSequence {
        val s = text.toString()
        val card = WebCite.card(s, g) ?: return if (WebCite.ids(s).isEmpty()) text else WebCite.strip(s)
        val lay = WebCite.layout(card.text)
        val dp = c.resources.displayMetrics.density
        val sp = c.resources.displayMetrics.scaledDensity
        val out = SpannableStringBuilder(lay.text)
        for ((pos, ids) in lay.at.asReversed()) {
            val chips = chipsFor(card, ids)
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

    /** एक जगह के चिप: (site, link) - एक ही वेबसाइट दो बार नहीं, सिर्फ़ असली स्रोत */
    private fun chipsFor(card: WebCite.Card, ids: List<Int>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        for (id in ids) {
            val s = card.srcs.firstOrNull { it.id == id } ?: continue
            val n = s.site.ifBlank { Grounding.host(s.url) }
            if (n.isBlank() || !s.url.startsWith("http") || out.any { it.first == n }) continue
            out.add(n to s.url)
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
        val card = WebCite.card("", g)
        val chips = if (card != null) chipsFor(card, WebCite.used(card).map { it.id }) else GroundCite.allChips(g)
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
            text = WebCite.title(uniq.size); textSize = 12f; setTextColor(dim); setTypeface(typeface, android.graphics.Typeface.BOLD)
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
     * 13 नवंबर 2026: "स्रोत (N)" - सिकुड़ने लायक बटन, उसी हेडर में प्रदाता (usedProvider) और फ़ॉलबैक हुआ हो तो छोटे अक्षरों में
     * "(Tavily फ़ेल)". सूची में सिर्फ़ जवाब में सच में इस्तेमाल हुए स्रोत; हर पंक्ति: क्रम संख्या, शीर्षक (दो लाइन तक),
     * वेबसाइट · तारीख़. पूरा url नहीं छपता - पूरी पंक्ति टैप = link.
     */
    fun sourcesList(c: Context, card: WebCite.Card, fg: Int, dim: Int, accent: Int, open: Boolean = false): View? {
        val used = WebCite.used(card)
        if (used.isEmpty()) return null
        val dp = c.resources.displayMetrics.density
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val rows = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; visibility = if (open) View.VISIBLE else View.GONE }
        val head = TextView(c).apply {
            textSize = 12.5f; setTextColor(fg); minHeight = (36 * dp).toInt(); gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true; UiLang.keep(this)
            setPadding(0, (2 * dp).toInt(), 0, (2 * dp).toInt())
        }
        fun paint() {
            val shown = rows.visibility == View.VISIBLE
            val b = SpannableStringBuilder()
            b.append(if (shown) "▾ " else "▸ ")
            val st = b.length; b.append(WebCite.title(used.size))
            b.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), st, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (card.provider.isNotBlank()) {
                b.append("  ·  ")
                val p0 = b.length; b.append(card.provider)
                b.setSpan(android.text.style.ForegroundColorSpan(accent), p0, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val note = WebCite.failNote(card.failed)
            if (note.isNotBlank()) {
                b.append(" ")
                val n0 = b.length; b.append(note)
                b.setSpan(android.text.style.RelativeSizeSpan(0.8f), n0, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                b.setSpan(android.text.style.ForegroundColorSpan(dim), n0, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            head.text = b
            head.contentDescription = WebCite.title(used.size) + ", " + WebCite.providerLine(card.provider, card.failed) + if (shown) ", बंद करें" else ", खोलें"
        }
        head.setOnClickListener { rows.visibility = if (rows.visibility == View.VISIBLE) View.GONE else View.VISIBLE; paint() }
        paint()
        box.addView(head, LinearLayout.LayoutParams(-1, -2))
        for (s in used) {
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP
                isClickable = true; isFocusable = true; minimumHeight = (40 * dp).toInt()
                setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
                contentDescription = "स्रोत ${s.id}: ${s.title.ifBlank { s.site }} (${s.site}), खोलें"
                setOnClickListener { SourcesView.open(c, s.url) }
            }
            row.addView(TextView(c).apply { text = "${s.id}."; textSize = 12f; setTextColor(accent); minWidth = (24 * dp).toInt() })
            val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(c).apply { text = s.title.ifBlank { s.site }; textSize = 13f; setTextColor(fg); maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END; UiLang.keep(this) })
            val meta = listOf(s.site, s.date).filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) col.addView(TextView(c).apply { text = meta; textSize = 11f; setTextColor(dim); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END; UiLang.keep(this) })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            rows.addView(row, LinearLayout.LayoutParams(-1, -2))
        }
        box.addView(rows, LinearLayout.LayoutParams(-1, -2))
        return box
    }

    /**
     * जवाब के नीचे का पूरा हिस्सा: (लाइव/जगह न मिली = चिप कतार) + "स्रोत (N)" सूची + Google searchEntryPoint (Google की शर्त).
     * कोई स्रोत नहीं = null (कुछ नहीं दिखता). पुराने सहेजे जवाब (साँचे से पहले के) = पहले जैसी सूची.
     */
    fun footer(c: Context, text: String, sources: List<Pair<String, String>>, g: GroundCite.G?, fg: Int, dim: Int, accent: Int, forceRow: Boolean = false): View? {
        val dp = c.resources.displayMetrics.density
        val card = WebCite.card(text, g)
        if (card == null) {
            if (sources.isEmpty()) return null
            return list(c, sources, fg, dim, accent)
        }
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        if (forceRow || !card.marked) chipRow(c, g, fg)?.let {
            col.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (6 * dp).toInt() })
        }
        sourcesList(c, card, fg, dim, accent)?.let { col.addView(it, LinearLayout.LayoutParams(-1, -2)) }
        entry(c, card.entryHtml)?.let { col.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * dp).toInt() }) }
        return if (col.childCount == 0) null else col
    }
}

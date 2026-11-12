package com.dk.assistant

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * नवंबर 2026 (मालिक): साइड में "लाइव कोड" - AI जो फ़ाइल लिख रहा है, वो पूरी की पूरी यहाँ टाइप होती दिखती है.
 * ऊपर: कौन-सी फ़ाइल लिखी जा रही है • 🎨 रंग (6 थीम) • ⧉ कॉपी • ✕.
 * बीच: फ़ाइलों के टैब (● लाइव = हमेशा नई फ़ाइल पर; कोई टैब छुआ = वहीं रुका).
 * नीचे: लाइन नंबर + रंगीन कोड; बदली हुई लाइनें हल्की चमक में.
 * DK कंप्यूटर, कोडिंग एजेंट और ऐप स्टूडियो - तीनों स्क्रीन पर एक ही पैनल.
 *
 * [match] = किसकी फ़ाइलें (CodeLive.WHO_*), [extra] = डिस्क की फ़ाइलें (स्टूडियो का project) जो अभी नहीं लिखी गईं.
 */
class CodeLivePanel(
    private val act: Activity,
    private val match: (String) -> Boolean,
    private val extra: (() -> List<Pair<String, String>>)? = null,
    private val onCount: ((Int) -> Unit)? = null,
) : FrameLayout(act) {
    private val dp = resources.displayMetrics.density
    private val ui = Handler(Looper.getMainLooper())
    private var theme = CodeLive.theme(act)
    private val panel = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; isClickable = true }
    private val title = TextView(act)
    private val status = TextView(act)
    private val themeRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
    private val themeScroll = HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; visibility = View.GONE }
    private val tabs = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
    private val code = TextView(act)
    private val vScroll = ScrollView(act)
    private val hScroll = HorizontalScrollView(act)
    private val btns = mutableListOf<Button>()

    /** true = नई लिखी फ़ाइल अपने-आप खुले */
    private var follow = true
    private var shownKey = ""
    private var shownId = 0L
    private var shownText = ""
    private var full: SpannableStringBuilder = SpannableStringBuilder()
    private var revealAt = 0
    private var revealing = false

    val isOpen: Boolean get() = visibility == View.VISIBLE

    private val listener: (CodeLive.Write) -> Unit = { w -> if (match(w.who)) ui.post { onWrite(w) } }

    private val step = object : Runnable {
        override fun run() {
            if (!revealing) return
            revealAt = (revealAt + CodeLive.revealStep(full.length)).coerceAtMost(full.length)
            code.text = full.subSequence(0, revealAt)
            followCursor()
            if (revealAt >= full.length) { revealing = false; code.text = full; return }
            ui.postDelayed(this, 16)
        }
    }

    init {
        visibility = View.GONE
        UiLang.keep(code)
        // बाहर छुआ = बंद (फ़ोन पर). पैनल दाईं ओर
        setBackgroundColor(0x66000000)
        setOnClickListener { close() }
        val wide = resources.configuration.screenWidthDp >= 720
        val w = (resources.displayMetrics.widthPixels * if (wide) 0.48f else 0.94f).toInt()
        addView(panel, LayoutParams(w, LayoutParams.MATCH_PARENT, Gravity.END))

        // ---- ऊपर
        val head = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        title.apply { text = "</> लाइव कोड"; textSize = 15f; setTypeface(Typeface.DEFAULT, Typeface.BOLD) }
        head.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(btn("🎨 रंग") { themeScroll.visibility = if (themeScroll.visibility == View.VISIBLE) View.GONE else View.VISIBLE })
        head.addView(btn("⧉ कॉपी") { copy() })
        head.addView(btn("✕") { close() })
        panel.addView(head)
        status.apply { textSize = 11.5f; maxLines = 2; setPadding(0, (2 * dp).toInt(), 0, (6 * dp).toInt()) }
        panel.addView(status)
        themeScroll.addView(themeRow)
        panel.addView(themeScroll, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (6 * dp).toInt() })
        panel.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(tabs) },
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (6 * dp).toInt() })

        // ---- कोड
        code.apply {
            typeface = Typeface.MONOSPACE; textSize = 12f; setLineSpacing(0f, 1.08f); setTextIsSelectable(true)
            setHorizontallyScrolling(true); val q = (10 * dp).toInt(); setPadding(q, q, q * 2, q * 3)
        }
        hScroll.addView(code)
        vScroll.addView(hScroll)
        vScroll.isFillViewport = true
        panel.addView(vScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        applyTheme()
    }

    /** स्क्रीन के सबसे ऊपर रखो (FrameLayout root) */
    fun attachTo(root: FrameLayout) {
        root.addView(this, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        UiFit.safeArea(panel)
        androidx.core.view.ViewCompat.requestApplyInsets(panel)
    }

    fun toggle() = if (isOpen) close() else open()

    fun open() {
        visibility = View.VISIBLE
        bringToFront()
        renderTabs()
        val l = CodeLive.latest(match)
        if (shownKey.isEmpty() || follow) { if (l != null) show(l, animate = false) else showDisk(null) }
    }

    fun close() { visibility = View.GONE; revealing = false; ui.removeCallbacks(step) }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); CodeLive.listen(listener); onCount?.invoke(CodeLive.count(match)) }
    override fun onDetachedFromWindow() { CodeLive.unlisten(listener); ui.removeCallbacks(step); super.onDetachedFromWindow() }

    private fun onWrite(w: CodeLive.Write) {
        onCount?.invoke(CodeLive.count(match))
        if (!isOpen) return
        renderTabs()
        if (follow || key(w) == shownKey) show(w, animate = true)
    }

    private fun key(w: CodeLive.Write) = w.who + "|" + w.path

    /** फ़ाइल दिखाओ. animate = नया / बदला हिस्सा टाइप होता दिखे */
    private fun show(w: CodeLive.Write, animate: Boolean) {
        shownKey = key(w); shownId = w.id
        status.text = "${CodeLive.whoLabel(w.who)} • " + CodeLive.status(w)
        if (w.deleted) { setCode(w.old.orEmpty(), w.path, IntRange.EMPTY, 0, false); markTabs(); return }
        val ch = if (w.isNew) IntRange.EMPTY else w.changed
        val from = if (w.isNew) 0 else ch.first.coerceAtLeast(0)
        setCode(w.text, w.path, ch, from, animate)
        markTabs()
    }

    private fun showDisk(p: Pair<String, String>?) {
        val f = p ?: extra?.invoke()?.firstOrNull()
        if (f == null) { status.text = CodeLive.status(null); shownText = ""; full = SpannableStringBuilder(); code.text = ""; return }
        shownKey = "disk|" + f.first; shownId = 0
        status.text = "📁 ${f.first} • ${f.second.count { it == '\n' } + 1} लाइन"
        setCode(f.second, f.first, IntRange.EMPTY, 0, false)
        markTabs()
    }

    private fun setCode(text: String, path: String, changed: IntRange, fromLine: Int, animate: Boolean) {
        ui.removeCallbacks(step)
        shownText = text
        val starts = mutableListOf<Int>()
        full = highlight(text, CodeLive.langOf(path), changed, starts)
        val from = starts.getOrNull(fromLine) ?: 0
        if (animate && full.length - from in 1..CodeLive.ANIMATE_MAX) {
            revealAt = from; revealing = true
            code.text = full.subSequence(0, revealAt)
            ui.post(step)
        } else {
            revealing = false
            code.text = full
            if (fromLine > 0) code.post { scrollToOffset(from) } else vScroll.post { vScroll.scrollTo(0, 0) }
        }
    }

    private fun followCursor() {
        val lay = code.layout ?: return
        val line = lay.getLineForOffset(revealAt.coerceAtMost(code.text.length))
        val y = lay.getLineBottom(line) + code.paddingTop
        val vis = vScroll.height
        if (vis > 0 && (y > vScroll.scrollY + vis - (24 * dp).toInt() || y < vScroll.scrollY)) vScroll.scrollTo(0, (y - vis * 2 / 3).coerceAtLeast(0))
        if (hScroll.scrollX != 0) hScroll.scrollTo(0, 0)
    }

    private fun scrollToOffset(off: Int) {
        val lay = code.layout ?: return
        val y = lay.getLineTop(lay.getLineForOffset(off.coerceAtMost(code.text.length)))
        vScroll.scrollTo(0, (y - (40 * dp).toInt()).coerceAtLeast(0))
    }

    /** लाइन नंबर + रंग. [starts] में हर लाइन की शुरुआत (दिखने वाले पाठ में) */
    private fun highlight(text: String, lang: String, changed: IntRange, starts: MutableList<Int>): SpannableStringBuilder {
        val lines = text.split('\n')
        val w = lines.size.toString().length
        val sb = SpannableStringBuilder()
        var block = false
        val t = theme
        lines.forEachIndexed { n, line ->
            starts.add(sb.length)
            val gs = sb.length
            sb.append((n + 1).toString().padStart(w)).append("  ")
            sb.setSpan(ForegroundColorSpan(t.gutter), gs, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val ls = sb.length
            sb.append(line)
            val r = CodeLive.lineSpans(line, lang, block)
            block = r.inBlock
            for (s in r.spans) sb.setSpan(ForegroundColorSpan(t.color(s.kind)), ls + s.start, ls + s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (n in changed) sb.setSpan(BackgroundColorSpan(t.changed), gs, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (n < lines.size - 1) sb.append('\n')
        }
        return sb
    }

    // ---------------- टैब ----------------

    private fun renderTabs() {
        tabs.removeAllViews()
        tabs.addView(chip("● लाइव", follow) {
            follow = true
            CodeLive.latest(match)?.let { show(it, animate = false) } ?: showDisk(null)
            markTabs()
        })
        val live = CodeLive.files(match)
        live.forEach { w ->
            val icon = when { w.deleted -> "🗑"; w.isNew -> "✍"; else -> "✏" }
            tabs.addView(chip("$icon ${w.name}", false) { follow = false; show(w, animate = false) }.apply { tag = key(w) })
        }
        val seen = live.map { it.path }.toSet()
        extra?.invoke()?.filter { it.first !in seen }?.take(80)?.forEach { f ->
            tabs.addView(chip("📄 ${f.first.substringAfterLast('/')}", false) { follow = false; showDisk(f) }.apply { tag = "disk|" + f.first })
        }
        markTabs()
    }

    private fun markTabs() {
        for (i in 0 until tabs.childCount) {
            val b = tabs.getChildAt(i) as? Button ?: continue
            val on = if (i == 0) follow else b.tag == shownKey
            styleChip(b, on)
        }
    }

    private fun chip(t: String, on: Boolean, f: () -> Unit) = Button(act).apply {
        text = t; isAllCaps = false; textSize = 12f; minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        val ph = (10 * dp).toInt(); setPadding(ph, (5 * dp).toInt(), ph, (5 * dp).toInt()); isFocusable = false
        setOnClickListener { f() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (6 * dp).toInt() }
        styleChip(this, on)
    }

    private fun styleChip(b: Button, on: Boolean) {
        val t = theme
        b.background = GradientDrawable().apply {
            cornerRadius = 14 * dp
            setColor(if (on) (t.keyword and 0x00FFFFFF) or 0x33000000 else (t.fg and 0x00FFFFFF) or 0x14000000)
            setStroke((1 * dp).toInt().coerceAtLeast(1), if (on) t.keyword else (t.gutter and 0x00FFFFFF) or 0x88000000.toInt())
        }
        b.setTextColor(if (on) t.keyword else t.fg)
    }

    private fun btn(t: String, f: () -> Unit) = Button(act).apply {
        text = t; isAllCaps = false; textSize = 12f; minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        val ph = (9 * dp).toInt(); setPadding(ph, (5 * dp).toInt(), ph, (5 * dp).toInt()); isFocusable = false
        setOnClickListener { f() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginStart = (6 * dp).toInt() }
        btns.add(this)
    }

    // ---------------- रंग / थीम ----------------

    private fun renderThemes() {
        themeRow.removeAllViews()
        CodeLive.THEMES.forEach { th ->
            themeRow.addView(Button(act).apply {
                text = (if (th.id == theme.id) "✓ " else "") + th.name; isAllCaps = false; textSize = 12f
                minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
                val ph = (11 * dp).toInt(); setPadding(ph, (6 * dp).toInt(), ph, (6 * dp).toInt()); isFocusable = false
                setTextColor(th.keyword)
                background = GradientDrawable().apply {
                    cornerRadius = 14 * dp; setColor(th.bg)
                    setStroke(((if (th.id == theme.id) 2 else 1) * dp).toInt().coerceAtLeast(1), if (th.id == theme.id) th.keyword else th.gutter)
                }
                setOnClickListener {
                    CodeLive.setTheme(act, th.id); theme = th; applyTheme()
                    UiLang.toast(act, "कोड का रंग: ${th.name}", Toast.LENGTH_SHORT).show()
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (6 * dp).toInt() })
        }
    }

    private fun applyTheme() {
        val t = theme
        panel.background = GradientDrawable().apply {
            setColor(t.bg); cornerRadii = floatArrayOf(18 * dp, 18 * dp, 0f, 0f, 0f, 0f, 18 * dp, 18 * dp)
            setStroke((1 * dp).toInt().coerceAtLeast(1), (t.keyword and 0x00FFFFFF) or 0x55000000)
        }
        val p = (12 * dp).toInt(); panel.setPadding(p, p, p, (6 * dp).toInt())
        title.setTextColor(t.keyword)
        status.setTextColor((t.fg and 0x00FFFFFF) or 0xBB000000.toInt())
        code.setTextColor(t.fg)
        btns.forEach { styleChip(it, false) }
        renderThemes()
        if (tabs.childCount > 0) markTabs()
        // रंग बदला = अभी वाली फ़ाइल नए रंगों में (टाइप हो रही हो तो पूरी)
        if (shownText.isNotEmpty() || shownKey.isNotEmpty()) {
            val path = shownKey.substringAfter('|')
            val w = CodeLive.files(match).firstOrNull { key(it) == shownKey }
            val ch = if (w == null || w.isNew) IntRange.EMPTY else w.changed
            setCode(shownText, path, ch, 0, false)
        }
    }

    private fun copy() {
        if (shownText.isEmpty()) { UiLang.toast(act, "अभी कोई कोड नहीं", Toast.LENGTH_SHORT).show(); return }
        (act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("code", shownText))
        UiLang.toast(act, "कोड कॉपी हो गया", Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** ऊपर के बटन का नाम: कितनी फ़ाइलें लिखी गईं */
        fun label(n: Int) = if (n > 0) "</> कोड ($n)" else "</> कोड"
    }
}

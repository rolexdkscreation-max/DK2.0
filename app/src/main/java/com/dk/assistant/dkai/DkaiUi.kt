package com.dk.assistant.dkai

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import com.dk.assistant.UiLang
import com.dk.assistant.dkai.DkaiTheme.dp

/**
 * 13 नवंबर 2026 (मालिक - दूसरी spec): DKAI के bottom-sheet / popup / पूरी-स्क्रीन पन्ने.
 * सब DkaiScreen.root के ऊपर एक परत (layer) में; फ़ोन का back = सबसे ऊपर वाली परत बंद.
 */
object DkaiUi {
    /** सिस्टम पट्टियों की जगह (DkaiScreen भरता है) */
    var insetTop = 0
    var insetBottom = 0

    private val stacks = java.util.WeakHashMap<FrameLayout, ArrayList<Layer>>()

    class Layer(val view: FrameLayout, val onClose: (() -> Unit)?)

    enum class Place { BOTTOM, CENTER, FULL, ANCHOR }

    /** परत खोलो: [body] नीचे (sheet) / बीच में (modal) / पूरी स्क्रीन; बाहर टैप = बंद (FULL को छोड़कर) */
    fun open(root: FrameLayout, body: View, place: Place = Place.BOTTOM, onClose: (() -> Unit)? = null,
             anchorBottom: Int = 0, anchorEnd: Int = 0): Layer {
        val c = root.context
        val layer = FrameLayout(c).apply {
            isClickable = true; isFocusable = true
            setBackgroundColor(if (place == Place.FULL) DkaiTheme.BG else if (place == Place.ANCHOR) 0x33000000 else 0x99000000.toInt())
        }
        val lp = when (place) {
            Place.BOTTOM -> FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
            Place.CENTER -> FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { setMargins(dp(c, 18f), insetTop + dp(c, 18f), dp(c, 18f), insetBottom + dp(c, 18f)) }
            Place.FULL -> FrameLayout.LayoutParams(-1, -1)
            Place.ANCHOR -> FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(dp(c, 12f), 0, anchorEnd, anchorBottom) }
        }
        if (place == Place.BOTTOM) body.setPadding(body.paddingLeft, body.paddingTop, body.paddingRight, body.paddingBottom + insetBottom)
        if (place == Place.FULL) body.setPadding(body.paddingLeft, body.paddingTop + insetTop, body.paddingRight, body.paddingBottom + insetBottom)
        body.isClickable = true
        layer.addView(body, lp)
        val l = Layer(layer, onClose)
        if (place != Place.FULL) layer.setOnClickListener { close(root, l) }
        stacks.getOrPut(root) { ArrayList() }.add(l)
        root.addView(layer, FrameLayout.LayoutParams(-1, -1))
        layer.alpha = 0f; layer.animate().alpha(1f).setDuration(140).start()
        if (place == Place.BOTTOM) { body.translationY = dp(c, 60f).toFloat(); body.animate().translationY(0f).setDuration(180).start() }
        return l
    }

    fun close(root: FrameLayout, l: Layer) {
        val st = stacks[root] ?: return
        if (!st.remove(l)) return
        l.view.animate().alpha(0f).setDuration(110).withEndAction { root.removeView(l.view) }.start()
        try { l.onClose?.invoke() } catch (_: Throwable) {}
    }

    /** back: सबसे ऊपर वाली परत बंद (true = संभाला) */
    fun closeTop(root: FrameLayout): Boolean {
        val l = stacks[root]?.lastOrNull() ?: return false
        close(root, l); return true
    }

    fun closeAll(root: FrameLayout) { stacks[root]?.toList()?.reversed()?.forEach { close(root, it) } }

    fun anyOpen(root: FrameLayout) = !stacks[root].isNullOrEmpty()

    // ================================================================== बने-बनाए हिस्से
    fun d(c: Context, v: Float) = dp(c, v)

    /** नीचे वाली sheet: ऊपर पकड़ने की लकीर + शीर्षक + ✕; [body] में सामग्री (बहुत लंबी = scroll) */
    fun sheet(c: Context, title: String, sub: String? = null, onX: (() -> Unit)? = null): Pair<LinearLayout, LinearLayout> {
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(DkaiTheme.CARD); val r = DkaiTheme.dpf(c, 24f)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f); setStroke(1, DkaiTheme.BORDER)
            }
            setPadding(d(c, 18f), d(c, 10f), d(c, 18f), d(c, 16f))
        }
        card.addView(View(c).apply { background = DkaiTheme.round(c, DkaiTheme.BORDER, 99f) },
            LinearLayout.LayoutParams(d(c, 40f), d(c, 4f)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = d(c, 10f) })
        card.addView(head(c, title, sub, onX))
        val body = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val max = (c.resources.displayMetrics.heightPixels * 0.72f).toInt()
        card.addView(MaxScroll(c, max).apply { addView(body) }, LinearLayout.LayoutParams(-1, -2))
        return card to body
    }

    /** बीच वाला modal डिब्बा */
    fun modal(c: Context, title: String, sub: String? = null, onX: (() -> Unit)? = null): Pair<LinearLayout, LinearLayout> {
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = DkaiTheme.round(c, DkaiTheme.CARD, 22f, DkaiTheme.BORDER)
            setPadding(d(c, 18f), d(c, 14f), d(c, 18f), d(c, 16f)); elevation = d(c, 10f).toFloat()
        }
        card.addView(head(c, title, sub, onX))
        val body = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val max = (c.resources.displayMetrics.heightPixels * 0.70f).toInt()
        card.addView(MaxScroll(c, max).apply { addView(body) }, LinearLayout.LayoutParams(-1, -2))
        return card to body
    }

    /** पूरी स्क्रीन वाला पन्ना: ← शीर्षक  [दाएँ बटन] */
    fun page(c: Context, title: String, onBack: () -> Unit): Triple<LinearLayout, LinearLayout, LinearLayout> {
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(DkaiTheme.BG) }
        val bar = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(d(c, 4f), d(c, 6f), d(c, 8f), d(c, 6f)) }
        bar.addView(iconBtn(c, DkaiIcon.Kind.BACK, "वापस", DkaiTheme.WHITE) { onBack() })
        bar.addView(DkaiTheme.text(c, title, 18f, DkaiTheme.WHITE, DkaiTheme.SANS_BOLD).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, -2, 1f))
        val right = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(right)
        col.addView(bar, LinearLayout.LayoutParams(-1, -2))
        col.addView(View(c).apply { setBackgroundColor(DkaiTheme.BORDER) }, LinearLayout.LayoutParams(-1, 1))
        val body = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        col.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        return Triple(col, body, right)
    }

    private fun head(c: Context, title: String, sub: String?, onX: (() -> Unit)?): View {
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, d(c, 10f)) }
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        col.addView(DkaiTheme.text(c, title, 17f, DkaiTheme.WHITE, DkaiTheme.SANS_BOLD))
        if (!sub.isNullOrBlank()) col.addView(DkaiTheme.text(c, sub, 12f, DkaiTheme.GRAY).apply { setPadding(0, d(c, 3f), 0, 0) })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (onX != null) row.addView(iconBtn(c, DkaiIcon.Kind.X, "बंद करें", DkaiTheme.GRAY) { onX() })
        return row
    }

    fun iconBtn(c: Context, kind: DkaiIcon.Kind, desc: String, tint: Int = DkaiTheme.WHITE, sizeDp: Float = 44f, fill: Int = 0, onTap: () -> Unit): ImageView = ImageView(c).apply {
        setImageDrawable(DkaiIcon(kind, tint)); scaleType = ImageView.ScaleType.CENTER_INSIDE
        val p = d(c, sizeDp * 0.25f); setPadding(p, p, p, p)
        background = DkaiTheme.press(DkaiTheme.oval(fill), DkaiTheme.oval(0xFFFFFFFF.toInt()))
        contentDescription = desc; isClickable = true; isFocusable = true
        setOnClickListener { onTap() }
        layoutParams = LinearLayout.LayoutParams(d(c, sizeDp), d(c, sizeDp))
    }

    /** icon + शीर्षक + विवरण वाली पंक्ति (चुना = नीली लकीर + ✓) */
    fun row(c: Context, kind: DkaiIcon.Kind?, title: String, sub: String?, selected: Boolean = false, tint: Int = DkaiTheme.CYAN,
            danger: Boolean = false, onTap: () -> Unit): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = d(c, 52f)
        setPadding(d(c, 12f), d(c, 8f), d(c, 12f), d(c, 8f))
        background = DkaiTheme.press(DkaiTheme.round(c, if (selected) DkaiTheme.SEL else DkaiTheme.FIELD, 14f, if (selected) DkaiTheme.BLUE else DkaiTheme.BORDER, if (selected) 1.5f else 1f))
        isClickable = true; isFocusable = true; contentDescription = title + (sub?.let { ", $it" } ?: "")
        if (kind != null) {
            val ic = FrameLayout(c).apply { background = DkaiTheme.round(c, (if (danger) DkaiTheme.RED else tint) and 0x26FFFFFF, 12f) }
            ic.addView(ImageView(c).apply { setImageDrawable(DkaiIcon(kind, if (danger) DkaiTheme.RED else tint)) }, FrameLayout.LayoutParams(d(c, 20f), d(c, 20f), Gravity.CENTER))
            addView(ic, LinearLayout.LayoutParams(d(c, 38f), d(c, 38f)).apply { rightMargin = d(c, 12f) })
        }
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        col.addView(DkaiTheme.text(c, title, 14.5f, if (danger) DkaiTheme.RED else DkaiTheme.WHITE, DkaiTheme.SANS_MED))
        if (!sub.isNullOrBlank()) col.addView(DkaiTheme.text(c, sub, 12f, DkaiTheme.GRAY).apply { setPadding(0, d(c, 3f), 0, 0) })
        addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (selected) addView(ImageView(c).apply { setImageDrawable(DkaiIcon(DkaiIcon.Kind.CHECK, DkaiTheme.BLUE)) }, LinearLayout.LayoutParams(d(c, 20f), d(c, 20f)))
        setOnClickListener { onTap() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(c, 8f) }
    }

    fun label(c: Context, s: String): TextView = DkaiTheme.text(c, s.uppercase(), 11f, DkaiTheme.GRAY, DkaiTheme.SANS_BOLD).apply {
        letterSpacing = 0.1f; setPadding(d(c, 2f), d(c, 12f), 0, d(c, 8f))
    }

    /** भरा हुआ बटन */
    fun button(c: Context, s: String, fill: Int = DkaiTheme.BLUE, color: Int = DkaiTheme.ON, kind: DkaiIcon.Kind? = null, onTap: () -> Unit): TextView =
        DkaiTheme.text(c, s, 14f, color, DkaiTheme.SANS_BOLD).apply {
            gravity = Gravity.CENTER; minHeight = d(c, 46f); setPadding(d(c, 16f), d(c, 10f), d(c, 16f), d(c, 10f))
            background = DkaiTheme.press(DkaiTheme.round(c, fill, 14f, if (fill == DkaiTheme.FIELD || fill == DkaiTheme.PILL) DkaiTheme.BORDER else 0))
            isClickable = true; isFocusable = true
            if (kind != null) { setCompoundDrawablesRelative(DkaiTheme.icon(c, kind, color, 18f), null, null, null); compoundDrawablePadding = d(c, 8f) }
            setOnClickListener { onTap() }
        }

    /** दो बटन बराबर (रद्द / ठीक) */
    fun twoButtons(c: Context, a: TextView, b: TextView): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, d(c, 12f), 0, 0)
        addView(a, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = d(c, 10f) })
        addView(b, LinearLayout.LayoutParams(0, -2, 1f))
    }

    fun field(c: Context, hint: String, start: String = "", multi: Boolean = false, mono: Boolean = false): EditText = EditText(c).apply {
        this.hint = hint; setText(start); textSize = 14f
        setTextColor(DkaiTheme.WHITE); setHintTextColor(DkaiTheme.HINT)
        if (mono) typeface = DkaiTheme.MONO
        background = DkaiTheme.round(c, DkaiTheme.FIELD, 12f, DkaiTheme.BORDER)
        setPadding(d(c, 12f), d(c, 11f), d(c, 12f), d(c, 11f))
        inputType = if (multi) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        if (multi) { minLines = 3; maxLines = 6; gravity = Gravity.TOP or Gravity.START } else setSingleLine()
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        UiLang.keep(this)
    }

    /** segmented control (High / Medium / Low / Off) */
    fun segmented(c: Context, items: List<String>, sel: Int, onPick: (Int) -> Unit): LinearLayout {
        val box = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; background = DkaiTheme.round(c, DkaiTheme.FIELD, 14f, DkaiTheme.BORDER); setPadding(d(c, 4f), d(c, 4f), d(c, 4f), d(c, 4f)) }
        fun paint(s: Int) { for (i in 0 until box.childCount) (box.getChildAt(i) as TextView).let { t -> val on = i == s
            t.background = DkaiTheme.round(c, if (on) DkaiTheme.BLUE else 0, 11f); t.setTextColor(if (on) DkaiTheme.ON else DkaiTheme.GRAY) } }
        items.forEachIndexed { i, s ->
            box.addView(DkaiTheme.text(c, s, 13f, DkaiTheme.GRAY, DkaiTheme.SANS_MED).apply {
                gravity = Gravity.CENTER; minHeight = d(c, 40f); isClickable = true; isFocusable = true; contentDescription = s
                setOnClickListener { paint(i); onPick(i) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        paint(sel); return box
    }

    /** चिप की कतार (एक चुना) */
    fun chips(c: Context, items: List<String>, sel: Int, onPick: (Int) -> Unit): HorizontalScrollView {
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        fun paint(s: Int) { for (i in 0 until row.childCount) (row.getChildAt(i) as TextView).let { t -> val on = i == s
            t.background = DkaiTheme.press(DkaiTheme.round(c, if (on) DkaiTheme.BLUE else DkaiTheme.PILL, 999f, if (on) 0 else DkaiTheme.BORDER)); t.setTextColor(if (on) DkaiTheme.ON else DkaiTheme.SUB) } }
        items.forEachIndexed { i, s ->
            row.addView(DkaiTheme.pill(c, s, DkaiTheme.PILL, DkaiTheme.SUB, 12f).apply { minHeight = d(c, 36f); setOnClickListener { paint(i); onPick(i) } },
                LinearLayout.LayoutParams(-2, -2).apply { rightMargin = d(c, 8f) })
        }
        paint(sel)
        return HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    /** छोटा रंगीन टैग (HTML / CSS / JS …, PREFERENCE …) */
    fun tag(c: Context, s: String, color: Int): TextView = DkaiTheme.text(c, s, 10f, color, DkaiTheme.SANS_BOLD).apply {
        letterSpacing = 0.06f; setPadding(d(c, 7f), d(c, 3f), d(c, 7f), d(c, 3f))
        background = DkaiTheme.round(c, color and 0x26FFFFFF, 6f, color and 0x66FFFFFF)
    }

    /** हरा/लाल बिंदु + लिखावट वाली pill ("• READY", "Server: 8080 (Online)") */
    fun status(c: Context, s: String, color: Int): TextView = DkaiTheme.text(c, "● $s", 11.5f, color, DkaiTheme.SANS_BOLD).apply {
        letterSpacing = 0.04f; setPadding(d(c, 10f), d(c, 5f), d(c, 10f), d(c, 5f))
        background = DkaiTheme.round(c, color and 0x1FFFFFFF, 999f, color and 0x55FFFFFF)
    }

    fun switch(c: Context, title: String, sub: String?, on: Boolean, onChange: (Boolean) -> Unit): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = d(c, 56f)
        setPadding(d(c, 14f), d(c, 8f), d(c, 8f), d(c, 8f))
        background = DkaiTheme.round(c, DkaiTheme.FIELD, 14f, DkaiTheme.BORDER)
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        col.addView(DkaiTheme.text(c, title, 14.5f, DkaiTheme.WHITE, DkaiTheme.SANS_MED))
        if (!sub.isNullOrBlank()) col.addView(DkaiTheme.text(c, sub, 12f, DkaiTheme.GRAY).apply { setPadding(0, d(c, 3f), 0, 0) })
        addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        val sw = Switch(c).apply { isChecked = on; contentDescription = title; setOnCheckedChangeListener { _, v -> onChange(v) } }
        addView(sw)
        setOnClickListener { sw.toggle() }
    }

    fun gap(c: Context, h: Float): View = View(c).apply { layoutParams = LinearLayout.LayoutParams(1, d(c, h)) }

    fun copy(c: Context, label: String, s: String) {
        try {
            val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText(label, s))
            UiLang.toast(c, "कॉपी हो गया", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    fun shareText(c: Context, s: String, title: String = "शेयर करें") {
        try { c.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, s), title)) } catch (_: Exception) {}
    }
}

/** ज़्यादा-से-ज़्यादा ऊँचाई वाला ScrollView (sheet स्क्रीन से बाहर न जाए) */
class MaxScroll(c: Context, private val maxPx: Int) : ScrollView(c) {
    init { isFillViewport = false; isVerticalScrollBarEnabled = false }
    override fun onMeasure(w: Int, h: Int) {
        super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.AT_MOST))
    }
}

/** सबसे ऊपर का View (बच्चों के margin जोड़कर) - ViewGroup.LayoutParams बदलने का छोटा helper */
internal fun View.lpMargins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) {
    val p = layoutParams as? ViewGroup.MarginLayoutParams ?: return
    p.setMargins(l, t, r, b); layoutParams = p
}

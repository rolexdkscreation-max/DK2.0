package com.dk.assistant.dkai

import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.dk.assistant.UiLang
import com.dk.assistant.dkai.DkaiTheme.dp

/**
 * 13 नवंबर 2026 (मालिक - लाइव सर्च कार्ड): चैट में, सिर्फ़ असली वेब खोज के समय.
 *  "Processing" चिप + "Searching…" / "Researching: Live Research…" / "Exploring live web sources…"
 *  नतीजा आते ही असली वेबसाइटों के चिप एक-एक करके जुड़ते हैं और "N visited" गिनती बढ़ती है.
 * [DkaiSearchLive] से खुद हर 120ms पर ताज़ा होता है (चैट पूरा दोबारा नहीं बनता).
 */
@SuppressLint("ViewConstructor")
class DkaiSearchCard(c: Context) : LinearLayout(c) {
    private fun d(v: Float) = dp(context, v)
    private val searching: TextView
    private val queryLine: TextView
    private val exploring: TextView
    private val count: TextView
    private val chipRow = LinearLayout(c).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private var shownChips = 0
    private var attached = false
    private val tick = object : Runnable {
        override fun run() { if (!attached) return; update(); postDelayed(this, 120) }
    }

    init {
        orientation = VERTICAL
        background = DkaiTheme.round(c, DkaiTheme.FIELD, 16f, DkaiTheme.BORDER)
        setPadding(d(14f), d(12f), d(14f), d(12f))
        contentDescription = "इंटरनेट पर खोज चल रही है"
        val head = LinearLayout(c).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(LinearLayout(c).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = DkaiTheme.round(c, if (DkaiTheme.light) 0xFFEDE9FE.toInt() else 0x337C3AED, 999f)
            setPadding(d(8f), d(3f), d(10f), d(3f))
            addView(PulseDot(c).apply { color = 0xFF8B5CF6.toInt() }, LayoutParams(d(9f), d(9f)))
            addView(DkaiTheme.text(c, "Processing", 11f, if (DkaiTheme.light) 0xFF5B21B6.toInt() else 0xFFD8B4FE.toInt(), DkaiTheme.SANS_BOLD).apply { setPadding(d(5f), 0, 0, 0) })
        })
        searching = DkaiTheme.text(c, "Searching…", 13f, DkaiTheme.SUB, DkaiTheme.SANS_MED).apply { setPadding(d(8f), 0, 0, 0) }
        head.addView(searching, LayoutParams(0, -2, 1f))
        count = DkaiTheme.text(c, "", 12f, DkaiTheme.CYAN, DkaiTheme.SANS_BOLD)
        head.addView(count)
        addView(head, LayoutParams(-1, -2))
        addView(DkaiTheme.text(c, "Researching: Live Research…", 14f, DkaiTheme.WHITE, DkaiTheme.SANS_BOLD).apply { setPadding(0, d(10f), 0, 0) })
        queryLine = DkaiTheme.text(c, "", 12f, DkaiTheme.GRAY).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; UiLang.keep(this) }
        addView(queryLine)
        exploring = DkaiTheme.text(c, "Exploring live web sources…", 12f, DkaiTheme.SUB).apply { setPadding(0, d(6f), 0, d(8f)) }
        addView(exploring)
        addView(HorizontalScrollView(c).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) }, LayoutParams(-1, -2))
        update()
    }

    private fun chip(site: String): View = DkaiTheme.pill(context, site, DkaiTheme.PILL, DkaiTheme.WHITE, 12f).apply {
        setCompoundDrawablesRelative(DkaiTheme.icon(context, DkaiIcon.Kind.GLOBE, DkaiTheme.CYAN, 12f), null, null, null)
        compoundDrawablePadding = d(5f); maxLines = 1; UiLang.keep(this)
        layoutParams = LayoutParams(-2, -2).apply { rightMargin = d(6f) }
        alpha = 0f; translationY = d(6f).toFloat()
        animate().alpha(1f).translationY(0f).setDuration(200).start()
    }

    /** असली हालत से: साइटें एक-एक करके, गिनती लगातार */
    fun update() {
        val s = DkaiSearchLive.snap()
        if (!s.on) { visibility = View.GONE; return }
        if (s.query.isNotBlank() && queryLine.text.toString() != s.query) queryLine.text = s.query
        val dots = ".".repeat(((android.os.SystemClock.uptimeMillis() / 400) % 3 + 1).toInt())
        searching.text = "Searching$dots"
        while (shownChips < s.shown && shownChips < s.sites.size) { chipRow.addView(chip(s.sites[shownChips])); shownChips++ }
        count.text = if (s.found) "${s.visited} visited" else ""
        exploring.text = if (s.found && s.sites.isEmpty()) "Reading the answer…" else "Exploring live web sources$dots"
        chipRow.visibility = if (shownChips > 0) View.VISIBLE else View.GONE
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); attached = true; post(tick) }
    override fun onDetachedFromWindow() { attached = false; removeCallbacks(tick); super.onDetachedFromWindow() }
}

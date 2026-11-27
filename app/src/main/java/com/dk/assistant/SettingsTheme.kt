package com.dk.assistant

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.dk.assistant.ui.ClayCheckDrawable
import com.dk.assistant.ui.ClayDrawable
import com.dk.assistant.ui.ClayTheme

/** हिस्से का नाम (दिखावट और Theme पेज दोनों में) */
internal const val THEMES_TITLE = "Themes"

/**
 * 23 नवंबर 2026 (मालिक: Soft 3D Clay UI): Settings › Theme पेज.
 * 8 उभरे कार्ड - बाईं ओर रंग-स्विच की छोटी झलक, थीम का नाम, एक पंक्ति विवरण, दाईं ओर गोल खाना.
 * चुनते ही पूरा ऐप उसी थीम में (यह स्क्रीन तुरंत, पीछे वाली लौटते ही) और चुनाव याद रहता है ([ClayTheme.save]).
 */
internal fun SettingsActivity.secTheme() {
    // 27 नवंबर 2026 (मालिक): थीम की सूची भी बाकी हिस्सों जैसा कार्ड - ऊपर "Themes" + तीर, शुरू में बंद, टैप पर खुले.
    // अंदर का काम वैसा ही (चुनना, सेव, पूरा ऐप नई थीम में) - बस कार्ड अब container की जगह इस हिस्से के box में.
    card(THEMES_TITLE, null) { container ->
        val k = ClayTheme.t
        val cards = ArrayList<Pair<ClayTheme.Theme, LinearLayout>>()
        for (th in ClayTheme.ALL) {
            val on = th.id == ClayTheme.current.id
            val title = TextView(this).apply { text = th.name; textSize = 17f; setTypeface(typeface, Typeface.BOLD) }
            val desc = TextView(this).apply { text = th.desc; textSize = 12.5f; setPadding(0, (3 * dp).toInt(), 0, 0) }
            fun paint(sel: Boolean) {
                title.setTextColor(if (sel) k.onAccent else k.text)
                desc.setTextColor(if (sel) com.dk.assistant.ui.NeonTheme.alpha(k.onAccent, 0xD9) else k.gray)
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = com.dk.assistant.ui.Clay.card(this@secTheme, selectable = true)
                val p = (18 * dp).toInt(); setPadding(p, p, p, p)
                minimumHeight = (84 * dp).toInt()
                isClickable = true; isFocusable = true
                contentDescription = th.name + ", " + th.desc
                isSelected = on
            }
            card.addView(ImageView(this).apply {
                setImageDrawable(ThemeSwatch(th)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams((52 * dp).toInt(), (52 * dp).toInt()).apply { marginEnd = (14 * dp).toInt() })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(title); col.addView(desc)
            card.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(ImageView(this).apply {
                setImageDrawable(ClayCheckDrawable((26 * dp).toInt())); isDuplicateParentStateEnabled = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams((26 * dp).toInt(), (26 * dp).toInt()).apply { marginStart = (10 * dp).toInt() })
            paint(on)
            card.setOnClickListener {
                if (th.id == ClayTheme.current.id) return@setOnClickListener
                // चयन वाला रूप 200ms में भरता है, फिर पूरी स्क्रीन नई थीम में
                for ((t2, c2) in cards) { c2.isSelected = t2.id == th.id }
                paint(true)
                ClayTheme.save(this, th)
                com.dk.assistant.ui.NeonRefresh.bars(this)
                card.postDelayed({ recreate() }, ClayDrawable.FILL_MS + 40)
            }
            cards.add(th to card)
            container.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (12 * dp).toInt()
            })
        }
        hint(container, "थीम चुनते ही पूरा DK उसी रंग में बदल जाता है और ऐप दोबारा खोलने पर भी वही रहती है।")
    }
}

/** थीम की झलक: गोल-कोने वाला खाना (पृष्ठभूमि) + अंदर कार्ड का रंग + accent का गोला */
private class ThemeSwatch(private val th: ClayTheme.Theme) : Drawable() {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    override fun draw(c: Canvas) {
        val b = bounds; if (b.isEmpty) return
        val w = b.width().toFloat(); val h = b.height().toFloat()
        r.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
        p.color = th.bg; c.drawRoundRect(r, w * 0.3f, w * 0.3f, p)
        r.set(b.left + w * 0.14f, b.top + h * 0.14f, b.left + w * 0.62f, b.bottom - h * 0.14f)
        p.color = th.surface; c.drawRoundRect(r, w * 0.16f, w * 0.16f, p)
        p.color = th.accent; c.drawCircle(b.left + w * 0.74f, b.exactCenterY(), w * 0.16f, p)
    }
    override fun setAlpha(alpha: Int) { p.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

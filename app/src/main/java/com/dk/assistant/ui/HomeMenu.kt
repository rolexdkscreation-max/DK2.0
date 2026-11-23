package com.dk.assistant.ui

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import com.dk.assistant.R
import com.dk.assistant.UiLang
import com.dk.assistant.dkai.DkaiTheme

/**
 * v4 होम मेनू (ऊपर-बाएँ ☰): पहले होम पर नीचे जो बड़े कार्ड थे (चैट, आवाज़, मेमोरी) वे और बाकी स्क्रीनें यहाँ.
 * PopupWindow activity के view-tree से बाहर है, इसलिए अनुवाद यहीं UiLang.s से.
 * हर पंक्ति वही पुराना काम खोलती है - कोई नया काम नहीं. पंक्ति 52dp ऊँची (48dp से ज़्यादा छूने की जगह).
 */
object HomeMenu {
    class Item(val icon: Int, val title: String, val sub: String, val action: () -> Unit)

    fun show(a: Activity, anchor: View, items: List<Item>) {
        val dp = a.resources.displayMetrics.density
        // 14 नवंबर 2026 (मालिक): काँच (ग्लासी) हटा - होम पैनल जैसा साधारण सपाट रंग
        DkaiTheme.load(a)
        // 23 नवंबर 2026 (Soft Clay): उभरा बड़ा कार्ड, हर आइटम उभरी पट्टी (दबाने पर धँसे), नीचे ऐप version छोटे अक्षरों में
        val k = ClayTheme.t
        // 23 नवंबर 2026 (मालिक: "☰ की लिस्ट स्क्रॉल नहीं होती, नीचे के आइटम कट जाते हैं"): वही उभरा कार्ड, पर आइटम
        // अब ऊपर-नीचे स्क्रॉल होते हैं ([scroll]); कार्ड की ऊँचाई स्क्रीन के अंदर ([maxHeight]) - Settings पूरा दिखे
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = Clay.card(a)
            val p = (16 * dp).toInt(); setPadding(p, p, p, p)
        }
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(a).apply {
            isVerticalScrollBarEnabled = true; isFillViewport = false; overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(box, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        card.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val pw = PopupWindow(card, (300 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        items.forEach { it0 ->
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                // 23 नवंबर 2026 (मालिक: "मेन्यू के आठों आइटम पर वही साझा कार्ड स्टाइल"): बड़ी नरम गोलाई, सख़्त रेखा / गहरी
                // छाया नहीं, दबाने पर हल्का धँसे और 180ms में चिकनी वापसी - सब [Clay.card] ([ClayCard]) से
                background = Clay.card(a)
                minimumHeight = (60 * dp).toInt()
                val p = (14 * dp).toInt(); setPadding(p, (10 * dp).toInt(), p, (10 * dp).toInt())
                isClickable = true; isFocusable = true
                contentDescription = UiLang.s(it0.title)
                setOnClickListener { pw.dismiss(); it0.action() }
            }
            row.addView(ImageView(a).apply {
                setImageResource(it0.icon); background = Clay.circle(a, 3f)
                val ip = (10 * dp).toInt(); setPadding(ip, ip, ip, ip)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams((42 * dp).toInt(), (42 * dp).toInt()).apply { marginEnd = (12 * dp).toInt() })
            val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(a).apply {
                text = UiLang.s(it0.title); textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(k.text)
            })
            col.addView(TextView(a).apply {
                text = UiLang.s(it0.sub); textSize = 12f; setTextColor(k.gray)
            })
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (ROW_GAP_DP * dp).toInt()
            })
        }
        // सबसे नीचे (स्क्रॉल के बाहर, हमेशा दिखे). 23 नवंबर 2026 (मालिक): version नंबर की जगह दो पंक्तियाँ बीच में -
        // "DK AI" (सुनहरा, Settings के शीर्षक वाला ही [com.dk.assistant.FutureViews.goldShader]; लाइट थीम पर ज़रा गहरा)
        // और ठीक नीचे "By Dhiraj Kumar Singh". version नंबर "ऐप के बारे में" में ही रहता है
        val sign = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            contentDescription = BRAND + ", " + BYLINE
            setTag(com.dk.assistant.PlainLook.SKIP, true)        // रंग अपने हैं - लाइट थीम वाला रंग-बदलाव इन पर नहीं
        }
        sign.addView(TextView(a).apply {
            text = BRAND; textSize = 16f; gravity = Gravity.CENTER; letterSpacing = BRAND_SPACING
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)          // आधा-मोटा
            setTextColor(NeonTheme.title()[1]); includeFontPadding = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            com.dk.assistant.FutureViews.goldShader(this, goldDeeper(k.light))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sign.addView(TextView(a).apply {
            text = BYLINE; textSize = 10.5f; gravity = Gravity.CENTER; letterSpacing = 0.04f; includeFontPadding = false
            setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC))
            setTextColor(bylineColor(k.light))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (2 * dp).toInt() })
        card.addView(sign, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (14 * dp).toInt() })
        // कितनी ऊँचाई मिले: anchor के नीचे से स्क्रीन के नीचे तक (थोड़ी जगह छोड़कर)
        val loc = IntArray(2); anchor.getLocationInWindow(loc)
        val screenH = anchor.rootView?.height?.takeIf { it > 0 } ?: a.resources.displayMetrics.heightPixels
        val maxH = maxHeight(screenH, loc[1] + anchor.height + (6 * dp).toInt(), (16 * dp).toInt())
        card.measure(View.MeasureSpec.makeMeasureSpec((300 * dp).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val natural = card.measuredHeight
        pw.height = minOf(natural, maxH)
        com.dk.assistant.PlainLook.apply(card)   // लाइट थीम: सफ़ेद चिह्न गहरे (रंगीन वैसे ही)
        pw.elevation = 0f
        pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        pw.isOutsideTouchable = true
        if (!NeonTheme.reducedMotion()) pw.animationStyle = android.R.style.Animation_Dialog else pw.animationStyle = 0
        pw.showAsDropDown(anchor, 0, (6 * dp).toInt())
    }

    /** मेन्यू के नीचे की दो पंक्तियाँ (23 नवंबर 2026, मालिक) */
    const val BRAND = "DK AI"
    const val BYLINE = "By Dhiraj Kumar Singh"
    /** "letter spacing लगभग 2" - 16sp पर ~2sp (em में) */
    const val BRAND_SPACING = 0.125f
    /** पंक्तियों के बीच बराबर जगह (dp) - कार्ड की अपनी परछाईं-जगह के साथ पहले जितनी ही */
    const val ROW_GAP_DP = 4f
    /** लाइट थीम पर सुनहरा इतना गहरा (डार्क = Settings वाला हूबहू) - pure */
    fun goldDeeper(light: Boolean): Float = if (light) 0.32f else 0f
    /** नीचे वाली पंक्ति: हल्का सुनहरा ~70% (लाइट थीम पर गहरा सुनहरा, ताकि पढ़ा जाए) - pure */
    fun bylineColor(light: Boolean): Int {
        val g = NeonTheme.title()
        val base = if (light) NeonTheme.mix(g[1], android.graphics.Color.BLACK, goldDeeper(true)) else g[0]
        return (0xB3 shl 24) or (base and 0x00FFFFFF)
    }

    /** pure (JVM test): पैनल की सबसे ज़्यादा ऊँचाई = anchor के नीचे से स्क्रीन के नीचे तक, [margin] छोड़कर (कम से कम 200px) */
    fun maxHeight(screenH: Int, top: Int, margin: Int): Int = (screenH - top - margin).coerceAtLeast(200)
}

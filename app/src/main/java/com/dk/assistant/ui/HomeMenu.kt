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
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = Clay.raised(a, 26f, 7f)
            val p = (16 * dp).toInt(); setPadding(p, p, p, p)
        }
        val pw = PopupWindow(box, (300 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        items.forEach { it0 ->
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = Clay.chip(a, 20f, 4f)
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
                topMargin = (8 * dp).toInt()
            })
        }
        // सबसे नीचे: ऐप का version (छोटा, हल्का)
        box.addView(TextView(a).apply {
            text = try { com.dk.assistant.Updater.displayVersion(a) } catch (_: Exception) { "DK" }
            textSize = 11f; setTextColor(k.hint); gravity = Gravity.CENTER; letterSpacing = 0.06f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (14 * dp).toInt() })
        com.dk.assistant.PlainLook.apply(box)   // लाइट थीम: सफ़ेद चिह्न गहरे (रंगीन वैसे ही)
        pw.elevation = 0f
        pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        pw.isOutsideTouchable = true
        if (!NeonTheme.reducedMotion()) pw.animationStyle = android.R.style.Animation_Dialog else pw.animationStyle = 0
        pw.showAsDropDown(anchor, 0, (6 * dp).toInt())
    }
}

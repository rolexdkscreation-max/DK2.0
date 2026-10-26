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

/**
 * v4 होम मेनू (ऊपर-बाएँ ☰): पहले होम पर नीचे जो बड़े कार्ड थे (चैट, आवाज़, मेमोरी) वे और बाकी स्क्रीनें यहाँ.
 * हर पंक्ति वही पुराना काम खोलती है - कोई नया काम नहीं. पंक्ति 52dp ऊँची (48dp से ज़्यादा छूने की जगह).
 */
object HomeMenu {
    class Item(val icon: Int, val title: String, val sub: String, val action: () -> Unit)

    fun show(a: Activity, anchor: View, items: List<Item>) {
        val dp = a.resources.displayMetrics.density
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.glass_dialog)
            val p = (10 * dp).toInt(); setPadding(p, p, p, p)
        }
        val pw = PopupWindow(box, (288 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        items.forEach { it0 ->
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.glass_row)
                minimumHeight = (56 * dp).toInt()
                val p = (12 * dp).toInt(); setPadding(p, (8 * dp).toInt(), p, (8 * dp).toInt())
                isClickable = true; isFocusable = true
                contentDescription = UiLang.s(it0.title)
                setOnClickListener { pw.dismiss(); it0.action() }
            }
            row.addView(ImageView(a).apply {
                setImageResource(it0.icon); setBackgroundResource(R.drawable.glass_circle)
                val ip = (9 * dp).toInt(); setPadding(ip, ip, ip, ip)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()).apply { marginEnd = (12 * dp).toInt() })
            val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(a).apply {
                UiLang.setText(this, it0.title); textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(NeonTheme.TEXT)
            })
            col.addView(TextView(a).apply {
                UiLang.setText(this, it0.sub); textSize = 12f; setTextColor(NeonTheme.TEXT3)
            })
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (4 * dp).toInt()
            })
        }
        pw.elevation = 0f
        pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        pw.isOutsideTouchable = true
        if (!NeonTheme.reducedMotion()) pw.animationStyle = android.R.style.Animation_Dialog else pw.animationStyle = 0
        pw.showAsDropDown(anchor, 0, (6 * dp).toInt())
    }
}

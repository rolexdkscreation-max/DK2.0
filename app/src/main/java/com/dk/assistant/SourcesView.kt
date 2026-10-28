package com.dk.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Jawab ke neeche chhote "स्रोत" chips (v3.9) - sirf jab jawab sach me web search se aaya.
 * Har chip dabane par woh page browser me khulta hai. Bolkar kabhi nahi padhe jaate.
 */
object SourcesView {
    fun build(c: Context, sources: List<Pair<String, String>>): View? {
        if (sources.isEmpty()) return null
        val dp = c.resources.displayMetrics.density
        val col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(c).apply {
            text = "वेब सर्च से • स्रोत"
            textSize = 11f; setTextColor(0x99FFFFFF.toInt())
            setPadding((4 * dp).toInt(), 0, 0, (3 * dp).toInt())
        })
        // v3.93: खोज किस सेवा से हुई - छोटे अक्षरों में (Tavily / Exa / Gemini); पता न हो (बिना-key बचाव खोज) = कुछ नहीं
        WebSearch.viaOf(sources)?.let { via ->
            head.addView(TextView(c).apply {
                text = "  • $via"
                textSize = 10f; setTextColor(0x80FFFFFF.toInt())
                setPadding(0, 0, 0, (3 * dp).toInt())
                contentDescription = "खोज $via से हुई"
            })
        }
        col.addView(head)
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sources.forEachIndexed { i, (title, url) ->
            val host = Grounding.host(url)
            // grounding ke link aksar google redirect hote hain - title hi asli site ka naam hota hai
            val name = if (title.isNotBlank()) title else host
            row.addView(TextView(c).apply {
                text = "${i + 1}  ${Grounding.label(name)}"
                textSize = 12f; setTextColor(0xFFE3F2FD.toInt()); maxLines = 1
                setBackgroundResource(R.drawable.glass_pill)
                val ph = (10 * dp).toInt(); val pv = (5 * dp).toInt(); setPadding(ph, pv, ph, pv)
                isClickable = true; isFocusable = true
                contentDescription = "स्रोत ${i + 1}: $name, खोलें"
                setOnClickListener { open(c, url) }
            }, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) leftMargin = (6 * dp).toInt() })
        }
        col.addView(HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        })
        return col
    }

    fun open(c: Context, url: String) {
        try {
            c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { DKLog.d("स्रोत नहीं खुला: ${e.message}") }
    }
}

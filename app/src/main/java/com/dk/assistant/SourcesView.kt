package com.dk.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Jawab ke neeche "स्रोत" (v3.9) - sirf jab jawab sach me web search se aaya.
 * 13 नवंबर 2026 (मालिक): पहले सारे स्रोत एक ही पट्टी में ठुँसे दिखते थे - अब वाक्य के अंत में चिप ([GroundView.decorate]) और
 * नीचे क्रम-संख्या वाली सूची (शीर्षक, वेबसाइट, पूरा link) + Google का searchEntryPoint. Bolkar kabhi nahi padhe jaate.
 */
object SourcesView {
    /** पुराने UI (चैट / आवाज़ वाली स्क्रीन) के लिए: गहरे शीशे पर सफ़ेद अक्षर */
    fun build(c: Context, sources: List<Pair<String, String>>, g: GroundCite.G? = null, text: String = "", chipRow: Boolean = false): View? {
        if (sources.isEmpty() && g == null) return null
        val foot = GroundView.footer(c, text, sources, g, 0xFFFFFFFF.toInt(), 0x99FFFFFF.toInt(), 0xFF90CAF9.toInt(), chipRow) ?: return null
        val dp = c.resources.displayMetrics.density
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.glass_bubble_dk)
            val ph = (12 * dp).toInt(); val pv = (8 * dp).toInt(); setPadding(ph, pv, ph, pv)
            contentDescription = "स्रोत: " + sources.joinToString(", ") { Grounding.site(it.first, it.second) }
        }
        box.addView(foot)
        // v3.93: खोज किस सेवा से हुई - छोटे अक्षरों में (Tavily / Exa / Gemini); पता न हो = कुछ नहीं
        // 13 नवंबर 2026: साझा साँचे वाले जवाब में प्रदाता "स्रोत (N)" के हेडर में ही - यह पुरानी लाइन सिर्फ़ पुराने सहेजे जवाबों के लिए
        if (WebCite.card(text, g) == null) WebSearch.viaOf(sources)?.let { via ->
            box.addView(TextView(c).apply { this.text = UiLang.s("खोज") + ": $via"; textSize = 10f; setTextColor(0x80FFFFFF.toInt()); setPadding(0, (4 * dp).toInt(), 0, 0) })
        }
        return box
    }

    fun open(c: Context, url: String) {
        try {
            c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { DKLog.d("स्रोत नहीं खुला: ${e.message}") }
    }
}

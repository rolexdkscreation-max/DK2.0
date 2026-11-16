package com.dk.assistant

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.TextView
import com.dk.assistant.inspector.BetaCheck
import com.dk.assistant.inspector.BetaCheckStore

/**
 * नवंबर 2026 (मालिक): "बीटा जाँच" कार्ड - सिर्फ़ बीटा APK में दिखता है (अपडेट + डिबग, दोनों जगह)।
 * इस version में आए क्रैश / नई गड़बड़ियाँ, मालिक की टिक-सूची, और "स्टेबल में भेजूँ या नहीं" का फ़ैसला।
 * कुछ अपने-आप नहीं भेजता - रिपोर्ट सिर्फ़ बटन दबाने पर GitHub खुलता है।
 */
internal const val BETA_CARD = "बीटा जाँच"

internal fun SettingsActivity.secBetaCheck() {
    if (BetaCheckStore.channel() != BetaCheck.Channel.BETA) return
    card(BETA_CARD, "यह बीटा स्टेबल में भेजने लायक है या नहीं") { box ->
        try { betaBody(box) } catch (e: Throwable) { hint(box, "बीटा जाँच अभी नहीं खुल पाई") }
    }
}

private fun SettingsActivity.betaBody(box: android.widget.LinearLayout) {
    val st = TextView(this).apply {
        textSize = 13f; setTextColor(0xFFFFFFFF.toInt()); setTextIsSelectable(true)
        setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, 0)
    }
    box.addView(st)
    val checks = BetaCheckStore.checklist(this)
    fun upd() {
        val s = BetaCheckStore.session(this)
        st.text = BetaCheck.summary(s, BetaCheck.verdict(s, System.currentTimeMillis()), checks)
    }
    label(box, "आपकी जाँच (इस version में क्या बदला - एक-एक आज़माकर टिक करें)")
    val done = BetaCheckStore.session(this).checked
    checks.forEachIndexed { n, t ->
        switch(box, t, n in done).setOnCheckedChangeListener { _, on -> BetaCheckStore.toggle(this, n, on); upd() }
    }
    upd()
    row(box, button("ताज़ा करें") { upd() }, button("रिपोर्ट भेजें") { openBetaIssue(checks) })
    hint(box, "क्रैश और गड़बड़ियाँ DK Inspector हर 20 मिनट में गिनता है। ✅ = 0 क्रैश + 0 नई गंभीर + 24 घंटे। " +
        "\"रिपोर्ट भेजें\" GitHub पर issue खोलता है (label: beta) - भेजना आपके हाथ में।")
}

private fun SettingsActivity.openBetaIssue(checks: List<String>) {
    try {
        val s = BetaCheckStore.session(this)
        val v = BetaCheck.verdict(s, System.currentTimeMillis())
        val body = BetaCheck.reportBody(s, v, checks, Build.MANUFACTURER + " " + Build.MODEL + " • Android " + Build.VERSION.SDK_INT)
        val url = BetaCheck.issueUrl(Updater.REPO, BetaCheck.reportTitle(s, v), body)
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (e: Exception) { toast("GitHub नहीं खुल पाया") }
}

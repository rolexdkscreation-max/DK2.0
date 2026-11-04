package com.dk.assistant

import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * नवंबर 2026 (मालिक): Settings → Accessibility → "पेमेंट ऐप पर अपने-आप बंद / चालू".
 * फ़ोन के सारे पेमेंट ऐप अपने-आप दिखते हैं (हर एक का अलग स्विच). [PayGuard]
 */
internal fun SettingsActivity.secPayGuard() {
    card("पेमेंट ऐप पर अपने-आप बंद / चालू", "पेमेंट ऐप खुलते ही DK का फ़ोन कंट्रोल बंद, बाहर आते ही चालू", startOpen = true) { box ->
        val master = switch(box, "चालू", PayGuard.on(this))
        master.setOnCheckedChangeListener { _, on -> PayGuard.setOn(this, on) }

        val need = TextView(this).apply { textSize = 13f; setTextColor(0xCCFFFFFF.toInt()); setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, (4 * dp).toInt()) }
        box.addView(need)
        fun refreshNeed() {
            val usage = PayGuard.usageOk(this)
            val write = Setup.canWriteSecure(this)
            need.text = UiLang.s(
                (if (usage) "✓ Usage access: मिला (पेमेंट ऐप से बाहर आना पहचान लेगा)" else "✗ Usage access: नहीं - इसके बिना पेमेंट के बाद notification पर टैप करना होगा") + "\n" +
                (if (write) "✓ \"हमेशा ON\" इजाज़त: मिली (DK खुद फिर चालू कर लेगा)" else "✗ \"हमेशा ON\" इजाज़त: नहीं - ऊपर \"बार-बार बंद न हो\" में एक बार दें, वरना पेमेंट के बाद Accessibility में खुद ON करना होगा"))
        }
        refreshNeed()
        row(box, button("Usage access दें") { PayGuard.openUsageAccess(this) }, button("फिर जाँचें") { refreshNeed() })

        label(box, "इस फ़ोन के पेमेंट ऐप")
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list)
        fun fill(apps: List<PayGuardRules.App>) {
            list.removeAllViews()
            if (apps.isEmpty()) { hint(list, "कोई पेमेंट ऐप नहीं मिला।"); return }
            for (a in apps) {
                val sw = switch(list, a.label, PayGuard.appOn(this, a.pkg))
                sw.setOnCheckedChangeListener { _, on -> PayGuard.setAppOn(this, a.pkg, on) }
            }
        }
        hint(list, "खोज रहा हूँ…")
        fun rescan() = lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { try { PayGuard.scan(this@secPayGuard) } catch (_: Exception) { emptyList() } }
            fill(apps)
        }
        rescan()
        row(box, button("फिर से खोजें") { rescan() })
        hint(box, "UPI से पैसे भेजने वाले सारे ऐप और बैंक / वॉलेट ऐप अपने-आप दिखते हैं। WhatsApp, Amazon जैसे ऐप (जिनमें पेमेंट सिर्फ़ एक हिस्सा है) " +
            "शुरू में बंद हैं ताकि वहाँ DK काम करता रहे — चाहें तो चालू कर दें। पेमेंट के समय DK का फ़ोन कंट्रोल बंद रहता है (उस समय DK सुन / बोल भी नहीं पाएगा)।")
    }
}

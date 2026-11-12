package com.dk.assistant

import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 12 नवंबर 2026 (मालिक):
 *  - Settings → ओर्ब और चेहरा → "DK island (iPhone जैसा)"  [DKIsland]
 *  - Settings → स्क्रीन गार्ड → "प्राइवेसी स्क्रीन (बगल से न दिखे)"  [PrivacyScreen]
 * नई menu row नहीं (ControlCenterTest) - पुराने section में ही नया card.
 */
internal fun SettingsActivity.secIsland() {
    card("DK island (iPhone जैसा)", "DK से बाहर हों तो ऊपर काली गोली - टैप पर काम, कॉल, गाना", startOpen = false) { box ->
        val on = switch(box, "चालू", DKIsland.on(this))
        on.setOnCheckedChangeListener { _, v -> DKIsland.setOn(this, v) }
        val live = switch(box, "DK लाइव भी दिखाएँ (म्यूट / कॉल काटें)", DKIsland.liveOn(this))
        live.setOnCheckedChangeListener { _, v -> DKIsland.setLiveOn(this, v) }
        val media = switch(box, "चल रहा गाना / वीडियो दिखाएँ (YouTube, Spotify…)", DKIsland.mediaOn(this))
        media.setOnCheckedChangeListener { _, v -> DKIsland.setMediaOn(this, v) }

        val need = TextView(this).apply { textSize = 13f; setTextColor(0xCCFFFFFF.toInt()); setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, (4 * dp).toInt()) }
        box.addView(need)
        fun refreshNeed() {
            need.text = UiLang.s(
                (if (DKAccessibilityService.instance != null) "✓ Accessibility: चालू (island दिखेगा)" else "✗ Accessibility: बंद - इसके बिना island नहीं दिखेगा") + "\n" +
                (if (DKNotifListener.enabled(this)) "✓ Notification access: मिला (गाना / वीडियो दिखेगा)" else "✗ Notification access: नहीं - गाना / वीडियो नहीं दिखेगा"))
        }
        refreshNeed()
        row(box, button("Notification access दें") { DKNotifListener.openSettings(this) }, button("फिर जाँचें") { refreshNeed() })
        hint(box, "DK ऐप के बाहर कोई काम चल रहा हो (PC / Coder / Studio का काम, DK लाइव, आती कॉल, कोई गाना) तो स्क्रीन के ऊपर बीच में " +
            "iPhone जैसी छोटी काली गोली दिखती है। उस पर टैप करें तो बड़ी होकर सब दिखाती है: कॉल (उठाएँ / काटें), DK लाइव (म्यूट / कॉल काटें), " +
            "गाना (पीछे / रोकें / आगे) और हाल के ${IslandCore.MAX_TASKS} काम। बाहर टैप करें या ${IslandCore.AUTO_COLLAPSE_MS / 1000} सेकंड छोड़ दें तो फिर छोटी हो जाती है।")
    }
}

internal fun SettingsActivity.secPrivacy() {
    card("प्राइवेसी स्क्रीन (बगल से न दिखे)", "Samsung S26 Ultra जैसा - सामने वाले को दिखे, बगल वाले को कम", startOpen = false) { box ->
        val master = switch(box, "चालू", PrivacyScreen.on(this))
        master.setOnCheckedChangeListener { _, v -> PrivacyScreen.setOn(this, v) }

        label(box, "कहाँ लगे")
        val scopes = PrivacyCore.Scope.values().toList()
        val g = radios(box, scopes.map { it.id to it.hi }, PrivacyScreen.scope(this).id, 7600)
        g.setOnCheckedChangeListener { grp, _ ->
            PrivacyScreen.setScope(this, PrivacyCore.Scope.of(radioValue(grp, scopes.map { it.id }, 7600)))
        }

        label(box, "कितना गहरा")
        val str = SeekBar(this).apply {
            max = PrivacyCore.MAX_STRENGTH - PrivacyCore.MIN_STRENGTH
            progress = PrivacyScreen.strength(this@secPrivacy) - PrivacyCore.MIN_STRENGTH
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) PrivacyScreen.setStrength(this@secPrivacy, PrivacyCore.MIN_STRENGTH + p) }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        box.addView(str, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val hide = switch(box, "चुने ऐप के notification popup न दिखें (island में ताला दिखेगा)", PrivacyScreen.hideNotifs(this))
        hide.setOnCheckedChangeListener { _, v -> PrivacyScreen.setHideNotifs(this, v) }
        val pop = switch(box, "बाकी सब ऐप के popup आते समय भी ऊपर परदा", PrivacyScreen.popups(this))
        pop.setOnCheckedChangeListener { _, v -> PrivacyScreen.setPopups(this, v) }
        if (!DKNotifListener.enabled(this)) row(box, button("Notification access दें (popup छिपाने के लिए)") { DKNotifListener.openSettings(this) })

        label(box, "किन ऐप में (\"सिर्फ़ चुने ऐप\" और notification के लिए)")
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(list)
        fun fill(apps: List<PrivacyScreen.App>) {
            list.removeAllViews()
            if (apps.isEmpty()) { hint(list, "कोई ऐप नहीं मिला।"); return }
            for (a in apps) {
                val sw = switch(list, a.label, PrivacyScreen.appOn(this, a.pkg))
                sw.setOnCheckedChangeListener { _, v -> PrivacyScreen.setAppOn(this, a.pkg, v) }
            }
        }
        hint(list, "ऐप खोज रहा हूँ…")
        fun rescan() = lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { try { PrivacyScreen.scan(this@secPrivacy) } catch (_: Exception) { emptyList() } }
            fill(apps)
        }
        rescan()
        row(box, button("फिर से खोजें") { rescan() })
        hint(box, "Samsung S26 Ultra में यह hardware से होता है (स्क्रीन के pixel ही बगल की तरफ़ रोशनी कम भेजते हैं)। दूसरे फ़ोन में ऐसा hardware नहीं होता, " +
            "इसलिए DK software से वैसा ही करता है: स्क्रीन पर हल्का काला परदा (किनारों पर ज़्यादा गहरा, बारीक लाइनें) ताकि सामने से पढ़ा जाए " +
            "और बगल / तिरछे से पढ़ना मुश्किल हो। चुने ऐप के notification popup नहीं आते - DK उन्हें पढ़ / जवाब दे सकता है और island में ताले के साथ दिखते हैं, टैप करें तो वही ऐप खुले। " +
            "इसके लिए Accessibility चालू होना चाहिए।")
    }
}

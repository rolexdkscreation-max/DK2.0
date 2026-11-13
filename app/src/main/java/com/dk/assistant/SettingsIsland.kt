package com.dk.assistant

import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 12 नवंबर 2026 (मालिक):
 *  - Settings → ओर्ब और चेहरा → "DK Island"  [DKIsland]
 *  - Settings → स्क्रीन गार्ड → "DK Privacy (बगल से न दिखे)"  [PrivacyScreen] - 13 नवंबर 2026 से सिर्फ़ notification का ताला
 * 13 नवंबर 2026 (मालिक): नाम सिर्फ़ "DK Island" / "DK Privacy"; island में सारे notification, चार्जिंग, रील गिनती.
 * नई menu row नहीं (ControlCenterTest) - पुराने section में ही नया card.
 */
internal fun SettingsActivity.secIsland() {
    card("DK Island", "DK से बाहर हों तो ऊपर काली गोली - टैप पर काम, कॉल, गाना, सारे notification, चार्जिंग", startOpen = false) { box ->
        val on = switch(box, "चालू", DKIsland.on(this))
        on.setOnCheckedChangeListener { _, v -> DKIsland.setOn(this, v) }
        val live = switch(box, "DK लाइव भी दिखाएँ (म्यूट / कॉल काटें)", DKIsland.liveOn(this))
        live.setOnCheckedChangeListener { _, v -> DKIsland.setLiveOn(this, v) }
        val media = switch(box, "चल रहा गाना / वीडियो दिखाएँ (YouTube, Spotify…)", DKIsland.mediaOn(this))
        media.setOnCheckedChangeListener { _, v -> DKIsland.setMediaOn(this, v) }
        val notifs = switch(box, "फ़ोन के सारे notification island में (टैप = वहीं खुले, स्लाइड = हटे, सब साफ़ करें)", DKIsland.notifsOn(this))
        notifs.setOnCheckedChangeListener { _, v -> DKIsland.setNotifsOn(this, v) }
        val charge = switch(box, "चार्जिंग animation (बैटरी % + कितनी देर में पूरा)", DKIsland.chargeOn(this))
        charge.setOnCheckedChangeListener { _, v -> DKIsland.setChargeOn(this, v) }
        val peek = switch(box, "नई सूचना / बजती कॉल / काम पूरा = थोड़ी देर अपने-आप बड़ा", DKIsland.peekOn(this))
        peek.setOnCheckedChangeListener { _, v -> DKIsland.setPeekOn(this, v) }

        islandLook(box)

        val need = TextView(this).apply { textSize = 13f; setTextColor(0xCCFFFFFF.toInt()); setPadding((4 * dp).toInt(), (6 * dp).toInt(), 0, (4 * dp).toInt()) }
        box.addView(need)
        fun refreshNeed() {
            need.text = UiLang.s(
                (if (DKAccessibilityService.instance != null) "✓ Accessibility: चालू (island दिखेगा)" else "✗ Accessibility: बंद - इसके बिना island नहीं दिखेगा") + "\n" +
                (if (DKNotifListener.enabled(this)) "✓ Notification access: मिला (notification / गाना / वीडियो दिखेंगे)" else "✗ Notification access: नहीं - notification / गाना / वीडियो नहीं दिखेंगे"))
        }
        refreshNeed()
        row(box, button("Notification access दें") { DKNotifListener.openSettings(this) }, button("फिर जाँचें") { refreshNeed() })
        hint(box, "DK ऐप के बाहर कोई काम चल रहा हो (PC / Coder / Studio का काम, DK लाइव, आती कॉल, कोई गाना, YouTube / फेसबुक / इंस्टाग्राम का काम या रिव्यू) तो स्क्रीन के ऊपर बीच में " +
            "DK Island (छोटी काली गोली) दिखता है - सिर्फ़ वही जो पीछे चल रहा है (जिस ऐप में आप अभी हैं उसका गाना / वीडियो / notification नहीं दिखता)। उस पर टैप करें तो बड़ा होकर सब दिखाता है: कॉल (उठाएँ / काटें, कार्ड पर टैप = चलती कॉल), DK लाइव (म्यूट / कॉल काटें), " +
            "गाना (पीछे / रोकें / आगे), हाल के ${IslandCore.MAX_TASKS} काम और फ़ोन के सारे notification। notification पर टैप = वही चैट / जगह खुले, बाएँ-दाएँ स्लाइड = हटे, " +
            "आख़िर में \"सब साफ़ करें\" = सब हटें। चार्जर लगाते ही बैटरी भरने का animation, % और पूरा होने में कितना समय। रील देखते समय रील की गिनती भी island में ही। " +
            "चलती कॉल पर छोटे island को टैप = सीधे कॉल स्क्रीन; देर तक दबाएँ = पूरा island। बाहर टैप करें या ${IslandCore.AUTO_COLLAPSE_MS / 1000} सेकंड छोड़ दें तो फिर छोटा (कॉल चलते समय भी)। " +
            "दो चीज़ें एक साथ (जैसे दो कॉल, या कॉल + गाना) = पहली गोली में, दूसरी उसके दाएँ अलग छोटे गोल बुलबुले में - हर एक पर टैप = उसी का ऐप / कॉल, देर तक दबाएँ = वही सबसे ऊपर फैलकर। " +
            "कॉल आए तो island अपने-आप बड़ा होकर उठाएँ / काटें दिखाता है; नई सूचना कुछ सेकंड बड़ी दिखती है - ऊपर खींचें या बाहर छुएँ तो छोटा। " +
            "आकार, ऊपर से दूरी और बाएँ-दाएँ ऊपर की पट्टियों से बदलें; \"नमूना देखें\" से कुछ सेकंड island यहीं दिखता है।")
    }
}

internal fun SettingsActivity.secPrivacy() {
    // 13 नवंबर 2026 (मालिक): स्क्रीन वाली परत हटाई (काम नहीं करती थी) - सिर्फ़ notification का ताला; सभी ऐप या एक-एक ऐप
    card("DK Privacy (बगल से न दिखे)", "notification ताले में - ऊपर से न दिखे, ऐप खोलें तब दिखे", startOpen = false) { box ->
        val master = switch(box, "चालू (notification ताले में)", PrivacyScreen.on(this))
        master.setOnCheckedChangeListener { _, v -> PrivacyScreen.setOn(this, v) }
        if (!DKNotifListener.enabled(this)) row(box, button("Notification access दें (ताले के लिए ज़रूरी)") { DKNotifListener.openSettings(this) })

        label(box, "किन ऐप के notification")
        val scopes = PrivacyCore.Scope.values().toList()
        val g = radios(box, scopes.map { it.id to it.hi }, PrivacyScreen.scope(this).id, 7600)

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val switches = ArrayList<Pair<String, android.widget.CompoundButton>>()
        var apps: List<PrivacyScreen.App> = emptyList()
        fun applyScope() {
            val all = PrivacyScreen.scope(this) == PrivacyCore.Scope.ALL
            list.alpha = if (all) 0.45f else 1f
            switches.forEach { it.second.isEnabled = !all }
        }
        g.setOnCheckedChangeListener { grp, _ ->
            PrivacyScreen.setScope(this, PrivacyCore.Scope.of(radioValue(grp, scopes.map { it.id }, 7600)))
            applyScope()
        }

        label(box, "एक-एक ऐप चुनें (\"सिर्फ़ चुने ऐप\" के लिए)")
        fun setAll(v: Boolean) {
            PrivacyScreen.setApps(this, if (v) apps.map { it.pkg }.toSet() else emptySet())
            switches.forEach { it.second.isChecked = v }
        }
        row(box, button("सब चुनें") { setAll(true) }, button("कोई नहीं") { setAll(false) })
        box.addView(list)
        fun fill(found: List<PrivacyScreen.App>) {
            list.removeAllViews(); switches.clear(); apps = found.filter { it.pkg != packageName }
            if (apps.isEmpty()) { hint(list, "कोई ऐप नहीं मिला।"); return }
            for (a in apps) {
                val sw = switch(list, a.label, PrivacyScreen.appOn(this, a.pkg))
                sw.setOnCheckedChangeListener { _, v -> PrivacyScreen.setAppOn(this, a.pkg, v) }
                switches.add(a.pkg to sw)
            }
            applyScope()
        }
        hint(list, "ऐप खोज रहा हूँ…")
        fun rescan() = lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { try { PrivacyScreen.scan(this@secPrivacy) } catch (_: Exception) { emptyList() } }
            fill(found)
        }
        rescan()
        row(box, button("फिर से खोजें") { rescan() })
        hint(box, "DK Privacy: चुने ऐप (या \"सभी ऐप\") का notification आते ही ताले में चला जाता है - ऊपर status bar / popup में नहीं दिखता, " +
            "island में सिर्फ़ \"🔒 ऐप • N नए\" दिखता है। उस पर टैप करें या वह ऐप खुद खोलें, तभी अंदर बात दिखती है। " +
            "कॉल, गाना और फ़ोन के अपने ज़रूरी notification कभी नहीं छिपते। DK छिपे संदेश पढ़ / ऑटो रिप्लाई कर सकता है। " +
            "इसके लिए Notification access चाहिए; island में ताला देखने के लिए DK Island और Accessibility चालू रखें।")
    }
}


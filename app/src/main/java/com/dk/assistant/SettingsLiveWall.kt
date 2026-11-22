package com.dk.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.dk.assistant.livewall.LiveWallPreview
import com.dk.assistant.livewall.LiveWallStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 22 नवंबर 2026 (मालिक: "डीके लाइव के अंदर लाइव वॉलपेपर का सेक्शन, होम और लॉक स्क्रीन दोनों पे,
 * इंटरनेट से डाउनलोड होने वाला, अभी एक, बाद में जितने चाहें"): DK लाइव → लाइव वॉलपेपर.
 * हर वॉलपेपर = repo की catalog.json की एक entry (नया जोड़ने के लिए ऐप बदलना ज़रूरी नहीं).
 */
internal fun SettingsActivity.secLiveWall() {
    val items = LiveWallStore.items(this)
    var cur = items.firstOrNull { it.id == LiveWallStore.selectedId(this) } ?: items.firstOrNull()

    card("लाइव वॉलपेपर • देखें और लगाएँ", "असली समय और आपकी जगह के हिसाब से सूरज, चाँद, तारे — होम + लॉक स्क्रीन", startOpen = true) { box ->
        val name = TextView(this).apply { textSize = 16f; setTextColor(0xFFFFFFFF.toInt()); setPadding((4 * dp).toInt(), (4 * dp).toInt(), 0, 0) }
        box.addView(name)
        val sub = TextView(this).apply { textSize = 12.5f; setTextColor(0x99FFFFFF.toInt()); setPadding((4 * dp).toInt(), (2 * dp).toInt(), 0, (8 * dp).toInt()) }
        box.addView(sub)
        val pv = LiveWallPreview(this)
        box.addView(pv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (440 * dp).toInt()))
        val timeTv = TextView(this).apply { textSize = 13f; setTextColor(0xCCFFFFFF.toInt()); setPadding((4 * dp).toInt(), (10 * dp).toInt(), 0, 0) }
        box.addView(timeTv)
        val fmt = SimpleDateFormat("h:mm a", Locale("hi", "IN"))
        fun showTime(step: Int) {
            val t = System.currentTimeMillis() + step * 10 * 60_000L
            timeTv.text = if (step == 0) "समय: अभी (${fmt.format(Date(t))}) — वॉलपेपर हमेशा अभी का समय दिखाता है"
            else "देखें: ${fmt.format(Date(t))} पर आसमान कैसा होगा (+${step * 10 / 60} घंटे ${step * 10 % 60} मिनट)"
        }
        val seek = SeekBar(this).apply { max = 143; progress = 0 }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { pv.offsetMin = p * 10; showTime(p) }
            override fun onStartTrackingTouch(s: SeekBar?) = Unit
            override fun onStopTrackingTouch(s: SeekBar?) = Unit
        })
        box.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        showTime(0)
        val st = status(box)
        val bDown = button("डाउनलोड") { }
        val bApply = button("लगाएँ") { }
        val bDel = button("हटाएँ") { }
        row(box, bDown, bApply, bDel)
        val credits = TextView(this).apply { textSize = 11f; setTextColor(0x80FFFFFF.toInt()); setPadding((4 * dp).toInt(), (10 * dp).toInt(), 0, 0) }
        box.addView(credits)

        fun refresh() {
            val it = cur
            if (it == null) { name.text = "अभी कोई वॉलपेपर नहीं"; st.text = "इंटरनेट से सूची नहीं आई"; return }
            name.text = it.title; sub.text = it.sub; credits.text = it.credits
            val mb = String.format(Locale.US, "%.1f", it.totalBytes / 1_048_576.0)
            val have = LiveWallStore.installed(this, it)
            bDown.text = if (have) "दोबारा डाउनलोड" else "डाउनलोड ($mb MB)"
            bApply.isEnabled = have; bDel.isEnabled = have
            st.text = when {
                have && LiveWallStore.isActive(this) && LiveWallStore.selectedId(this) == it.id -> "✓ लगा हुआ है"
                have -> "✓ डाउनलोड हो चुका — \"लगाएँ\" दबाएँ"
                else -> "डाउनलोड नहीं हुआ ($mb MB, एक बार)"
            }
            if (have) pv.show(it.id) else { pv.release(); pv.message = "पहले डाउनलोड करें" }
        }

        bDown.setOnClickListener {
            val it = cur ?: return@setOnClickListener
            bDown.isEnabled = false; st.text = "डाउनलोड हो रहा है… 0%"
            lifecycleScope.launch {
                val err = withContext(Dispatchers.IO) {
                    LiveWallStore.download(this@secLiveWall, it, { f -> runOnUiThread { st.text = "डाउनलोड हो रहा है… ${(f * 100).toInt()}%" } })
                }
                bDown.isEnabled = true
                if (err == null) { LiveWallStore.select(this@secLiveWall, it.id); toast("डाउनलोड हो गया") } else toast(err)
                refresh()
                if (err != null) st.text = "✗ $err"
            }
        }
        bApply.setOnClickListener {
            val it = cur ?: return@setOnClickListener
            LiveWallStore.select(this, it.id)
            if (LiveWallStore.isActive(this)) {
                // पहले से हमारा लाइव वॉलपेपर लगा है - बस नया दृश्य (वॉलपेपर अपने-आप बदल जाता है) + लॉक भी
                if (LiveWallStore.target(this) == LiveWallStore.TARGET_BOTH) LiveWallStore.lockToo(this)
                toast("लग गया"); refresh(); return@setOnClickListener
            }
            try { startActivity(LiveWallStore.applyIntent(this)) }
            catch (_: Exception) {
                try { startActivity(LiveWallStore.chooserIntent()); toast("सूची में \"DK AI\" चुनें") } catch (_: Exception) { toast("यह फ़ोन लाइव वॉलपेपर नहीं लगाने देता") }
            }
        }
        bDel.setOnClickListener {
            val it = cur ?: return@setOnClickListener
            LiveWallStore.remove(this, it.id); toast("हटा दिया"); refresh()
        }

        if (items.size > 1) {
            label(box, "और लाइव वॉलपेपर")
            val g = radios(box, items.map { it.id to it.title }, cur?.id ?: "", 7400)
            g.setOnCheckedChangeListener { _, id -> cur = items.getOrNull(id - 7400); refresh() }
        }
        refresh()
        // नई सूची (नए वॉलपेपर) चुपचाप लाओ
        lifecycleScope.launch {
            val got = withContext(Dispatchers.IO) { LiveWallStore.refreshCatalog(this@secLiveWall) }
            if (got) {
                val fresh = LiveWallStore.items(this@secLiveWall)
                cur = fresh.firstOrNull { it.id == cur?.id } ?: fresh.firstOrNull()
                refresh()
            }
        }
    }

    card("कहाँ लगाना है", "होम स्क्रीन + लॉक स्क्रीन दोनों, या सिर्फ़ होम") { box ->
        val g = radios(box, listOf(
            LiveWallStore.TARGET_BOTH to "होम स्क्रीन + लॉक स्क्रीन (दोनों)",
            LiveWallStore.TARGET_HOME to "सिर्फ़ होम स्क्रीन"
        ), LiveWallStore.target(this), 7300)
        g.setOnCheckedChangeListener { _, id ->
            LiveWallStore.setTarget(this, if (id == 7301) LiveWallStore.TARGET_HOME else LiveWallStore.TARGET_BOTH)
            if (id == 7300 && LiveWallStore.isActive(this)) LiveWallStore.lockToo(this)
        }
        hint(box, "\"लगाएँ\" दबाने पर फ़ोन का अपना पन्ना खुलेगा — वहाँ \"Set wallpaper\" / \"होम और लॉक स्क्रीन\" चुनें. " +
            "कुछ फ़ोन (जैसे MIUI / कुछ Samsung) लॉक स्क्रीन पर लाइव वॉलपेपर नहीं चलाते — वहाँ लॉक पर फ़ोन की अपनी फ़ोटो रहेगी.")
    }

    card("जगह (सूरज-चाँद का सही समय)", "आपके शहर में सूरज कब उगता-डूबता है, चाँद कहाँ है — उसी हिसाब से") { box ->
        val tv = status(box)
        fun show() {
            tv.text = String.format(Locale.US, "%.2f°, %.2f°", LiveWallStore.lat(this), LiveWallStore.lon(this)) +
                if (LiveWallStore.locAuto(this)) " — फ़ोन की जगह से" else " — अभी पुणे / पिंपरी (शुरुआती)"
        }
        show()
        row(box, button("मेरी जगह लें") {
            val ok = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!ok) { requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 7310); toast("इजाज़त देकर दोबारा दबाएँ"); return@button }
            if (LiveWallStore.refreshLocation(this)) { show(); toast("जगह मिल गई") } else toast("अभी जगह नहीं मिली — एक बार मैप खोलकर फिर दबाएँ")
        })
        hint(box, "जगह सिर्फ़ फ़ोन में रहती है (सूरज-चाँद की गणना के लिए), कहीं भेजी नहीं जाती.")
    }

    card("लाइव वॉलपेपर कैसे काम करता है", "असली खगोल गणना • भौतिकी से आसमान • बैटरी का ध्यान") { box ->
        hint(box, "सूरज और चाँद की जगह असली खगोल-गणित से (हर पल) — सुबह पूरब (बाएँ) से उगता, दोपहर ऊपर बीच में, शाम पश्चिम (दाएँ) में डूबता. " +
            "चाँद की कला (पूर्णिमा, अर्धचंद्र…) भी असली, NASA की असली चाँद की तस्वीर पर. रात में असली तारे (Yale तारा-सूची) टिमटिमाते हैं.")
        hint(box, "आसमान का रंग भौतिकी से बनता है (हवा के कण नीला बिखेरते हैं, धूल सूरज के पास चमक, शाम को लंबा रास्ता = नारंगी-लाल). " +
            "झील में आसमान, पेड़, सूरज-चाँद की परछाईं लहरों के साथ.")
        hint(box, "बैटरी: वॉलपेपर सिर्फ़ तब चलता है जब स्क्रीन पर दिख रहा हो; बैटरी सेवर में धीमा. फ़ाइलें एक बार डाउनलोड होकर फ़ोन में रहती हैं.")
        hint(box, "नए लाइव वॉलपेपर यहीं अपने-आप दिखेंगे (इंटरनेट से).")
    }
}

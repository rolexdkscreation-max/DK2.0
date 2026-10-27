package com.dk.assistant

import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** DK ki yaadein: jo baatein "याद रखो" bolkar sikhayi, unhe dekho / jodo / hatao. Chat aur baatcheet bhi mita sakte hain. */
class MemoryActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    // v3.88: फ़ोन की लोकल याददाश्त (30 दिन की बातें + हमेशा वाले सार)
    private lateinit var localInfo: TextView
    private lateinit var pointList: LinearLayout
    private lateinit var turnList: LinearLayout
    private lateinit var talkList: LinearLayout
    private lateinit var info: TextView
    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.bg)) }
        root.addView(AuroraView(this), FrameLayout.LayoutParams(-1, -1))
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * dp).toInt(); setPadding(p, p, p, p)
        }
        scroll.addView(col)
        root.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        // ---- upar
        // v4: काँच वाला header (पीछे का बटन 48dp, नाम के साथ)
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.glass_card)
            val hp = (10 * dp).toInt(); setPadding(hp, hp, (14 * dp).toInt(), (12 * dp).toInt())
        }
        top.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_back); setBackgroundResource(R.drawable.glass_circle)
            val p = (12 * dp).toInt(); setPadding(p, p, p, p); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "वापस"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt()))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((12 * dp).toInt(), 0, 0, 0) }
        titles.addView(TextView(this).apply {
            text = "मेमोरी"; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt())
        })
        info = TextView(this).apply { textSize = 12f; setTextColor(0x99FFFFFF.toInt()) }
        titles.addView(info)
        top.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(top)

        // ---- nayi baat jodo
        val addCard = card(col, "नई बात याद कराएँ", "या बोलिए: \"याद रखो मेरी गाड़ी का नंबर …\"")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val edt = EditText(this).apply {
            hint = "जैसे: मेरी बहन का नाम प्रिया है"; setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0x66FFFFFF)
            textSize = 15f; setBackgroundResource(R.drawable.glass_input)
            val p = (12 * dp).toInt(); setPadding(p, p, p, p)
        }
        row.addView(edt, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = "जोड़ें"; textSize = 15f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.btn_accent)
            val p = (14 * dp).toInt(); setPadding(p, p, p, p)
            setOnClickListener {
                val t = edt.text.toString().trim()
                if (Memory.addFact(this@MemoryActivity, t)) { edt.setText(""); toast("याद रख लिया"); refresh() }
                else toast("थोड़ा और लिखिए")
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = (8 * dp).toInt() })
        addCard.addView(row)

        // ---- yaadon ki list
        val listCard = card(col, "मुझे ये बातें याद हैं", "किसी बात को हटाने के लिए ✕ दबाइए")
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listCard.addView(list)

        // ---- v3.45: दोस्त वाली याद (आखिरी मुलाकात, बातचीत के सारांश, अधूरी बातें) - सिर्फ़ इसी फ़ोन पर
        val talkCard = card(col, "बातचीत की याद", "हर बातचीत का छोटा सारांश, आखिरी मुलाकात और अधूरी बातें — सिर्फ़ इसी फ़ोन पर। बोलिए \"यह भूल जाओ\" तो मिट जाएगा। पासवर्ड/बैंक/कार्ड नंबर कभी सेव नहीं होते।")
        talkList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        talkCard.addView(talkList)
        talkCard.addView(button("बातचीत की सारी याद मिटाएँ") {
            confirm("सारे सारांश, आखिरी मुलाकात और अधूरी बातें मिटा दें?") { FriendTalk.clear(this); refresh(); toast("बातचीत की याद मिटा दी") }
        })

        // ---- v3.88: Memory - फ़ोन की लोकल याददाश्त
        val localCard = card(col, "Memory (फ़ोन की याददाश्त)",
            "हर बातचीत (आवाज़ + टेक्स्ट) सिर्फ़ इसी फ़ोन में सेव होती है — 30 दिन बाद अपने-आप मिटती है, उससे पहले ज़रूरी बातों का छोटा सार बनता है जो हमेशा रहता है। पासवर्ड/बैंक/कार्ड नंबर सेव नहीं होते। कुछ भी upload नहीं होता।")
        localInfo = TextView(this).apply { textSize = 13f; setTextColor(0x99FFFFFF.toInt()); UiLang.keep(this) }
        localCard.addView(localInfo)
        localCard.addView(androidx.appcompat.widget.SwitchCompat(this).apply {
            text = "बातचीत फ़ोन में याद रखो"; setTextColor(0xFFFFFFFF.toInt()); textSize = 15f
            isChecked = Prefs.localMemoryOn(this@MemoryActivity)
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            setOnCheckedChangeListener { _, on -> Prefs.edit(this@MemoryActivity) { putBoolean("local_memory_on", on) } }
        })
        localCard.addView(TextView(this).apply { text = "हमेशा वाले सार"; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt()); setPadding(0, (10 * dp).toInt(), 0, 0) })
        pointList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        localCard.addView(pointList)
        localCard.addView(TextView(this).apply { text = "पिछले 30 दिन की बातचीत (नई ऊपर)"; textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt()); setPadding(0, (14 * dp).toInt(), 0, 0) })
        turnList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        localCard.addView(turnList)
        localCard.addView(button("सारी लोकल याददाश्त मिटाएँ") {
            confirm("फ़ोन में सेव पूरी बातचीत और सारे सार मिटा दें? यह वापस नहीं आएगा।") {
                lifecycleScope.launch { LocalMemory.clearAll(this@MemoryActivity); refreshLocal(); toast("लोकल याददाश्त मिटा दी") }
            }
        })

        // ---- saaf karna
        val clean = card(col, "साफ़ करें", "")
        clean.addView(button("सारी यादें मिटाएँ") {
            confirm("सारी याद रखी बातें मिटा दें?") { Memory.clearFacts(this); refresh(); toast("सारी यादें मिटा दीं") }
        })
        clean.addView(button("चैट की बातचीत मिटाएँ") {
            confirm("चैट स्क्रीन की पूरी बातचीत मिटा दें?") { DKState.clearChat(); refresh(); toast("चैट साफ़") }
        })
        clean.addView(button("नई बातचीत (AI पिछली बातें भूले)") {
            Memory.clearHistory(this); toast("ठीक है, अब नई बातचीत शुरू")
        })

        setContentView(root)
        Glass.install(this)
        refresh()
    }

    private fun refreshTalk() {
        talkList.removeAllViews()
        val b = FriendTalk.load(this)
        val now = System.currentTimeMillis()
        val fmt = java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale("hi", "IN"))
        fun line(t: String, dim: Boolean = false) = talkList.addView(TextView(this).apply {
            text = t; textSize = if (dim) 13f else 14f; setTextColor(if (dim) 0x99FFFFFF.toInt() else 0xFFFFFFFF.toInt())
            setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt()); UiLang.keep(this)
        })
        line("आखिरी मुलाकात: " + if (b.lastSeen > 0) "${fmt.format(java.util.Date(b.lastSeen))} (${b.gapText(now)})" else "अभी कोई नहीं")
        if (b.mood.isNotBlank()) line("पिछली बार मूड: ${b.mood}", true)
        if (b.threads.isNotEmpty()) {
            line("अधूरी बातें (DK अगली बार पूछेगा):")
            b.threads.reversed().forEach { line("• ${it.text}", true) }
        }
        val ss = b.sessions.filter { it.summary.isNotBlank() }.takeLast(8).reversed()
        if (ss.isNotEmpty()) {
            line("पिछली बातचीत:")
            ss.forEach { line("• ${fmt.format(java.util.Date(it.start))} (${it.minutes} मिनट): ${it.summary}", true) }
        }
    }

    private fun refreshLocal() {
        if (!::pointList.isInitialized) return
        lifecycleScope.launch {
            val (nt, np) = LocalMemory.counts(this@MemoryActivity)
            val pts = LocalMemory.points(this@MemoryActivity)
            val turns = LocalMemory.recent(this@MemoryActivity, 150)
            if (isFinishing || isDestroyed) return@launch
            localInfo.text = "$nt बातें (30 दिन) • $np सार • सब सिर्फ़ इसी फ़ोन में"
            pointList.removeAllViews(); turnList.removeAllViews()
            if (pts.isEmpty()) pointList.addView(dim("अभी कोई सार नहीं — बातचीत खत्म होने पर / रोज़ बनता है"))
            pts.forEach { p -> pointList.addView(row("${p.day} • ${p.text}") { lifecycleScope.launch { LocalMemory.deletePoint(this@MemoryActivity, p.id); refreshLocal() } }) }
            if (turns.isEmpty()) turnList.addView(dim("अभी कोई बातचीत सेव नहीं"))
            val fmt = java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale("hi", "IN"))
            turns.forEach { t ->
                val who = if (t.who == "u") "आप" else Prefs.assistantName(this@MemoryActivity)
                turnList.addView(row("${fmt.format(java.util.Date(t.at))} • $who${if (t.src == "voice") " 🎤" else ""}: ${t.text.take(400)}") {
                    lifecycleScope.launch { LocalMemory.deleteTurn(this@MemoryActivity, t.id); refreshLocal() }
                })
            }
        }
    }

    private fun dim(t: String) = TextView(this).apply { text = t; textSize = 13f; setTextColor(0x99FFFFFF.toInt()); setPadding(0, (6 * dp).toInt(), 0, 0) }

    private fun row(t: String, onDelete: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.glass_row)
        val p = (10 * dp).toInt(); setPadding(p + (4 * dp).toInt(), p / 2, p / 2, p / 2)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * dp).toInt() }
        addView(TextView(this@MemoryActivity).apply { text = t; textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); UiLang.keep(this) },
            LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(this@MemoryActivity).apply {
            text = "✕"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(0xCCFFFFFF.toInt())
            contentDescription = "हटाएँ"
            setOnClickListener { onDelete() }
        }, LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()))
    }

    private fun refresh() {
        refreshTalk()
        refreshLocal()
        list.removeAllViews()
        val facts = Memory.facts(this)
        info.text = "${facts.size} बातें याद • ${DKState.messages.size} चैट मैसेज"
        if (facts.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "अभी कुछ याद नहीं है। ऊपर लिखकर जोड़िए या बोलिए \"याद रखो …\""
                textSize = 14f; setTextColor(0x99FFFFFF.toInt())
            })
            return
        }
        facts.reversed().forEach { f ->
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.glass_row)
                val p = (12 * dp).toInt(); setPadding(p + (4 * dp).toInt(), p / 2, p / 2, p / 2)
            }
            r.addView(TextView(this).apply { text = f; textSize = 15f; setTextColor(0xFFFFFFFF.toInt()); UiLang.keep(this) },
                LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(TextView(this).apply {
                text = "✕"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(0xCCFFFFFF.toInt())
                contentDescription = "हटाएँ"
                setOnClickListener { Memory.removeFact(this@MemoryActivity, f); refresh() }
            }, LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()))
            list.addView(r, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() })
        }
    }

    /** v3.69: खुलने-बंद होने वाला कॉलम (साझा ui/CollapsibleSection, सेटिंग्स जैसा) - शुरू में बंद */
    private fun card(parent: LinearLayout, title: String, sub: String): LinearLayout =
        com.dk.assistant.ui.CollapsibleSection.add(this, parent, title, sub.ifBlank { null }) {}.body

    private fun button(t: String, on: () -> Unit) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.glass_pill)
        val p = (13 * dp).toInt(); setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() }
        setOnClickListener { on() }
    }

    private fun confirm(q: String, yes: () -> Unit) {
        UiDialog(this).setMessage(q).setPositiveButton("हाँ") { _, _ -> yes() }.setNegativeButton("नहीं", null).show()
    }

    private fun toast(t: String) = UiLang.toast(this, t, Toast.LENGTH_SHORT).show()
}

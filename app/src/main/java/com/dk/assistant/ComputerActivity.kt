package com.dk.assistant

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/** DK कंप्यूटर की स्क्रीन: काम लिखो/बोलो → हर कदम लाइव → फ़ाइलें → जवाब + रिपोर्ट */
class ComputerActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var sub: TextView
    private lateinit var edt: EditText
    private lateinit var send: ImageButton
    private lateinit var askBox: LinearLayout
    private var shown = 0
    private val dp get() = resources.displayMetrics.density
    // ऊपर: "DK कंप्यूटर काम कर रहा है" - animation + अभी का कदम + प्रगति + योजना
    private lateinit var workBox: LinearLayout
    private lateinit var workOrb: ComputerWorkView
    private lateinit var workBar: ComputerWorkView
    private lateinit var workTitle: TextView
    private lateinit var workNow: TextView
    private lateinit var workPlan: TextView
    private var planOpen = false
    private var dots = 0
    private val tick = android.os.Handler(android.os.Looper.getMainLooper())
    private val dotTick = object : Runnable {
        override fun run() {
            if (!ComputerSession.busy) { renderWork(); return }
            dots = (dots + 1) % 4; renderWork()
            tick.postDelayed(this, 500)
        }
    }
    // DK का काम = सूची के आख़िर में जवाब वाले बुलबुले के अंदर तरंग (नीचे अलग animation नहीं)
    private lateinit var inline: DKInlineReply
    private lateinit var composer: DKComposer
    private var busySince = 0L
    private var wasBusy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.bg)) }
        root.addView(AuroraView(this), FrameLayout.LayoutParams(-1, -1))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = (14 * dp).toInt(); setPadding(p, p, p, p) }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_back); setBackgroundResource(R.drawable.glass_circle)
            val p = (12 * dp).toInt(); setPadding(p, p, p, p); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "वापस"; setOnClickListener { finish() }
        }, LinearLayout.LayoutParams((46 * dp).toInt(), (46 * dp).toInt()))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((12 * dp).toInt(), 0, 0, 0) }
        titles.addView(TextView(this).apply { text = "${Prefs.assistantName(this@ComputerActivity)} कंप्यूटर"; textSize = 20f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt()) })
        sub = TextView(this).apply { textSize = 12f; setTextColor(0x99FFFFFF.toInt()); maxLines = 2 }
        titles.addView(sub)
        top.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(smallBtn("फ़ाइलें") { showFiles() })
        top.addView(smallBtn("नया") { newSession() }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = (6 * dp).toInt() })
        col.addView(top)
        col.addView(buildWork(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = (10 * dp).toInt() })

        scroll = ScrollView(this).apply { isFillViewport = true }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt()) }
        scroll.addView(list)
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = (10 * dp).toInt() })

        // इजाज़त कार्ड (मैसेज/कॉल जैसे काम से पहले)
        askBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.glass_card)
            val p = (12 * dp).toInt(); setPadding(p, p, p, p); visibility = View.GONE
        }
        col.addView(askBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (6 * dp).toInt() })

        // साझा इनपुट बॉक्स (हल्के कोनों वाला आयत; 🎤 बार के अंदर बोलकर लिखना - Google पॉप-अप नहीं)
        composer = DKComposer(this, "कोई भी काम बताइए… (जैसे: Nashik के 5 अच्छे स्कूलों की फ़ीस की PDF रिपोर्ट बनाओ)", maxLines = 5)
        edt = composer.edt
        send = composer.send
        col.addView(composer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() })
        inline = DKInlineReply(list, scroll, 0.86f)
        composer.onSend = { doSend() }

        send.setOnClickListener { if (ComputerSession.busy) ComputerSession.stop() else doSend() }
        setContentView(root)
        Glass.install(this)
        if (ComputerSession.lines.isEmpty()) ComputerSession.add("d", welcome())
        // बाहर से काम आया हो (जैसे Settings / आवाज़)
        intent.getStringExtra("task")?.takeIf { it.isNotBlank() }?.let { ComputerSession.start(this, it)?.let { e -> ComputerSession.add("e", e) } }
    }

    private fun buildWork(): View {
        workBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundResource(R.drawable.glass_card)
            val p = (12 * dp).toInt(); setPadding(p, (10 * dp).toInt(), p, (10 * dp).toInt()); visibility = View.GONE
            setOnClickListener { planOpen = !planOpen; renderWork() }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        workOrb = ComputerWorkView(this, bar = false)
        // चलता गोला अब नहीं (DK की तरंग जवाब वाले बुलबुले में है) - यहाँ सिर्फ़ योजना + प्रगति
        workOrb.visibility = View.GONE
        row.addView(workOrb, LinearLayout.LayoutParams((58 * dp).toInt(), (58 * dp).toInt()))
        val txt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        workTitle = TextView(this).apply { textSize = 15f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt()) }
        workNow = TextView(this).apply { textSize = 12.5f; setTextColor(0xCCFFFFFF.toInt()); maxLines = 3; setPadding(0, (2 * dp).toInt(), 0, 0) }
        txt.addView(workTitle); txt.addView(workNow)
        row.addView(txt, LinearLayout.LayoutParams(0, -2, 1f))
        workBox.addView(row)
        workBar = ComputerWorkView(this, bar = true)
        workBox.addView(workBar, LinearLayout.LayoutParams(-1, (6 * dp).toInt()).apply { topMargin = (10 * dp).toInt() })
        workPlan = TextView(this).apply { textSize = 12.5f; setTextColor(0xDDFFFFFF.toInt()); setPadding(0, (8 * dp).toInt(), 0, 0); setLineSpacing(0f, 1.15f) }
        workBox.addView(workPlan)
        return workBox
    }

    /** ऊपर की पट्टी: काम चले तब animation + "कदम 3/6: …" + checklist (छूकर पूरी देखें) */
    private fun renderWork() {
        val busy = ComputerSession.busy
        if (!busy) {
            if (workBox.visibility != View.GONE) workBox.visibility = View.GONE
            workOrb.running = false; workBar.running = false
            return
        }
        if (workBox.visibility != View.VISIBLE) workBox.visibility = View.VISIBLE
        if (!workBar.running) workBar.running = true
        val plan = ComputerSession.plan
        UiLang.setText(workTitle, "काम की प्रगति")
        if (inline.active) inline.show(busyLabel(), WorkState.WORKING)
        UiLang.setText(workNow, ComputerPlan.header(plan, ComputerSession.now))
        workBar.fraction = plan?.fraction ?: 0f
        if (plan == null) { workPlan.visibility = View.GONE; return }
        workPlan.visibility = View.VISIBLE
        val list = ComputerPlan.checklist(plan, title = false)
        UiLang.setText(workPlan, if (planOpen) "$list\n▲ छोटा करें" else "📋 योजना: ${plan.doneCount}/${plan.total} कदम पूरे — पूरी देखने के लिए छुएँ ▼")
    }

    private fun welcome(): String {
        val ai = CoderAi.forComputer(this) { _, _ -> }
        if (!Prefs.computerOn(this)) return "DK कंप्यूटर बंद है — Settings → DK कंप्यूटर में चालू करें।"
        if (ai == null) return "पहले Settings → AI दिमाग → DK कंप्यूटर में API key डालें (Gemini / Groq / OpenRouter / Custom)।"
        return "नमस्ते। मैं ${Prefs.assistantName(this)} कंप्यूटर हूँ — कोई भी काम दीजिए, मैं कदम-दर-कदम पूरा करूँगा और आखिर में रिपोर्ट दूँगा।\n\n" +
            "कैसे काम करता हूँ:\n📋 पहले आपको योजना दिखाता हूँ (क्या समझा, कौन-से कदम, आपको क्या मिलेगा)\n▶ ऊपर हर पल दिखता है कि अभी कौन-सा कदम चल रहा है\n🧐 जवाब देने से पहले खुद जाँचता हूँ कि काम पूरा और सही है\n🪜 जो आपको खुद करना हो, उसके एक-एक कदम समझाता हूँ\n\n" +
            "मैं कर सकता हूँ:\n• इंटरनेट पर खोज और वेबसाइटें पढ़कर रिसर्च (स्रोत के लिंक के साथ)\n• हिसाब, डेटा, तुलना (कोड चलाकर)\n" +
            "• रिपोर्ट, लिस्ट, टेबल — Markdown / CSV / HTML / PDF फ़ाइल\n• फ़ोन के काम (ऐप खोलना, मैसेज, कॉल — पहले आपसे पूछकर)\n" +
            "• GitHub पर कोड बदलवाना (कोडिंग टीम)\n• बातें याद रखना, बाद में काम चलाना / रिमाइंडर\n\n" +
            "उदाहरण: \"आज की भारत की 5 बड़ी टेक खबरें स्रोत के साथ\", \"₹15 लाख के होम लोन की EMI 8.5% पर 20 साल\", \"शाम 6 बजे मुझे पानी पीने की याद दिलाना\""
    }

    private fun doSend() {
        val t = edt.text.toString().trim()
        if (t.isEmpty()) return
        edt.setText("")
        ComputerSession.start(this, t)?.let { toast(it); ComputerSession.add("e", it) }
        render()
    }

    /** बुलबुले में तरंग के बगल का संदेश: अभी का कदम, या असली रुकावट (दोबारा कोशिश / मॉडल / सीमा) */
    private fun busyLabel(): String =
        DKStatus.fresh(busySince) ?: ComputerSession.now.trim().lineSequence().firstOrNull().orEmpty().take(90)

    private fun newSession() {
        ComputerSession.reset(); shown = 0; list.removeAllViews()
        ComputerSession.add("d", welcome())
    }

    private fun showFiles() {
        val fs = ComputerTools.list(this)
        if (fs.isEmpty()) { toast("अभी कोई फ़ाइल नहीं बनी"); return }
        UiDialog(this, Glass.DIALOG)
            .setTitle("DK कंप्यूटर की फ़ाइलें")
            .setItems(fs.map { "${it.name}  (${maxOf(1, it.length() / 1024)} KB)" }.toTypedArray()) { _, i -> fileMenu(fs[i]) }
            .setNegativeButton("बंद करें", null).show()
    }

    private fun fileMenu(f: File) {
        UiDialog(this, Glass.DIALOG)
            .setTitle(f.name)
            .setItems(arrayOf("खोलें", "शेयर करें", "हटाएँ")) { _, w ->
                try {
                    when (w) {
                        0 -> ComputerTools.open(this, f)
                        1 -> ComputerTools.share(this, f)
                        else -> { f.delete(); toast("हटा दी") }
                    }
                } catch (e: Exception) { toast("नहीं खुली: ${e.message?.take(80)}") }
            }.show()
    }

    override fun onResume() {
        super.onResume()
        ComputerSession.visible = true
        ComputerSession.listener = { runOnUiThread { render() } }
        render()
    }

    override fun onPause() { ComputerSession.visible = false; ComputerSession.listener = null; tick.removeCallbacks(dotTick); workOrb.running = false; workBar.running = false; super.onPause() }

    private fun render() {
        val ai = CoderAi.forComputer(this) { _, _ -> }
        val src = ComputerTools.searchOrder(Prefs.computerSearch(this), Prefs.tavilyKey(this).isNotBlank(), Prefs.braveKey(this).isNotBlank(),
            Prefs.scopeKey(this, ApiScopes.PC, "gemini").isNotBlank()).firstOrNull() ?: "search नहीं"
        sub.text = "${ai?.label ?: "AI नहीं"} • खोज: $src" + if (ComputerSession.busy) " • काम चल रहा है…" else ""
        send.setImageResource(if (ComputerSession.busy) android.R.drawable.ic_media_pause else R.drawable.ic_send)
        send.contentDescription = if (ComputerSession.busy) "रोकें" else "भेजें"
        val lines = ComputerSession.lines
        val busy = ComputerSession.busy
        if (busy && !wasBusy) busySince = System.currentTimeMillis()
        wasBusy = busy
        if (lines.size < shown) { inline.drop(); list.removeAllViews(); shown = 0 }
        while (shown < lines.size) {
            val l = lines[shown]; shown++
            // जवाब/गलती = उसी बुलबुले में जहाँ तरंग चल रही थी (links भी छूने लायक)
            if (l.role == "d" || l.role == "e") {
                val b = inline.answer(l.text, if (l.role == "e") 0xFFFFB4B4.toInt() else 0xFFFFFFFF.toInt())
                if (b != null) { b.body.setLinkTextColor(0xFF9FD2FF.toInt()); Linkify.addLinks(b.body, Linkify.WEB_URLS); continue }
            }
            bubble(l); inline.keepLast()
        }
        if (busy) inline.show(busyLabel(), WorkState.WORKING)
        else if (inline.bubble?.isWorking == true) inline.hide()
        renderAsk()
        renderWork()
        if (ComputerSession.busy) { tick.removeCallbacks(dotTick); tick.postDelayed(dotTick, 500) }
    }

    private fun renderAsk() {
        askBox.removeAllViews()
        val a = ComputerSession.ask
        if (a == null) { askBox.visibility = View.GONE; return }
        askBox.visibility = View.VISIBLE
        askBox.addView(TextView(this).apply { text = "❓ इजाज़त चाहिए"; textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt()) })
        askBox.addView(TextView(this).apply { text = a.text; textSize = 13f; setTextColor(0xDDFFFFFF.toInt()); setPadding(0, (4 * dp).toInt(), 0, (8 * dp).toInt()) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(smallBtn("हाँ, करो", accent = true) { ComputerSession.answer(true) }, LinearLayout.LayoutParams(0, (40 * dp).toInt(), 1f).apply { marginEnd = (8 * dp).toInt() })
        row.addView(smallBtn("नहीं") { ComputerSession.answer(false) }, LinearLayout.LayoutParams(0, (40 * dp).toInt(), 1f))
        askBox.addView(row)
    }

    private fun bubble(l: ComputerSession.Line) {
        if (l.role == "f") { fileChip(l.text); return }
        val mine = l.role == "u"
        val tv = TextView(this).apply {
            text = l.text; setTextIsSelectable(true)
            when (l.role) {
                "s" -> { textSize = 12f; setTextColor(0x99FFFFFF.toInt()); setPadding((6 * dp).toInt(), (2 * dp).toInt(), 0, 0) }
                "p" -> {
                    textSize = 14f; setTextColor(0xFFE8F4FF.toInt()); setBackgroundResource(R.drawable.glass_card); setLineSpacing(0f, 1.15f)
                    val ph = (14 * dp).toInt(); val pv = (10 * dp).toInt(); setPadding(ph, pv, ph, pv)
                    maxWidth = (resources.displayMetrics.widthPixels * 0.9).toInt()
                }
                else -> {
                    textSize = 15f; setTextColor(if (l.role == "e") 0xFFFFB4B4.toInt() else 0xFFFFFFFF.toInt())
                    setBackgroundResource(if (mine) R.drawable.glass_bubble_me else R.drawable.glass_bubble_dk)
                    val ph = (14 * dp).toInt(); val pv = (10 * dp).toInt(); setPadding(ph, pv, ph, pv)
                    maxWidth = (resources.displayMetrics.widthPixels * 0.86).toInt()
                    setLinkTextColor(0xFF9FD2FF.toInt())
                    Linkify.addLinks(this, Linkify.WEB_URLS)
                }
            }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (mine) Gravity.END else Gravity.START
            topMargin = ((if (l.role == "s") 2 else 8) * dp).toInt()
        }
        list.addView(tv, lp)
        scroll.post { scroll.smoothScrollTo(0, list.height) }
    }

    private fun fileChip(name: String) {
        val f = File(ComputerTools.dir(this), name)
        val b = smallBtn("📎 $name  — खोलें / शेयर") { if (f.exists()) fileMenu(f) else toast("फ़ाइल अब मौजूद नहीं") }
        list.addView(b, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (6 * dp).toInt() })
    }

    private fun smallBtn(t: String, accent: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 13f; setTextColor(0xFFFFFFFF.toInt())
        setBackgroundResource(if (accent) R.drawable.btn_accent else R.drawable.glass_pill)
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        val ph = (12 * dp).toInt(); setPadding(ph, (6 * dp).toInt(), ph, (6 * dp).toInt())
        isFocusable = false
        setOnClickListener { onClick() }
    }

    private fun toast(t: String) = UiLang.toast(this, t, Toast.LENGTH_SHORT).show()
}

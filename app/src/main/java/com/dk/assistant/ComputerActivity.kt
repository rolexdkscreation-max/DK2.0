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
    private lateinit var edt: EditText
    private lateinit var send: ImageButton
    private lateinit var askBox: LinearLayout
    /** नवंबर 2026: "आगे क्या करें" के बटन (डिब्बे के ऊपर) */
    private lateinit var choiceBar: ChoiceBar
    /** नवंबर 2026 (मालिक): साइड में लाइव कोड - AI जो फ़ाइल लिख रहा है, पूरी दिखती है (रंग बदलने का विकल्प) */
    private lateinit var codePanel: CodeLivePanel
    private lateinit var codeBtn: Button
    private var shown = 0
    /** नवंबर 2026 (बग/स्मूथ): ComputerSession.dropped का आख़िरी देखा मान + एक frame में एक render */
    private var seenDrop = 0L
    private var renderQueued = false
    private val renderNow = Runnable { renderQueued = false; if (!isDestroyed) render() }
    private fun scheduleRender() { if (renderQueued) return; renderQueued = true; list.postOnAnimation(renderNow) }
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

    /** v5 मालिक: फ़ोटो / PDF / text फ़ाइल जोड़ो -> DK कंप्यूटर के फ़ोल्डर में कॉपी, काम में नाम लिख दो (look / read_file से पढ़ेगा) */
    private val pick = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val names = uris.take(6).mapNotNull { u -> try { copyIn(u) } catch (_: Exception) { null } }
        if (names.isEmpty()) return@registerForActivityResult
        val cur = edt.text.toString().trimEnd()
        edt.setText((if (cur.isBlank()) "" else "$cur\n") + attachNote(names))
        edt.setSelection(edt.text.length)
        Toast.makeText(this, "${names.size} फ़ाइल जुड़ी — अब काम लिखिए", Toast.LENGTH_SHORT).show()
    }

    private fun copyIn(u: android.net.Uri): String? = copyIn(this, u)

    companion object {
        /** होम के + (फोटो / फ़ाइल अपलोड) से आई फ़ाइलें: DK कंप्यूटर के फ़ोल्डर में पहले से कॉपी, यहाँ बस नाम */
        const val EXTRA_ATTACHED = "attached"
        const val MAX_BYTES = 30L * 1024 * 1024
        /** जो DK कंप्यूटर पढ़/देख सकता है: फ़ोटो, PDF, text */
        val PICK_TYPES = arrayOf("image/*", "application/pdf", "text/*")

        fun attachNote(names: List<String>) = "(जोड़ी गई फ़ाइल: ${names.joinToString()}) "

        /** null = तस्वीर है पर DK सीधे नहीं पढ़ता (HEIC वगैरह) - JPEG में बदलो */
        fun extFor(mime: String?): String? = when (mime) {
            "application/pdf" -> ".pdf"; "image/png" -> ".png"; "image/webp" -> ".webp"; "image/jpeg", "image/jpg" -> ".jpg"
            else -> if (mime != null && mime.startsWith("image/")) null else ".txt"
        }

        /** फ़ोटो / PDF / text -> DK कंप्यूटर का फ़ोल्डर (30MB तक). सफल = फ़ाइल का नाम */
        fun copyIn(c: android.content.Context, u: android.net.Uri): String? {
            var name = "file"
            c.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { q -> if (q.moveToFirst()) name = q.getString(0) ?: name }
            val ext = extFor(c.contentResolver.getType(u))
            if (ext == null) {
                val bmp = (if (android.os.Build.VERSION.SDK_INT >= 28)
                    android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(c.contentResolver, u)) { d, _, _ ->
                        d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE }
                else c.contentResolver.openInputStream(u)?.use { android.graphics.BitmapFactory.decodeStream(it) }) ?: return null
                val f = java.io.File(ComputerTools.dir(c), ComputerTools.safeName(name.substringBeforeLast('.') + ".jpg", ".jpg"))
                f.outputStream().use { o -> bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, o) }
                return f.name
            }
            val f = java.io.File(ComputerTools.dir(c), ComputerTools.safeName(name, ext))
            c.contentResolver.openInputStream(u)?.use { i -> f.outputStream().use { o -> val n = i.copyTo(o, 64 * 1024); if (n > MAX_BYTES) { f.delete(); return null } } } ?: return null
            return f.name
        }
    }

    /** नवंबर 2026: फ़ोन और टैब पर एक जैसे अक्षर (बहुत बड़े system font से लेआउट नहीं टूटता) */
    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(UiFit.wrap(newBase)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.bg)) }
        root.addView(AuroraView(this), FrameLayout.LayoutParams(-1, -1))
        // नवंबर 2026 (मालिक): पूरी स्क्रीन भरी - किनारे की जगह डिवाइस के हिसाब से (UiFit), notch / नीचे की पट्टी से बचाव
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val e = UiFit.edgePx(this@ComputerActivity); val p = (14 * dp).toInt(); setPadding(e, p, e, (10 * dp).toInt()) }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))
        UiFit.safeArea(root)

        // नवंबर 2026 (मालिक): फ़ोन पर बटन नाम को दबा देते थे - अब दो लाइन (UiFit.header), बटन कभी कटते नहीं
        val back = ImageButton(this).apply {
            setImageResource(R.drawable.ic_back); setBackgroundResource(R.drawable.glass_circle)
            val p = (12 * dp).toInt(); setPadding(p, p, p, p); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "वापस"; setOnClickListener { finish() }
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((12 * dp).toInt(), 0, 0, 0) }
        // नवंबर 2026 (मालिक): Futuristic रूप - चमकदार "DK COMPUTER" (मालिक: ऊपर सिर्फ़ काँच की पट्टी + नाम, status टैग नहीं)
        titles.addView(FutureViews.title(this, Prefs.assistantName(this).uppercase() + " COMPUTER"))
        val top = UiFit.header(this, back, titles, listOf(
            smallBtn("+ जोड़ें") { pick.launch(PICK_TYPES) },   // v5: फ़ोटो / PDF / text DK कंप्यूटर को
            smallBtn("फ़ाइलें") { showFiles() },
            smallBtn("इतिहास") { showHistory() },
            smallBtn("नया") { newSession() },
            smallBtn(CodeLivePanel.label(0)) { codePanel.toggle() }.also { codeBtn = it },
            smallBtn("स्टूडियो") { com.dk.assistant.studio.StudioSession.open(this, null) }))
        FutureViews.glassTopBar(top)      // संदर्भ चित्र: ऊपर काँच की पट्टी (सिर्फ़ नाम)
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
        choiceBar = ChoiceBar(this).apply {
            onPick = { ch ->
                if (ch.act == FollowUp.Act.REROUTE) ComputerSession.reroute()
                else { ComputerSession.choices = emptyList(); edt.setText(ch.text); doSend() }
                render()
            }
            onEdit = { t -> edt.setText(t); edt.setSelection(edt.text.length); edt.requestFocus()
                getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showSoftInput(edt, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
        }
        col.addView(choiceBar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (4 * dp).toInt() })

        // साझा इनपुट बॉक्स (हल्के कोनों वाला आयत; 🎤 बार के अंदर बोलकर लिखना - Google पॉप-अप नहीं)
        composer = DKComposer(this, "कोई भी काम लिखिए…", maxLines = 5)
        FutureViews.glassCommandBar(composer)   // नीचे काँच का कमांड बार (नियॉन किनारा)
        edt = composer.edt
        send = composer.send
        col.addView(composer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() })
        inline = DKInlineReply(list, scroll, 0.86f, agent = true)
        composer.onSend = { doSend() }

        // काम चलते समय: डिब्बा खाली = रोकें; कुछ लिखा = भेजें (उसी काम का = साथ में, अलग = बाद में)
        send.setOnClickListener { if (ChoiceBar.stops(edt, ComputerSession.busy)) ComputerSession.stop() else doSend() }
        ChoiceBar.wireSend(edt, send) { ComputerSession.busy }
        // DK कंप्यूटर की फ़ाइलें + इससे बने स्टूडियो ऐप की फ़ाइलें
        codePanel = CodeLivePanel(this, { it == CodeLive.WHO_COMPUTER || CodeLive.isStudio(it) }) { n -> codeBtn.text = UiLang.s(CodeLivePanel.label(n)) }
        codePanel.attachTo(root)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (codePanel.isOpen) codePanel.close() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() } }
        })
        setContentView(root)
        Glass.install(this)
        PlainLook.install(this)   // 14 नवंबर 2026 (मालिक): होम स्क्रीन जैसा साधारण रूप (काँच नहीं)
        ComputerSession.restoreLines(this)          // v3.88: ऐप बंद हुआ था - पिछली बातचीत वापस
        if (ComputerSession.lines.isEmpty()) ComputerSession.add("d", welcome())
        ComputerSession.pendingResume(this)?.let { s -> if (intent.getStringExtra("task").isNullOrBlank()) askResume(s) }
        // बाहर से काम आया हो (जैसे Settings / आवाज़)
        intent.getStringExtra("task")?.takeIf { it.isNotBlank() }?.let { ComputerSession.start(this, it)?.let { e -> ComputerSession.add("e", e) } }
        // होम का + (फोटो / फ़ाइल अपलोड): फ़ाइल पहले से फ़ोल्डर में - बस नाम लिख दो, मालिक काम लिखे
        intent.getStringArrayListExtra(EXTRA_ATTACHED)?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { names ->
            edt.setText(attachNote(names)); edt.setSelection(edt.text.length); edt.requestFocus()
            UiLang.toast(this, "${names.size} फ़ाइल जुड़ी — अब काम लिखिए", Toast.LENGTH_SHORT).show()
        }
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
        if (ai == null) return "पहले Settings → AI दिमाग → DK कंप्यूटर में API key डालें (Gemini / OpenRouter / Z.ai / NVIDIA / Groq)।"
        // नवंबर 2026 (मालिक): खुलते ही लंबी सूची नहीं - पूछने पर ही (AgentIntro.computerCapabilities)
        return AgentIntro.computerHello(Prefs.assistantName(this))
    }

    /** इस समय चालू टूल (Settings → DK कंप्यूटर) */
    private fun enabledTools(): List<String> = ComputerAgent.TOOL_IDS.filter { Prefs.computerTool(this, it) }

    private fun doSend() {
        val t = edt.text.toString().trim()
        if (t.isEmpty()) return
        edt.setText("")
        ComputerSession.choices = emptyList()
        // नवंबर 2026 (मालिक): "तुम क्या-क्या कर सकते हो" = उसी समय चालू टूल की सूची (AI कॉल नहीं)
        if (!ComputerSession.busy && AgentIntro.isCapabilityQuestion(t)) {
            ComputerSession.add("u", t); ComputerSession.add("a", AgentIntro.computerCapabilities(Prefs.assistantName(this), enabledTools())); render(); return
        }
        // नवंबर 2026 (मालिक): ऐप स्टूडियो से जुड़ा - "स्टूडियो में क्या बन रहा है / उसमें X बदलो / रोको / खोलो"
        com.dk.assistant.studio.StudioLink.handle(this, t, com.dk.assistant.studio.StudioSession.WHO_COMPUTER)?.let { (msg, ok) ->
            ComputerSession.add("u", t); ComputerSession.add(if (ok) "a" else "e", msg); render(); return
        }
        // नवंबर 2026 (ऐप स्टूडियो): "ऐप / वेब ऐप / वेबसाइट / गेम बनाओ" = फ़ोन पर project + वहीं चलता preview
        if (!ComputerSession.busy) com.dk.assistant.studio.StudioCore.extract(t)?.let { want ->
            ComputerSession.add("u", t)
            val err = com.dk.assistant.studio.StudioSession.startFrom(this, want, com.dk.assistant.studio.StudioSession.WHO_COMPUTER)
            ComputerSession.add(if (err == null) "s" else "e", err ?: com.dk.assistant.studio.StudioRoute.STARTED_COMPUTER)
            render(); return
        }
        ComputerSession.start(this, t)?.let { toast(it); ComputerSession.add("e", it) }
        render()
    }

    /** बुलबुले में तरंग के बगल का संदेश: अभी का कदम (v5, मालिक: रुकावट / दोबारा कोशिश / सीमा जैसी गलतियाँ नहीं दिखतीं) */
    private fun busyLabel(): String =
        ComputerSession.plan.let { p -> ChatClean.busyLabel(p?.doneCount, p?.total) }   // नवंबर 2026: लंबा कदम-पाठ नहीं, छोटा "काम चल रहा है"

    private fun newSession() {
        ComputerSession.reset(); shown = 0; seenDrop = ComputerSession.dropped; list.removeAllViews()
        ComputerSession.add("d", welcome())
    }

    /** v3.88: पिछला अधूरा काम - वहीं से आगे? */
    private fun askResume(s: ComputerSaved.State) {
        UiDialog(this, Glass.DIALOG)
            .setTitle("पिछला काम अधूरा रह गया था")
            .setMessage(s.task.take(300) + "\n\n" + (if (s.netPaused) "नेटवर्क टूटने से रुका था।" else "ऐप बंद होने से बीच में रुका था।") +
                " जो कदम हो चुके वो दोबारा नहीं होंगे — वहीं से आगे करूँ?")
            .setPositiveButton("हाँ, आगे करो") { _, _ -> ComputerSession.resume(this)?.let { toast(it) } }
            .setNegativeButton("नहीं") { _, _ -> ComputerSaved.clear() }
            .show()
    }

    /** v3.88: हर काम और उसकी हालत (DK कंप्यूटर + कोडिंग टीम) */
    private fun showHistory() {
        val l = TaskHistory.all(this)
        if (l.isEmpty()) { toast("अभी कोई काम का इतिहास नहीं"); return }
        UiDialog(this, Glass.DIALOG)
            .setTitle("कामों का इतिहास")
            .setItems(l.map { TaskHistory.line(it) }.toTypedArray()) { _, _ -> }
            .setNegativeButton("बंद करें", null).show()
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
                } catch (e: Exception) { DKLog.d("Computer: फ़ाइल नहीं खुली ${e.javaClass.simpleName}: ${e.message?.take(160)}"); toast("फ़ाइल नहीं खुली") }
            }.show()
    }

    override fun onResume() {
        super.onResume()
        ComputerSession.visible = true
        ComputerSession.listener = { runOnUiThread { scheduleRender() } }
        render()
    }

    override fun onPause() { ComputerSession.visible = false; ComputerSession.listener = null; tick.removeCallbacks(dotTick); workOrb.running = false; workBar.running = false; super.onPause() }

    private fun render() {
        ChoiceBar.icon(edt, send, ComputerSession.busy)
        val lines = ComputerSession.lines
        val busy = ComputerSession.busy
        if (busy && !wasBusy) busySince = System.currentTimeMillis()
        wasBusy = busy
        val drop = ComputerSession.dropped
        shown = UiPace.shownAfterDrop(shown, seenDrop, drop); seenDrop = drop
        if (lines.size < shown) { inline.drop(); list.removeAllViews(); shown = 0 }
        while (shown < lines.size) {
            val l = lines[shown]; shown++
            // जवाब/गलती = उसी बुलबुले में जहाँ तरंग चल रही थी (links भी छूने लायक)
            if (l.role == "d" || l.role == "e") {
                val b = inline.answer(l.text, if (l.role == "e") 0xFFFFB4B4.toInt() else 0xFFFFFFFF.toInt())
                if (b != null) {
                    FutureViews.addCodeParts(b, b.body, l.text, ::copyCode)      // ``` कोड = IDE जैसा पैनल + कॉपी
                    b.body.setLinkTextColor(0xFF9FD2FF.toInt()); Linkify.addLinks(b.body, Linkify.WEB_URLS); continue
                }
            }
            // नवंबर 2026 (मालिक): "क्या कर रहा है" वाली कदम-लाइनें चैट के बीच में नहीं (ऊपर प्रगति पट्टी में दिखती हैं)
            if (!ChatClean.showInChat(l.role, busy, shown == lines.size)) continue
            bubble(l); inline.keepLast()
        }
        if (busy) inline.show(busyLabel(), WorkState.WORKING)
        else if (inline.bubble?.isWorking == true) inline.hide()
        renderAsk()
        renderChoices()
        renderWork()
        if (ComputerSession.busy) { tick.removeCallbacks(dotTick); tick.postDelayed(dotTick, 500) }
    }

    /** काम चलते समय: बीच में भेजी बात का रास्ता पलटने का बटन; काम पूरा/सवाल: आगे के बटन */
    private fun renderChoices() {
        val r = ComputerSession.routed
        choiceBar.show(when {
            ComputerSession.ask != null -> emptyList()
            ComputerSession.busy -> when (r?.route) { FollowUp.Route.NOW -> listOf(FollowUp.TO_NEXT); FollowUp.Route.NEXT -> listOf(FollowUp.TO_NOW); null -> emptyList() }
            else -> ComputerSession.choices
        })
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
        if (l.role == "d") FutureViews.dkCodeBubble(this, l.text, (resources.displayMetrics.widthPixels * 0.86).toInt(), 0xFFFFFFFF.toInt(), ::copyCode)?.let {
            list.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() })
            scroll.post { scroll.smoothScrollTo(0, list.height) }; return
        }
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
                    // नवंबर 2026: आपका बुलबुला = गहरा नीला → बैंगनी (16dp), DK का = काँच
                    if (mine) background = FutureViews.userBubble(this@ComputerActivity) else setBackgroundResource(R.drawable.glass_bubble_dk)
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

    private fun copyCode(code: String) {
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("code", code)); toast("कोड कॉपी हो गया")
    }

    private fun toast(t: String) = UiLang.toast(this, t, Toast.LENGTH_SHORT).show()
}

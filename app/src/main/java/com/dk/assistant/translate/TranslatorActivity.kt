package com.dk.assistant.translate

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.dk.assistant.Prefs
import com.dk.assistant.R
import com.dk.assistant.UiLang
import com.dk.assistant.plus.models.ModelCatalog
import com.dk.assistant.plus.models.ModelManager

/**
 * 25 नवंबर 2026 (मालिक: "DK ट्रांसलेटर"): दुभाषिया स्क्रीन.
 * ऊपर = सामने वाले की बात (हिंदी में, नीचे छोटे में असली), नीचे = मेरी बात (अनुवाद सहित + हिंदी लिपि में पढ़ने लायक).
 * भाषा चुनें, माइक एक बार चालू = लगातार सुनना, बड़ा बटन दबाकर हिंदी बोलें, या लिखकर भेजें.
 */
class TranslatorActivity : AppCompatActivity(), TrEngine.Ui {
    companion object {
        const val PREF_LANG = "dk_translator_lang"
        private const val REQ_MIC = 7311
        private const val GOOD = 0xFF4CAF50.toInt()
        private const val WARN = 0xFFFFC107.toInt()

        fun open(c: Context): String = try { c.startActivity(TrEngine.intent(c)); "OK" } catch (e: Exception) { "ERROR: ${e.message}" }
    }

    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(com.dk.assistant.UiFit.tablet(newBase)) }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var routeTv: TextView
    private lateinit var noteTv: TextView
    private lateinit var topBox: LinearLayout
    private lateinit var botBox: LinearLayout
    private lateinit var topScroll: ScrollView
    private lateinit var botScroll: ScrollView
    private lateinit var micBtn: TextView
    private lateinit var ptt: TextView
    private lateinit var dlBtn: TextView
    private val k get() = com.dk.assistant.ui.ClayTheme.t
    private var d = 1f
    private var closing = false

    private val tick = object : Runnable {
        override fun run() { showModel(); main.postDelayed(this, 1000) }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        d = resources.displayMetrics.density
        // 25 नवंबर 2026 (मालिक: "ट्रांसलेटर का विंडो अलग लगता है - पूरे फ़ोन (DK) जैसा UI हो"): वही थीम / रंग / काँच वाला header,
        // वही कार्ड और बटन जो मेमोरी / सेटिंग में हैं ([com.dk.assistant.ui.ClayTheme]) - अपने अलग पक्के रंग नहीं.
        val frame = FrameLayout(this).apply { setBackgroundColor(k.bg) }
        frame.addView(com.dk.assistant.AuroraView(this), FrameLayout.LayoutParams(-1, -1))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(14), px(14), px(14), px(14)) }
        frame.addView(root, FrameLayout.LayoutParams(-1, -1))

        // शीर्षक (काँच वाला header: पीछे का बटन + नाम + रास्ता)
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.glass_card); setPadding(px(10), px(10), px(14), px(12))
        }
        head.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_back); setBackgroundResource(R.drawable.glass_circle)
            setPadding(px(12), px(12), px(12), px(12)); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = UiLang.s("बंद करें"); setOnClickListener { close() }
        }, LinearLayout.LayoutParams(px(48), px(48)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(12), 0, 0, 0) }
        titles.addView(TextView(this).apply { text = UiLang.s("DK ट्रांसलेटर"); textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(k.text) })
        routeTv = TextView(this).apply { textSize = 12f; setTextColor(k.sub) }
        titles.addView(routeTv)
        head.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(head)

        // भाषा
        root.addView(header("सामने वाले की भाषा"))
        val sp = Spinner(this).apply { setBackgroundResource(R.drawable.glass_input); setPadding(px(12), px(8), px(12), px(8)) }
        val labels = TrLang.CHOICES.map { TrLang.label(it) }
        sp.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, labels) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View =
                super.getView(position, convertView, parent).also { (it as? TextView)?.setTextColor(k.text) }
        }
        val saved = Prefs.str(this, PREF_LANG, TrLang.ALL.first().code)
        sp.setSelection(TrLang.CHOICES.indexOf(saved).coerceAtLeast(0))
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val code = TrLang.CHOICES.getOrNull(pos) ?: return
                Prefs.edit(this@TranslatorActivity) { putString(PREF_LANG, code) }
                TrEngine.setLang(code)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) { /* कुछ नहीं */ }
        }
        UiLang.keep(sp)
        root.addView(sp, LinearLayout.LayoutParams(-1, -2))

        status = TextView(this).apply { textSize = 20f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(0, px(8), 0, px(2)) }
        noteTv = TextView(this).apply { textSize = 13f; setTextColor(WARN); gravity = Gravity.CENTER; visibility = View.GONE }
        UiLang.keep(status); UiLang.keep(routeTv); UiLang.keep(noteTv)
        root.addView(status); root.addView(noteTv)

        // ऊपर: सामने वाला
        root.addView(header("सामने वाला (हिंदी में)"))
        topBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(6), px(6), px(6), px(6)) }
        topScroll = ScrollView(this).apply { addView(topBox); setBackgroundResource(R.drawable.glass_card) }
        UiLang.keep(topBox)
        root.addView(topScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // नीचे: मैं
        root.addView(header("मेरी बात (अनुवाद सहित)"))
        botBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(6), px(6), px(6), px(6)) }
        botScroll = ScrollView(this).apply { addView(botBox); setBackgroundResource(R.drawable.glass_card) }
        UiLang.keep(botBox)
        root.addView(botScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // लिखकर भेजें
        val typeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(8), 0, 0) }
        val et = EditText(this).apply {
            hint = UiLang.s("हिंदी में लिखें…"); setTextColor(k.text); setHintTextColor(k.hint); maxLines = 3; textSize = 15f
            setBackgroundResource(R.drawable.glass_input); setPadding(px(12), px(12), px(12), px(12))
        }
        typeRow.addView(et, LinearLayout.LayoutParams(0, -2, 1f))
        typeRow.addView(TextView(this).apply {
            text = UiLang.s("भेजें"); textSize = 15f; setTextColor(k.onAccent); gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.btn_accent); setPadding(px(16), px(13), px(16), px(13))
            setOnClickListener { val s = et.text.toString(); if (s.isNotBlank()) { et.setText(""); TrEngine.typed(s) } }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = px(8) })
        root.addView(typeRow)

        // माइक + ऑफ़लाइन मॉडल
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(8), 0, 0) }
        micBtn = pill { TrEngine.setMic(!TrEngine.micOn); showMic() }
        dlBtn = pill { TrOfflineMt.download(this@TranslatorActivity); showModel() }
        UiLang.keep(micBtn); UiLang.keep(dlBtn)
        row.addView(micBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(4) })
        row.addView(dlBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = px(4) })
        root.addView(row)

        // बड़ा बटन: दबाकर हिंदी बोलें
        ptt = TextView(this).apply {
            text = UiLang.s("दबाए रखें - हिंदी बोलें"); textSize = 20f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
            setTextColor(k.onAccent); setBackgroundResource(R.drawable.btn_accent); isClickable = true
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { v.alpha = 0.6f; TrEngine.pttDown() }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.alpha = 1f; TrEngine.pttUp() }
                }
                true
            }
        }
        root.addView(ptt, LinearLayout.LayoutParams(-1, px(96)).apply { topMargin = px(10) })
        setContentView(frame)
        com.dk.assistant.PlainLook.install(this)
        com.dk.assistant.Glass.install(this)

        TrEngine.ui = this
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin()
        else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    private fun begin() {
        TrEngine.start(this, Prefs.str(this, PREF_LANG, TrLang.ALL.first().code))
        onStatus(TrEngine.status); onRoute(TrEngine.route); onLines(); showMic(); showModel()
        if (TrEngine.lastNote.isNotEmpty()) onNote(TrEngine.lastNote)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) begin() else onNote("माइक की अनुमति के बिना ट्रांसलेटर नहीं सुन सकता")
    }

    override fun onResume() { super.onResume(); TrEngine.ui = this; main.post(tick) }
    override fun onPause() { super.onPause(); main.removeCallbacks(tick) }

    override fun onDestroy() {
        if (TrEngine.ui === this) TrEngine.ui = null
        main.removeCallbacksAndMessages(null)
        // बाहर निकलना = ट्रांसलेटर बंद (माइक DK Live को वापस)
        if (isFinishing && !closing) TrEngine.stop()
        super.onDestroy()
    }

    private fun close() { closing = true; TrEngine.stop(); finish() }

    // ---------------------------------------------------------------- TrEngine.Ui

    override fun onStatus(st: TrEngine.St) {
        status.text = st.hi
        status.setTextColor(when (st) { TrEngine.St.LISTENING -> GOOD; TrEngine.St.TRANSLATING -> WARN; TrEngine.St.SPEAKING -> k.accent })
        ptt.text = if (st == TrEngine.St.LISTENING && TrEngine.offline) "दबाए रखें - हिंदी बोलें (ऑफ़लाइन)" else "दबाए रखें - हिंदी बोलें"
    }

    override fun onRoute(s: String) { routeTv.text = s }

    override fun onNote(s: String) {
        noteTv.text = s; noteTv.visibility = View.VISIBLE
        main.removeCallbacks(hideNote); main.postDelayed(hideNote, 7000)
    }

    private val hideNote = Runnable { noteTv.visibility = View.GONE }

    override fun onLines() {
        fill(topBox, TrEngine.top, mine = false); fill(botBox, TrEngine.bottom, mine = true)
        topScroll.post { topScroll.fullScroll(View.FOCUS_DOWN) }
        botScroll.post { botScroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onClosed() { if (!isFinishing) { closing = true; finish() } }

    private fun fill(box: LinearLayout, list: List<TrEngine.Line>, mine: Boolean) {
        box.removeAllViews()
        for (l in list) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(px(12), px(8), px(12), px(8))
                setBackgroundResource(if (mine) R.drawable.glass_bubble_me else R.drawable.glass_bubble_dk)
            }
            val lang = TrLang.of(l.lang)?.hi ?: ""
            if (!mine) {
                // बड़ा: हिंदी अनुवाद; छोटा: असली बात
                if (l.tr.isNotBlank()) item.addView(tv(l.tr, 18f, k.text))
                if (l.orig.isNotBlank()) item.addView(tv((if (lang.isNotEmpty()) "$lang: " else "") + l.orig, 13f, k.sub))
            } else {
                if (l.orig.isNotBlank()) item.addView(tv("मैं: " + l.orig, 16f, k.text))
                if (l.tr.isNotBlank()) item.addView(tv((if (lang.isNotEmpty()) "$lang: " else "") + l.tr, 17f, k.accent))
                if (l.read.isNotBlank() && l.read != l.tr) item.addView(tv("(हिंदी में पढ़ें: ${l.read})", 13f, k.sub))
            }
            if (l.note.isNotBlank()) item.addView(tv(l.note, 13f, WARN))
            if (item.childCount > 0) box.addView(item, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(6) })
        }
    }

    private fun showMic() {
        micBtn.text = if (TrEngine.micOn) "सुनना: चालू (बंद करें)" else "सुनना: बंद (चालू करें)"
    }

    private fun showModel() {
        if (TrOfflineMt.downloaded(this)) { dlBtn.text = "ऑफ़लाइन मॉडल: तैयार"; dlBtn.isEnabled = false; return }
        val sts = ModelCatalog.MT_INDIC.map { ModelManager.state(it.id).value }
        val run = sts.filterIsInstance<ModelManager.St.Running>()
        val failed = sts.firstOrNull { it is ModelManager.St.Failed } as? ModelManager.St.Failed
        dlBtn.isEnabled = run.isEmpty()
        dlBtn.text = when {
            run.isNotEmpty() -> "ऑफ़लाइन मॉडल: ${run.sumOf { it.done } / 1_048_576} / ${ModelCatalog.MT_INDIC.sumOf { it.bytes } / 1_048_576} MB"
            failed != null -> "ऑफ़लाइन मॉडल: दोबारा डाउनलोड (${failed.why.take(30)})"
            else -> "ऑफ़लाइन मॉडल डाउनलोड (Wi-Fi, ~${ModelCatalog.MT_INDIC.sumOf { it.bytes } / 1_048_576} MB)"
        }
    }

    private fun header(t: String) = TextView(this).apply { text = UiLang.s(t); textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(k.sub); setPadding(px(4), px(10), 0, px(4)) }

    private fun pill(on: () -> Unit) = TextView(this).apply {
        textSize = 14f; setTextColor(k.text); gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.glass_pill); setPadding(px(10), px(12), px(10), px(12))
        setOnClickListener { on() }
    }

    private fun tv(t: String, size: Float, color: Int) = TextView(this).apply { text = t; textSize = size; setTextColor(color); setTextIsSelectable(true) }

    private fun px(v: Int) = (v * d).toInt()
}

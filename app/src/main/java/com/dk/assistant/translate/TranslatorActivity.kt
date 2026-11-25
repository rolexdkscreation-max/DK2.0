package com.dk.assistant.translate

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.dk.assistant.Prefs
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

        fun open(c: Context): String = try { c.startActivity(TrEngine.intent(c)); "OK" } catch (e: Exception) { "ERROR: ${e.message}" }
    }

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(com.dk.assistant.UiFit.tablet(newBase)) }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var routeTv: TextView
    private lateinit var noteTv: TextView
    private lateinit var topBox: LinearLayout
    private lateinit var botBox: LinearLayout
    private lateinit var topScroll: ScrollView
    private lateinit var botScroll: ScrollView
    private lateinit var micBtn: Button
    private lateinit var ptt: Button
    private lateinit var dlBtn: Button
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
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF0B1220.toInt()); setPadding(px(12), px(12), px(12), px(12)) }

        // शीर्षक + बंद
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply { text = UiLang.s("DK ट्रांसलेटर"); textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF00E5FF.toInt()) },
            LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(Button(this).apply { text = UiLang.s("बंद करें"); isAllCaps = false; setOnClickListener { close() } })
        root.addView(head)

        // भाषा
        root.addView(TextView(this).apply { text = UiLang.s("सामने वाले की भाषा"); textSize = 13f; setTextColor(0x99FFFFFF.toInt()) })
        val sp = Spinner(this)
        val labels = TrLang.CHOICES.map { TrLang.label(it) }
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
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
        root.addView(sp)

        status = TextView(this).apply { textSize = 20f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(0, px(6), 0, px(2)) }
        routeTv = TextView(this).apply { textSize = 12f; setTextColor(0x99FFFFFF.toInt()); gravity = Gravity.CENTER }
        noteTv = TextView(this).apply { textSize = 13f; setTextColor(0xFFFFC107.toInt()); gravity = Gravity.CENTER; visibility = View.GONE }
        UiLang.keep(status); UiLang.keep(routeTv); UiLang.keep(noteTv)
        root.addView(status); root.addView(routeTv); root.addView(noteTv)

        // ऊपर: सामने वाला
        root.addView(header("सामने वाला (हिंदी में)"))
        topBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        topScroll = ScrollView(this).apply { addView(topBox); background = box(0xFF111A2E.toInt()) }
        UiLang.keep(topBox)
        root.addView(topScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // नीचे: मैं
        root.addView(header("मेरी बात (अनुवाद सहित)"))
        botBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        botScroll = ScrollView(this).apply { addView(botBox); background = box(0xFF13202A.toInt()) }
        UiLang.keep(botBox)
        root.addView(botScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // लिखकर भेजें
        val typeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val et = EditText(this).apply { hint = UiLang.s("हिंदी में लिखें…"); setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0x66FFFFFF); maxLines = 3 }
        typeRow.addView(et, LinearLayout.LayoutParams(0, -2, 1f))
        typeRow.addView(Button(this).apply { text = UiLang.s("भेजें"); isAllCaps = false
            setOnClickListener { val s = et.text.toString(); if (s.isNotBlank()) { et.setText(""); TrEngine.typed(s) } } })
        root.addView(typeRow)

        // माइक + ऑफ़लाइन मॉडल
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        micBtn = Button(this).apply { isAllCaps = false; setOnClickListener { TrEngine.setMic(!TrEngine.micOn); showMic() } }
        dlBtn = Button(this).apply { isAllCaps = false; setOnClickListener { TrOfflineMt.download(this@TranslatorActivity); showModel() } }
        UiLang.keep(micBtn); UiLang.keep(dlBtn)
        row.addView(micBtn, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(dlBtn, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)

        // बड़ा बटन: दबाकर हिंदी बोलें
        ptt = Button(this).apply {
            text = UiLang.s("दबाए रखें - हिंदी बोलें"); textSize = 20f; isAllCaps = false; setTextColor(0xFF000000.toInt())
            background = GradientDrawable().apply { cornerRadius = 28 * d; setColor(0xFF00E5FF.toInt()) }
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { v.alpha = 0.6f; TrEngine.pttDown() }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.alpha = 1f; TrEngine.pttUp() }
                }
                true
            }
        }
        root.addView(ptt, LinearLayout.LayoutParams(-1, px(96)).apply { topMargin = px(8) })
        setContentView(root)

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
        status.setTextColor(when (st) { TrEngine.St.LISTENING -> 0xFF4CAF50.toInt(); TrEngine.St.TRANSLATING -> 0xFFFFC107.toInt(); TrEngine.St.SPEAKING -> 0xFF00E5FF.toInt() })
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
            val item = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(10), px(6), px(10), px(6)) }
            val lang = TrLang.of(l.lang)?.hi ?: ""
            if (!mine) {
                // बड़ा: हिंदी अनुवाद; छोटा: असली बात
                if (l.tr.isNotBlank()) item.addView(tv(l.tr, 18f, 0xFFFFFFFF.toInt()))
                if (l.orig.isNotBlank()) item.addView(tv((if (lang.isNotEmpty()) "$lang: " else "") + l.orig, 13f, 0x99FFFFFF.toInt()))
            } else {
                if (l.orig.isNotBlank()) item.addView(tv("मैं: " + l.orig, 16f, 0xFFFFFFFF.toInt()))
                if (l.tr.isNotBlank()) item.addView(tv((if (lang.isNotEmpty()) "$lang: " else "") + l.tr, 17f, 0xFF00E5FF.toInt()))
                if (l.read.isNotBlank() && l.read != l.tr) item.addView(tv("(हिंदी में पढ़ें: ${l.read})", 13f, 0x99FFFFFF.toInt()))
            }
            if (l.note.isNotBlank()) item.addView(tv(l.note, 13f, 0xFFFFC107.toInt()))
            if (item.childCount > 0) box.addView(item)
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

    private fun header(t: String) = TextView(this).apply { text = UiLang.s(t); textSize = 13f; setTextColor(0xFF80DEEA.toInt()); setPadding(0, px(8), 0, px(4)) }

    private fun tv(t: String, size: Float, color: Int) = TextView(this).apply { text = t; textSize = size; setTextColor(color); setTextIsSelectable(true) }

    private fun box(color: Int) = GradientDrawable().apply { cornerRadius = 14 * d; setColor(color) }

    private fun px(v: Int) = (v * d).toInt()
}

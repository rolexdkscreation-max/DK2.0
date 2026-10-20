package com.dk.assistant

import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Likhkar baat karne wali alag screen (WhatsApp jaisi). Phone ke kaam bhi yahin se likhkar karwa sakte hain. */
class ChatActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var swSpeak: SwitchCompat
    private lateinit var edt: EditText
    private var shown = 0
    private val dp get() = resources.displayMetrics.density
    private val listener: () -> Unit = { render() }
    private var localBrain: BrainManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // likhne ki jagah hamesha khuli rahe (keyboard turant, bhejne ke baad bhi)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        val root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.bg)) }
        root.addView(AuroraView(this), FrameLayout.LayoutParams(-1, -1))
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (14 * dp).toInt(); setPadding(p, p, p, p)
        }
        root.addView(col, FrameLayout.LayoutParams(-1, -1))

        // ---- upar
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_back); setBackgroundResource(R.drawable.glass_circle)
            val p = (12 * dp).toInt(); setPadding(p, p, p, p); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams((46 * dp).toInt(), (46 * dp).toInt()))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding((12 * dp).toInt(), 0, 0, 0) }
        titles.addView(TextView(this).apply {
            text = "${Prefs.assistantName(this@ChatActivity)} चैट"; textSize = 20f
            setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt())
        })
        status = TextView(this).apply { textSize = 12f; setTextColor(0x99FFFFFF.toInt()) }
        titles.addView(status)
        top.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        swSpeak = SwitchCompat(this).apply {
            text = "बोलकर भी"; textSize = 12f; setTextColor(0xCCFFFFFF.toInt())
            isChecked = getSharedPreferences("dk_prefs", MODE_PRIVATE).getBoolean("chat_speak", false)
            setOnCheckedChangeListener { _, on -> getSharedPreferences("dk_prefs", MODE_PRIVATE).edit().putBoolean("chat_speak", on).apply() }
        }
        top.addView(swSpeak)
        col.addView(top)

        // ---- baatcheet
        scroll = ScrollView(this).apply { isFillViewport = true }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt()) }
        scroll.addView(list)
        col.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = (10 * dp).toInt() })

        // ---- neeche likhne ki bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.glass_pill)
            setPadding((18 * dp).toInt(), 0, (6 * dp).toInt(), 0)
        }
        edt = EditText(this).apply {
            hint = "कुछ भी लिखिए… (जैसे: WhatsApp खोलो, आज का मौसम?)"
            setHintTextColor(0x66FFFFFF); setTextColor(0xFFFFFFFF.toInt()); textSize = 15f
            background = null; maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        bar.addView(edt, LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = (6 * dp).toInt(); bottomMargin = (6 * dp).toInt() })
        val send = ImageButton(this).apply {
            setImageResource(R.drawable.ic_send); setBackgroundResource(R.drawable.btn_accent_circle)
            val p = (11 * dp).toInt(); setPadding(p, p, p, p); scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "भेजें"
        }
        bar.addView(send, LinearLayout.LayoutParams((46 * dp).toInt(), (46 * dp).toInt()))
        col.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() })

        val doSend = {
            val t = edt.text.toString().trim()
            if (t.isNotEmpty()) { edt.setText(""); sendText(t) }
            keepTyping()
        }
        send.setOnClickListener { doSend() }
        edt.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { doSend(); true } else false }
        setContentView(root)
        keepTyping()
    }

    /** Cursor + keyboard hamesha likhne ki jagah par - har message ke baad dobara tap na karna pade */
    private fun keepTyping() {
        edt.requestFocus()
        edt.post {
            edt.requestFocus()
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.showSoftInput(edt, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun sendText(t: String) {
        if (!Prefs.hasAnyKey(this)) { bubble("d", "पहले सेटिंग्स में किसी AI की API key डालकर सेव कीजिए।"); return }
        val svc = DKAccessibilityService.instance
        if (svc != null) { svc.runCommand(t, speak = swSpeak.isChecked); return }
        // Accessibility band hai -> sirf baatcheet (phone ke kaam nahi) - jawab tukdon me turant dikhe
        DKState.user(t)
        DKState.set(Mode.THINKING)
        val b = localBrain ?: BrainManager(this).also { localBrain = it }
        lifecycleScope.launch {
            try {
                LocalBrain.instant(this@ChatActivity, t)?.let { DKState.reply(it); return@launch }
                val sb = StringBuilder()
                fun clean(x: String) = x.replace(Regex("\\[(SCREEN|SEARCH)\\]"), "").substringBefore("[YAAD").replace(Regex("[*#`]"), "").trim()
                val full = b.streamChat("USER SAYS: $t", "USER SAYS: $t") { d ->
                    sb.append(d)
                    val c = clean(sb.toString())
                    if (c.isNotBlank() && !c.startsWith("{")) DKState.replyLive(c)
                }
                val c = clean(full)
                DKState.reply(when {
                    full.contains("[SCREEN]") || full.contains("need_screen") -> "फ़ोन पर काम करने के लिए पहले Accessibility में DK चालू करें।"
                    c.startsWith("{") -> try { org.json.JSONObject(c.substring(0, c.lastIndexOf('}') + 1)).optString("say") } catch (_: Exception) { c }.ifBlank { "…" }
                    else -> c.ifBlank { "…" }
                })
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                DKState.reply(b.explainAll(e))
            } finally { DKState.set(Mode.IDLE) }
        }
    }

    override fun onResume() {
        super.onResume()
        DKState.listen(listener)
        render()
        keepTyping()
    }

    override fun onPause() {
        DKState.unlisten(listener)
        super.onPause()
    }

    private fun render() {
        status.text = when (DKState.mode) {
            Mode.THINKING -> "जवाब आ रहा है…"
            Mode.WORKING -> "काम कर रहा है…"
            Mode.SPEAKING -> "Speaking • बोल रहा है…"
            Mode.LISTENING -> "Listening • सुन रहा है…"
            else -> if (DKAccessibilityService.instance != null) "ऑनलाइन • फ़ोन कंट्रोल चालू" else "सिर्फ़ बातचीत • फ़ोन कंट्रोल बंद"
        }
        val msgs = DKState.messages
        if (msgs.size < shown) { list.removeAllViews(); shown = 0 }
        if (shown == 0 && msgs.isEmpty()) {
            bubble("d", "नमस्ते${Prefs.userName(this).let { if (it.isNotBlank()) " $it" else "" }}! यहाँ लिखकर कुछ भी पूछिए या कोई काम बोलिए।", keep = false)
        }
        if (shown < msgs.size) { liveView?.let { list.removeView(it) }; liveView = null }
        while (shown < msgs.size) { val m = msgs[shown]; bubble(m.role, m.text); shown++ }
        // jawab ban raha hai -> wahi tukde turant dikhao (poora hone ka intezaar nahi)
        val live = DKState.liveReply
        if (live.isNotBlank()) {
            val v = liveView
            if (v == null) { bubble("d", live, keep = false); liveView = placeholder as? TextView; placeholder = null }
            else { v.text = live; scroll.post { scroll.smoothScrollTo(0, list.height) } }
        }
    }

    private var liveView: TextView? = null

    private var placeholder: View? = null

    private fun bubble(role: String, text: String, keep: Boolean = true) {
        placeholder?.let { list.removeView(it); placeholder = null }
        val mine = role == "u"
        val tv = TextView(this).apply {
            this.text = text; textSize = 15f; setTextColor(0xFFFFFFFF.toInt()); setTextIsSelectable(true)
            setBackgroundResource(if (mine) R.drawable.btn_accent else R.drawable.glass_card)
            val ph = (14 * dp).toInt(); val pv = (10 * dp).toInt(); setPadding(ph, pv, ph, pv)
            maxWidth = (resources.displayMetrics.widthPixels * 0.78).toInt()
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = if (mine) Gravity.END else Gravity.START
            topMargin = (8 * dp).toInt()
        }
        list.addView(tv, lp)
        if (!keep) placeholder = tv
        scroll.post { scroll.smoothScrollTo(0, list.height) }
    }
}

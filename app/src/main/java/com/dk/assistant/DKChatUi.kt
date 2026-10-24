package com.dk.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/*
 * साझा चैट हिस्से - DK चैट, कोडिंग एजेंट, DK कंप्यूटर: सब जगह एक जैसा.
 *  DKReplyBubble  = जवाब वाला बुलबुला: लिखते/सोचते समय अंदर DK की तरंग (+ हल्का स्थिति-संदेश), जवाब आते ही
 *                   तरंग स्मूद फीकी होकर उसी जगह जवाब का पाठ. "DK is typing" / नीचे अलग animation नहीं.
 *  DKInlineReply  = सूची के आख़िर में वही एक बुलबुला संभालता है (जवाब आने पर उसी को भरता है).
 *  DKComposer     = हल्के गोल कोनों वाला आयताकार इनपुट बॉक्स; माइक बार के अंदर ही (Google पॉप-अप नहीं).
 */

/** जवाब वाला बुलबुला. working() = तरंग + संदेश; showText() = तरंग फीकी, उसी बुलबुले में जवाब */
class DKReplyBubble(ctx: Context) : LinearLayout(ctx) {
    private val dp = resources.displayMetrics.density
    val wave = DKWorkingIndicator(ctx).apply { bare = true; state = WorkState.THINKING }
    private val status = TextView(ctx).apply {
        textSize = 13f; setTextColor(0x99FFFFFF.toInt()); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        visibility = GONE
    }
    private val head = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val body = TextView(ctx).apply {
        textSize = 15f; setTextColor(0xFFFFFFFF.toInt()); setTextIsSelectable(true); visibility = GONE
        UiLang.keep(this)
    }
    /** अभी तरंग दिख रही है (जवाब का पाठ नहीं आया) */
    var isWorking = true
        private set

    init {
        orientation = VERTICAL
        setBackgroundResource(R.drawable.glass_bubble_dk)
        val ph = (14 * dp).toInt(); val pv = (10 * dp).toInt(); setPadding(ph, pv, ph, pv)
        // छोटी, लिखावट की ऊँचाई के बराबर तरंग
        head.addView(wave, LayoutParams((22 * dp).toInt(), (18 * dp).toInt()))
        head.addView(status, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = (8 * dp).toInt() })
        addView(head, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(body, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        contentDescription = "जवाब लिखा जा रहा है"
    }

    fun setMaxTextWidth(px: Int) {
        body.maxWidth = px
        status.maxWidth = (px - 30 * dp).toInt().coerceAtLeast((80 * dp).toInt())
    }

    /** तरंग चलती रहे + बगल में हल्का संदेश ("" = सिर्फ़ तरंग) */
    fun working(label: String, st: WorkState = WorkState.THINKING) {
        if (!isWorking) return
        wave.state = st
        if (status.text.toString() != label) {
            status.text = label
            status.visibility = if (label.isEmpty()) GONE else VISIBLE
            contentDescription = if (label.isEmpty()) "जवाब लिखा जा रहा है" else label
        }
    }

    /** जवाब का पाठ: पहली बार = तरंग+संदेश स्मूद फीके, फिर उसी जगह पाठ धीरे से उभरे. बाद में = सिर्फ़ पाठ बदले */
    fun showText(t: CharSequence, color: Int = 0xFFFFFFFF.toInt()) {
        body.setTextColor(color)
        body.text = t
        if (!isWorking) return
        isWorking = false
        contentDescription = null
        wave.state = WorkState.IDLE
        head.animate().cancel()
        head.animate().alpha(0f).setDuration(BubbleFade.OUT_MS).withEndAction {
            head.visibility = GONE
            body.alpha = 0f; body.visibility = VISIBLE
            body.animate().alpha(1f).setDuration(BubbleFade.IN_MS).start()
        }.start()
    }

    /** जवाब नहीं आया (काम खत्म/रुका): पूरा बुलबुला स्मूद फीका होकर हटे */
    fun fadeAway(done: () -> Unit) {
        wave.state = WorkState.IDLE
        animate().cancel()
        animate().alpha(0f).setDuration(BubbleFade.OUT_MS).withEndAction(done).start()
    }
}

/** सूची के सबसे नीचे एक ही \"लिख रहा है\" बुलबुला. जवाब आए तो उसी को भरो (नया बुलबुला नहीं) */
class DKInlineReply(private val list: LinearLayout, private val scroll: ScrollView?, private val widthFrac: Float = 0.82f) {
    private val ctx = list.context
    private val dp = ctx.resources.displayMetrics.density
    var bubble: DKReplyBubble? = null
        private set
    val active get() = bubble != null

    private fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        gravity = Gravity.START; topMargin = (8 * dp).toInt()
    }

    /** तरंग वाला बुलबुला दिखाओ/ताज़ा करो (हमेशा सूची के आख़िर में) */
    fun show(label: String, st: WorkState = WorkState.THINKING) {
        val b = bubble ?: DKReplyBubble(ctx).also {
            it.setMaxTextWidth((ctx.resources.displayMetrics.widthPixels * widthFrac).toInt())
            bubble = it
            it.alpha = 0f
            list.addView(it, lp())
            it.animate().alpha(1f).setDuration(160).start()
            scrollDown()
        }
        keepLast()
        b.working(label, st)
    }

    /** जवाब बनते-बनते (टुकड़े): उसी बुलबुले में पाठ; बुलबुला \"चालू\" रहता है ताकि पूरा जवाब भी इसी में आए */
    fun live(t: CharSequence) {
        val b = bubble ?: run { show(""); bubble!! }
        b.showText(t); keepLast(); scrollDown()
    }

    /** पक्का जवाब: इसी बुलबुले में (अब यह आम संदेश बन गया). null = बुलबुला था ही नहीं */
    fun answer(t: CharSequence, color: Int = 0xFFFFFFFF.toInt()): DKReplyBubble? {
        val b = bubble ?: return null
        bubble = null
        b.showText(t, color); scrollDown()
        return b
    }

    /** काम खत्म और कोई जवाब नहीं - स्मूद हटाओ */
    fun hide() {
        val b = bubble ?: return
        bubble = null
        b.fadeAway { list.removeView(b) }
    }

    /** बिना animation के हटाओ (सूची दोबारा बन रही है) */
    fun drop() { bubble?.let { list.removeView(it) }; bubble = null }

    /** कोई दूसरा view जुड़ा - बुलबुला फिर सबसे नीचे */
    fun keepLast() {
        val b = bubble ?: return
        if (list.indexOfChild(b) != list.childCount - 1) { list.removeView(b); list.addView(b, lp()) }
    }

    private fun scrollDown() { scroll?.let { s -> s.post { s.smoothScrollTo(0, list.height) } } }
}

/** माइक की तरंग: इनपुट बार के अंदर पतली सफ़ेद खड़ी रेखाएँ, आवाज़ के साथ घटती-बढ़ती (दाएँ से नई) */
class MicWaveView(ctx: Context) : View(ctx) {
    private val dp = resources.displayMetrics.density
    private val n = 36
    private val levels = FloatArray(n)
    private var headIdx = 0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); strokeCap = Paint.Cap.ROUND }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun push(level: Float) {
        // हल्की चिकनाई: अचानक उछाल कम
        val prev = levels[(headIdx + n - 1) % n]
        levels[headIdx] = (prev * 0.35f + level.coerceIn(0f, 1f) * 0.65f)
        headIdx = (headIdx + 1) % n
        invalidate()
    }

    fun reset() { levels.fill(0f); headIdx = 0; invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val step = w / n
        p.strokeWidth = (step * 0.38f).coerceIn(1.5f * dp, 3f * dp)
        val cy = h / 2f
        val minH = 3f * dp
        for (i in 0 until n) {
            val v = levels[(headIdx + i) % n]
            val bh = (minH + v * (h * 0.92f - minH)).coerceAtMost(h)
            val x = step * (i + 0.5f)
            p.alpha = (140 + 115 * v).toInt().coerceIn(0, 255)
            c.drawLine(x, cy - bh / 2f, x, cy + bh / 2f, p)
        }
    }
}

/** बोलकर लिखते समय DK का अपना सुनना (wake word / Live) माइक छोड़ दे - दोनों एक साथ माइक नहीं लेते */
object DKMic {
    @Volatile var dictating = false
        private set

    fun begin() {
        if (dictating) return
        dictating = true
        DKLog.d("बोलकर लिखना शुरू - DK का अपना माइक रुका")
        try { DKAccessibilityService.instance?.onDictation(true) } catch (_: Exception) {}
    }

    fun end() {
        if (!dictating) return
        dictating = false
        DKLog.d("बोलकर लिखना खत्म - DK का माइक वापस")
        try { DKAccessibilityService.instance?.onDictation(false) } catch (_: Exception) {}
    }
}

/**
 * ऐप के अंदर ही आवाज़-पहचान (SpeechRecognizer) - Google का \"बोलिए…\" पॉप-अप कभी नहीं.
 * हिंदी (भारत) + अंग्रेज़ी शब्द. बोलते समय शब्द-दर-शब्द onText, आवाज़ का स्तर onLevel.
 * चुप्पी पर अपने-आप फिर सुनता है (stop/send/X दबाने तक). onEnd(संदेश) = अपने-आप रुका (null = ठीक).
 */
class InlineDictation(
    private val ctx: Context,
    private val onText: (String) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onEnd: (String?) -> Unit
) {
    private var sr: SpeechRecognizer? = null
    private var active = false
    private var committed = ""
    private var partial = ""
    private var silent = 0
    private var busy = 0
    private val h = Handler(Looper.getMainLooper())
    private val listenNow = Runnable { listen() }

    val text: String get() = DictationRules.join(committed, partial)

    fun start() {
        if (active) return
        active = true
        DKMic.begin()
        // DK का अपना माइक छूटने दो, फिर सुनो
        h.postDelayed(listenNow, 250)
    }

    private fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, DictationRules.LANG)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, DictationRules.LANG)
        // हिंदी के बीच अंग्रेज़ी शब्द भी (Hinglish)
        putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf(DictationRules.EXTRA_LANG))
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        // लिखवाते समय सोचने के ठहराव पर तुरंत न रुके
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { if (active) onLevel(DictationRules.level(rmsdB)) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { if (active) onLevel(0f) }
        override fun onPartialResults(b: Bundle?) {
            if (!active) return
            val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (t.isBlank()) return
            partial = t; silent = 0
            onText(text)
        }
        override fun onResults(b: Bundle?) {
            if (!active) return
            val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            val heard = t.ifBlank { partial }
            if (heard.isBlank()) { onError(DictationRules.ERR_NO_MATCH); return }
            committed = DictationRules.join(committed, heard); partial = ""
            silent = 0; busy = 0
            onText(text)
            // आप stop/भेजें दबाएँ तब तक आगे भी सुनो
            h.postDelayed(listenNow, 120)
        }
        override fun onError(error: Int) {
            if (!active) return
            if (partial.isNotBlank()) { committed = DictationRules.join(committed, partial); partial = "" }
            when (DictationRules.next(error, silent, busy, text.isNotBlank())) {
                DictationRules.Next.RESTART -> { silent++; h.postDelayed(listenNow, 150) }
                DictationRules.Next.RETRY_SOON -> {
                    busy++
                    try { sr?.destroy() } catch (_: Exception) {}
                    sr = null
                    h.postDelayed(listenNow, 400)
                }
                DictationRules.Next.STOP_KEEP -> finish(null)
                DictationRules.Next.STOP_MESSAGE -> {
                    DKLog.d("बोलकर लिखना रुका: गलती $error")
                    finish(DictationRules.message(error))
                }
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun listen() {
        if (!active) return
        try {
            val r = sr ?: SpeechRecognizer.createSpeechRecognizer(ctx).also { sr = it; it.setRecognitionListener(listener) }
            r.startListening(intent())
        } catch (e: Exception) {
            DKLog.d("बोलकर लिखना शुरू नहीं हुआ: ${e.message}")
            finish(DictationRules.message(DictationRules.ERR_CLIENT))
        }
    }

    /** रोको, अब तक का लिखा रखो */
    fun stop(): String { val t = text; active = false; release(); return t }

    /** रद्द */
    fun cancel() { active = false; release() }

    private fun finish(msg: String?) { active = false; release(); onEnd(msg) }

    private fun release() {
        h.removeCallbacks(listenNow)
        val r = sr; sr = null
        try { r?.cancel() } catch (_: Exception) {}
        try { r?.destroy() } catch (_: Exception) {}
        onLevel(0f)
        DKMic.end()
    }
}

/**
 * इनपुट बॉक्स (हर स्क्रीन पर एक जैसा): हल्के गोल कोनों वाला आयत.
 *   ऊपर: लिखने की जगह (बोलते समय यहीं शब्द-दर-शब्द)
 *   नीचे: [संलग्न] ………… [माइक] [भेजें]
 *   माइक मोड: [X रद्द] [सफ़ेद तरंग] [स्टॉप] [भेजें]
 * onCreate में बनाइए (माइक की इजाज़त वाला launcher तभी जुड़ता है).
 */
class DKComposer(
    private val act: AppCompatActivity,
    hintText: String,
    maxLines: Int = 5,
    attach: (() -> Unit)? = null
) : LinearLayout(act) {
    private val dp = resources.displayMetrics.density
    val edt: EditText
    val send: ImageButton
    /** भेजें दबाया (या माइक मोड का तीर / keyboard का Send) */
    var onSend: () -> Unit = {}
    private val normalRow: LinearLayout
    private val micRow: LinearLayout
    private val wave = MicWaveView(act)
    private val note: TextView
    private var dict: InlineDictation? = null
    private var before = ""
    private val prefs = act.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE)
    private val askMic = act.registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        prefs.edit().putBoolean("dictation_mic_asked", true).apply()
        if (ok) startMic() else showNote(DictationRules.NO_PERMISSION)
    }
    private val hideNote = Runnable { hideNoteNow() }
    private fun hideNoteNow() { note.visibility = GONE }

    val micActive get() = dict != null

    init {
        orientation = VERTICAL
        setBackgroundResource(R.drawable.glass_composer)
        setPadding((12 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())

        edt = EditText(act).apply {
            hint = hintText
            setHintTextColor(0x66FFFFFF); setTextColor(0xFFFFFFFF.toInt()); textSize = 15f
            background = null; this.maxLines = maxLines; minLines = 1
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setPadding((4 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
        }
        addView(edt, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        note = TextView(act).apply {
            textSize = 12f; setTextColor(0xFFFFC9C9.toInt()); visibility = GONE
            setPadding((4 * dp).toInt(), 0, (4 * dp).toInt(), (4 * dp).toInt())
        }
        addView(note, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ---- सामान्य पंक्ति
        normalRow = LinearLayout(act).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (attach != null) normalRow.addView(iconBtn(R.drawable.ic_attach, "फ़ोटो या वीडियो जोड़ें", R.drawable.glass_circle, 10) { attach() },
            LayoutParams(px(ComposerShape.BUTTON_DP), px(ComposerShape.BUTTON_DP)))
        normalRow.addView(View(act), LayoutParams(0, 1, 1f))
        normalRow.addView(iconBtn(R.drawable.ic_mic, "बोलकर लिखें", R.drawable.glass_circle, 10) { micTap() },
            LayoutParams(px(ComposerShape.BUTTON_DP), px(ComposerShape.BUTTON_DP)).apply { marginEnd = (8 * dp).toInt() })
        send = iconBtn(R.drawable.ic_send, "भेजें", R.drawable.btn_accent_circle, 10) { onSend() }
        normalRow.addView(send, LayoutParams(px(ComposerShape.SEND_DP), px(ComposerShape.SEND_DP)))
        addView(normalRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // ---- माइक पंक्ति: बाएँ X, बीच तरंग, दाएँ स्टॉप + भेजें
        micRow = LinearLayout(act).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = GONE }
        micRow.addView(iconBtn(R.drawable.ic_close, "रद्द करें", R.drawable.glass_circle, 10) { cancelMic() },
            LayoutParams(px(ComposerShape.BUTTON_DP), px(ComposerShape.BUTTON_DP)))
        micRow.addView(wave, LayoutParams(0, (30 * dp).toInt(), 1f).apply {
            marginStart = (10 * dp).toInt(); marginEnd = (10 * dp).toInt()
        })
        micRow.addView(iconBtn(R.drawable.ic_stop, "रोकें", R.drawable.glass_circle, 11) { stopMic() },
            LayoutParams(px(ComposerShape.BUTTON_DP), px(ComposerShape.BUTTON_DP)).apply { marginEnd = (8 * dp).toInt() })
        micRow.addView(iconBtn(R.drawable.ic_send, "भेजें", R.drawable.btn_accent_circle, 10) { stopMic(); onSend() },
            LayoutParams(px(ComposerShape.SEND_DP), px(ComposerShape.SEND_DP)))
        addView(micRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        edt.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { if (micActive) stopMic(); onSend(); true } else false }
        // स्क्रीन पीछे गई = माइक बंद (लिखा हुआ रहता है)
        act.lifecycle.addObserver(LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE && micActive) stopMic()
            if (e == Lifecycle.Event.ON_DESTROY) dict?.cancel()
        })
    }

    private fun px(v: Int) = (v * dp).toInt()

    private fun iconBtn(icon: Int, desc: String, bg: Int, padDp: Int, click: () -> Unit) = ImageButton(act).apply {
        setImageResource(icon); setBackgroundResource(bg)
        val p = (padDp * dp).toInt(); setPadding(p, p, p, p); scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = desc
        setOnClickListener { click() }
    }

    private fun showNote(t: String) {
        note.text = t; note.visibility = VISIBLE
        removeCallbacks(hideNote); postDelayed(hideNote, 9000)
    }

    private fun micTap() {
        if (micActive) return
        if (!SpeechRecognizer.isRecognitionAvailable(act)) { showNote(DictationRules.NO_SERVICE); return }
        if (!Setup.hasMic(act)) {
            // इजाज़त एक ही बार पूछो; हमेशा के लिए मना हो चुकी हो तो साफ़ बताओ
            val asked = prefs.getBoolean("dictation_mic_asked", false)
            if (asked && !act.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) showNote(DictationRules.NO_PERMISSION)
            else try { askMic.launch(Manifest.permission.RECORD_AUDIO) } catch (_: Exception) { showNote(DictationRules.NO_PERMISSION) }
            return
        }
        startMic()
    }

    private fun startMic() {
        if (micActive) return
        removeCallbacks(hideNote); note.visibility = GONE
        before = edt.text.toString().trim()
        val d = InlineDictation(act,
            onText = { t -> setLive(DictationRules.join(before, t)) },
            onLevel = { wave.push(it) },
            onEnd = { msg -> dict = null; setMicMode(false); if (msg != null) showNote(msg) })
        dict = d
        setMicMode(true)
        d.start()
    }

    private fun setLive(t: String) {
        edt.setText(t)
        edt.setSelection(edt.text.length)
    }

    /** रोको - बोला हुआ लिखने की जगह में रहता है (सुधार कर भेज सकते हैं) */
    fun stopMic() {
        val d = dict ?: return
        dict = null
        val t = d.stop()
        setLive(DictationRules.join(before, t))
        setMicMode(false)
    }

    /** X: बोला हुआ हटाओ, पहले जैसा */
    fun cancelMic() {
        val d = dict ?: return
        dict = null
        d.cancel()
        setLive(before)
        setMicMode(false)
    }

    private fun setMicMode(on: Boolean) {
        normalRow.visibility = if (on) GONE else VISIBLE
        micRow.visibility = if (on) VISIBLE else GONE
        wave.reset()
        if (on) act.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(edt.windowToken, 0)
    }
}

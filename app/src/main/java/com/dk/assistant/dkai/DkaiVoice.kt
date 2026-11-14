package com.dk.assistant.dkai

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.dk.assistant.*
import com.dk.assistant.dkai.DkaiTheme.dp
import com.dk.assistant.voice.engine.VoiceEngines
import com.dk.assistant.voice.engine.VoiceLevel
import com.dk.assistant.voice.engine.VoiceTune

/**
 * State 2 (पूरी स्क्रीन वाला आवाज़ orb) + State 3 (छोटी तैरती पट्टी) - दोनों एक ही Live बातचीत दिखाते हैं.
 */
class DkaiVoice(private val s: DkaiScreen) {
    private val c = s.activity
    private fun d(v: Float) = dp(c, v)

    val sheet = FrameLayout(c)
    val mini = LinearLayout(c)

    private lateinit var col: LinearLayout
    private lateinit var voicePill: TextView
    private lateinit var speedPill: TextView
    private lateinit var badge: LinearLayout
    private lateinit var badgeDot: PulseDot
    private lateinit var badgeTxt: TextView
    private lateinit var orb: FluidOrbView
    private lateinit var classicOrb: OrbView
    private lateinit var orbBox: FrameLayout
    private lateinit var transcript: TextView
    private lateinit var box: LinearLayout
    private lateinit var boxTitle: TextView
    private lateinit var boxDot: PulseDot
    private lateinit var miniWave: WaveformView
    private lateinit var wave: WaveformView
    private lateinit var tabs: LinearLayout
    private lateinit var bigMic: RippleMicView
    private lateinit var menuLayer: FrameLayout

    private lateinit var miniOrb: FluidOrbView
    private lateinit var miniDot: PulseDot
    private lateinit var miniStatus: TextView
    private lateinit var miniText: TextView
    private lateinit var miniMic: ImageView

    var voiceTab = DkaiMode.CHAT; private set
    /** 13 नवंबर 2026 (मालिक - वॉइस-कॉल): अभी की तीन में से एक हालत (orb / वेव हर फ़्रेम यही पढ़ते हैं) */
    private var callNow = DkaiCore.Call.LISTENING
    private val callInt: () -> Int = {
        when (callNow) { DkaiCore.Call.SPEAKING -> FluidOrbView.SPEAK; DkaiCore.Call.PROCESSING -> FluidOrbView.THINK; else -> FluidOrbView.LISTEN }
    }
    private val speaking: () -> Boolean = { callNow == DkaiCore.Call.SPEAKING }
    private var sessionStart = 0L
    private var closedAt = 0L
    private var pendingNote: String? = null

    /** अभी कितना ज़ोर (बोल रहा = DK की आवाज़, सुन रहा = माइक) */
    private val level: () -> Float = {
        when {
            DKState.mode == Mode.SPEAKING -> VoiceLevel.mouth() ?: 0.25f
            DKState.live && !DKState.micMuted -> DKState.level
            else -> 0f
        }
    }
    private val active: () -> Boolean = { DKState.live && (DKState.mode == Mode.SPEAKING || !DKState.micMuted) }

    init { buildSheet(); buildMini() }

    // ================================================================== State 2
    private fun buildSheet() {
        sheet.visibility = View.GONE
        sheet.isClickable = true; sheet.isFocusable = true           // नीचे की स्क्रीन पर छूना न जाए
        sheet.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(DkaiTheme.BG, if (DkaiTheme.light) 0xFFE6ECFA.toInt() else 0xFF101634.toInt(), DkaiTheme.BG))
        col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        sheet.addView(col, FrameLayout.LayoutParams(-1, -1))

        // ऊपर: आवाज़ | बैज | गति
        val top = FrameLayout(c).apply { setPadding(d(16f), d(12f), d(16f), d(8f)) }
        voicePill = DkaiTheme.pill(c, "Emma", DkaiTheme.PILL, DkaiTheme.WHITE, 14f).apply {
            minHeight = d(40f); compoundDrawablePadding = d(6f); contentDescription = "आवाज़ चुनें"
            setOnClickListener { voiceSheet() }
            setOnLongClickListener { voiceMenu(it); true }
        }
        top.addView(voicePill, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
        badge = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = DkaiTheme.round(c, DkaiTheme.MINT, 999f); setPadding(d(10f), d(6f), d(12f), d(6f)); elevation = d(6f).toFloat()
        }
        badgeDot = PulseDot(c).apply { color = 0xFF059669.toInt() }
        badge.addView(badgeDot, LinearLayout.LayoutParams(d(12f), d(12f)))
        badgeTxt = DkaiTheme.text(c, "LISTENING", 12f, DkaiTheme.MINT_TEXT, DkaiTheme.SANS_BOLD).apply { letterSpacing = 0.12f; setPadding(d(6f), 0, 0, 0) }
        badge.addView(badgeTxt)
        top.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        speedPill = DkaiTheme.pill(c, "1.0x", DkaiTheme.PILL, DkaiTheme.WHITE, 14f).apply {
            minHeight = d(40f); compoundDrawablePadding = d(4f); contentDescription = "बोलने की गति"
            setOnClickListener { voiceSheet() }
            setOnLongClickListener { speedMenu(it); true }
        }
        top.addView(speedPill, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
        col.addView(top, LinearLayout.LayoutParams(-1, -2))

        col.addView(View(c), LinearLayout.LayoutParams(1, 0, 1f))
        // 13 नवंबर 2026 (वॉइस-कॉल): फ़ोन और टैबलेट दोनों - orb चौड़ाई और ऊँचाई दोनों से नपे (छोटे फ़ोन पर नीचे के बटन न कटें)
        val dm = c.resources.displayMetrics
        val size = minOf((dm.widthPixels * 0.72f).toInt(), (dm.heightPixels * 0.34f).toInt(), d(320f))
        val boxW = minOf(dm.widthPixels - d(40f), d(560f))
        // 13 नवंबर 2026 (मालिक): orb बदल सकें - orb पर टैप = सूची (5+ नए orb या पुराना DK orb)
        orb = FluidOrbView(c).apply { level = this@DkaiVoice.level; active = this@DkaiVoice.active; style = { DkaiOrbs.get(c) }; call = callInt }
        classicOrb = OrbView(c).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        orbBox = FrameLayout(c).apply {
            isClickable = true; contentDescription = "DK orb - बदलने के लिए टैप"
            addView(orb, FrameLayout.LayoutParams(-1, -1)); addView(classicOrb, FrameLayout.LayoutParams(-1, -1))
            setOnClickListener { DkaiOrbs.pick(c) { orbStyle() } }
        }
        orbStyle()
        col.addView(orbBox, LinearLayout.LayoutParams(size, size))
        // साउंड-वेव: बोलते समय खड़ी नीली पट्टियाँ, सुनते / चुप में छोटे बिंदु
        wave = WaveformView(c).apply { level = this@DkaiVoice.level; active = this@DkaiVoice.active; bars = speaking; count = 32 }
        col.addView(wave, LinearLayout.LayoutParams(minOf((dm.widthPixels * 0.7f).toInt(), d(420f)), d(44f)).apply { topMargin = d(6f) })
        // 13 नवंबर 2026 (मालिक): सफ़ेद, गोल कोनों वाला टेक्स्ट बॉक्स - ऊपर हालत ("Listening…"), नीचे जो अभी बोल रहा उसकी बात
        box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = DkaiTheme.press(DkaiTheme.round(c, 0xFFFFFFFF.toInt(), 22f, if (DkaiTheme.light) 0xFFE2E8F0.toInt() else 0), DkaiTheme.round(c, 0xFFF1F5F9.toInt(), 22f))
            elevation = d(6f).toFloat(); setPadding(d(18f), d(12f), d(18f), d(14f))
            isClickable = true; isFocusable = true; contentDescription = "बातचीत - टैप करें"
            setOnClickListener { boxTap() }
        }
        val head = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        boxDot = PulseDot(c).apply { color = 0xFF2563EB.toInt() }
        head.addView(boxDot, LinearLayout.LayoutParams(d(10f), d(10f)))
        boxTitle = DkaiTheme.text(c, DkaiCore.Call.LISTENING.title, 13f, 0xFF2563EB.toInt(), DkaiTheme.SANS_BOLD).apply { setPadding(d(6f), 0, 0, 0) }
        head.addView(boxTitle)
        box.addView(head, LinearLayout.LayoutParams(-2, -2))
        transcript = DkaiTheme.text(c, DkaiCore.Call.LISTENING.hint, 17f, 0xFF0F172A.toInt(), DkaiTheme.SANS_MED).apply {
            gravity = Gravity.CENTER; maxLines = 4; setLineSpacing(0f, 1.2f); setPadding(0, d(6f), 0, 0)
            ellipsize = android.text.TextUtils.TruncateAt.START
        }
        UiLang.keep(transcript)
        box.addView(transcript, LinearLayout.LayoutParams(-1, -2))
        col.addView(box, LinearLayout.LayoutParams(boxW, -2).apply { topMargin = d(10f) })
        col.addView(View(c), LinearLayout.LayoutParams(1, 0, 1f))

        // मोड टैब
        tabs = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(d(8f), 0, d(8f), d(10f)) }
        for (m in DkaiMode.VOICE_TABS) tabs.addView(tabView(m), LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(tabs, LinearLayout.LayoutParams(-1, -2))

        // नीचे: (v) | माइक | (X)
        val bottom = FrameLayout(c).apply { setPadding(d(28f), d(2f), d(28f), d(16f)) }
        bottom.addView(roundBtn(DkaiIcon.Kind.DOWN, DkaiTheme.WHITE, DkaiTheme.PILL, "छोटा करें") { s.event(VoiceEvent.MINIMIZE) },
            FrameLayout.LayoutParams(d(56f), d(56f), Gravity.START or Gravity.CENTER_VERTICAL))
        bigMic = RippleMicView(c, 84f).apply {
            level = this@DkaiVoice.level; active = this@DkaiVoice.active; contentDescription = "माइक"
            setOnClickListener { micToggle() }
        }
        bottom.addView(bigMic, FrameLayout.LayoutParams(d(136f), d(136f), Gravity.CENTER))
        // 13 नवंबर 2026 (मालिक): दाएँ लाल कॉल-कट बटन
        bottom.addView(roundBtn(DkaiIcon.Kind.CALL_END, DkaiTheme.WHITE, DkaiTheme.RED, "कॉल काटें - बातचीत बंद", DkaiTheme.RED) { close() },
            FrameLayout.LayoutParams(d(56f), d(56f), Gravity.END or Gravity.CENTER_VERTICAL))
        col.addView(bottom, LinearLayout.LayoutParams(-1, -2))

        menuLayer = FrameLayout(c).apply { visibility = View.GONE; isClickable = true; setBackgroundColor(0x66000000); setOnClickListener { closeMenu() } }
        sheet.addView(menuLayer, FrameLayout.LayoutParams(-1, -1))
    }

    private fun tabView(m: DkaiMode): View = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; minimumHeight = d(44f); tag = m
        isClickable = true; isFocusable = true; contentDescription = m.label
        background = DkaiTheme.press(DkaiTheme.round(c, 0, 12f), DkaiTheme.round(c, 0xFFFFFFFF.toInt(), 12f))
        setPadding(d(2f), d(6f), d(2f), d(6f))
        addView(DkaiTheme.text(c, m.label, 13f, DkaiTheme.GRAY, DkaiTheme.SANS_MED).apply { maxLines = 1; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-2, -2))
        // 13 नवंबर 2026 (मालिक): चुना हुआ मोड आसमानी रंग में चमके (नीचे चमकती लकीर + हल्की आसमानी पृष्ठभूमि)
        addView(View(c).apply { background = DkaiTheme.round(c, CYAN_ON, 2f); elevation = d(3f).toFloat() }, LinearLayout.LayoutParams(d(26f), d(3f)).apply { topMargin = d(5f) })
        setOnClickListener { pickTab(m) }
        // 14 नवंबर 2026: Broadcast टैब अब Broadcast का काम है; लगातार स्क्रीन शेयर = उसी टैब को देर तक दबाएँ ("स्क्रीन देखो" बोलने पर DK एक बार तुरंत देख लेता है)
        if (m == DkaiMode.BROADCAST) setOnLongClickListener { toggleScreenShare(); true }
    }

    private val CYAN_ON get() = if (DkaiTheme.light) 0xFF0284C7.toInt() else 0xFF22D3EE.toInt()
    private fun tabOn() = DkaiTheme.round(c, if (DkaiTheme.light) 0x1F0284C7 else 0x2622D3EE, 12f, if (DkaiTheme.light) 0x660284C7 else 0x8822D3EE.toInt())

    private fun roundBtn(k: DkaiIcon.Kind, tint: Int, fill: Int, desc: String, stroke: Int = DkaiTheme.BORDER, onTap: () -> Unit) = FrameLayout(c).apply {
        background = DkaiTheme.press(DkaiTheme.oval(fill, stroke, d(1f)), DkaiTheme.oval(0xFFFFFFFF.toInt()))
        isClickable = true; isFocusable = true; contentDescription = desc
        addView(ImageView(c).apply { setImageDrawable(DkaiIcon(k, tint)) }, FrameLayout.LayoutParams(d(22f), d(22f), Gravity.CENTER))
        setOnClickListener { onTap() }
    }

    fun applyInsets(top: Int, bottom: Int) { col.setPadding(0, top, 0, bottom) }

    // ================================================================== State 3
    private fun buildMini() {
        mini.visibility = View.GONE
        mini.orientation = LinearLayout.HORIZONTAL; mini.gravity = Gravity.CENTER_VERTICAL
        mini.background = DkaiTheme.round(c, DkaiTheme.CARD, 999f, DkaiTheme.BORDER)       // तैरती गोली
        mini.elevation = d(12f).toFloat()
        mini.setPadding(d(8f), d(6f), d(8f), d(6f))
        mini.isClickable = true
        mini.setOnClickListener { s.event(VoiceEvent.EXPAND) }
        mini.contentDescription = "DK बातचीत - बड़ा करने के लिए टैप"
        miniOrb = FluidOrbView(c).apply { glow = false; level = this@DkaiVoice.level; active = this@DkaiVoice.active; call = callInt
            style = { DkaiOrbs.get(c).let { if (it == DkaiOrbs.CLASSIC) DkaiOrbs.FLUID else it } } }
        mini.addView(miniOrb, LinearLayout.LayoutParams(d(46f), d(46f)))
        val mid = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(d(10f), 0, d(6f), 0) }
        val st = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        miniDot = PulseDot(c).apply { color = DkaiTheme.GREEN }
        st.addView(miniDot, LinearLayout.LayoutParams(d(10f), d(10f)))
        miniStatus = DkaiTheme.text(c, "LISTENING...", 11f, DkaiTheme.GREEN, DkaiTheme.SANS_BOLD).apply { letterSpacing = 0.1f; setPadding(d(5f), 0, 0, 0) }
        st.addView(miniStatus)
        // 13 नवंबर 2026 (मालिक): छोटी पट्टी में भी छोटी वेव
        miniWave = WaveformView(c).apply { level = this@DkaiVoice.level; active = this@DkaiVoice.active; bars = speaking; count = 9 }
        st.addView(miniWave, LinearLayout.LayoutParams(d(40f), d(16f)).apply { leftMargin = d(6f) })
        mid.addView(st)
        miniText = DkaiTheme.text(c, DkaiCore.preview(DkaiCore.HELLO), 13f, DkaiTheme.WHITE).apply { maxLines = 1; setPadding(0, d(4f), 0, 0) }
        UiLang.keep(miniText)
        mid.addView(miniText)
        mini.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
        miniMic = ImageView(c).apply { setImageDrawable(DkaiIcon(DkaiIcon.Kind.MIC, DkaiTheme.WHITE)) }
        mini.addView(miniBtn(miniMic, DkaiTheme.PILL, "माइक म्यूट / चालू") { micToggle() })
        mini.addView(miniBtn(ImageView(c).apply { setImageDrawable(DkaiIcon(DkaiIcon.Kind.UP, DkaiTheme.WHITE)) }, DkaiTheme.PILL, "बड़ा करें") { s.event(VoiceEvent.EXPAND) })
        mini.addView(miniBtn(ImageView(c).apply { setImageDrawable(DkaiIcon(DkaiIcon.Kind.CALL_END, DkaiTheme.WHITE)) }, DkaiTheme.RED, "कॉल काटें - बातचीत बंद") { close() })
    }

    private fun miniBtn(icon: ImageView, fill: Int, desc: String, onTap: () -> Unit): View = FrameLayout(c).apply {
        background = DkaiTheme.press(DkaiTheme.oval(fill), DkaiTheme.oval(0xFFFFFFFF.toInt()))
        isClickable = true; isFocusable = true; contentDescription = desc
        addView(icon, FrameLayout.LayoutParams(d(18f), d(18f), Gravity.CENTER))
        setOnClickListener { onTap() }
        layoutParams = LinearLayout.LayoutParams(d(44f), d(44f)).apply { leftMargin = d(4f) }
    }

    /** टैबलेट पर पूरी चौड़ाई की जगह बीच में (560dp तक) */
    fun miniParams() = FrameLayout.LayoutParams(minOf(c.resources.displayMetrics.widthPixels - d(24f), d(560f)), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
        leftMargin = d(12f); rightMargin = d(12f); bottomMargin = d(140f)
    }

    // ================================================================== animation
    fun transition(from: VoiceSessionState, to: VoiceSessionState, animate: Boolean) {
        closeMenu()
        val h = (s.root.height.takeIf { it > 0 } ?: c.resources.displayMetrics.heightPixels).toFloat()
        val dur = if (animate) 320L else 0L
        when (to) {
            VoiceSessionState.ACTIVE_FULL -> {
                sheet.animate().cancel(); sheet.visibility = View.VISIBLE
                if (animate) { sheet.translationY = h; sheet.alpha = 0.6f }
                sheet.animate().translationY(0f).alpha(1f).setDuration(dur).setInterpolator(DecelerateInterpolator(2f)).start()
                if (mini.visibility == View.VISIBLE) mini.animate().setStartDelay(0).alpha(0f).translationY(d(24f).toFloat()).setDuration(dur / 2).withEndAction { mini.visibility = View.GONE }.start()
            }
            VoiceSessionState.ACTIVE_MINIMIZED -> {
                if (sheet.visibility == View.VISIBLE)
                    sheet.animate().translationY(h).alpha(0.6f).setDuration(dur).setInterpolator(AccelerateInterpolator(1.5f)).withEndAction { sheet.visibility = View.GONE }.start()
                mini.animate().cancel(); mini.visibility = View.VISIBLE
                if (animate) { mini.alpha = 0f; mini.translationY = d(40f).toFloat() }
                mini.animate().alpha(1f).translationY(0f).setStartDelay(if (from == VoiceSessionState.ACTIVE_FULL) dur / 2 else 0).setDuration(dur).setInterpolator(DecelerateInterpolator()).withEndAction { mini.animate().setStartDelay(0) }.start()
            }
            VoiceSessionState.INACTIVE -> {
                closedAt = System.currentTimeMillis()
                if (sheet.visibility == View.VISIBLE)
                    sheet.animate().translationY(h).alpha(0f).setDuration(dur).setInterpolator(AccelerateInterpolator(1.5f)).withEndAction { sheet.visibility = View.GONE }.start()
                if (mini.visibility == View.VISIBLE)
                    mini.animate().setStartDelay(0).alpha(0f).translationY(d(40f).toFloat()).setDuration(dur * 2 / 3).withEndAction { mini.visibility = View.GONE }.start()
            }
        }
        if (!animate) {
            sheet.visibility = if (to == VoiceSessionState.ACTIVE_FULL) View.VISIBLE else View.GONE
            mini.visibility = if (to == VoiceSessionState.ACTIVE_MINIMIZED) View.VISIBLE else View.GONE
            sheet.translationY = 0f; sheet.alpha = 1f; mini.translationY = 0f; mini.alpha = 1f
        }
        render()
    }

    // ================================================================== काम
    /**
     * 14 नवंबर 2026 (मालिक: "बोर्ड कास्ट सेलेक्ट किया है तो उसी के ऊपर काम होना चाहिए"): पहले हर नए सत्र में टैब Chat पर लौट
     * जाता था और Live को चुना मोड बताया ही नहीं जाता था - इसलिए Broadcast चुनकर कहानी बोलने पर Story Writer खुलता था.
     * अब होम पर चुना मोड ही आवाज़ का टैब, और जुड़ते ही Live को उसका नियम ([DkaiCore.voiceModeNote]).
     */
    fun newSession() { sessionStart = System.currentTimeMillis(); voiceTab = s.mode; pendingNote = DkaiCore.voiceModeNote(voiceTab); render() }

    /** होम पर मोड बदला = आवाज़ का टैब भी वही (सत्र चालू हो तो Live को बताओ) */
    fun follow(m: DkaiMode) {
        if (m == voiceTab) return
        voiceTab = m
        if (s.voiceSessionState.active) { val note = DkaiCore.voiceModeNote(m); if (liveTalk()) DKAccessibilityService.instance?.gemLive?.pushNote(note) else pendingNote = note }
        render()
    }

    private fun toggleScreenShare() {
        if (!liveTalk()) { UiLang.toast(c, "DK लाइव जुड़ते ही स्क्रीन शेयर होगी — पहले माइक दबाइए", Toast.LENGTH_SHORT).show(); return }
        val turningOn = !(LiveVision.screenOn || LiveVision.asking)
        val r = LiveVision.toggleScreen(c)
        if (turningOn && r.startsWith("This Live model")) UiLang.toast(c, "यह Gemini Live मॉडल तस्वीर नहीं लेता — सेटिंग्स में दूसरा मॉडल चुनें", Toast.LENGTH_LONG).show()
        else if (turningOn) UiLang.toast(c, "Android पूछेगा — \"Start / शुरू करें\" दबाते ही DK आपकी स्क्रीन देखेगा", Toast.LENGTH_SHORT).show()
        else UiLang.toast(c, "स्क्रीन शेयर बंद", Toast.LENGTH_SHORT).show()
        render()
    }
    fun closedRecently() = System.currentTimeMillis() - closedAt < 3_000

    private fun close() { s.event(VoiceEvent.CLOSE); if (LiveVision.screenOn || LiveVision.asking) LiveVision.setScreen(c, false) }

    private fun micToggle() {
        if (!DKState.live) { s.startLive(); return }
        val m = !DKState.micMuted
        DKState.setMicMuted(m)
        UiLang.toast(c, if (m) "माइक म्यूट - DK अभी कुछ नहीं सुनेगा" else "माइक चालू - DK फिर से सुन रहा है", Toast.LENGTH_SHORT).show()
        render()
    }

    /**
     * 13 नवंबर 2026 (मालिक): बॉक्स पर टैप - DK बोल रहा हो = तुरंत चुप होकर सुनने लगे (बीच में टोकना),
     * सुन रहा हो = "Tap to stop" (माइक रुके), म्यूट हो = फिर से सुनना.
     */
    private fun boxTap() {
        if (!DKState.live) { s.startLive(); return }
        if (callNow == DkaiCore.Call.SPEAKING) {
            try { DKAccessibilityService.instance?.gemLive?.bargeIn("बॉक्स पर टैप") } catch (_: Exception) {}
            render(); return
        }
        micToggle()
    }

    private fun liveTalk(): Boolean = DKState.live && try { DKAccessibilityService.instance?.gemLive?.canTalk() == true } catch (_: Exception) { false }

    private fun pickTab(m: DkaiMode) {
        if (m == voiceTab) return
        voiceTab = m
        s.pickMode(m)                       // होम का मोड भी वही (लिखकर भेजो तो भी उसी मोड में)
        val note = DkaiCore.voiceModeNote(m)
        if (liveTalk()) DKAccessibilityService.instance?.gemLive?.pushNote(note) else pendingNote = note
        val tip = when (m) {
            DkaiMode.BROADCAST -> "Broadcast: कहानी / पॉडकास्ट / खबर बोलिए - यहीं लिखी जाएगी और आवाज़ अपने-आप बनेगी (स्क्रीन शेयर = देर तक दबाएँ)"
            DkaiMode.PIPELINE -> "Pipeline: ऐप / वेबसाइट / कोड का काम यहीं कदम-दर-कदम"
            DkaiMode.BUILDER -> "Builder: ऐप / वेबसाइट Builder में बनेगी"
            DkaiMode.AGENT -> "Agent: कई कदम वाला काम DK कंप्यूटर करेगा"
            DkaiMode.CHAT -> "Chat: सिर्फ़ बातचीत"
        }
        UiLang.toast(c, tip, Toast.LENGTH_SHORT).show()
        render()
    }

    /** चुना orb दिखाओ: पुराना = OrbView (DK की हालत ख़ुद देखता), बाकी = FluidOrbView की ड्रॉइंग */
    fun orbStyle() {
        if (!::orb.isInitialized) return
        val classic = DkaiOrbs.get(c) == DkaiOrbs.CLASSIC
        classicOrb.visibility = if (classic) View.VISIBLE else View.GONE
        orb.visibility = if (classic) View.INVISIBLE else View.VISIBLE
        orb.invalidate(); classicOrb.invalidate(); if (::miniOrb.isInitialized) miniOrb.invalidate()
    }

    // ------------------------------------------------------------------ dropdown
    private fun menu(anchor: View, rows: List<Triple<DkaiIcon.Kind?, String, Boolean>>, tint: (Int) -> Int, onPick: (Int) -> Unit) {
        menuLayer.removeAllViews()
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = DkaiTheme.round(c, DkaiTheme.CARD, 16f, DkaiTheme.BORDER); elevation = d(16f).toFloat()
            setPadding(d(6f), d(6f), d(6f), d(6f)); isClickable = true
        }
        rows.forEachIndexed { i, (icon, label, on) ->
            val r = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = d(48f)
                setPadding(d(12f), 0, d(14f), 0); isClickable = true; isFocusable = true
                background = DkaiTheme.press(DkaiTheme.round(c, if (on) DkaiTheme.SEL else 0, 10f), DkaiTheme.round(c, 0xFFFFFFFF.toInt(), 10f))
                setOnClickListener { closeMenu(); onPick(i) }
            }
            if (icon != null) r.addView(FrameLayout(c).apply {
                background = DkaiTheme.oval(tint(i) and 0x26FFFFFF)
                addView(ImageView(c).apply { setImageDrawable(DkaiIcon(icon, tint(i))) }, FrameLayout.LayoutParams(d(16f), d(16f), Gravity.CENTER))
            }, LinearLayout.LayoutParams(d(30f), d(30f)).apply { rightMargin = d(10f) })
            r.addView(DkaiTheme.text(c, label, 15f, if (on) DkaiTheme.WHITE else DkaiTheme.SUB, if (on) DkaiTheme.SANS_BOLD else DkaiTheme.SANS), LinearLayout.LayoutParams(0, -2, 1f))
            if (on) r.addView(ImageView(c).apply { setImageDrawable(DkaiIcon(DkaiIcon.Kind.CHECK, DkaiTheme.BLUE)) }, LinearLayout.LayoutParams(d(18f), d(18f)).apply { leftMargin = d(12f) })
            card.addView(r, LinearLayout.LayoutParams(-1, -2))
        }
        val loc = IntArray(2); val sl = IntArray(2)
        anchor.getLocationInWindow(loc); sheet.getLocationInWindow(sl)
        val w = if (rows.any { it.second.length > 18 }) d(260f) else d(200f)
        val left = (loc[0] - sl[0]).coerceAtMost(sheet.width - w - d(12f)).coerceAtLeast(d(12f))
        // नीचे जगह कम = ऊपर खोलो (स्क्रीन से बाहर न जाए)
        val est = rows.size * d(48f) + d(12f)
        var top = loc[1] - sl[1] + anchor.height + d(6f)
        if (sheet.height > 0 && top + est > sheet.height - d(12f)) top = (loc[1] - sl[1] - est - d(6f)).coerceAtLeast(d(12f))
        val lp = FrameLayout.LayoutParams(w, -2).apply { leftMargin = left; topMargin = top }
        menuLayer.addView(card, lp)
        menuLayer.visibility = View.VISIBLE; menuLayer.alpha = 0f
        card.scaleX = 0.92f; card.scaleY = 0.92f; card.pivotX = 0f; card.pivotY = 0f
        menuLayer.animate().alpha(1f).setDuration(140).start()
        card.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(DecelerateInterpolator()).start()
    }

    /** true = कोई dropdown खुला था और बंद किया */
    fun closeMenu(): Boolean {
        if (!::menuLayer.isInitialized || menuLayer.visibility != View.VISIBLE) return false
        menuLayer.animate().alpha(0f).setDuration(120).withEndAction { menuLayer.visibility = View.GONE; menuLayer.removeAllViews() }.start()
        return true
    }

    private fun currentVoice(): DkaiCore.Voice = DkaiCore.voiceFor(Prefs.str(c, "gemini_voice", LiveVoice.DEFAULT), Prefs.voiceGender(c))

    private fun voiceMenu(anchor: View) {
        val cur = currentVoice()
        val vs = DkaiCore.VOICES
        menu(anchor, vs.map { Triple(if (it.female) DkaiIcon.Kind.FEMALE else DkaiIcon.Kind.MALE, it.name, it == cur) },
            { i -> if (vs[i].female) DkaiTheme.PINK else DkaiTheme.CYAN }) { i ->
            val v = vs[i]
            Prefs.edit(c) { putString("gemini_voice", v.api); putString("voice_gender", v.gender) }
            VoiceEngines.liveChanged()                     // Live अगले जवाब से (चुप होते ही) नई आवाज़ से जुड़ता है
            val jarvis = try { JarvisMode.on(c) } catch (_: Exception) { false }
            UiLang.toast(c, if (jarvis) "आवाज़ ${v.name} सेव — अभी JARVIS मोड चालू है, उसकी अपनी आवाज़ बोलेगी" else "आवाज़: ${v.name} — अगले जवाब से", Toast.LENGTH_SHORT).show()
            render()
        }
    }

    /** 13 नवंबर 2026 (मालिक - M2): Voices & Speed एक ही नीचे वाली sheet - टैप करते ही लागू */
    private fun voiceSheet() {
        lateinit var layer: DkaiUi.Layer
        val (card, body) = DkaiUi.sheet(c, "Voices & Speed", "आवाज़ और बोलने की गति - तुरंत लागू") { DkaiUi.close(s.root, layer) }
        fun fill() {
            body.removeAllViews()
            VoiceTune.load(c)
            val cur = currentVoice()
            body.addView(DkaiUi.label(c, "Voice"))
            for (v in DkaiCore.VOICES) {
                body.addView(DkaiUi.row(c, if (v.female) DkaiIcon.Kind.FEMALE else DkaiIcon.Kind.MALE, v.name, DkaiKit.voiceTag(v.name, v.female), v == cur,
                    if (v.female) DkaiTheme.PINK else DkaiTheme.CYAN) {
                    Prefs.edit(c) { putString("gemini_voice", v.api); putString("voice_gender", v.gender) }
                    VoiceEngines.liveChanged()
                    UiLang.toast(c, "आवाज़: ${v.name} — अगले जवाब से", Toast.LENGTH_SHORT).show()
                    render(); fill()
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = d(6f) })
            }
            body.addView(DkaiUi.label(c, "Speed"))
            val sp = DkaiCore.nearestSpeed(VoiceTune.speedPct)
            val names = DkaiCore.SPEEDS.map { DkaiCore.speedLabel(it) + " " + speedName(it) }
            body.addView(DkaiUi.chips(c, names, DkaiCore.SPEEDS.indexOf(sp)) { i ->
                VoiceTune.setSpeed(c, DkaiCore.SPEEDS[i]); render(); fill()
            })
        }
        fill()
        layer = DkaiUi.open(s.root, card, DkaiUi.Place.BOTTOM)
    }

    private fun speedName(p: Int) = when { p <= 100 -> "Normal"; p <= 120 -> "Fast"; p <= 130 -> "Faster"; p < 200 -> "Turbo"; else -> "Ultra" }

    private fun speedMenu(anchor: View) {
        VoiceTune.load(c)
        val cur = DkaiCore.nearestSpeed(VoiceTune.speedPct)
        menu(anchor, DkaiCore.SPEEDS.map { Triple(null, DkaiCore.speedLabel(it), it == cur) }, { DkaiTheme.BLUE }) { i ->
            VoiceTune.setSpeed(c, DkaiCore.SPEEDS[i])         // चलती आवाज़ पर भी तुरंत
            render()
        }
    }

    // ================================================================== ताज़ा
    private fun shownText(): String {
        val live = DKState.liveReply
        if (live.isNotBlank()) return live
        val m = DKState.messages.lastOrNull { it.time >= sessionStart }
        return m?.text ?: DkaiCore.HELLO
    }

    fun render() {
        if (!::badge.isInitialized) return
        val connected = liveTalk()
        // Live जुड़ते ही बाकी SYSTEM NOTE भेजो (टैब पहले चुना था)
        pendingNote?.let { n -> if (connected) { pendingNote = null; DKAccessibilityService.instance?.gemLive?.pushNote(n) } }
        val st = DkaiCore.voiceStatus(DKState.mode, DKState.live, DKState.micMuted, DKState.working(), connected)

        // 13 नवंबर 2026 (मालिक): सिर्फ़ तीन हालत; बॉक्स में एक समय पर सिर्फ़ वही जो अभी बोल रहा है
        val call = DkaiCore.callState(st)
        callNow = call
        val (cbg, cfg, cdot) = when (call) {
            DkaiCore.Call.SPEAKING -> Triple(0xFFBAE6FD.toInt(), 0xFF075985.toInt(), 0xFF0284C7.toInt())
            DkaiCore.Call.PROCESSING -> Triple(0xFFE9D5FF.toInt(), 0xFF5B21B6.toInt(), 0xFF7C3AED.toInt())
            else -> Triple(DkaiTheme.MINT, DkaiTheme.MINT_TEXT, 0xFF059669.toInt())
        }
        (badge.background as? GradientDrawable)?.setColor(cbg)
        badgeTxt.text = DkaiCore.callBadge(st); badgeTxt.setTextColor(cfg)
        badgeDot.color = cdot; badgeDot.pulsing = true; badgeDot.invalidate()
        badge.contentDescription = "हालत: ${DkaiCore.callBadge(st)}"
        val lastDk = DKState.messages.lastOrNull { it.role != "u" }
        val ct = DkaiCore.connectingOr(st, DkaiCore.callText(call, DKState.micMuted, DKState.lastUser, DKState.lastUserAt, DKState.liveReply, lastDk?.text.orEmpty(), lastDk?.time ?: 0L, sessionStart))
        if (boxTitle.text.toString() != ct.title) boxTitle.text = ct.title
        if (transcript.text.toString() != ct.body) transcript.text = ct.body
        val muteLine = call == DkaiCore.Call.LISTENING && DKState.micMuted
        val tc = when { muteLine -> 0xFFDC2626.toInt(); call == DkaiCore.Call.PROCESSING -> 0xFF7C3AED.toInt(); call == DkaiCore.Call.SPEAKING -> 0xFF0284C7.toInt(); else -> 0xFF2563EB.toInt() }
        boxTitle.setTextColor(tc); boxDot.color = tc; boxDot.pulsing = !muteLine; boxDot.invalidate()
        transcript.setTextColor(if (ct.placeholder) 0xFF64748B.toInt() else 0xFF0F172A.toInt())
        transcript.textSize = if (ct.placeholder) 15f else 17f
        val text = if (ct.placeholder) shownText() else ct.body
        miniText.text = DkaiCore.preview(if (muteLine) DkaiCore.MUTED_LINE else text, 26)
        miniStatus.text = DkaiCore.callBadge(st)
        val miniCol = when (call) { DkaiCore.Call.SPEAKING -> DkaiTheme.CYAN; DkaiCore.Call.PROCESSING -> DkaiTheme.VIOLET; else -> if (DKState.micMuted) DkaiTheme.RED else DkaiTheme.GREEN }
        miniStatus.setTextColor(miniCol); miniDot.color = miniCol; miniDot.invalidate()
        miniMic.setImageDrawable(DkaiIcon(if (DKState.micMuted) DkaiIcon.Kind.MIC_OFF else DkaiIcon.Kind.MIC, if (DKState.micMuted) DkaiTheme.RED else DkaiTheme.WHITE))
        bigMic.muted = DKState.micMuted; bigMic.invalidate()

        val v = currentVoice()
        voicePill.text = v.name
        voicePill.setCompoundDrawablesRelative(DkaiTheme.icon(c, if (v.female) DkaiIcon.Kind.FEMALE else DkaiIcon.Kind.MALE, if (v.female) DkaiTheme.PINK else DkaiTheme.CYAN, 16f), null,
            DkaiTheme.icon(c, DkaiIcon.Kind.DOWN, DkaiTheme.GRAY, 14f), null)
        speedPill.text = DkaiCore.speedLabel(VoiceTune.speedPct)
        speedPill.setCompoundDrawablesRelative(null, null, DkaiTheme.icon(c, DkaiIcon.Kind.DOWN, DkaiTheme.GRAY, 14f), null)

        for (i in 0 until tabs.childCount) {
            val t = tabs.getChildAt(i) as LinearLayout
            val m = t.tag as DkaiMode
            val on = m == voiceTab
            (t.getChildAt(0) as TextView).setTextColor(if (on) CYAN_ON else DkaiTheme.GRAY)
            (t.getChildAt(0) as TextView).setShadowLayer(if (on) d(8f).toFloat() else 0f, 0f, 0f, if (on) CYAN_ON else 0)
            t.getChildAt(1).visibility = if (on) View.VISIBLE else View.INVISIBLE
            t.background = DkaiTheme.press(if (on) tabOn() else DkaiTheme.round(c, 0, 12f), DkaiTheme.round(c, 0xFFFFFFFF.toInt(), 12f))
            t.isSelected = on
        }
        orbStyle(); wave.invalidate(); miniWave.invalidate()
    }

    init { VoiceTune.load(c) }
}

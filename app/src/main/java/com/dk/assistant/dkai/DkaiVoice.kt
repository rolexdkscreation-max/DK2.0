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
    private lateinit var transcript: TextView
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
        sheet.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF0B101D.toInt(), 0xFF101634.toInt(), 0xFF0B101D.toInt()))
        col = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        sheet.addView(col, FrameLayout.LayoutParams(-1, -1))

        // ऊपर: आवाज़ | बैज | गति
        val top = FrameLayout(c).apply { setPadding(d(16f), d(12f), d(16f), d(8f)) }
        voicePill = DkaiTheme.pill(c, "Emma", DkaiTheme.PILL, DkaiTheme.WHITE, 14f).apply {
            minHeight = d(40f); compoundDrawablePadding = d(6f); contentDescription = "आवाज़ चुनें"
            setOnClickListener { voiceMenu(it) }
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
            setOnClickListener { speedMenu(it) }
        }
        top.addView(speedPill, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
        col.addView(top, LinearLayout.LayoutParams(-1, -2))

        col.addView(View(c), LinearLayout.LayoutParams(1, 0, 1f))
        val size = minOf((c.resources.displayMetrics.widthPixels * 0.78f).toInt(), d(320f))
        orb = FluidOrbView(c).apply { level = this@DkaiVoice.level; active = this@DkaiVoice.active; contentDescription = "DK orb" }
        col.addView(orb, LinearLayout.LayoutParams(size, size))
        transcript = DkaiTheme.text(c, DkaiCore.HELLO, 20f, DkaiTheme.WHITE, DkaiTheme.SANS_MED).apply {
            gravity = Gravity.CENTER; maxLines = 4; setLineSpacing(0f, 1.2f); setPadding(d(28f), d(6f), d(28f), d(10f))
            ellipsize = android.text.TextUtils.TruncateAt.START
        }
        UiLang.keep(transcript)
        col.addView(transcript, LinearLayout.LayoutParams(-1, -2))
        wave = WaveformView(c).apply { level = this@DkaiVoice.level; active = this@DkaiVoice.active }
        col.addView(wave, LinearLayout.LayoutParams((c.resources.displayMetrics.widthPixels * 0.78f).toInt(), d(56f)))
        col.addView(View(c), LinearLayout.LayoutParams(1, 0, 1f))

        // मोड टैब
        tabs = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(d(8f), 0, d(8f), d(10f)) }
        for (m in DkaiMode.VOICE_TABS) tabs.addView(tabView(m), LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(tabs, LinearLayout.LayoutParams(-1, -2))

        // नीचे: (v) | माइक | (X)
        val bottom = FrameLayout(c).apply { setPadding(d(28f), d(4f), d(28f), d(20f)) }
        bottom.addView(roundBtn(DkaiIcon.Kind.DOWN, DkaiTheme.WHITE, DkaiTheme.PILL, "छोटा करें") { s.event(VoiceEvent.MINIMIZE) },
            FrameLayout.LayoutParams(d(56f), d(56f), Gravity.START or Gravity.CENTER_VERTICAL))
        bigMic = RippleMicView(c, 84f).apply {
            level = this@DkaiVoice.level; active = this@DkaiVoice.active; contentDescription = "माइक"
            setOnClickListener { micToggle() }
        }
        bottom.addView(bigMic, FrameLayout.LayoutParams(d(150f), d(150f), Gravity.CENTER))
        bottom.addView(roundBtn(DkaiIcon.Kind.X, DkaiTheme.RED, DkaiTheme.RED_BG, "बातचीत बंद करें", DkaiTheme.RED and 0x66FFFFFF) { close() },
            FrameLayout.LayoutParams(d(56f), d(56f), Gravity.END or Gravity.CENTER_VERTICAL))
        col.addView(bottom, LinearLayout.LayoutParams(-1, -2))

        menuLayer = FrameLayout(c).apply { visibility = View.GONE; isClickable = true; setBackgroundColor(0x66000000); setOnClickListener { closeMenu() } }
        sheet.addView(menuLayer, FrameLayout.LayoutParams(-1, -1))
    }

    private fun tabView(m: DkaiMode): View = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; minimumHeight = d(44f); tag = m
        isClickable = true; isFocusable = true; contentDescription = m.label
        background = DkaiTheme.press(DkaiTheme.round(c, 0, 10f), DkaiTheme.round(c, 0xFFFFFFFF.toInt(), 10f))
        addView(DkaiTheme.text(c, m.label, 13f, DkaiTheme.GRAY, DkaiTheme.SANS_MED).apply { maxLines = 1 })
        addView(View(c).apply { background = DkaiTheme.round(c, DkaiTheme.BLUE, 2f) }, LinearLayout.LayoutParams(d(26f), d(3f)).apply { topMargin = d(6f) })
        setOnClickListener { pickTab(m) }
    }

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
        mini.background = DkaiTheme.round(c, 0xF2161E31.toInt(), 22f, 0xFF33406A.toInt())
        mini.elevation = d(12f).toFloat()
        mini.setPadding(d(8f), d(6f), d(6f), d(6f))
        mini.isClickable = true
        mini.setOnClickListener { s.event(VoiceEvent.EXPAND) }
        mini.contentDescription = "DK बातचीत - बड़ा करने के लिए टैप"
        miniOrb = FluidOrbView(c).apply { glow = false; level = this@DkaiVoice.level; active = this@DkaiVoice.active }
        mini.addView(miniOrb, LinearLayout.LayoutParams(d(46f), d(46f)))
        val mid = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(d(10f), 0, d(6f), 0) }
        val st = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        miniDot = PulseDot(c).apply { color = DkaiTheme.GREEN }
        st.addView(miniDot, LinearLayout.LayoutParams(d(10f), d(10f)))
        miniStatus = DkaiTheme.text(c, "LISTENING...", 11f, DkaiTheme.GREEN, DkaiTheme.SANS_BOLD).apply { letterSpacing = 0.1f; setPadding(d(5f), 0, 0, 0) }
        st.addView(miniStatus)
        mid.addView(st)
        miniText = DkaiTheme.text(c, DkaiCore.preview(DkaiCore.HELLO), 13f, DkaiTheme.WHITE).apply { maxLines = 1; setPadding(0, d(4f), 0, 0) }
        UiLang.keep(miniText)
        mid.addView(miniText)
        mini.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
        miniMic = ImageView(c).apply { setImageDrawable(DkaiIcon(DkaiIcon.Kind.MIC, DkaiTheme.WHITE)) }
        mini.addView(miniBtn(miniMic, DkaiTheme.PILL, "माइक म्यूट / चालू") { micToggle() })
        mini.addView(miniBtn(ImageView(c).apply { setImageDrawable(DkaiIcon(DkaiIcon.Kind.UP, DkaiTheme.WHITE)) }, DkaiTheme.PILL, "बड़ा करें") { s.event(VoiceEvent.EXPAND) })
        mini.addView(miniBtn(ImageView(c).apply { setImageDrawable(DkaiIcon(DkaiIcon.Kind.X, DkaiTheme.RED)) }, DkaiTheme.RED_BG, "बातचीत बंद करें") { close() })
    }

    private fun miniBtn(icon: ImageView, fill: Int, desc: String, onTap: () -> Unit): View = FrameLayout(c).apply {
        background = DkaiTheme.press(DkaiTheme.oval(fill), DkaiTheme.oval(0xFFFFFFFF.toInt()))
        isClickable = true; isFocusable = true; contentDescription = desc
        addView(icon, FrameLayout.LayoutParams(d(18f), d(18f), Gravity.CENTER))
        setOnClickListener { onTap() }
        layoutParams = LinearLayout.LayoutParams(d(44f), d(44f)).apply { leftMargin = d(4f) }
    }

    fun miniParams() = FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
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
    fun newSession() { sessionStart = System.currentTimeMillis(); if (voiceTab != DkaiMode.BROADCAST) voiceTab = DkaiMode.CHAT }
    fun closedRecently() = System.currentTimeMillis() - closedAt < 3_000

    private fun close() { s.event(VoiceEvent.CLOSE); if (LiveVision.screenOn || LiveVision.asking) LiveVision.setScreen(c, false) }

    private fun micToggle() {
        if (!DKState.live) { s.startLive(); return }
        val m = !DKState.micMuted
        DKState.setMicMuted(m)
        UiLang.toast(c, if (m) "माइक म्यूट - DK अभी कुछ नहीं सुनेगा" else "माइक चालू - DK फिर से सुन रहा है", Toast.LENGTH_SHORT).show()
        render()
    }

    private fun liveTalk(): Boolean = DKState.live && try { DKAccessibilityService.instance?.gemLive?.canTalk() == true } catch (_: Exception) { false }

    private fun pickTab(m: DkaiMode) {
        if (m == DkaiMode.BROADCAST) {
            if (!liveTalk()) { UiLang.toast(c, "DK लाइव जुड़ते ही स्क्रीन शेयर होगी — पहले माइक दबाइए", Toast.LENGTH_SHORT).show(); return }
            val turningOn = !(LiveVision.screenOn || LiveVision.asking)
            val r = LiveVision.toggleScreen(c)
            if (turningOn && r.startsWith("This Live model")) UiLang.toast(c, "यह Gemini Live मॉडल तस्वीर नहीं लेता — सेटिंग्स में दूसरा मॉडल चुनें", Toast.LENGTH_LONG).show()
            else if (turningOn) UiLang.toast(c, "Android पूछेगा — \"Start / शुरू करें\" दबाते ही DK आपकी स्क्रीन देखेगा", Toast.LENGTH_SHORT).show()
            render(); return
        }
        if (m == voiceTab) return
        voiceTab = m
        val note = DkaiCore.voiceModeNote(m)
        if (liveTalk()) DKAccessibilityService.instance?.gemLive?.pushNote(note) else pendingNote = note
        render()
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
                background = DkaiTheme.press(DkaiTheme.round(c, if (on) 0xFF1E2A47.toInt() else 0, 10f), DkaiTheme.round(c, 0xFFFFFFFF.toInt(), 10f))
                setOnClickListener { closeMenu(); onPick(i) }
            }
            if (icon != null) r.addView(FrameLayout(c).apply {
                background = DkaiTheme.oval(tint(i) and 0x26FFFFFF)
                addView(ImageView(c).apply { setImageDrawable(DkaiIcon(icon, tint(i))) }, FrameLayout.LayoutParams(d(16f), d(16f), Gravity.CENTER))
            }, LinearLayout.LayoutParams(d(30f), d(30f)).apply { rightMargin = d(10f) })
            r.addView(DkaiTheme.text(c, label, 15f, if (on) DkaiTheme.WHITE else 0xFFCBD5E1.toInt(), if (on) DkaiTheme.SANS_BOLD else DkaiTheme.SANS), LinearLayout.LayoutParams(0, -2, 1f))
            if (on) r.addView(ImageView(c).apply { setImageDrawable(DkaiIcon(DkaiIcon.Kind.CHECK, DkaiTheme.BLUE)) }, LinearLayout.LayoutParams(d(18f), d(18f)).apply { leftMargin = d(12f) })
            card.addView(r, LinearLayout.LayoutParams(-1, -2))
        }
        val loc = IntArray(2); val sl = IntArray(2)
        anchor.getLocationInWindow(loc); sheet.getLocationInWindow(sl)
        val w = d(200f)
        val left = (loc[0] - sl[0]).coerceAtMost(sheet.width - w - d(12f)).coerceAtLeast(d(12f))
        val lp = FrameLayout.LayoutParams(w, -2).apply { leftMargin = left; topMargin = loc[1] - sl[1] + anchor.height + d(6f) }
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
        val (bg, fg, dot) = when (st) {
            DkaiCore.Status.SPEAKING -> Triple(0xFFBAE6FD.toInt(), 0xFF075985.toInt(), 0xFF0284C7.toInt())
            DkaiCore.Status.THINKING -> Triple(0xFFE9D5FF.toInt(), 0xFF5B21B6.toInt(), 0xFF7C3AED.toInt())
            DkaiCore.Status.MUTED, DkaiCore.Status.OFFLINE -> Triple(0xFFE2E8F0.toInt(), 0xFF334155.toInt(), 0xFF64748B.toInt())
            DkaiCore.Status.CONNECTING -> Triple(0xFFFDE68A.toInt(), 0xFF78350F.toInt(), 0xFFD97706.toInt())
            else -> Triple(DkaiTheme.MINT, DkaiTheme.MINT_TEXT, 0xFF059669.toInt())
        }
        (badge.background as? GradientDrawable)?.setColor(bg)
        badgeTxt.text = st.label; badgeTxt.setTextColor(fg)
        badgeDot.color = dot; badgeDot.pulsing = st != DkaiCore.Status.MUTED && st != DkaiCore.Status.OFFLINE; badgeDot.invalidate()
        badge.contentDescription = "हालत: ${st.label}"

        val text = shownText()
        if (transcript.text.toString() != DkaiCore.tail(text)) transcript.text = DkaiCore.tail(text)
        miniText.text = DkaiCore.preview(text, 22)
        miniStatus.text = st.label + "..."
        val miniCol = when (st) { DkaiCore.Status.SPEAKING -> DkaiTheme.CYAN; DkaiCore.Status.THINKING -> DkaiTheme.VIOLET; DkaiCore.Status.LISTENING -> DkaiTheme.GREEN; else -> DkaiTheme.GRAY }
        miniStatus.setTextColor(miniCol); miniDot.color = miniCol; miniDot.invalidate()
        miniMic.setImageDrawable(DkaiIcon(if (DKState.micMuted) DkaiIcon.Kind.MIC_OFF else DkaiIcon.Kind.MIC, if (DKState.micMuted) DkaiTheme.RED else DkaiTheme.WHITE))
        bigMic.muted = DKState.micMuted; bigMic.invalidate()

        val v = currentVoice()
        voicePill.text = v.name
        voicePill.setCompoundDrawablesRelative(DkaiTheme.icon(c, if (v.female) DkaiIcon.Kind.FEMALE else DkaiIcon.Kind.MALE, if (v.female) DkaiTheme.PINK else DkaiTheme.CYAN, 16f), null,
            DkaiTheme.icon(c, DkaiIcon.Kind.DOWN, DkaiTheme.GRAY, 14f), null)
        speedPill.text = DkaiCore.speedLabel(VoiceTune.speedPct)
        speedPill.setCompoundDrawablesRelative(null, null, DkaiTheme.icon(c, DkaiIcon.Kind.DOWN, DkaiTheme.GRAY, 14f), null)

        val sharing = LiveVision.screenOn || LiveVision.asking
        for (i in 0 until tabs.childCount) {
            val t = tabs.getChildAt(i) as LinearLayout
            val m = t.tag as DkaiMode
            val on = if (m == DkaiMode.BROADCAST) sharing else m == voiceTab
            (t.getChildAt(0) as TextView).setTextColor(if (on) DkaiTheme.WHITE else DkaiTheme.GRAY)
            t.getChildAt(1).visibility = if (on) View.VISIBLE else View.INVISIBLE
            t.isSelected = on
        }
        orb.invalidate(); wave.invalidate(); miniOrb.invalidate()
    }

    init { VoiceTune.load(c) }
}

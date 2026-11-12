package com.dk.assistant

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * मालिक (12 नवंबर 2026): iPhone के Dynamic Island जैसा "DK island" - DK से बाहर हों और DK का कोई काम चले (कोडिंग, DK कंप्यूटर,
 * रिव्यू, फ़ोन का काम, लाइव बात), कॉल आए या कोई गाना/वीडियो बजे, तो स्क्रीन के ऊपर बीच में काली गोली (pill).
 * टैप = फैलकर पूरा हाल: हाल के 1-5 काम (असली कदम), कॉल (उठाएँ / काटें), गाना (पीछे / रोकें-चलाएँ / आगे),
 * DK लाइव (म्यूट / बंद), प्राइवेसी से छिपे notification. बाहर छूने पर या थोड़ी देर बाद फिर छोटा.
 * नियम [IslandCore] में. Accessibility overlay (TYPE_ACCESSIBILITY_OVERLAY) - बाकी स्क्रीन का छूना आर-पार.
 */
@SuppressLint("StaticFieldLeak")
object DKIsland {
    private const val P_ON = "island_on"
    private const val P_LIVE = "island_live"
    private const val P_MEDIA = "island_media"
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE)
    fun on(c: Context) = sp(c).getBoolean(P_ON, true)
    fun setOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(P_ON, v).apply(); poke() }
    fun liveOn(c: Context) = sp(c).getBoolean(P_LIVE, true)
    fun setLiveOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(P_LIVE, v).apply(); poke() }
    fun mediaOn(c: Context) = sp(c).getBoolean(P_MEDIA, true)
    fun setMediaOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(P_MEDIA, v).apply(); poke() }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var svc: DKAccessibilityService? = null
    private var wm: WindowManager? = null
    private var root: FrameLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var expanded = false
    private var touchedAt = 0L
    private var callSince = 0L
    private var history: List<IslandCore.Task> = emptyList()
    private var historyAt = 0L
    private var lastSig = ""
    private val updaters = ArrayList<(IslandCore.Snap, Long) -> Unit>()
    private var mediaCtl: MediaController? = null
    private var mediaArt: Bitmap? = null

    private val ticker = Runnable { tick() }
    private val onTask = TaskStatus.Listener { _, _ -> historyAt = 0L; poke() }
    private val onState: () -> Unit = { poke() }

    fun start(s: DKAccessibilityService) {
        svc = s; wm = s.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        TaskStatus.listen(onTask); DKState.listen(onState)
        poke()
    }

    fun stop() {
        TaskStatus.unlisten(onTask); DKState.unlisten(onState)
        main.removeCallbacks(ticker)
        main.post { remove() }
        svc = null
    }

    /** कुछ बदला - अभी देखो (किसी भी thread से) */
    fun poke() {
        main.removeCallbacks(ticker)
        // DK बोलते समय DKState बहुत बार बदलता है - island ज़्यादा से ज़्यादा ~4 बार/सेकंड देखे
        main.postDelayed(ticker, (lastTickAt + 250L - SystemClock.uptimeMillis()).coerceIn(0L, 250L))
    }
    @Volatile private var lastTickAt = 0L
    private var mediaAt = 0L
    private var lastMedia: IslandCore.Media? = null

    private fun px(c: Context, dp: Float) = (dp * c.resources.displayMetrics.density).roundToInt()

    // ------------------------------------------------------------ हालत इकट्ठा करना (असली)
    private fun snap(s: DKAccessibilityService, now: Long): IslandCore.Snap {
        // कॉल: SIM या WhatsApp/Instagram… वाली
        val call = try {
            val calls = s.calls
            val ringing = calls.ringing; val inCall = calls.inCall
            if (ringing || inCall) {
                if (inCall && callSince == 0L) callSince = now
                if (!inCall) callSince = 0L
                val app = AppCall.current?.takeIf { AppCall.active() }?.app.orEmpty()
                IslandCore.Call(calls.caller, ringing && !inCall, if (callSince > 0) callSince else now, app)
            } else { callSince = 0L; null }
        } catch (_: Exception) { null }
        // DK लाइव / फ़ोन पर DK का काम (बिना Live भी)
        val live = if (liveOn(s) && (DKState.live || DKState.mode == Mode.WORKING)) IslandCore.Live(DKState.shownMode(), DKState.micMuted) else null
        // काम: चलते (TaskStatus) + फ़ोन में सेव इतिहास (TaskHistory) - इतिहास हर 5 सेकंड या बदलाव पर ही पढ़ो
        if (now - historyAt > 5_000L) {
            historyAt = now
            history = try { TaskHistory.all(s).take(12).map { e -> IslandCore.fromHistory(e.id, e.who, e.task, e.state, e.last, e.steps, e.started, e.ended) } } catch (_: Exception) { emptyList() }
        }
        val jobs = TaskStatus.all().map { IslandCore.fromJob(it) }
        val tasks = IslandCore.recent(jobs.filter { it.active }, jobs.filter { !it.active } + history.filter { h -> jobs.none { it.id == h.id } })
        val media = if (!mediaOn(s)) { mediaCtl = null; null }
            else { if (now - mediaAt > 1_500L) { mediaAt = now; lastMedia = readMedia(s) }; lastMedia }
        return IslandCore.Snap(call, live, tasks, media, PrivacyLocked.list())
    }

    /** फ़ोन में अभी बजता गाना/वीडियो (कोई भी ऐप, YouTube भी) - Notification access चाहिए, न हो तो कुछ नहीं */
    private fun readMedia(s: Context): IslandCore.Media? = try {
        val msm = s.getSystemService(MediaSessionManager::class.java)
        val list = msm?.getActiveSessions(ComponentName(s, DKNotifListener::class.java)).orEmpty().filter { it.packageName != s.packageName }
        val ctl = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { it.metadata != null && it.playbackState?.state == PlaybackState.STATE_PAUSED }
        mediaCtl = ctl
        ctl?.let { m ->
            val md = m.metadata
            mediaArt = md?.let { it.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON) }
            val app = try { s.packageManager.getApplicationLabel(s.packageManager.getApplicationInfo(m.packageName, 0)).toString() } catch (_: Exception) { m.packageName }
            IslandCore.Media(md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().ifBlank { md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty() },
                md?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty().ifBlank { md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty() },
                app, m.packageName, m.playbackState?.state == PlaybackState.STATE_PLAYING)
        }
    } catch (_: SecurityException) { mediaCtl = null; null } catch (_: Exception) { mediaCtl = null; null }

    // ------------------------------------------------------------ हर सेकंड
    private fun tick() {
        main.removeCallbacks(ticker)
        val s = svc ?: return
        lastTickAt = SystemClock.uptimeMillis()
        val now = System.currentTimeMillis()
        val sn = snap(s, now)
        if (!IslandCore.wanted(on(s), AppVisible.visible, sn, now)) { remove(); main.postDelayed(ticker, 1_500L); return }
        if (root == null) show(s)
        if (expanded && SystemClock.uptimeMillis() - touchedAt > IslandCore.AUTO_COLLAPSE_MS && sn.call == null) collapse()
        render(s, sn, now)
        main.postDelayed(ticker, 1_000L)
    }

    // ------------------------------------------------------------ खिड़की
    @SuppressLint("ClickableViewAccessibility")
    private fun show(s: DKAccessibilityService) {
        val w = wm ?: return
        // कोई भी छुअन (स्क्रॉल भी) = अभी पढ़ रहे हैं, अपने-आप छोटा न हो
        val box = object : FrameLayout(s) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean { touchedAt = SystemClock.uptimeMillis(); return super.dispatchTouchEvent(ev) }
        }.apply {
            background = GradientDrawable().apply { setColor(0xFF000000.toInt()); cornerRadius = px(s, 22f).toFloat(); setStroke(px(s, 1f), 0x22FFFFFF) }
            elevation = px(s, 8f).toFloat()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = px(s, 8f)
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        box.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) { if (expanded) collapse(); true } else false
        }
        box.setOnClickListener { touchedAt = SystemClock.uptimeMillis(); if (expanded) collapse() else expand() }
        // आते समय: छोटी बूँद से फैलकर (iPhone जैसा)
        box.alpha = 0f; box.scaleX = 0.5f; box.scaleY = 0.6f
        try { w.addView(box, p) } catch (e: Exception) { DKLog.d("island नहीं लगा: ${e.javaClass.simpleName}"); return }
        root = box; lp = p; expanded = false; lastSig = ""
        box.post { box.pivotX = box.width / 2f; box.pivotY = 0f
            box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(1.2f)).start() }
    }

    private fun remove() {
        val r = root ?: return
        root = null; lp = null; expanded = false; lastSig = ""; updaters.clear()
        r.animate().alpha(0f).scaleX(0.5f).scaleY(0.6f).setDuration(200).withEndAction { try { wm?.removeView(r) } catch (_: Exception) {} }.start()
    }

    private fun expand() {
        val r = root ?: return; val p = lp ?: return; val s = svc ?: return
        expanded = true; touchedAt = SystemClock.uptimeMillis(); lastSig = ""
        p.width = s.resources.displayMetrics.widthPixels - px(s, 16f)
        try { wm?.updateViewLayout(r, p) } catch (_: Exception) {}
        (r.background as? GradientDrawable)?.cornerRadius = px(s, 30f).toFloat()
        historyAt = 0L
        tick()
        r.pivotX = p.width / 2f; r.pivotY = 0f
        r.scaleX = 0.55f; r.scaleY = 0.35f; r.alpha = 0.6f
        r.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(340).setInterpolator(OvershootInterpolator(0.9f)).start()
    }

    private fun collapse() {
        val r = root ?: return; val p = lp ?: return; val s = svc ?: return
        expanded = false; lastSig = ""
        p.width = WindowManager.LayoutParams.WRAP_CONTENT
        try { wm?.updateViewLayout(r, p) } catch (_: Exception) {}
        (r.background as? GradientDrawable)?.cornerRadius = px(s, 22f).toFloat()
        tick()
        r.pivotY = 0f; r.scaleX = 1.25f; r.scaleY = 1.1f
        r.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(1.1f)).start()
    }

    // ------------------------------------------------------------ बनाना
    /** ढाँचा तभी दोबारा जब कुछ असली बदले (सिर्फ़ समय बदले तो [updaters] वही लाइनें बदलते हैं) */
    private fun render(s: DKAccessibilityService, sn: IslandCore.Snap, now: Long) {
        val r = root ?: return
        val sig = (if (expanded) "E|" else "C|") + sig(sn, now)
        if (sig != lastSig) {
            lastSig = sig; updaters.clear(); r.removeAllViews()
            r.addView(if (expanded) big(s, sn, now) else small(s, sn, now))
        }
        updaters.forEach { try { it(sn, now) } catch (_: Exception) {} }
    }

    private fun sig(sn: IslandCore.Snap, now: Long): String = buildString {
        append(IslandCore.lead(sn, now)); append('|')
        sn.call?.let { append("c${it.who}${it.ringing}") }; append('|')
        sn.live?.let { append("l${it.mode}${it.muted}") }; append('|')
        sn.tasks.forEach { append("t${it.id}${it.state}${it.steps}${it.last.hashCode()}${IslandCore.justEnded(it, now)}") }; append('|')
        sn.media?.let { append("m${it.title}${it.artist}${it.playing}") }; append('|')
        sn.locked.forEach { append("k${it.pkg}${it.count}") }
    }

    private fun tv(c: Context, size: Float, color: Int, bold: Boolean = false, lines: Int = 1) = TextView(c).apply {
        textSize = size; setTextColor(color); maxLines = lines; ellipsize = TextUtils.TruncateAt.END
        if (bold) setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = false
    }

    private fun dot(c: Context, glyph: String, bg: Int, dp: Float = 24f) = TextView(c).apply {
        text = glyph; gravity = Gravity.CENTER; textSize = dp * 0.5f; setTextColor(0xFFFFFFFF.toInt())
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bg) }
        layoutParams = LinearLayout.LayoutParams(px(c, dp), px(c, dp))
    }

    private fun workState(m: Mode) = when (m) {
        Mode.LISTENING -> WorkState.LISTENING; Mode.THINKING -> WorkState.THINKING
        Mode.SPEAKING -> WorkState.SPEAKING; Mode.WORKING -> WorkState.WORKING; Mode.IDLE -> WorkState.IDLE
    }

    /** छोटा island: [निशान] लाइन ............ दाईं ओर (समय / कदम / +2) */
    private fun small(s: DKAccessibilityService, sn: IslandCore.Snap, now: Long): View {
        val row = LinearLayout(s).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(px(s, 8f), px(s, 6f), px(s, 14f), px(s, 6f))
            minimumWidth = px(s, 150f)
        }
        val icon: View = when (IslandCore.lead(sn, now)) {
            IslandCore.Kind.CALL -> dot(s, "📞", 0xFF22C55E.toInt())
            IslandCore.Kind.MEDIA -> mediaArt?.let { b -> ImageView(s).apply { setImageBitmap(b); scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true; background = GradientDrawable().apply { cornerRadius = px(s, 6f).toFloat(); setColor(0xFF222222.toInt()) }
                layoutParams = LinearLayout.LayoutParams(px(s, 24f), px(s, 24f)) } } ?: dot(s, "♪", 0xFFF97316.toInt())
            IslandCore.Kind.LOCKED -> dot(s, "🔒", 0xFF334155.toInt())
            else -> DKWorkingIndicator(s).apply {
                state = sn.live?.let { if (it.muted) WorkState.IDLE else workState(it.mode) } ?: if (sn.tasks.any { it.active }) WorkState.WORKING else WorkState.IDLE
                layoutParams = LinearLayout.LayoutParams(px(s, 24f), px(s, 24f))
            }
        }
        row.addView(icon)
        val left = tv(s, 13f, 0xFFFFFFFF.toInt(), bold = true).apply { maxWidth = px(s, 170f) }
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(s, 8f) })
        val right = tv(s, 12f, rightColor(sn, now)).apply { gravity = Gravity.END }
        row.addView(right, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(s, 10f) })
        updaters.add { a, t -> left.text = UiLang.s(IslandCore.compactText(a, t)); right.text = UiLang.s(IslandCore.compactRight(a, t)) }
        row.contentDescription = UiLang.s("DK island - दबाकर पूरा देखें")
        return row
    }

    private fun rightColor(sn: IslandCore.Snap, now: Long): Int = when (IslandCore.lead(sn, now)) {
        IslandCore.Kind.CALL -> 0xFF4ADE80.toInt()
        IslandCore.Kind.LIVE -> if (sn.live?.muted == true) 0xFFF87171.toInt() else 0xFF60A5FA.toInt()
        IslandCore.Kind.MEDIA -> 0xFFFDBA74.toInt()
        else -> 0xFFA5B4FC.toInt()
    }

    /** बड़ा island: कॉल • लाइव • गाना • हाल के काम • छिपे notification */
    private fun big(s: DKAccessibilityService, sn: IslandCore.Snap, now: Long): View {
        val col = LinearLayout(s).apply { orientation = LinearLayout.VERTICAL; setPadding(px(s, 16f), px(s, 14f), px(s, 16f), px(s, 12f)) }
        // ऊपर: DK का नाम + छोटा करें
        val head = LinearLayout(s).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(DKWorkingIndicator(s).apply {
            state = sn.live?.let { if (it.muted) WorkState.IDLE else workState(it.mode) } ?: if (sn.tasks.any { it.active }) WorkState.WORKING else WorkState.IDLE
            layoutParams = LinearLayout.LayoutParams(px(s, 22f), px(s, 22f))
        })
        head.addView(tv(s, 14f, 0xFFFFFFFF.toInt(), bold = true).apply { text = UiLang.s("${Prefs.assistantName(s)} island") },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(s, 8f) })
        head.addView(pill(s, "DK खोलें", 0xFF1E293B.toInt()) { openDk(s) })
        head.addView(pill(s, "छोटा करें", 0xFF1E293B.toInt()) { collapse() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(s, 6f) })
        col.addView(head)

        sn.call?.let { c -> col.addView(section(s, callCard(s, c))) }
        sn.live?.let { l -> col.addView(section(s, liveCard(s, l))) }
        sn.media?.let { m -> col.addView(section(s, mediaCard(s, m))) }

        // हाल के काम (1-5)
        col.addView(tv(s, 12f, 0x99FFFFFF.toInt(), bold = true).apply {
            text = UiLang.s(if (sn.tasks.isEmpty()) "हाल के काम: अभी कोई नहीं" else "हाल के काम (${sn.tasks.size})")
            setPadding(px(s, 2f), px(s, 12f), 0, px(s, 4f))
        })
        val list = LinearLayout(s).apply { orientation = LinearLayout.VERTICAL }
        sn.tasks.forEachIndexed { i, t -> list.addView(taskRow(s, i + 1, t)) }
        if (sn.locked.isNotEmpty()) {
            list.addView(tv(s, 12f, 0x99FFFFFF.toInt(), bold = true).apply { text = UiLang.s("🔒 प्राइवेसी से छिपे notification"); setPadding(px(s, 2f), px(s, 12f), 0, px(s, 4f)) })
            sn.locked.forEach { k -> list.addView(lockedRow(s, k)) }
            list.addView(pill(s, "सब हटाएँ", 0xFF1E293B.toInt()) { PrivacyLocked.clear() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(s, 6f) })
        }
        val scroll = ScrollView(s).apply { isVerticalScrollBarEnabled = false; addView(list) }
        val maxH = (s.resources.displayMetrics.heightPixels * 0.5f).roundToInt()
        scroll.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        col.addView(object : FrameLayout(s) {
            override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
        }.apply { addView(scroll) })
        return col
    }

    private fun section(c: Context, v: View) = v.apply {
        background = GradientDrawable().apply { cornerRadius = px(c, 18f).toFloat(); setColor(0xFF111827.toInt()) }
        setPadding(px(c, 12f), px(c, 10f), px(c, 12f), px(c, 10f))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(c, 10f) }
    }

    private fun pill(c: Context, t: String, bg: Int, fg: Int = 0xFFFFFFFF.toInt(), onTap: () -> Unit) = TextView(c).apply {
        text = UiLang.s(t); textSize = 12.5f; setTextColor(fg); gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setPadding(px(c, 12f), px(c, 7f), px(c, 12f), px(c, 7f))
        background = GradientDrawable().apply { cornerRadius = px(c, 16f).toFloat(); setColor(bg) }
        isClickable = true
        setOnClickListener { touchedAt = SystemClock.uptimeMillis(); try { onTap() } catch (e: Exception) { DKLog.d("island बटन: ${e.message}") }; mediaAt = 0L; main.postDelayed({ mediaAt = 0L; poke() }, 400L); poke() }
    }

    private fun buttons(c: Context, vararg v: View) = LinearLayout(c).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL
        v.forEachIndexed { i, b -> addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { if (i > 0) marginStart = px(c, 8f) }) }
    }

    private fun callCard(s: DKAccessibilityService, c: IslandCore.Call): View {
        val row = LinearLayout(s).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(dot(s, "📞", 0xFF22C55E.toInt(), 36f))
        val txt = LinearLayout(s).apply { orientation = LinearLayout.VERTICAL; setPadding(px(s, 10f), 0, px(s, 8f), 0) }
        txt.addView(tv(s, 15f, 0xFFFFFFFF.toInt(), bold = true).apply { text = c.who.ifBlank { UiLang.s("अनजान नंबर") } })
        val sub = tv(s, 12f, 0xFF86EFAC.toInt())
        txt.addView(sub)
        updaters.add { a, t -> a.call?.let { k -> sub.text = UiLang.s((if (k.app.isNotBlank()) "${k.app} कॉल • " else "फ़ोन कॉल • ") + (if (k.ringing) "आ रहा है" else "चल रही है ${IslandCore.timer(t - k.since)}")) } }
        row.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val end = pill(s, "काटें", 0xFFDC2626.toInt()) { s.calls.end() }
        row.addView(if (c.ringing) buttons(s, pill(s, "उठाएँ", 0xFF16A34A.toInt()) { s.calls.answer() }, end) else buttons(s, end))
        return row
    }

    private fun liveCard(s: DKAccessibilityService, l: IslandCore.Live): View {
        val row = LinearLayout(s).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(DKWorkingIndicator(s).apply { state = if (l.muted) WorkState.IDLE else workState(l.mode); layoutParams = LinearLayout.LayoutParams(px(s, 34f), px(s, 34f)) })
        val txt = LinearLayout(s).apply { orientation = LinearLayout.VERTICAL; setPadding(px(s, 10f), 0, px(s, 8f), 0) }
        txt.addView(tv(s, 15f, 0xFFFFFFFF.toInt(), bold = true).apply { text = UiLang.s(if (DKState.live) "DK लाइव" else "DK फ़ोन पर काम") })
        txt.addView(tv(s, 12f, if (l.muted) 0xFFFCA5A5.toInt() else 0xFF93C5FD.toInt(), lines = 2).apply {
            text = UiLang.s(if (l.muted) "म्यूट है - DK अभी आपकी बात नहीं सुन रहा" else IslandCore.modeHi(l.mode)) + DKState.lastUser.takeIf { it.isNotBlank() }?.let { "\n" + UiLang.s("आप: ") + it.take(60) }.orEmpty()
        })
        row.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (DKState.live) row.addView(buttons(s,
            pill(s, if (l.muted) "माइक चालू" else "म्यूट", if (l.muted) 0xFF2563EB.toInt() else 0xFF374151.toInt()) { DKState.setMicMuted(!DKState.micMuted); LiveNotify.sync(s) },
            pill(s, "कॉल काटें", 0xFFDC2626.toInt()) { if (DKState.micMuted) DKState.setMicMuted(false); s.stopLive("island से कॉल काटा") }))
        return row
    }

    private fun mediaCard(s: DKAccessibilityService, m: IslandCore.Media): View {
        val col = LinearLayout(s).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(s).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isClickable = true
            setOnClickListener { touchedAt = SystemClock.uptimeMillis(); openMediaApp(s, m) } }
        val art = mediaArt
        row.addView(if (art != null) ImageView(s).apply { setImageBitmap(art); scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = px(s, 10f).toFloat(); setColor(0xFF222222.toInt()) }
            layoutParams = LinearLayout.LayoutParams(px(s, 48f), px(s, 48f)) } else dot(s, "♪", 0xFFF97316.toInt(), 44f))
        val txt = LinearLayout(s).apply { orientation = LinearLayout.VERTICAL; setPadding(px(s, 10f), 0, px(s, 8f), 0) }
        txt.addView(tv(s, 15f, 0xFFFFFFFF.toInt(), bold = true).apply { text = m.title.ifBlank { m.app } })
        txt.addView(tv(s, 12f, 0xFFFDBA74.toInt()).apply { text = listOf(m.artist, m.app).filter { it.isNotBlank() }.distinct().joinToString(" • ") })
        row.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row)
        val ctl = mediaCtl
        col.addView(buttons(s,
            pill(s, "⏮", 0xFF374151.toInt()) { ctl?.transportControls?.skipToPrevious() },
            pill(s, if (m.playing) "❚❚ रोकें" else "▶ चलाएँ", 0xFFEA580C.toInt()) { if (m.playing) ctl?.transportControls?.pause() else ctl?.transportControls?.play() },
            pill(s, "⏭", 0xFF374151.toInt()) { ctl?.transportControls?.skipToNext() }).apply { setPadding(0, px(s, 8f), 0, 0) })
        return col
    }

    private fun stateColor(t: IslandCore.Task): Int = when {
        t.active && t.state == TaskStatus.State.WAITING.hi -> 0xFFFBBF24.toInt()
        t.active -> 0xFF60A5FA.toInt()
        t.ok -> 0xFF4ADE80.toInt()
        t.state == TaskStatus.State.FAILED.hi -> 0xFFF87171.toInt()
        else -> 0xFFCBD5E1.toInt()
    }

    private fun taskRow(s: DKAccessibilityService, n: Int, t: IslandCore.Task): View {
        val row = LinearLayout(s).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { cornerRadius = px(s, 14f).toFloat(); setColor(0xFF0F172A.toInt()) }
            setPadding(px(s, 10f), px(s, 8f), px(s, 10f), px(s, 8f))
            isClickable = true
            setOnClickListener { touchedAt = SystemClock.uptimeMillis(); openTask(s, t) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(s, 6f) }
        }
        row.addView(dot(s, "$n", stateColor(t), 22f))
        val txt = LinearLayout(s).apply { orientation = LinearLayout.VERTICAL; setPadding(px(s, 10f), 0, 0, 0) }
        val head = tv(s, 13f, stateColor(t), bold = true)
        val body = tv(s, 12f, 0xCCFFFFFF.toInt(), lines = 4)
        txt.addView(head); txt.addView(body)
        updaters.add { _, now -> val (h, b) = IslandCore.taskLine(t.copy(), now); head.text = UiLang.s(h); body.text = b }
        row.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun lockedRow(s: DKAccessibilityService, k: IslandCore.Locked): View = LinearLayout(s).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = GradientDrawable().apply { cornerRadius = px(s, 14f).toFloat(); setColor(0xFF0F172A.toInt()) }
        setPadding(px(s, 10f), px(s, 8f), px(s, 10f), px(s, 8f))
        isClickable = true
        setOnClickListener { touchedAt = SystemClock.uptimeMillis(); PrivacyScreen.open(s, k.pkg); collapse() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(s, 6f) }
        addView(dot(s, "🔒", 0xFF334155.toInt(), 22f))
        addView(tv(s, 13f, 0xFFFFFFFF.toInt(), bold = true).apply { text = UiLang.s("${k.app} • ${k.count} नए • ${IslandCore.ago(System.currentTimeMillis() - k.at)}") },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(s, 10f) })
        addView(tv(s, 12f, 0xFF93C5FD.toInt()).apply { text = UiLang.s("खोलें") })
    }

    // ------------------------------------------------------------ खोलना
    private fun openDk(c: Context) = launch(c, Intent(c, MainActivity::class.java))

    private fun openTask(c: Context, t: IslandCore.Task) {
        val cls = when (t.key) {
            TaskStatus.PC -> ComputerActivity::class.java
            TaskStatus.CODER -> CodingActivity::class.java
            com.dk.assistant.studio.StudioLink.KEY -> com.dk.assistant.studio.StudioActivity::class.java
            else -> MainActivity::class.java
        }
        launch(c, Intent(c, cls))
    }

    private fun openMediaApp(c: Context, m: IslandCore.Media) {
        val pi = mediaCtl?.sessionActivity
        if (pi != null) try { pi.send(); collapse(); return } catch (_: Exception) {}
        c.packageManager.getLaunchIntentForPackage(m.pkg)?.let { launch(c, it) }
    }

    private fun launch(c: Context, i: Intent) {
        try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) } catch (e: Exception) { DKLog.d("island: नहीं खुला: ${e.message}") }
        collapse()
    }
}

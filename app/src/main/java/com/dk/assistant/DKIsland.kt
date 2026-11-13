package com.dk.assistant

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * DK Island - मालिक (12 नवंबर 2026): DK से बाहर हों और DK का कोई काम चले (कोडिंग, DK कंप्यूटर,
 * रिव्यू, फ़ोन का काम, लाइव बात), कॉल आए या कोई गाना/वीडियो बजे, तो स्क्रीन के ऊपर बीच में काली गोली (pill).
 * टैप = फैलकर पूरा हाल: हाल के 1-5 काम (असली कदम), कॉल (उठाएँ / काटें), गाना (पीछे / रोकें-चलाएँ / आगे),
 * DK लाइव (म्यूट / बंद), DK Privacy से छिपे notification. बाहर छूने पर या थोड़ी देर बाद फिर छोटा.
 * 13 नवंबर 2026 (मालिक): फ़ोन के सारे notification ([IslandFeed]: टैप = वहीं खुले, स्लाइड = हटे, आख़िर में "सब साफ़ करें"),
 * चार्जिंग animation (% + कितनी देर में पूरा), रील गिनती (पहले अलग पट्टी) और YouTube / फेसबुक / इंस्टाग्राम का काम भी इसी में.
 * नियम [IslandCore] में. Accessibility overlay (TYPE_ACCESSIBILITY_OVERLAY) - बाकी स्क्रीन का छूना आर-पार.
 */
@SuppressLint("StaticFieldLeak")
object DKIsland {
    private const val P_ON = "island_on"
    private const val P_LIVE = "island_live"
    private const val P_MEDIA = "island_media"
    private const val P_NOTIF = "island_notifs"
    private const val P_CHARGE = "island_charge"
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE)
    fun on(c: Context) = sp(c).getBoolean(P_ON, true)
    fun setOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(P_ON, v).apply(); poke() }
    fun liveOn(c: Context) = sp(c).getBoolean(P_LIVE, true)
    fun setLiveOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(P_LIVE, v).apply(); poke() }
    fun mediaOn(c: Context) = sp(c).getBoolean(P_MEDIA, true)
    fun setMediaOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(P_MEDIA, v).apply(); poke() }
    fun notifsOn(c: Context) = sp(c).getBoolean(P_NOTIF, true)
    fun setNotifsOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(P_NOTIF, v).apply(); poke() }
    fun chargeOn(c: Context) = sp(c).getBoolean(P_CHARGE, true)
    fun setChargeOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(P_CHARGE, v).apply(); poke() }
    // 13 नवंबर 2026 (मालिक): island छोटा-बड़ा, ऊपर से कितनी दूर, बाएँ-दाएँ; नई चीज़ पर थोड़ी देर अपने-आप बड़ा
    private const val P_SIZE = "island_size"
    private const val P_TOP = "island_y"
    private const val P_SIDE = "island_x"
    private const val P_PEEK = "island_peek"
    fun sizePct(c: Context) = IslandCore.size(sp(c).getInt(P_SIZE, IslandCore.SIZE_DEF))
    fun setSizePct(c: Context, v: Int) { sp(c).edit().putInt(P_SIZE, IslandCore.size(v)).apply(); relayout() }
    fun topDp(c: Context) = IslandCore.top(sp(c).getInt(P_TOP, IslandCore.TOP_DEF))
    fun setTopDp(c: Context, v: Int) { sp(c).edit().putInt(P_TOP, IslandCore.top(v)).apply(); relayout() }
    fun sideDp(c: Context) = IslandCore.side(sp(c).getInt(P_SIDE, 0))
    fun setSideDp(c: Context, v: Int) { sp(c).edit().putInt(P_SIDE, IslandCore.side(v)).apply(); relayout() }
    fun peekOn(c: Context) = sp(c).getBoolean(P_PEEK, true)
    fun setPeekOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(P_PEEK, v).apply(); poke() }
    fun resetLook(c: Context) { sp(c).edit().remove(P_SIZE).remove(P_TOP).remove(P_SIDE).apply(); relayout() }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var svc: DKAccessibilityService? = null
    private var wm: WindowManager? = null
    private var root: FrameLayout? = null
    private var lp: WindowManager.LayoutParams? = null
    private var expanded = false
    /** थोड़ी देर का बड़ा रूप (नई सूचना / बजती कॉल / काम पूरा) - छूने पर नहीं, अपने-आप */
    private var peeking = false
    private var peekUntil = 0L
    private var peekNotif: IslandCore.Notif? = null
    private var peekTask: IslandCore.Task? = null
    private val seenNotifs = HashSet<String>()
    private var seenFirst = true
    private val seenDone = HashSet<String>()
    private val ringClosed = HashSet<String>()
    /** फैलाते समय किस चीज़ को सबसे ऊपर रखना है (गोली या बुलबुला जिसे दबाया) */
    private var focus: IslandCore.Item? = null
    /** उँगली अभी island पर है - तब ढाँचा दोबारा न बने (बटन उँगली के नीचे से न हटे = अटकना नहीं) */
    private var fingerDown = false
    /** उँगली रखी है - पर कोई UP छूट जाए तो 5 सेकंड बाद अपने-आप छोड़ो (island कभी हमेशा के लिए न जमे) */
    private fun holding() = fingerDown && SystemClock.uptimeMillis() - touchedAt < 5_000L
    private var previewUntil = 0L
    /** island का आकार (Settings: छोटा-बड़ा) */
    private var k = 1f
    private var bub: FrameLayout? = null
    private var bubLp: WindowManager.LayoutParams? = null
    private var bubSig = ""
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
        try {
            val f = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val sticky = if (Build.VERSION.SDK_INT >= 33) s.registerReceiver(battRx, f, Context.RECEIVER_NOT_EXPORTED) else s.registerReceiver(battRx, f)
            battReg = true
            sticky?.let { onBattery(it, first = true) }
        } catch (e: Exception) { DKLog.d("island बैटरी: ${e.javaClass.simpleName}") }
        poke()
    }

    fun stop() {
        TaskStatus.unlisten(onTask); DKState.unlisten(onState)
        if (battReg) { try { svc?.unregisterReceiver(battRx) } catch (_: Exception) {}; battReg = false }
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

    /** island के अंदर का नाप - Settings वाले आकार (k) से छोटा-बड़ा */
    private fun px(c: Context, dp: Float) = (dp * k * c.resources.displayMetrics.density).roundToInt()
    /** सिर्फ़ जगह (ऊपर से दूरी / बाएँ-दाएँ) - आकार से नहीं बदलती */
    private fun dpx(c: Context, dp: Float) = (dp * c.resources.displayMetrics.density).roundToInt()

    /** Settings में आकार / जगह बदली = चलता island अभी नए नाप में (बंद-चालू किए बिना) */
    fun relayout() {
        main.post {
            val s = svc ?: return@post
            k = sizePct(s) / 100f
            lp?.let { p -> p.y = dpx(s, topDp(s).toFloat()); p.x = dpx(s, sideDp(s).toFloat()); root?.let { r -> try { wm?.updateViewLayout(r, p) } catch (_: Exception) {} } }
            (root?.background as? GradientDrawable)?.cornerRadius = px(s, if (expanded || peeking) 30f else 22f).toFloat()
            lastSig = ""; bubSig = ""
            poke()
        }
    }

    /** Settings में "नमूना देखें": कुछ सेकंड island दिखे (DK खुला हो तब भी) ताकि आकार / दूरी देखकर सेट करें */
    fun preview() { previewUntil = SystemClock.uptimeMillis() + 6_000L; relayout() }

    // ------------------------------------------------------------ रील गिनती (ReelWatch) - पहले अलग पट्टी, अब island में
    @Volatile private var reel: IslandCore.Reel? = null
    /** island चालू और लगा है = रील की गिनती यहीं दिखे (ReelWatch अपनी पट्टी नहीं लगाता) */
    fun takesReel(c: Context): Boolean = svc != null && on(c)
    fun reel(text: String, warn: Boolean) {
        val o = reel
        reel = IslandCore.Reel(text, warn, System.currentTimeMillis())
        if (o?.text != text || o.warn != warn) poke() else if (root == null) poke()
    }
    fun reelOff() { if (reel != null) { reel = null; poke() } }

    // ------------------------------------------------------------ बैटरी / चार्जिंग
    @Volatile private var batt: IslandCore.Charge? = null
    private var battReg = false
    private var plugged = false
    private val battSamples = ArrayList<Pair<Long, Int>>()
    private val battRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { onBattery(i, first = false) }
    }

    private fun onBattery(i: Intent, first: Boolean) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return
        val pct = (level * 100f / scale).roundToInt().coerceIn(0, 100)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plug = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val now = System.currentTimeMillis()
        val isPlugged = plug != 0
        val full = isPlugged && (status == BatteryManager.BATTERY_STATUS_FULL || pct >= 100)
        val old = batt
        // चार्जर अभी लगा (DK शुरू होते समय पहले से लगा हो तो animation नहीं)
        val plugAt = when {
            !isPlugged -> 0L
            !plugged && !first -> now
            else -> old?.plugAt ?: 0L
        }
        if (isPlugged && !plugged) synchronized(battSamples) { battSamples.clear() }
        if (isPlugged) synchronized(battSamples) {
            if (battSamples.lastOrNull()?.second != pct) battSamples.add(now to pct)
            while (battSamples.size > 40) battSamples.removeAt(0)
        }
        plugged = isPlugged
        batt = IslandCore.Charge(pct, isPlugged, full, plugAt, old?.fullInMs ?: -1L, IslandCore.chargeSource(plug))
        if (old == null || old.pct != pct || old.charging != isPlugged || old.full != full) poke()
    }

    /** पूरा होने में कितना: Android (9+) का अपना अंदाज़ा, नहीं तो % बढ़ने की असली रफ़्तार से; पता नहीं = -1 */
    private fun chargeNow(c: Context): IslandCore.Charge? {
        val b = batt ?: return null
        if (!b.charging) return b
        var ms = -1L
        if (Build.VERSION.SDK_INT >= 28) try { ms = c.getSystemService(BatteryManager::class.java)?.computeChargeTimeRemaining() ?: -1L } catch (_: Exception) {}
        if (ms < 0) ms = synchronized(battSamples) { IslandCore.estimateFullMs(battSamples.toList(), b.pct) }
        return b.copy(fullInMs = if (b.full) 0L else ms)
    }

    // ------------------------------------------------------------ हालत इकट्ठा करना (असली)
    private fun snap(s: DKAccessibilityService, now: Long): IslandCore.Snap {
        // कॉल: SIM या WhatsApp/Instagram… वाली
        // 13 नवंबर 2026 (मालिक): 2 कॉल = अलग-अलग (SIM + WhatsApp, या दो ऐप कॉल) - हर कॉल का अपना नाम, समय, बटन
        val calls = try {
            val cm = s.calls
            val apps = AppCall.all(now).map { r -> IslandCore.Call(r.who, r.state == AppCall.State.RINGING, r.since, r.app, r.pkg, r.key, r.video) }
            val offhook = cm.state == android.telephony.TelephonyManager.CALL_STATE_OFFHOOK
            val simOn = offhook || (cm.simRinging && (cm.known || apps.isEmpty()))
            val sim = if (simOn) {
                if (offhook && callSince == 0L) callSince = now
                if (!offhook) callSince = 0L
                IslandCore.Call(cm.simWho, !offhook, if (callSince > 0) callSince else now, "", "", "sim")
            } else { callSince = 0L; null }
            IslandCore.mergeCalls(sim, apps)
        } catch (_: Exception) { emptyList() }
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
        // 13 नवंबर 2026: YouTube / फेसबुक / इंस्टाग्राम / WhatsApp का काम (SocialAgent) भी island के काम में
        val social = try {
            com.dk.assistant.agent.android.SocialAgent.lastReport?.takeIf { com.dk.assistant.agent.android.SocialAgent.startedAt > 0 }?.let { r ->
                IslandCore.fromSocial(r.taskId, r.platform.label, r.action.name, r.status.name, r.message, r.step, r.totalSteps,
                    com.dk.assistant.agent.android.SocialAgent.startedAt, com.dk.assistant.agent.android.SocialAgent.endedAt)
            }
        } catch (_: Exception) { null }
        val allTasks = if (social == null) tasks else IslandCore.recent((tasks + social).filter { it.active }, (tasks + social).filter { !it.active })
        val notifs = if (notifsOn(s)) IslandFeed.list() else emptyList()
        val charge = if (chargeOn(s)) chargeNow(s) else null
        val all = IslandCore.Snap(calls.firstOrNull(), live, allTasks, media, PrivacyLocked.list(), notifs, charge, reel?.takeIf { IslandCore.reelOn(it, now) }, calls)
        // 13 नवंबर 2026 (मालिक): सिर्फ़ पीछे चलती चीज़ें - जो ऐप सामने खुला है उसका गाना / वीडियो / notification / कॉल island में नहीं
        return IslandCore.background(all, frontPkg(s))
    }

    /** अभी सामने वाला ऐप. DK का अपना island / overlay सामने आए तो पिछला ऐप ही माना जाए */
    private var lastFront = ""
    private fun frontPkg(s: DKAccessibilityService): String {
        val p = s.currentPackage()
        if (p.isNotBlank() && p != s.packageName) lastFront = p
        return lastFront
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
        val up = SystemClock.uptimeMillis()
        val sn = snap(s, now)
        val demo = up < previewUntil && on(s)
        if (!demo && !IslandCore.wanted(on(s), AppVisible.visible, sn, now)) {
            // छिपे रहते भी देखे notification याद रहें - बाद में island आए तो पुराना notification "नया" बनकर न उछले
            seenFirst = false; seenNotifs.clear(); sn.notifs.forEach { seenNotifs.add(it.key) }
            remove(); main.postDelayed(ticker, 1_500L); return
        }
        if (root == null) show(s)
        // अपने-आप छोटा: कॉल चलते समय भी (पहले कॉल में कभी छोटा नहीं होता था = island अटका रहता था). बजती कॉल = बड़ा रहे
        if (IslandCore.autoCollapse(expanded, up - touchedAt, holding(), sn)) collapse()
        if (!expanded) updatePeek(s, sn, now, up)
        render(s, sn, now)
        renderBubble(s, sn, now)
        main.postDelayed(ticker, 1_000L)
    }

    /** थोड़ी देर का बड़ा रूप: बजती कॉल (जब तक बजे), नई सूचना / काम पूरा (कुछ सेकंड) - फिर अपने-आप छोटा */
    private fun updatePeek(s: DKAccessibilityService, sn: IslandCore.Snap, now: Long, up: Long) {
        val calls = IslandCore.allCalls(sn)
        ringClosed.retainAll(calls.filter { it.ringing }.map { it.key.ifBlank { it.who } }.toSet())
        val ring = calls.any { it.ringing && it.key.ifBlank { it.who } !in ringClosed }
        val fresh = IslandCore.freshNotif(seenNotifs, sn.notifs, seenFirst)
        seenFirst = false
        seenNotifs.clear(); sn.notifs.forEach { seenNotifs.add(it.key) }
        val done = sn.tasks.firstOrNull { IslandCore.justEnded(it, now) && it.id !in seenDone }
        if (seenDone.size > 300) seenDone.clear()
        sn.tasks.filter { !it.active }.forEach { seenDone.add(it.id) }
        if (peekOn(s) && !holding()) {
            if (fresh != null && !ring) { peekNotif = fresh; peekTask = null; peekUntil = up + IslandCore.PEEK_MS }
            else if (done != null && !ring) { peekTask = done; peekNotif = null; peekUntil = up + IslandCore.PEEK_MS }
        }
        val want = ring || (peekOn(s) && up < peekUntil && (peekNotif != null || peekTask != null))
        if (want != peeking) { if (want) setWide(s, true) else setWide(s, false); peeking = want; lastSig = "" }
        if (!want) { peekNotif = null; peekTask = null }
    }

    // ------------------------------------------------------------ खिड़की
    @SuppressLint("ClickableViewAccessibility")
    private fun show(s: DKAccessibilityService) {
        val w = wm ?: return
        k = sizePct(s) / 100f
        val slop = ViewConfiguration.get(s).scaledTouchSlop
        var downY = 0f
        // कोई भी छुअन (स्क्रॉल भी) = अभी पढ़ रहे हैं, अपने-आप छोटा न हो; उँगली रहते ढाँचा न बदले
        val box = object : FrameLayout(s) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                // बाहर (दूसरे ऐप) का छूना island का छूना नहीं - वरना island कभी ताज़ा न होता
                if (ev.actionMasked != MotionEvent.ACTION_OUTSIDE) touchedAt = SystemClock.uptimeMillis()
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { fingerDown = true; downY = ev.rawY }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        fingerDown = false
                        // ऊपर की ओर खींचा = छोटा (थोड़ी देर वाला बड़ा रूप; फैले island में ऊपर खींचना = स्क्रॉल)
                        if (ev.actionMasked == MotionEvent.ACTION_UP && downY - ev.rawY > slop * 3 && peeking) {
                            main.post { closeBig() }; return true
                        }
                    }
                    MotionEvent.ACTION_OUTSIDE -> fingerDown = false
                }
                return super.dispatchTouchEvent(ev)
            }
        }.apply {
            background = GradientDrawable().apply { setColor(0xFF000000.toInt()); cornerRadius = px(s, 22f).toFloat(); setStroke(dpx(s, 1f), 0x22FFFFFF) }
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
            y = dpx(s, topDp(s).toFloat()); x = dpx(s, sideDp(s).toFloat())
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        box.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) { if (expanded || peeking) closeBig(); true } else false
        }
        // टैप = उसी चीज़ का ऐप / स्क्रीन (कॉल = कॉल स्क्रीन, गाना = गाने वाला ऐप, काम = वह स्क्रीन); देर तक दबाएँ = फैलकर पूरा
        box.setOnClickListener {
            touchedAt = SystemClock.uptimeMillis()
            if (expanded || peeking) { closeBig(); return@setOnClickListener }
            val now = System.currentTimeMillis()
            val sn = svc?.let { snap(it, now) } ?: return@setOnClickListener
            val first = IslandCore.items(sn, now).firstOrNull()
            if (first == null || !openItem(s, sn, first)) expand(first)
        }
        box.setOnLongClickListener {
            touchedAt = SystemClock.uptimeMillis()
            if (!expanded) { val now = System.currentTimeMillis(); expand(svc?.let { IslandCore.items(snap(it, now), now).firstOrNull() }) }
            true
        }
        // गोली की चौड़ाई बदली = बुलबुला उसके साथ-साथ खिसके
        box.addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ -> if (r - l > 0) placeBubble(s, v.width) }
        // आते समय: छोटी बूँद से फैलकर
        box.alpha = 0f; box.scaleX = 0.5f; box.scaleY = 0.6f
        try { w.addView(box, p) } catch (e: Exception) { DKLog.d("island नहीं लगा: ${e.javaClass.simpleName}"); return }
        root = box; lp = p; expanded = false; peeking = false; fingerDown = false; lastSig = ""
        box.post { box.pivotX = box.width / 2f; box.pivotY = 0f
            box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(1.2f)).start() }
    }

    private fun remove() {
        removeBubble()
        val r = root ?: return
        root = null; lp = null; expanded = false; peeking = false; fingerDown = false; focus = null; lastSig = ""; updaters.clear()
        r.animate().alpha(0f).scaleX(0.5f).scaleY(0.6f).setDuration(200).withEndAction { try { wm?.removeView(r) } catch (_: Exception) {} }.start()
    }

    /** चौड़ा (फैला / थोड़ी देर का बड़ा) या वापस गोली */
    private fun setWide(s: DKAccessibilityService, wide: Boolean) {
        val r = root ?: return; val p = lp ?: return
        p.width = if (wide) s.resources.displayMetrics.widthPixels - dpx(s, 16f) else WindowManager.LayoutParams.WRAP_CONTENT
        // चौड़ा island बीच में; बाएँ-दाएँ खिसकाना सिर्फ़ गोली पर
        p.x = if (wide) 0 else dpx(s, sideDp(s).toFloat())
        try { wm?.updateViewLayout(r, p) } catch (_: Exception) {}
        (r.background as? GradientDrawable)?.cornerRadius = px(s, if (wide) 30f else 22f).toFloat()
        if (wide) removeBubble()
    }

    private fun expand(f: IslandCore.Item? = null) {
        val r = root ?: return; val s = svc ?: return
        focus = f
        expanded = true; peeking = false; peekUntil = 0L; touchedAt = SystemClock.uptimeMillis(); lastSig = ""
        setWide(s, true)
        historyAt = 0L
        tick()
        r.pivotX = (lp?.width ?: r.width) / 2f; r.pivotY = 0f
        r.scaleX = 0.55f; r.scaleY = 0.35f; r.alpha = 0.6f
        r.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(340).setInterpolator(OvershootInterpolator(0.9f)).start()
    }

    /** बाहर छुआ / ऊपर खींचा: थोड़ी देर वाला बड़ा रूप बंद (बजती कॉल भी - वह कॉल गोली में रहे), फैला island छोटा */
    private fun closeBig() = collapse()

    private fun collapse() {
        val r = root ?: return; val s = svc ?: return
        val was = expanded || peeking
        // थोड़ी देर वाला बड़ा रूप बंद = वही दोबारा न उछले (बजती कॉल गोली में रहे, कटने / उठने तक)
        if (peeking) IslandCore.allCalls(snap(s, System.currentTimeMillis())).filter { it.ringing }.forEach { ringClosed.add(it.key.ifBlank { it.who }) }
        peekUntil = 0L; peekNotif = null; peekTask = null
        expanded = false; peeking = false; focus = null; fingerDown = false; lastSig = ""
        setWide(s, false)
        tick()
        if (was) {
            r.pivotY = 0f; r.scaleX = 1.25f; r.scaleY = 1.1f
            r.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(1.1f)).start()
        }
    }

    // ------------------------------------------------------------ अलग बुलबुला (दूसरी चीज़ - जैसे दूसरी कॉल)
    private fun removeBubble() {
        val b = bub ?: return
        bub = null; bubLp = null; bubSig = ""
        b.animate().alpha(0f).scaleX(0.4f).scaleY(0.4f).setDuration(160).withEndAction { try { wm?.removeView(b) } catch (_: Exception) {} }.start()
    }

    private fun placeBubble(s: DKAccessibilityService, mainW: Int) {
        val b = bub ?: return; val p = bubLp ?: return
        val bw = b.width.takeIf { it > 0 } ?: px(s, 34f)
        val nx = IslandCore.bubbleX(dpx(s, sideDp(s).toFloat()), mainW, px(s, 6f), bw)
        if (p.x != nx || p.y != (lp?.y ?: p.y)) { p.x = nx; p.y = lp?.y ?: p.y; try { wm?.updateViewLayout(b, p) } catch (_: Exception) {} }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun renderBubble(s: DKAccessibilityService, sn: IslandCore.Snap, now: Long) {
        val it2 = if (expanded || peeking || root == null) null else IslandCore.bubble(sn, now)
        if (it2 == null) { removeBubble(); return }
        val calls = IslandCore.allCalls(sn)
        val c2 = if (it2.kind == IslandCore.Kind.CALL) calls.getOrNull(it2.idx) else null
        val sig = "${it2.kind}|${c2?.key}|${c2?.ringing}|${if (it2.kind == IslandCore.Kind.NOTIF) sn.notifs.first().pkg else ""}|${sn.live?.mode}|${sn.live?.muted}|$k"
        val w = wm ?: return
        if (bub == null) {
            val box = FrameLayout(s).apply {
                background = GradientDrawable().apply { setColor(0xFF000000.toInt()); cornerRadius = px(s, 17f).toFloat(); setStroke(dpx(s, 1f), 0x22FFFFFF) }
                elevation = px(s, 8f).toFloat(); minimumWidth = px(s, 34f); minimumHeight = px(s, 34f)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = lp?.y ?: dpx(s, topDp(s).toFloat())
                x = IslandCore.bubbleX(dpx(s, sideDp(s).toFloat()), root?.width ?: px(s, 150f), px(s, 6f), px(s, 34f))
                if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            box.setOnTouchListener { _, ev ->
                touchedAt = SystemClock.uptimeMillis()
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> fingerDown = true
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> fingerDown = false
                }
                false
            }
            box.alpha = 0f; box.scaleX = 0.4f; box.scaleY = 0.4f
            try { w.addView(box, p) } catch (e: Exception) { DKLog.d("island बुलबुला नहीं लगा: ${e.javaClass.simpleName}"); return }
            bub = box; bubLp = p; bubSig = ""
            box.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> root?.let { r -> placeBubble(s, r.width) } }
            box.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(1.4f)).start()
        }
        val b = bub ?: return
        // टैप = उसी चीज़ का ऐप (दूसरी कॉल = उसकी कॉल स्क्रीन); देर तक दबाएँ = फैलकर, यही चीज़ सबसे ऊपर
        b.setOnClickListener { touchedAt = SystemClock.uptimeMillis(); val n = System.currentTimeMillis(); val x = svc?.let { snap(it, n) }
            val item = x?.let { IslandCore.bubble(it, n) }; if (x == null || item == null || !openItem(s, x, item)) expand(item) }
        b.setOnLongClickListener { touchedAt = SystemClock.uptimeMillis(); val n = System.currentTimeMillis(); expand(svc?.let { IslandCore.bubble(snap(it, n), n) }); true }
        if (sig != bubSig && !holding()) {
            bubSig = sig; b.removeAllViews()
            val row = LinearLayout(s).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(px(s, 5f), px(s, 5f), px(s, 5f), px(s, 5f)) }
            row.addView(itemIcon(s, sn, it2, 24f))
            if (c2 != null) {
                val t = tv(s, 11f, 0xFF4ADE80.toInt(), bold = true)
                row.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(s, 5f); marginEnd = px(s, 4f) })
                b.tag = t
            } else b.tag = null
            b.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            b.contentDescription = UiLang.s(c2?.let { "दूसरी कॉल: " + IslandCore.callText(it) } ?: "DK Island - दूसरी चीज़")
        }
        (b.tag as? TextView)?.let { t -> c2?.let { t.text = UiLang.s(IslandCore.callRight(it, now)) } }
    }

    /** किसी चीज़ का छोटा निशान (गोली / बुलबुला) */
    private fun itemIcon(s: DKAccessibilityService, sn: IslandCore.Snap, it: IslandCore.Item, dp: Float): View = when (it.kind) {
        IslandCore.Kind.CALL -> dot(s, if (IslandCore.allCalls(sn).getOrNull(it.idx)?.video == true) "🎥" else "📞", 0xFF22C55E.toInt(), dp)
        IslandCore.Kind.MEDIA -> mediaArt?.let { b -> ImageView(s).apply { setImageBitmap(b); scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true; background = GradientDrawable().apply { cornerRadius = px(s, 6f).toFloat(); setColor(0xFF222222.toInt()) }
            layoutParams = LinearLayout.LayoutParams(px(s, dp), px(s, dp)) } } ?: dot(s, "♪", 0xFFF97316.toInt(), dp)
        IslandCore.Kind.LOCKED -> dot(s, "🔒", 0xFF334155.toInt(), dp)
        IslandCore.Kind.CHARGING -> dot(s, "⚡", 0xFF16A34A.toInt(), dp)
        IslandCore.Kind.REEL -> dot(s, "🎬", if (sn.reel?.warn == true) 0xFFDC2626.toInt() else 0xFF4F46E5.toInt(), dp)
        IslandCore.Kind.NOTIF -> appIcon(s, sn.notifs.first().pkg, dp)
        else -> DKWorkingIndicator(s).apply {
            state = if (it.kind == IslandCore.Kind.LIVE) sn.live?.let { l -> if (l.muted) WorkState.IDLE else workState(l.mode) } ?: WorkState.IDLE
                else if (sn.tasks.any { t -> t.active }) WorkState.WORKING else WorkState.IDLE
            layoutParams = LinearLayout.LayoutParams(px(s, dp), px(s, dp))
        }
    }

    /** छोटी गोली / बुलबुले पर टैप: उसी चीज़ का ऐप या स्क्रीन. false = उसका अपना ऐप नहीं (तो island फैले) */
    private fun openItem(s: DKAccessibilityService, sn: IslandCore.Snap, it: IslandCore.Item): Boolean {
        if (!IslandCore.tapOpens(it.kind)) return false
        val now = System.currentTimeMillis()
        return when (it.kind) {
            IslandCore.Kind.CALL -> IslandCore.allCalls(sn).getOrNull(it.idx)?.let { c -> openCall(s, c) } ?: false
            IslandCore.Kind.MEDIA -> sn.media?.let { m -> openMediaApp(s, m); true } ?: false
            IslandCore.Kind.TASK -> (sn.tasks.firstOrNull { t -> t.active } ?: sn.tasks.firstOrNull { t -> IslandCore.justEnded(t, now) })?.let { t -> openTask(s, t); true } ?: false
            IslandCore.Kind.LIVE -> { openDk(s); true }
            IslandCore.Kind.NOTIF -> sn.notifs.firstOrNull()?.let { n -> IslandFeed.open(s, n.key).also { ok -> if (ok) collapse() } } ?: false
            IslandCore.Kind.LOCKED -> sn.locked.singleOrNull()?.let { l -> PrivacyScreen.open(s, l.pkg); collapse(); true } ?: false
            else -> false
        }
    }

    // ------------------------------------------------------------ बनाना
    /** ढाँचा तभी दोबारा जब कुछ असली बदले (सिर्फ़ समय बदले तो [updaters] वही लाइनें बदलते हैं) */
    private fun render(s: DKAccessibilityService, sn: IslandCore.Snap, now: Long) {
        val r = root ?: return
        val mode = if (expanded) "E|" else if (peeking) "P|" else "C|"
        val sig = mode + sig(sn, now) + "|b${IslandCore.bubble(sn, now)}|f$focus|pk${peekNotif?.key}${peekTask?.id}|k$k"
        // छूते समय / छूने के ठीक बाद ढाँचा न बदले (वरना बटन उँगली के नीचे से हट जाता था, स्क्रॉल ऊपर कूदता था = "अटक गया").
        // खुलना / बंद होना (mode बदला) हमेशा तुरंत.
        val hold = holding() || SystemClock.uptimeMillis() - touchedAt < IslandCore.TOUCH_HOLD_MS
        val sameMode = lastSig.startsWith(mode)
        if (sig != lastSig && !(hold && sameMode && r.childCount > 0)) {
            val sy = (r.findViewWithTag<ScrollView>("island_scroll"))?.scrollY ?: 0
            lastSig = sig; updaters.clear(); r.removeAllViews()
            r.addView(when { expanded -> big(s, sn, now); peeking -> peekView(s, sn, now); else -> small(s, sn, now) })
            if (sameMode && sy > 0) r.findViewWithTag<ScrollView>("island_scroll")?.let { v -> v.post { v.scrollTo(0, sy) } }
        }
        updaters.forEach { try { it(sn, now) } catch (_: Exception) {} }
    }

    private fun sig(sn: IslandCore.Snap, now: Long): String = buildString {
        append(IslandCore.lead(sn, now)); append('|')
        IslandCore.allCalls(sn).forEach { append("c${it.key}${it.who}${it.ringing}${it.video}") }; append('|')
        sn.live?.let { append("l${it.mode}${it.muted}") }; append('|')
        sn.tasks.forEach { append("t${it.id}${it.state}${it.steps}${it.last.hashCode()}${IslandCore.justEnded(it, now)}") }; append('|')
        sn.media?.let { append("m${it.title}${it.artist}${it.playing}") }; append('|')
        sn.locked.forEach { append("k${it.pkg}${it.count}") }; append('|')
        // समय (at) नहीं - download / progress वाले notification हर सेकंड बदलते थे = island हर सेकंड नया बनता (अटकता)
        sn.notifs.forEach { append("n${it.key}${it.title.hashCode()}${it.text.hashCode()}") }; append('|')
        sn.charge?.let { append("b${it.pct}${it.charging}${it.full}${IslandCore.chargePop(it, now)}") }; append('|')
        if (sn.reel != null) append("r${sn.reel.warn}")
    }

    private fun tv(c: Context, size: Float, color: Int, bold: Boolean = false, lines: Int = 1) = TextView(c).apply {
        textSize = size * k; setTextColor(color); maxLines = lines; ellipsize = TextUtils.TruncateAt.END
        if (bold) setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = false
    }

    private fun dot(c: Context, glyph: String, bg: Int, dp: Float = 24f) = TextView(c).apply {
        text = glyph; gravity = Gravity.CENTER; textSize = dp * 0.5f * k; setTextColor(0xFFFFFFFF.toInt())
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(bg) }
        layoutParams = LinearLayout.LayoutParams(px(c, dp), px(c, dp))
    }

    private fun workState(m: Mode) = when (m) {
        Mode.LISTENING -> WorkState.LISTENING; Mode.THINKING -> WorkState.THINKING
        Mode.SPEAKING -> WorkState.SPEAKING; Mode.WORKING -> WorkState.WORKING; Mode.IDLE -> WorkState.IDLE
    }

    /** छोटा island: [निशान] लाइन ............ दाईं ओर (समय / कदम / +2). दूसरी चीज़ अलग बुलबुले में */
    private fun small(s: DKAccessibilityService, sn: IslandCore.Snap, now: Long): View {
        val row = LinearLayout(s).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(px(s, 8f), px(s, 6f), px(s, 14f), px(s, 6f))
            minimumWidth = px(s, 150f)
        }
        val first = IslandCore.items(sn, now).firstOrNull()
        if (first == null) {
            // Settings का "नमूना देखें" - अभी दिखाने को कुछ नहीं, बस आकार / जगह दिखे
            row.addView(dot(s, "DK", 0xFF4F46E5.toInt()))
            row.addView(tv(s, 13f, 0xFFFFFFFF.toInt(), bold = true).apply { text = "DK Island" },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(s, 8f) })
            row.addView(tv(s, 12f, 0xFFA5B4FC.toInt()).apply { text = UiLang.s("नमूना") },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(s, 10f) })
            return row
        }
        if (first.kind == IslandCore.Kind.CHARGING) return chargeSmall(s, sn, now)
        row.addView(itemIcon(s, sn, first, 24f))
        val left = tv(s, 13f, 0xFFFFFFFF.toInt(), bold = true).apply { maxWidth = px(s, 170f) }
        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(s, 8f) })
        val right = tv(s, 12f, rightColor(sn, now)).apply { gravity = Gravity.END }
        row.addView(right, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(s, 10f) })
        if (first.kind == IslandCore.Kind.REEL) { left.maxLines = 2; left.maxWidth = px(s, 230f) }
        updaters.add { a, t ->
            val nt = UiLang.s(IslandCore.compactText(a, t))
            // रील गिनती बढ़ी = छोटा "पॉप" (पहले वाली पट्टी जैसा, अब island में)
            if (IslandCore.lead(a, t) == IslandCore.Kind.REEL && left.text.isNotEmpty() && left.text.toString() != nt) {
                left.pivotX = 0f; left.pivotY = left.height / 2f
                left.scaleX = 1.22f; left.scaleY = 1.22f
                left.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(2f)).start()
            }
            if (left.text.toString() != nt) left.text = nt
            right.text = UiLang.s(IslandCore.compactRightWith(a, t, IslandCore.bubble(a, t) != null))
        }
        row.contentDescription = UiLang.s("DK Island - टैप = खोलें, दबाकर रखें = पूरा देखें")
        return row
    }

    /** थोड़ी देर का बड़ा रूप: बजती कॉल (हर कॉल के उठाएँ / काटें) या नई सूचना / पूरा हुआ काम - ऊपर खींचो या बाहर छुओ = छोटा */
    private fun peekView(s: DKAccessibilityService, sn: IslandCore.Snap, now: Long): View {
        val col = LinearLayout(s).apply { orientation = LinearLayout.VERTICAL; setPadding(px(s, 12f), px(s, 10f), px(s, 12f), px(s, 10f)) }
        val ringing = IslandCore.allCalls(sn).filter { it.ringing && it.key.ifBlank { it.who } !in ringClosed }
        if (ringing.isNotEmpty()) {
            ringing.forEach { c -> col.addView(section(s, callCard(s, c)).apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = if (col.childCount == 0) 0 else px(s, 8f) }) }
            // पहले से चलती कॉल भी दिखे (दूसरी कॉल आई = पहली कहाँ है पता रहे)
            IslandCore.allCalls(sn).filter { !it.ringing }.forEach { c -> col.addView(section(s, callCard(s, c))) }
            return col
        }
        peekNotif?.let { n -> col.addView(notifRow(s, n).apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = 0 }); return col }
        peekTask?.let { t -> col.addView(taskRow(s, 1, t).apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = 0 }); return col }
        return small(s, sn, now)
    }

    private fun rightColor(sn: IslandCore.Snap, now: Long): Int = when (IslandCore.lead(sn, now)) {
        IslandCore.Kind.CALL -> 0xFF4ADE80.toInt()
        IslandCore.Kind.LIVE -> if (sn.live?.muted == true) 0xFFF87171.toInt() else 0xFF60A5FA.toInt()
        IslandCore.Kind.MEDIA -> 0xFFFDBA74.toInt()
        IslandCore.Kind.NOTIF -> 0xFFFDE68A.toInt()
        IslandCore.Kind.REEL -> if (sn.reel?.warn == true) 0xFFFCA5A5.toInt() else 0xFFA5B4FC.toInt()
        else -> 0xFFA5B4FC.toInt()
    }

    /** ऐप का अपना icon (cache) - न मिले तो 🔔 */
    private val icons = HashMap<String, Drawable?>()
    private fun appIcon(c: Context, pkg: String, dp: Float): View {
        val d = icons.getOrPut(pkg) { try { c.packageManager.getApplicationIcon(pkg) } catch (_: Exception) { null } }
        return if (d != null) ImageView(c).apply { setImageDrawable(d); scaleType = ImageView.ScaleType.FIT_CENTER; layoutParams = LinearLayout.LayoutParams(px(c, dp), px(c, dp)) }
        else dot(c, "🔔", 0xFF334155.toInt(), dp)
    }

    /** छोटा island जब चार्जिंग सबसे ऊपर: चार्जर लगते ही बड़ा animation (बैटरी भरती हुई + % + कितनी देर), फिर छोटा "⚡ 72%" */
    private fun chargeSmall(s: DKAccessibilityService, sn: IslandCore.Snap, now: Long): View {
        val c = sn.charge!!
        val pop = IslandCore.chargePop(c, now)
        val row = LinearLayout(s).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            if (pop) setPadding(px(s, 14f), px(s, 12f), px(s, 18f), px(s, 12f)) else setPadding(px(s, 10f), px(s, 6f), px(s, 14f), px(s, 6f))
            minimumWidth = px(s, if (pop) 260f else 150f)
        }
        val bv = BatteryView(s).apply { pct = c.pct; charging = c.charging && !c.full
            layoutParams = LinearLayout.LayoutParams(px(s, if (pop) 54f else 30f), px(s, if (pop) 26f else 15f)) }
        row.addView(bv)
        val txt = LinearLayout(s).apply { orientation = LinearLayout.VERTICAL }
        val big = tv(s, if (pop) 17f else 13f, 0xFFFFFFFF.toInt(), bold = true)
        val sub = tv(s, 12f, 0xFF86EFAC.toInt())
        txt.addView(big); if (pop) txt.addView(sub, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(s, 3f) })
        row.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(s, 10f) })
        val right = tv(s, 12f, 0xFF86EFAC.toInt()).apply { gravity = Gravity.END }
        if (!pop) row.addView(right, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(s, 10f) })
        updaters.add { a, t -> a.charge?.let { k ->
            bv.pct = k.pct; bv.charging = k.charging && !k.full
            big.text = if (pop) UiLang.s(if (k.full) "पूरा चार्ज ${k.pct}%" else "चार्ज हो रहा है ${k.pct}%") else "⚡ ${k.pct}%"
            sub.text = UiLang.s(IslandCore.chargeLeft(k.fullInMs, k.full))
            right.text = UiLang.s(IslandCore.compactRightWith(a, t, IslandCore.bubble(a, t) != null))
        } }
        row.contentDescription = UiLang.s("DK Island - चार्जिंग")
        return row
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
        head.addView(tv(s, 14f, 0xFFFFFFFF.toInt(), bold = true).apply { text = "DK Island" },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(s, 8f) })
        head.addView(pill(s, "DK खोलें", 0xFF1E293B.toInt()) { openDk(s) })
        head.addView(pill(s, "छोटा करें", 0xFF1E293B.toInt()) { collapse() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(s, 6f) })
        col.addView(head)

        // जिसे दबाकर खोला (गोली या बुलबुला) वह सबसे ऊपर; हर कॉल का अलग कार्ड (अपने उठाएँ / काटें)
        val f = focus
        val calls = IslandCore.allCalls(sn).let { all -> if (f != null && f.kind == IslandCore.Kind.CALL && f.idx in all.indices) listOf(all[f.idx]) + all.filterIndexed { i, _ -> i != f.idx } else all }
        val tops: List<Pair<IslandCore.Kind, () -> Unit>> = listOf(
            Pair(IslandCore.Kind.CALL, fun() { calls.forEach { c -> col.addView(section(s, callCard(s, c))) } }),
            Pair(IslandCore.Kind.CHARGING, fun() { sn.charge?.takeIf { it.charging }?.let { b -> col.addView(section(s, chargeCard(s, b))) } }),
            Pair(IslandCore.Kind.LIVE, fun() { sn.live?.let { l -> col.addView(section(s, liveCard(s, l))) } }),
            Pair(IslandCore.Kind.REEL, fun() { sn.reel?.let { r -> col.addView(section(s, reelCard(s, r))) } }),
            Pair(IslandCore.Kind.MEDIA, fun() { sn.media?.let { m -> col.addView(section(s, mediaCard(s, m))) } }),
        )
        tops.sortedBy { if (it.first == f?.kind) 0 else 1 }.forEach { it.second() }

        // हाल के काम (1-5)
        col.addView(tv(s, 12f, 0x99FFFFFF.toInt(), bold = true).apply {
            text = UiLang.s(if (sn.tasks.isEmpty()) "हाल के काम: अभी कोई नहीं" else "हाल के काम (${sn.tasks.size})")
            setPadding(px(s, 2f), px(s, 12f), 0, px(s, 4f))
        })
        val list = LinearLayout(s).apply { orientation = LinearLayout.VERTICAL }
        sn.tasks.forEachIndexed { i, t -> list.addView(taskRow(s, i + 1, t)) }
        if (sn.locked.isNotEmpty()) {
            list.addView(tv(s, 12f, 0x99FFFFFF.toInt(), bold = true).apply { text = UiLang.s("🔒 DK Privacy से छिपे notification"); setPadding(px(s, 2f), px(s, 12f), 0, px(s, 4f)) })
            sn.locked.forEach { k -> list.addView(lockedRow(s, k)) }
            list.addView(pill(s, "सब हटाएँ", 0xFF1E293B.toInt()) { PrivacyLocked.clear() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(s, 6f) })
        }
        // 13 नवंबर 2026 (मालिक): फ़ोन के सारे notification - टैप = वहीं खुले, स्लाइड = हटे, आख़िर में "सब साफ़ करें"
        if (sn.notifs.isNotEmpty()) {
            list.addView(tv(s, 12f, 0x99FFFFFF.toInt(), bold = true).apply {
                text = UiLang.s("🔔 सूचनाएँ (${sn.notifs.size}) • स्लाइड करके हटाएँ"); setPadding(px(s, 2f), px(s, 12f), 0, px(s, 4f)) })
            sn.notifs.forEach { n -> list.addView(notifRow(s, n)) }
        }
        if (sn.notifs.isNotEmpty() || sn.locked.isNotEmpty()) {
            list.addView(pill(s, "🧹 सब साफ़ करें", 0xFFB91C1C.toInt()) { IslandFeed.clearAll() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(s, 10f) })
        }
        val scroll = ScrollView(s).apply { isVerticalScrollBarEnabled = false; tag = "island_scroll"; addView(list) }
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
        text = UiLang.s(t); textSize = 12.5f * k; setTextColor(fg); gravity = Gravity.CENTER
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
        val row = LinearLayout(s).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            // टैप = सीधे इसी कॉल की स्क्रीन (दो कॉल = हर एक की अपनी)
            isClickable = true; setOnClickListener { touchedAt = SystemClock.uptimeMillis(); if (!openCall(s, c) && !expanded) expand(IslandCore.Item(IslandCore.Kind.CALL)) } }
        row.addView(dot(s, if (c.video) "🎥" else "📞", 0xFF22C55E.toInt(), 36f))
        val txt = LinearLayout(s).apply { orientation = LinearLayout.VERTICAL; setPadding(px(s, 10f), 0, px(s, 8f), 0) }
        txt.addView(tv(s, 15f, 0xFFFFFFFF.toInt(), bold = true).apply { text = c.who.ifBlank { UiLang.s("अनजान नंबर") } })
        val sub = tv(s, 12f, 0xFF86EFAC.toInt())
        txt.addView(sub)
        updaters.add { a, t -> (IslandCore.allCalls(a).firstOrNull { c.key.isNotBlank() && it.key == c.key } ?: a.call?.takeIf { c.key.isBlank() })?.let { k ->
            sub.text = UiLang.s((if (k.app.isNotBlank()) "${k.app} कॉल • " else "फ़ोन कॉल • ") + (if (k.ringing) "आ रहा है" else "चल रही है ${IslandCore.timer(t - k.since)}")) } }
        row.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // ऐप कॉल = उसी कॉल का बटन (key से); SIM कॉल = फ़ोन का
        val app = c.key.isNotBlank() && c.key != "sim"
        val end = pill(s, "काटें", 0xFFDC2626.toInt()) { if (app) AppCall.endKey(s, c.key) else s.calls.end() }
        row.addView(if (c.ringing) buttons(s, pill(s, "उठाएँ", 0xFF16A34A.toInt()) { if (app) AppCall.answerKey(s, c.key) else s.calls.answer() }, end) else buttons(s, end))
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

    private fun chargeCard(s: DKAccessibilityService, b: IslandCore.Charge): View {
        val row = LinearLayout(s).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val bv = BatteryView(s).apply { pct = b.pct; charging = !b.full; layoutParams = LinearLayout.LayoutParams(px(s, 60f), px(s, 28f)) }
        row.addView(bv)
        val txt = LinearLayout(s).apply { orientation = LinearLayout.VERTICAL; setPadding(px(s, 12f), 0, px(s, 8f), 0) }
        val head = tv(s, 15f, 0xFFFFFFFF.toInt(), bold = true)
        val sub = tv(s, 12f, 0xFF86EFAC.toInt(), lines = 2)
        txt.addView(head); txt.addView(sub)
        row.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        updaters.add { a, _ -> a.charge?.let { k ->
            bv.pct = k.pct; bv.charging = k.charging && !k.full
            head.text = UiLang.s(if (k.full) "⚡ पूरा चार्ज • ${k.pct}%" else "⚡ चार्ज हो रहा है • ${k.pct}%")
            sub.text = UiLang.s(IslandCore.chargeLeft(k.fullInMs, k.full)) + k.source.takeIf { it.isNotBlank() }?.let { " • " + UiLang.s(it) }.orEmpty()
        } }
        return row
    }

    private fun reelCard(s: DKAccessibilityService, r: IslandCore.Reel): View {
        val row = LinearLayout(s).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(dot(s, "🎬", if (r.warn) 0xFFDC2626.toInt() else 0xFF4F46E5.toInt(), 34f))
        val t = tv(s, 13f, if (r.warn) 0xFFFCA5A5.toInt() else 0xFFFFFFFF.toInt(), bold = true, lines = 3)
        row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(s, 10f) })
        updaters.add { a, _ -> a.reel?.let { k -> if (t.text.toString() != k.text) t.text = k.text } }
        return row
    }

    /** एक notification: टैप = उसी जगह खुले; बाएँ / दाएँ खींचो = हटे (फ़ोन से भी) */
    @SuppressLint("ClickableViewAccessibility")
    private fun notifRow(s: DKAccessibilityService, n: IslandCore.Notif): View {
        val row = LinearLayout(s).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { cornerRadius = px(s, 14f).toFloat(); setColor(if (n.call) 0xFF052E16.toInt() else 0xFF0F172A.toInt()) }
            setPadding(px(s, 10f), px(s, 8f), px(s, 10f), px(s, 8f))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = px(s, 6f) }
            contentDescription = "${n.app}: ${n.title}"
        }
        row.addView(if (n.call) dot(s, "📞", 0xFF22C55E.toInt(), 30f) else appIcon(s, n.pkg, 30f))
        val txt = LinearLayout(s).apply { orientation = LinearLayout.VERTICAL; setPadding(px(s, 10f), 0, 0, 0) }
        txt.addView(tv(s, 11f, 0x99FFFFFF.toInt()).apply { text = n.app + " • " + UiLang.s(IslandCore.ago(System.currentTimeMillis() - n.at)) })
        if (n.title.isNotBlank()) txt.addView(tv(s, 13f, 0xFFFFFFFF.toInt(), bold = true).apply { text = n.title })
        if (n.text.isNotBlank()) txt.addView(tv(s, 12f, 0xCCFFFFFF.toInt(), lines = 2).apply { text = n.text })
        row.addView(txt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val slop = ViewConfiguration.get(s).scaledTouchSlop
        var downX = 0f; var downY = 0f; var drag = false; var moved = false
        row.setOnTouchListener { v, ev ->
            touchedAt = SystemClock.uptimeMillis()
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; drag = false; moved = false; v.alpha = 0.85f }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (abs(dx) > slop || abs(dy) > slop) moved = true
                    if (!drag && abs(dx) > slop && abs(dx) > abs(dy)) { drag = true; v.parent?.requestDisallowInterceptTouchEvent(true) }
                    if (drag) { v.translationX = dx; v.alpha = 1f - minOf(0.7f, abs(dx) / v.width.coerceAtLeast(1)) }
                }
                MotionEvent.ACTION_UP -> {
                    val dx = ev.rawX - downX
                    if (drag && IslandCore.swipeDismiss(dx, v.width)) {
                        v.animate().translationX(if (dx > 0) v.width.toFloat() else -v.width.toFloat()).alpha(0f).setDuration(180)
                            .withEndAction { IslandFeed.dismiss(n.key) }.start()
                    } else {
                        v.animate().translationX(0f).alpha(1f).setDuration(180).start()
                        if (!moved) { if (IslandFeed.open(s, n.key)) collapse() else DKLog.d("island: ${n.app} नहीं खुला") }
                    }
                }
                MotionEvent.ACTION_CANCEL -> v.animate().translationX(0f).alpha(1f).setDuration(180).start()
            }
            true
        }
        return row
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
        // सोशल काम = वही ऐप (YouTube / फेसबुक / इंस्टाग्राम…) जहाँ काम चल रहा है
        if (t.key == "social") com.dk.assistant.agent.android.SocialAgent.lastReport?.platform?.pkg?.let { pkg ->
            c.packageManager.getLaunchIntentForPackage(pkg)?.let { launch(c, it); return }
        }
        launch(c, Intent(c, cls))
    }

    private fun openMediaApp(c: Context, m: IslandCore.Media) {
        val pi = mediaCtl?.sessionActivity
        if (pi != null && IslandFeed.send(c, pi)) { collapse(); return }
        c.packageManager.getLaunchIntentForPackage(m.pkg)?.let { launch(c, it) }
    }

    /** इसी कॉल की स्क्रीन: WhatsApp… कॉल = उसी ऐप की कॉल; SIM कॉल = फ़ोन की कॉल स्क्रीन. false = नहीं खुली */
    private fun openCall(c: Context, call: IslandCore.Call): Boolean {
        if (call.key.isNotBlank() && call.key != "sim") {
            AppCall.openFor(call.key)?.let { if (IslandFeed.send(c, it)) { collapse(); return true } }
            IslandFeed.callIntent(call.key)?.let { if (IslandFeed.send(c, it)) { collapse(); return true } }
        } else {
            try {
                val tm = c.getSystemService(android.telecom.TelecomManager::class.java)
                if (tm != null && c.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                    tm.showInCallScreen(false); collapse(); return true
                }
            } catch (e: Exception) { DKLog.d("island: कॉल स्क्रीन नहीं खुली: ${e.javaClass.simpleName}") }
            IslandFeed.callIntent(null)?.let { if (IslandFeed.send(c, it)) { collapse(); return true } }
        }
        return false
    }

    private fun launch(c: Context, i: Intent) {
        try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) } catch (e: Exception) { DKLog.d("island: नहीं खुला: ${e.message}") }
        collapse()
    }

    /** बैटरी का चित्र: भरती हुई (चार्जर लगते ही 0 से % तक), चार्जिंग में चमकती लहर + धड़कता ⚡ */
    private class BatteryView(c: Context) : View(c) {
        var pct = 0
            set(v) { field = v.coerceIn(0, 100); invalidate() }
        var charging = true
            set(v) { field = v; invalidate() }
        private var shown = 0f
        private val t0 = SystemClock.uptimeMillis()
        private val d = c.resources.displayMetrics.density
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = 0xB3FFFFFF.toInt() }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x66FFFFFF }
        private val bolt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = 0xFFFFFFFF.toInt() }
        private val r = RectF()

        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val cap = maxOf(2f * d, w * 0.07f)
            val body = w - cap - d
            val rad = h * 0.28f
            r.set(line.strokeWidth, line.strokeWidth, body, h - line.strokeWidth)
            cv.drawRoundRect(r, rad, rad, line)
            r.set(body + d * 0.5f, h * 0.32f, w, h * 0.68f)
            fill.color = 0xB3FFFFFF.toInt(); cv.drawRoundRect(r, cap / 2, cap / 2, fill)
            // भरना: धीरे-धीरे असली % तक
            val target = pct / 100f
            shown += (target - shown) * 0.12f
            if (abs(target - shown) < 0.002f) shown = target
            val pad = 2.5f * d
            val inner = (body - pad * 2 - line.strokeWidth).coerceAtLeast(0f)
            val fw = inner * shown
            fill.color = IslandCore.batteryColor(pct).toInt()
            r.set(pad + line.strokeWidth / 2, pad + line.strokeWidth / 2, pad + line.strokeWidth / 2 + fw, h - pad - line.strokeWidth / 2)
            if (fw > 0f) cv.drawRoundRect(r, rad * 0.6f, rad * 0.6f, fill)
            val t = (SystemClock.uptimeMillis() - t0) / 1000f
            if (charging && fw > 0f) {
                // चमकती लहर बाएँ से दाएँ
                val band = maxOf(4f * d, fw * 0.25f)
                val x = r.left - band + ((t % 1.6f) / 1.6f) * (fw + band)
                cv.save(); cv.clipRect(r)
                cv.drawRect(x, r.top, x + band, r.bottom, shine)
                cv.restore()
                // धड़कता ⚡
                bolt.textSize = h * 0.7f
                bolt.alpha = (170 + 85 * kotlin.math.sin(t * 4f)).toInt().coerceIn(0, 255)
                cv.drawText("⚡", body / 2f, h / 2f - (bolt.descent() + bolt.ascent()) / 2f, bolt)
            }
            if (charging || shown != target) postInvalidateOnAnimation()
        }
    }
}

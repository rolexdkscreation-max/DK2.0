package com.dk.assistant

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.roundToInt

/** ORB के आकार की सीमा (dp) - Settings slider और pinch दोनों यही मानते हैं */
object FloatOrbSize {
    /** पुराना किनारे वाला छोटा ORB 76dp का था - वही शुरुआती आकार */
    const val DEFAULT_DP = 76
    const val MIN_DP = 48
    const val MAX_DP = 160
    fun clamp(dp: Int) = dp.coerceIn(MIN_DP, MAX_DP)
    /** नीचे से कितनी दूरी (dp) - navigation bar / gesture पट्टी के ऊपर */
    const val BOTTOM_GAP_DP = 120
}

/**
 * मालिक (02 नवंबर 2026): "ऐप खुला हो तब छोटा ORB नहीं आना चाहिए. ऐप से बाहर जाते ही बड़ा ORB सिकुड़कर नीचे बीच में छोटा
 * होकर आए, वहीं टिका रहे (हटे नहीं), उस पर ✕ नहीं. power बटन वाला popup अच्छे animation से आए, Live जुड़ने के थोड़ी देर बाद
 * नीचे की ओर animation से चला जाए."
 */
object OrbRules {
    /** power बटन / assistant वाला popup अभी स्क्रीन पर (तब छोटा ORB नहीं - दो ORB एक साथ नहीं) */
    @Volatile var popupShowing = false
    /** छोटा तैरता ORB: Settings में चालू + DK ऐप सामने नहीं + popup नहीं */
    fun floatWanted(prefOn: Boolean, inApp: Boolean, popup: Boolean): Boolean = prefOn && !inApp && !popup
    /** popup कम से कम इतना दिखे */
    const val POPUP_MIN_MS = 1_800L
    /** Live जुड़ने के बाद इतनी देर रुककर जाए */
    const val POPUP_AFTER_LIVE_MS = 2_500L
    /** Live न जुड़े (बैकअप / इजाज़त नहीं) तब भी इतने बाद (बोलना खत्म होते ही) */
    const val POPUP_MAX_MS = 20_000L
    /** ✕ नहीं है - इसलिए popup कभी हमेशा के लिए न अटके (शोर / लंबा काम): इतने बाद DK बोल न रहा हो तो ज़रूर जाए */
    const val POPUP_HARD_MAX_MS = 60_000L
    /** बाहर आते समय ORB कितना बड़ा दिखकर सिकुड़े */
    const val ENTER_SCALE = 1.9f
    /** popup जाए? बोलते/सोचते/उपयोगकर्ता के बोलते समय कभी नहीं */
    fun popupShouldLeave(shownMs: Long, liveUpMs: Long, mode: Mode, level: Float): Boolean {
        if (shownMs < POPUP_MIN_MS) return false
        if (shownMs >= POPUP_HARD_MAX_MS && mode != Mode.SPEAKING) return true
        if (mode == Mode.SPEAKING || mode == Mode.THINKING || mode == Mode.WORKING) return false
        if (level > 0.18f) return false
        return (liveUpMs >= POPUP_AFTER_LIVE_MS) || shownMs >= POPUP_MAX_MS
    }
    // ---- मालिक (02 नवंबर 2026): "छोटा ORB तभी दिखे जब DK सुन रहा हो / हम बोल रहे हों / DK बोल रहा हो;
    // बोलना-सुनना बंद = छिप जाए; फिर बोलना शुरू = फिर दिखे. उंगली से पकड़कर कहीं भी खिसका सकें."
    /** इतनी देर कोई न बोले (न आप, न DK) = ORB छिपे (Live पीछे चलता रहता है - बोलते ही ORB फिर) */
    const val QUIET_HIDE_MS = 6_000L
    /** बातचीत खत्म (Live बंद / सब IDLE) के बाद इतनी देर में छिपे - mode बदलते समय टिमटिमाए नहीं */
    const val IDLE_GRACE_MS = 1_200L
    /** माइक की आवाज़ इससे ऊपर = आप बोल रहे हैं */
    const val TALK_LEVEL = 0.18f
    /** दिखते समय कितनी बार जाँचें */
    const val TICK_MS = 300L
    /** अभी कोई बोल रहा / DK काम कर रहा है? (ORB तुरंत दिखे) */
    fun talkingNow(mode: Mode, level: Float): Boolean =
        mode == Mode.SPEAKING || mode == Mode.THINKING || mode == Mode.WORKING || (mode == Mode.LISTENING && level > TALK_LEVEL)
    /**
     * छोटा ORB अभी दिखे? [mode] = DKState.shownMode(), [quietMs] = आख़िरी बार कोई बोला / सुनना शुरू हुआ - उसके बाद का समय.
     * DK बोल / सोच / काम कर रहा = हमेशा. सुन रहा (Live) = आख़िरी बात के [QUIET_HIDE_MS] तक. बाकी (Hey DK का पीछे वाला सुनना भी) = छिपा.
     */
    fun talkWanted(mode: Mode, live: Boolean, quietMs: Long): Boolean = when {
        mode == Mode.SPEAKING || mode == Mode.THINKING || mode == Mode.WORKING -> true
        // सिर्फ़ Live वाला सुनना गिनो - बिना Live के "Hey DK" का पीछे वाला सुनना (हमेशा चलता) ORB नहीं दिखाता
        live -> quietMs < QUIET_HIDE_MS
        else -> quietMs < IDLE_GRACE_MS
    }
    /** खिसकाया ORB स्क्रीन के अंदर ही रहे */
    fun clampPos(x: Int, y: Int, w: Int, h: Int, screenW: Int, screenH: Int): Pair<Int, Int> =
        x.coerceIn(0, (screenW - w).coerceAtLeast(0)) to y.coerceIn(0, (screenH - h).coerceAtLeast(0))

    /** DK Live सच में जुड़ा? (GeminiLive जुड़ते ही "Live • <मॉडल> • …" लिखता है) */
    fun liveUp(link: String): Boolean = link.startsWith("Live •")
}

/**
 * मालिक (01 नवंबर 2026): "स्क्रीन पर दो ORB दिखते हैं - किनारे का छोटा और नीचे बीच का बड़ा (✕ वाला). सिर्फ़ एक चाहिए,
 * नीचे बीच में, पुराने छोटे जितना, छोटा-बड़ा हो सके (slider / pinch), ऐप खुला / minimize / दूसरे ऐप पर - हर हाल में यही एक.
 * ✕ = सिर्फ़ ORB छिपे, DK (माइक, Hey DK, सेवा) चलता रहे. खींचकर जगह बदले. बाकी स्क्रीन छूना पहले जैसा."
 *
 * Accessibility overlay (TYPE_ACCESSIBILITY_OVERLAY) - हर ऐप के ऊपर, DK का अपना ऐप खुला हो तब भी.
 * खिड़की सिर्फ़ ORB जितनी बड़ी + FLAG_NOT_FOCUSABLE / NOT_TOUCH_MODAL = बाकी स्क्रीन का छूना आर-पार.
 * टैप = पहले वाले किनारे के ORB जैसा (कॉल चल रही हो तो कॉल काटो, वरना Live चालू/बंद).
 */
class FloatOrb(private val c: Context, private val onTap: () -> Unit) {
    private val wm = c.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = c.resources.displayMetrics.density
    private var root: FrameLayout? = null
    private var orb: OrbView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var curDp = FloatOrbSize.DEFAULT_DP.toFloat()

    private fun px(dp: Float) = (dp * density).roundToInt()
    /** नवंबर 2026: ✕ हटाया - खिड़की = सिर्फ़ ORB (थोड़ी जगह animation की चमक के लिए) */
    private fun winPx(orbPx: Int) = orbPx

    val showing: Boolean get() = root != null

    // ---- बोलने-सुनने पर ही दिखना (OrbRules.talkWanted)
    private val main = Handler(Looper.getMainLooper())
    /** आख़िरी बार कोई बोला / सुनना शुरू हुआ / ORB छुआ (uptime) */
    private var lastTalkAt = 0L
    private var lastLive = false
    private var lastMode = Mode.IDLE
    private var touching = false
    private val ticker = Runnable { tick() }
    private val onState: () -> Unit = { main.post { tick() } }
    init { DKState.listen(onState) }

    /** सेवा बंद: सुनना बंद + ORB हटाओ */
    fun release() {
        DKState.unlisten(onState); main.removeCallbacks(ticker); remove()
    }

    /** DK की हालत देखो: बोलना/सुनना शुरू = अभी दिखाओ; चुप्पी लंबी = छिपाओ. दिखते / Live चलते समय हर TICK_MS पर */
    private fun tick() {
        main.removeCallbacks(ticker)
        val now = SystemClock.uptimeMillis()
        val m = DKState.shownMode(); val live = DKState.live
        // Live शुरू (power बटन / Hey DK / टैप) = सुनना शुरू. DK बोलना खत्म -> सुनना: SPEAKING वाला समय ही गिना जा चुका
        val started = (live && !lastLive) || (live && m == Mode.LISTENING && lastMode != Mode.LISTENING)
        if (started || touching || OrbRules.talkingNow(m, DKState.level)) lastTalkAt = now
        lastLive = live; lastMode = m
        sync()
        if (live || m != Mode.IDLE || root != null) main.postDelayed(ticker, OrbRules.TICK_MS)
    }

    /** Prefs + ऐप सामने है या नहीं + popup के हिसाब से दिखाओ / हटाओ / आकार ठीक करो (सब यहीं से) */
    fun sync() {
        if (!OrbRules.floatWanted(Prefs.floatOrbOn(c), AppVisible.visible, OrbRules.popupShowing)) { remove(animated = true); return }
        // बोलना-सुनना नहीं हो रहा = छिपा रहे (मालिक, 02 नवंबर 2026)
        if (!OrbRules.talkWanted(DKState.shownMode(), DKState.live, SystemClock.uptimeMillis() - lastTalkAt)) { remove(animated = true); return }
        val want = Prefs.floatOrbDp(c).toFloat()
        if (root == null) show() else if (abs(want - curDp) >= 1f) applySize(want, save = false)
        OrbNotify.cancel(c)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        curDp = Prefs.floatOrbDp(c).toFloat()
        val o = px(curDp)
        // शुरू में खिड़की ORB से 2 गुना - वरना 1.9x वाला सिकुड़ना खिड़की के किनारे पर कट जाता. animation के बाद असली आकार
        val w = (o * OrbRules.ENTER_SCALE).roundToInt() + px(4f)
        val dm = c.resources.displayMetrics
        val p = WindowManager.LayoutParams(
            w, w,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // पहली बार नीचे बीच में; उंगली से खिसकाया हो तो वहीं (बीच का बिंदु स्क्रीन के हिस्से में सेव)
            x = (dm.widthPixels - w) / 2
            y = dm.heightPixels - w - px(FloatOrbSize.BOTTOM_GAP_DP.toFloat())
            val fx = Prefs.floatOrbFx(c); val fy = Prefs.floatOrbFy(c)
            if (fx >= 0f && fy >= 0f) {
                val (cx, cy) = OrbRules.clampPos((fx * dm.widthPixels).roundToInt() - w / 2, (fy * dm.heightPixels).roundToInt() - w / 2,
                    w, w, dm.widthPixels, dm.heightPixels)
                x = cx; y = cy
            }
        }
        val box = FrameLayout(c)
        val ov = OrbView(c).apply { mini = true; contentDescription = "DK ORB - दबाएँ: बोलें / रोकें" }
        box.addView(ov, FrameLayout.LayoutParams(o, o, Gravity.CENTER))
        attachTouch(ov, p)
        // बड़ा ORB सिकुड़कर छोटा होता दिखे ("merge होकर छोटा"): बड़ा + धुँधला -> अपने आकार में, हल्का उछाल
        ov.alpha = 0f; ov.scaleX = OrbRules.ENTER_SCALE; ov.scaleY = OrbRules.ENTER_SCALE
        try { wm.addView(box, p) } catch (e: Exception) { DKLog.d("ORB नहीं लगा: ${e.javaClass.simpleName}"); return }
        root = box; orb = ov; lp = p
        ov.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(420)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.1f))
            .withEndAction { if (root === box) fitWindow() }.start()
    }

    /** हटाओ; [animated] = छोटा होकर धुँधला (ऐप खुलते ही / popup आते ही) */
    fun remove(animated: Boolean = false) {
        val r = root ?: return
        val ov = orb
        root = null; orb = null; lp = null
        if (animated && ov != null) {
            ov.animate().alpha(0f).scaleX(0.4f).scaleY(0.4f).setDuration(220)
                .withEndAction { try { wm.removeView(r) } catch (_: Exception) {} }.start()
        } else try { wm.removeView(r) } catch (_: Exception) {}
    }

    /** animation के बाद खिड़की = सिर्फ़ ORB (बीच वहीं) - ताकि आस-पास की स्क्रीन छूना चले */
    private fun fitWindow() {
        val r = root ?: return; val p = lp ?: return
        val w = winPx(px(curDp)); if (p.width == w) return
        val cx = p.x + p.width / 2; val cy = p.y + p.height / 2
        p.width = w; p.height = w; p.x = cx - w / 2; p.y = cy - w / 2
        try { wm.updateViewLayout(r, p) } catch (_: Exception) {}
    }

    /** नया आकार - ORB का बीच वहीं रहे */
    private fun applySize(dp: Float, save: Boolean) {
        val r = root ?: return; val p = lp ?: return; val ov = orb ?: return
        val nd = dp.coerceIn(FloatOrbSize.MIN_DP.toFloat(), FloatOrbSize.MAX_DP.toFloat())
        val oldW = p.width
        val o = px(nd); val w = winPx(o)
        val cx = p.x + oldW / 2; val cy = p.y + oldW / 2
        p.width = w; p.height = w; p.x = cx - w / 2; p.y = cy - w / 2
        ov.layoutParams = (ov.layoutParams as FrameLayout.LayoutParams).apply { width = o; height = o }
        curDp = nd
        try { wm.updateViewLayout(r, p) } catch (_: Exception) {}
        if (save) Prefs.setFloatOrbDp(c, nd.roundToInt())
    }

    /** खिड़की उंगली के साथ (स्क्रीन के अंदर) */
    private fun moveTo(x: Int, y: Int) {
        val r = root ?: return; val p = lp ?: return
        val dm = c.resources.displayMetrics
        val (nx, ny) = OrbRules.clampPos(x, y, p.width, p.height, dm.widthPixels, dm.heightPixels)
        p.x = nx; p.y = ny
        try { wm.updateViewLayout(r, p) } catch (_: Exception) {}
    }

    /** ORB का बीच = स्क्रीन का कौन-सा हिस्सा (0..1) - घुमाने / आकार बदलने पर भी वही जगह */
    private fun savePos() {
        val p = lp ?: return
        val dm = c.resources.displayMetrics
        if (dm.widthPixels <= 0 || dm.heightPixels <= 0) return
        Prefs.setFloatOrbPos(c, (p.x + p.width / 2f) / dm.widthPixels, (p.y + p.height / 2f) / dm.heightPixels)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouch(v: View, p: WindowManager.LayoutParams) {
        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var moved = false; var scaling = false
        // दो उंगलियों से फैलाओ / सिकोड़ो = आकार; छोड़ते ही सेव
        val scale = ScaleGestureDetector(c, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(d: ScaleGestureDetector): Boolean { scaling = true; return true }
            override fun onScale(d: ScaleGestureDetector): Boolean { applySize(curDp * d.scaleFactor, save = false); return true }
            override fun onScaleEnd(d: ScaleGestureDetector) { Prefs.setFloatOrbDp(c, curDp.roundToInt()) }
        })
        v.setOnTouchListener { _, ev ->
            scale.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; moved = false; scaling = false; touching = true
                    startX = p.x; startY = p.y
                }
                MotionEvent.ACTION_POINTER_DOWN -> scaling = true
                // 02 नवंबर 2026 (मालिक की नई माँग): "हाथ से पकड़कर इधर-उधर कहीं भी खिसका सकें" - उंगली के साथ चले,
                // स्क्रीन के अंदर ही; छोड़ते ही वह जगह सेव (अगली बार वहीं दिखे). उंगली हिली = टैप नहीं
                MotionEvent.ACTION_MOVE -> {
                    if (abs(ev.rawX - downX) > 12 || abs(ev.rawY - downY) > 12) moved = true
                    if (moved && !scaling) moveTo(startX + (ev.rawX - downX).roundToInt(), startY + (ev.rawY - downY).roundToInt())
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touching = false; lastTalkAt = SystemClock.uptimeMillis()
                    if (moved && !scaling) savePos()
                    else if (ev.actionMasked == MotionEvent.ACTION_UP && !moved && !scaling) onTap()
                }
            }
            true
        }
    }
}

/** ✕ के बाद "ORB दिखाओ" वाली notification (DK चालू है, सिर्फ़ ORB छिपा है) */
object OrbNotify {
    private const val CH = "dk_orb"
    private const val ID = 7411
    const val ACTION_SHOW = "com.dk.assistant.SHOW_ORB"

    fun showHidden(c: Context) = try {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(NotificationChannel(CH, "DK ORB", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getBroadcast(c, ID, Intent(c, OrbShowReceiver::class.java).setAction(ACTION_SHOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val name = Prefs.assistantName(c)
        @Suppress("DEPRECATION")
        val b = (if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(c, CH) else android.app.Notification.Builder(c))
            .setSmallIcon(R.drawable.ic_dk)
            .setContentTitle(UiLang.s("$name का ORB छिपा है"))
            .setContentText(UiLang.s("$name चालू है और सुन रहा है। ORB वापस लाने के लिए दबाएँ।"))
            .setContentIntent(pi).setOnlyAlertOnce(true).setAutoCancel(true)
            .addAction(android.app.Notification.Action.Builder(null as android.graphics.drawable.Icon?, UiLang.s("ORB दिखाओ"), pi).build())
        nm.notify(ID, b.build())
    } catch (e: Exception) { DKLog.d("ORB notification नहीं लगी: ${e.message}") }

    fun cancel(c: Context) {
        try { (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(ID) } catch (_: Exception) {}
    }
}

/** notification का "ORB दिखाओ" बटन */
class OrbShowReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        Prefs.setFloatOrbOn(c, true)
        OrbNotify.cancel(c)
        DKAccessibilityService.instance?.refreshOrb()
    }
}

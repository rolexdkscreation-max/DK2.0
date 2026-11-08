package com.dk.assistant

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * नवंबर 2026 (मालिक): रील गिनती + ऊपर छोटी पट्टी + हर ऐप की रोज़ की लिमिट. नियम: [ReelRules].
 *
 * - सिर्फ़ जब इंस्टाग्राम / YouTube / फेसबुक सामने हो, रील पर हर 0.45 सेकंड (बाकी हर 0.65) स्क्रीन का लिखा पढ़ता है (दूसरे ऐप में सिर्फ़ ऐप का नाम, हर 1.5 सेकंड).
 * - पट्टी = TYPE_ACCESSIBILITY_OVERLAY (नोटिफ़िकेशन नहीं), छूने लायक नहीं - नीचे का ऐप वैसे ही चलता है.
 * - DK खुद स्क्रीन चला रहा हो (सोशल रिव्यू / कोई काम) तो न गिनती, न रोक - रिव्यू कभी नहीं रुकेगा.
 * - लिमिट पूरी: फ़ोन लॉक या होम; वह ऐप "बंद" - जब भी सामने आए, DK होम पर ले जाता है, जब तक DK सेटिंग से खोलें नहीं.
 */
class ReelWatch(private val svc: DKAccessibilityService) {
    private val thread = HandlerThread("dk-reels").apply { start() }
    private val bg = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val wm = svc.getSystemService(WindowManager::class.java)
    // नवंबर 2026 (मालिक: "स्क्रॉल पर गिनती नहीं, जो देखा वही"): 0.45 सेकंड पर देखो, रील / वीडियो 1.5 सेकंड चली = एक (स्क्रॉल में निकली = नहीं)
    private val counters = ReelRules.App.values().associateWith { ReelRules.Counter(stableMs = ReelRules.WATCH_MS) }
    private val ytVideo = ReelRules.Counter(stableMs = ReelRules.WATCH_MS)
    @Volatile private var released = false
    private var pill: TextView? = null
    private var lastHome = 0L
    private var blockedShownUntil = 0L
    private var lastLog = ""
    // नवंबर 2026: 7 दिन का हिसाब (ReelDaily) - ऐप सामने रहने का समय जोड़कर हर ~10 सेकंड में सेव
    private var seenApp: ReelRules.App? = null
    private var seenMs = 0L
    private var lastTick = 0L
    private var lastFlush = 0L
    private var allNow: ReelDaily.Now? = null
    private var allAt = 0L

    fun start() { bg.post(tick) }

    fun release() {
        released = true
        bg.removeCallbacksAndMessages(null)
        thread.quitSafely()
        main.post { removePill() }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (released) return
            val next = try { step() } catch (e: Exception) { DKLog.d("रील गिनती: ${e.javaClass.simpleName}"); 1_500L }
            if (!released) bg.postDelayed(this, next)
        }
    }

    /** DK खुद फ़ोन चला रहा है = न गिनो, न रोको */
    private fun dkDriving(): Boolean =
        com.dk.assistant.insights.InsightsAgent.busy ||
            (try { com.dk.assistant.agent.android.SocialAgent.busy() } catch (_: Exception) { false }) ||
            (try { svc.agent.isBusy } catch (_: Exception) { false })

    /** एक बार देखो; return = अगली बार कितनी देर बाद */
    private fun step(): Long {
        val c = svc.applicationContext
        val root = try { svc.rootInActiveWindow } catch (_: Exception) { null }
        val app = ReelRules.appOf(root?.packageName?.toString())
        val tNow = now(); val dt = if (lastTick == 0L) 0L else (tNow - lastTick).coerceIn(0L, 3_000L); lastTick = tNow
        if (app == null || dkDriving()) {
            // DK खुद चला रहा (जैसे सोशल रिव्यू) = न गिनती, न समय
            flushSeen(force = true)
            app?.let { counters[it]?.feed(false, emptySet(), now()) }
            if (System.currentTimeMillis() > blockedShownUntil) hidePill()
            return if (app == null) 1_500L else 900L
        }
        ReelStore.newDayCheck(c)
        // लिमिट से बंद ऐप सामने आया = होम (DK सेटिंग से खोलने तक)
        if (ReelStore.blocked(c, app)) {
            val t = System.currentTimeMillis()
            if (t - lastHome > 1_200) {
                lastHome = t
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                DKLog.d("रील लिमिट: ${app.hi} बंद है - होम पर भेजा")
            }
            blockedShownUntil = t + 3_500
            showPill(ReelRules.blockedPill(app), warn = true)
            return 700L
        }
        val nodes = read(root)
        // नवंबर 2026 (मालिक: "रील या वीडियो, कहीं भी"): रील / शॉर्ट्स + YouTube का आम वीडियो
        val isReel = ReelRules.reelMode(app, nodes)
        val isVideo = !isReel && ReelRules.watchMode(app, nodes)
        val inReel = isReel || isVideo
        val sig = if (isReel) ReelRules.signature(nodes) else if (isVideo) ReelRules.watchSignature(nodes) else emptySet()
        if (seenApp != app) { flushSeen(force = true); seenApp = app }
        seenMs += dt
        flushSeen(force = false)
        // YouTube: शॉर्ट्स और आम वीडियो के अलग गिनने वाले (दोनों YouTube की लिमिट में)
        val counted = if (app == ReelRules.App.YOUTUBE) {
            val a = counters[app]!!.feed(isReel, if (isReel) sig else emptySet(), now())
            val b = ytVideo.feed(isVideo, if (isVideo) sig else emptySet(), now())
            a || b
        } else counters[app]!!.feed(inReel, sig, now())
        if (counted) {
            val limit = ReelStore.limit(c, app)
            val (n, stop) = ReelRules.onNewReel(ReelStore.count(c, app), limit)
            if (stop) { limitHit(app); return 700L }
            ReelStore.setCount(c, app, n)
            if (isVideo) ReelStore.setVideos(c, ReelStore.videos(c) + 1)
            val log = "रील गिनती ${app.en()}${if (isVideo) " वीडियो" else ""}: $n (${sig.take(3).joinToString(" | ") { it.take(24) }})"
            if (log != lastLog) { lastLog = log; DKLog.d(log) }
            ReelDaily.addReel(c, app, sig.maxByOrNull { it.length }, video = isVideo)
            ReelDaily.coach(c)
        }
        if (inReel && ReelStore.popupOn(c)) {
            val tn = now()
            val all = allNow?.takeIf { !counted && tn - allAt < 3_000 } ?: ReelDaily.now(c).also { allNow = it; allAt = tn }
            // नवंबर 2026 (मालिक): पट्टी पर सिर्फ़ आज की गिनती - कल / पुराना हिसाब DK सेटिंग → "7 दिन का रील हिसाब" में
            val extra = ReelRules.todayExtra(all.total, all.limit)
            val text = if (app == ReelRules.App.YOUTUBE) ReelStore.count(c, app).let { yt -> val v = ReelStore.videos(c).coerceAtMost(yt); ReelRules.ytPill(isVideo, yt - v, v, ReelStore.limit(c, app)) }
                else ReelRules.pill(app, ReelStore.count(c, app), ReelStore.limit(c, app))
            showPill(text + extra, warn = all.limit > 0 && all.total > all.limit)
        }
        else if (System.currentTimeMillis() > blockedShownUntil) hidePill()
        return if (inReel) 450L else 650L
    }

    private fun ReelRules.App.en() = name.lowercase()

    /** ऐप सामने रहने का समय (DK देख रहा था) 7 दिन वाले हिसाब में */
    private fun flushSeen(force: Boolean) {
        val a = seenApp ?: return
        val t = now()
        if (seenMs <= 0L || (!force && t - lastFlush < 10_000)) return
        lastFlush = t
        try { ReelDaily.addSeen(svc.applicationContext, a, seenMs) } catch (_: Exception) {}
        seenMs = 0L
    }

    private fun limitHit(app: ReelRules.App) {
        val c = svc.applicationContext
        ReelStore.setBlocked(c, app, true)
        counters[app]?.reset(); if (app == ReelRules.App.YOUTUBE) ytVideo.reset()
        val action = ReelStore.action(c)
        DKLog.d("रील लिमिट पूरी: ${app.hi} (${ReelStore.limit(c, app)}) - ${action.id}")
        blockedShownUntil = System.currentTimeMillis() + 4_000
        showPill(ReelRules.blockedPill(app), warn = true)
        if (action == ReelRules.Action.LOCK && Build.VERSION.SDK_INT >= 28) {
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        } else svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        lastHome = System.currentTimeMillis()
    }

    private fun now() = android.os.SystemClock.uptimeMillis()

    /** स्क्रीन पर दिख रहा लिखा (ज़्यादा से ज़्यादा 700 हिस्से - बड़ी स्क्रीन पर नीचे का नाम / caption न छूटे) */
    private fun read(root: AccessibilityNodeInfo?): List<ReelRules.Node> {
        root ?: return emptyList()
        val dm = svc.resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(1).toFloat(); val h = dm.heightPixels.coerceAtLeast(1).toFloat()
        val out = ArrayList<ReelRules.Node>()
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        val r = Rect()
        var seen = 0
        while (stack.isNotEmpty() && seen < 700) {
            val n = stack.removeLast(); seen++
            try {
                if (!n.isVisibleToUser) continue
                val txt = n.text?.toString().orEmpty(); val desc = n.contentDescription?.toString().orEmpty(); val id = n.viewIdResourceName.orEmpty()
                if (txt.isNotBlank() || desc.isNotBlank() || id.isNotBlank()) {
                    n.getBoundsInScreen(r)
                    out += ReelRules.Node(id, txt, desc, r.exactCenterX() / w, r.exactCenterY() / h, r.width() / w, r.height() / h)
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
            } catch (_: Exception) {}
        }
        return out
    }

    // ------------------------------------------------------------ ऊपर की छोटी पट्टी
    private fun px(dp: Float) = (dp * svc.resources.displayMetrics.density).roundToInt()

    private fun showPill(text: String, warn: Boolean) = main.post {
        if (released) return@post
        val tv = pill ?: makePill() ?: return@post
        if (tv.text != text) tv.text = text
        (tv.background as? GradientDrawable)?.setColor(if (warn) 0xE6B3261E.toInt() else 0xD9152A5C.toInt())
    }

    private fun hidePill() = main.post { removePill() }

    private fun makePill(): TextView? {
        val tv = TextView(svc).apply {
            textSize = 12f; setTextColor(0xFFFFFFFF.toInt())
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            setPadding(px(12f), px(4f), px(12f), px(4f))
            background = GradientDrawable().apply { cornerRadius = px(14f).toFloat(); setColor(0xD9152A5C.toInt()); setStroke(px(1f), 0xFF5B8CFF.toInt()) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = px(30f) }
        return try { wm.addView(tv, p); pill = tv; tv } catch (e: Exception) { DKLog.d("रील पट्टी नहीं लगी: ${e.javaClass.simpleName}"); null }
    }

    private fun removePill() { pill?.let { try { wm.removeView(it) } catch (_: Exception) {} }; pill = null }
}

/** रील गिनती की सेटिंग / आज की गिनती ("dk_prefs") */
object ReelStore {
    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE)
    private fun day() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())

    fun popupOn(c: Context) = sp(c).getBoolean("reel_popup", true)
    fun setPopup(c: Context, on: Boolean) = sp(c).edit().putBoolean("reel_popup", on).apply()
    fun limit(c: Context, a: ReelRules.App) = sp(c).getInt("reel_limit_${a.key}", 0)
    fun setLimit(c: Context, a: ReelRules.App, n: Int) = sp(c).edit().putInt("reel_limit_${a.key}", n.coerceIn(0, 10_000)).apply()
    fun action(c: Context) = ReelRules.Action.of(sp(c).getString("reel_action", null))
    fun setAction(c: Context, a: ReelRules.Action) = sp(c).edit().putString("reel_action", a.id).apply()
    fun blocked(c: Context, a: ReelRules.App) = sp(c).getBoolean("reel_blocked_${a.key}", false)
    fun setBlocked(c: Context, a: ReelRules.App, on: Boolean) = sp(c).edit().putBoolean("reel_blocked_${a.key}", on).apply()
    fun count(c: Context, a: ReelRules.App): Int { newDayCheck(c); return sp(c).getInt("reel_count_${a.key}", 0) }
    fun setCount(c: Context, a: ReelRules.App, n: Int) = sp(c).edit().putInt("reel_count_${a.key}", n).apply()
    /** आज के YouTube आम वीडियो (YouTube की गिनती "reel_count_yt" में ये भी शामिल - लिमिट कुल पर) */
    fun videos(c: Context): Int { newDayCheck(c); return sp(c).getInt("reel_count_ytv", 0) }
    fun setVideos(c: Context, n: Int) = sp(c).edit().putInt("reel_count_ytv", n).apply()

    /** DK सेटिंग से "खोलें": ब्लॉक हटा, उस ऐप की आज की गिनती 0 से */
    fun unblock(c: Context, a: ReelRules.App) = sp(c).edit().putBoolean("reel_blocked_${a.key}", false).putInt("reel_count_${a.key}", 0)
        .apply { if (a == ReelRules.App.YOUTUBE) putInt("reel_count_ytv", 0) }.apply()

    /** नया दिन = गिनती 0 (ब्लॉक अपने-आप नहीं हटता - मालिक की माँग: DK सेटिंग से खोलने तक) */
    fun newDayCheck(c: Context) {
        val d = day(); val s = sp(c)
        if (s.getString("reel_day", "") == d) return
        val e = s.edit().putString("reel_day", d)
        ReelRules.App.values().forEach { e.putInt("reel_count_${it.key}", 0) }
        e.putInt("reel_count_ytv", 0)
        e.apply()
    }
}

package com.dk.assistant

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * मालिक (12 नवंबर 2026): Samsung S26 Ultra जैसा "प्राइवेसी स्क्रीन" - नियम [PrivacyCore] में.
 * - पूरे फ़ोन में या सिर्फ़ चुने ऐप खुलें तब: गहरी प्राइवेसी परत (Accessibility overlay, छूना आर-पार).
 * - चुने ऐप का notification: popup नहीं आता (DK तुरंत छिपाता है), island में "🔒 ऐप • N नए" - टैप = वही खुले.
 * - चाहें तो हर notification popup के समय ऊपर का हिस्सा ढका (Samsung का "Notification pop-ups").
 */
object PrivacyScreen {
    private const val P_ON = "privacy_on"
    private const val P_SCOPE = "privacy_scope"
    private const val P_APPS = "privacy_apps"
    private const val P_STRENGTH = "privacy_strength"
    private const val P_HIDE_NOTIF = "privacy_hide_notif"
    private const val P_POPUPS = "privacy_popups"

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE)

    fun on(c: Context) = sp(c).getBoolean(P_ON, false)
    fun setOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(P_ON, v).apply(); refresh() }
    fun scope(c: Context) = PrivacyCore.Scope.of(sp(c).getString(P_SCOPE, null))
    fun setScope(c: Context, s: PrivacyCore.Scope) { sp(c).edit().putString(P_SCOPE, s.id).apply(); refresh() }
    fun apps(c: Context): Set<String> = PrivacyCore.parse(sp(c).getString(P_APPS, ""))
    fun appOn(c: Context, pkg: String) = pkg in apps(c)
    fun setAppOn(c: Context, pkg: String, v: Boolean) {
        val s = apps(c).toMutableSet(); if (v) s.add(pkg) else s.remove(pkg)
        sp(c).edit().putString(P_APPS, PrivacyCore.join(s)).apply(); refresh()
    }
    fun strength(c: Context) = PrivacyCore.clampStrength(sp(c).getInt(P_STRENGTH, PrivacyCore.DEF_STRENGTH))
    fun setStrength(c: Context, p: Int) { sp(c).edit().putInt(P_STRENGTH, PrivacyCore.clampStrength(p)).apply(); refresh() }
    fun hideNotifs(c: Context) = sp(c).getBoolean(P_HIDE_NOTIF, true)
    fun setHideNotifs(c: Context, v: Boolean) = sp(c).edit().putBoolean(P_HIDE_NOTIF, v).apply()
    fun popups(c: Context) = sp(c).getBoolean(P_POPUPS, false)
    fun setPopups(c: Context, v: Boolean) = sp(c).edit().putBoolean(P_POPUPS, v).apply()

    // ------------------------------------------------------------ फ़ोन के ऐप (Settings की लिस्ट)
    data class App(val pkg: String, val label: String)

    /** फ़ोन के सारे ऐप जो launcher में दिखते हैं (DK खुद भी) - नाम से क्रम में */
    fun scan(c: Context): List<App> {
        val pm = c.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val list = try { pm.queryIntentActivities(i, 0) } catch (_: Exception) { emptyList() }
        return list.mapNotNull { r -> r.activityInfo?.packageName?.let { App(it, r.loadLabel(pm).toString().ifBlank { it }) } }
            .distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
    }

    // ------------------------------------------------------------ परत (overlay)
    @Volatile private var svcRef: DKAccessibilityService? = null
    @Volatile private var fgPkg: String? = null
    private val main = Handler(Looper.getMainLooper())
    private var shield: View? = null
    private var shieldAlpha = -1
    private var top: View? = null
    private val topOff = Runnable { removeTop() }

    fun start(svc: DKAccessibilityService) { svcRef = svc; main.post(recheck) }
    fun stop() { main.post { removeShield(); removeTop() }; svcRef = null }

    /** accessibility: खिड़की बदली = सामने वाला ऐप बदला हो सकता है */
    fun onEvent(svc: DKAccessibilityService, e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!on(svc) && PrivacyLocked.isEmpty()) return
        main.removeCallbacks(recheck); main.postDelayed(recheck, 250)
    }

    private val recheck = Runnable {
        val s = svcRef ?: return@Runnable
        val p = try { s.rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
        // सिस्टम की पट्टी / कीबोर्ड = ऐप नहीं बदला
        if (!p.isNullOrBlank() && p != "com.android.systemui" && !p.contains("inputmethod") && !p.contains("keyboard")) {
            fgPkg = p
            PrivacyLocked.opened(p)       // वही ऐप खोल लिया = उसका 🔒 हटाओ
        }
        refresh()
    }

    fun refresh() = main.post {
        val s = svcRef
        if (s == null) { removeShield(); return@post }
        val want = PrivacyCore.shieldNow(on(s), scope(s), fgPkg, apps(s))
        if (want) showShield(s) else removeShield()
    }

    private fun lp(h: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    private fun showShield(s: DKAccessibilityService) {
        val st = strength(s)
        if (shield != null && shieldAlpha == st) return
        removeShield()
        val v = ShieldView(s, st)
        val wm = s.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = s.resources.displayMetrics
        try { wm.addView(v, lp(dm.heightPixels + (200 * dm.density).toInt())); shield = v; shieldAlpha = st }
        catch (e: Exception) { DKLog.d("प्राइवेसी परत नहीं लगी: ${e.javaClass.simpleName}") }
    }

    private fun removeShield() {
        val v = shield ?: return
        shield = null; shieldAlpha = -1
        try { (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Exception) {}
    }

    /** notification popup के समय ऊपर का हिस्सा कुछ सेकंड ढको */
    private fun showTop(s: DKAccessibilityService) = main.post {
        main.removeCallbacks(topOff)
        if (top == null) {
            val dm = s.resources.displayMetrics
            val v = ShieldView(s, strength(s), topBand = true)
            try { (s.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(v, lp((170 * dm.density).toInt())); top = v }
            catch (e: Exception) { DKLog.d("popup परत नहीं लगी: ${e.javaClass.simpleName}") }
        }
        main.postDelayed(topOff, PrivacyCore.POPUP_SHIELD_MS)
    }

    private fun removeTop() {
        val v = top ?: return
        top = null
        try { (v.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Exception) {}
    }

    /**
     * गहरी परत: बीच हल्की, बाएँ/दाएँ/ऊपर/नीचे किनारे गहरे + बारीक खड़ी धारियाँ (privacy filter जैसी) -
     * सामने से पढ़ा जाए, तिरछे से देखने वाले को रोशनी बहुत कम. onDraw में कुछ नया नहीं बनता.
     */
    private class ShieldView(c: Context, strength: Int, private val topBand: Boolean = false) : View(c) {
        private val center = PrivacyCore.alphaCenter(strength)
        private val edge = PrivacyCore.alphaEdge(strength)
        private val fill = Paint().apply { color = center shl 24 }
        private val sideL = Paint(); private val sideR = Paint(); private val sideB = Paint()
        private val line = Paint().apply { color = ((edge / 5).coerceIn(10, 50)) shl 24 }
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            val e = edge shl 24; val z = 0
            val band = w * 0.28f
            sideL.shader = LinearGradient(0f, 0f, band, 0f, e, z, Shader.TileMode.CLAMP)
            sideR.shader = LinearGradient(w.toFloat(), 0f, w - band, 0f, e, z, Shader.TileMode.CLAMP)
            sideB.shader = if (topBand) LinearGradient(0f, h.toFloat(), 0f, h * 0.55f, z, center shl 24, Shader.TileMode.CLAMP)
                else LinearGradient(0f, h.toFloat(), 0f, h - h * 0.12f, e, z, Shader.TileMode.CLAMP)
        }
        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (topBand) { cv.drawRect(0f, 0f, w, h * 0.55f, fill); cv.drawRect(0f, h * 0.55f, w, h, sideB) }
            else { cv.drawRect(0f, 0f, w, h, fill); cv.drawRect(0f, h - h * 0.12f, w, h, sideB) }
            cv.drawRect(0f, 0f, w * 0.28f, h, sideL); cv.drawRect(w - w * 0.28f, 0f, w, h, sideR)
            // बारीक खड़ी धारियाँ (हर 3px पर 1px)
            var x = 0f
            while (x < w) { cv.drawRect(x, 0f, x + 1f, h, line); x += 3f }
        }
    }

    // ------------------------------------------------------------ notification
    /**
     * DKNotifListener: नया notification. true = छिपा दिया (popup नहीं).
     * सिर्फ़ चुने ऐप के आम notification; कॉल / गाना / चलते (ongoing) कभी नहीं.
     */
    fun onPosted(l: DKNotifListener, sbn: StatusBarNotification): Boolean {
        val c = l.applicationContext
        if (!on(c)) return false
        val n = sbn.notification ?: return false
        val ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0 || (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0
        val isCall = n.category == Notification.CATEGORY_CALL || AppCall.current?.key == sbn.key
        val isMedia = n.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true || n.category == Notification.CATEGORY_TRANSPORT
        val summary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        if (PrivacyCore.hideNotif(true, hideNotifs(c), sbn.packageName, c.packageName, apps(c), ongoing, isCall, isMedia)) {
            if (!summary) {
                val app = try { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(sbn.packageName, 0)).toString() } catch (_: Exception) { sbn.packageName }
                val text = n.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                PrivacyLocked.add(sbn.key, sbn.packageName, app, text, n.contentIntent)
            }
            hidden.add(sbn.key)
            try { l.cancelNotification(sbn.key) } catch (e: Exception) { DKLog.d("प्राइवेसी: notification नहीं छिपा: ${e.message}") }
            DKIsland.poke()
            return true
        }
        if (PrivacyCore.shieldPopup(true, popups(c), sbn.packageName, c.packageName, ongoing)) svcRef?.let { showTop(it) }
        return false
    }

    /** DK ने खुद छिपाया (हटाया) - reply वाला बटन याद रहे (ऑटो रिप्लाई चलता रहे) */
    private val hidden = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    fun hidByUs(key: String): Boolean = hidden.remove(key)

    /** island से 🔒 पर टैप: वही चैट/ऐप खोलो (notification का अपना रास्ता, न चले तो ऐप) */
    fun open(c: Context, pkg: String) {
        val pi = PrivacyLocked.intentFor(pkg)
        PrivacyLocked.opened(pkg)
        var ok = false
        if (pi != null) try {
            if (Build.VERSION.SDK_INT >= 34) {
                val o = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(c, 0, null, null, null, null, o.toBundle())
            } else pi.send()
            ok = true
        } catch (_: Exception) {}
        if (!ok) try {
            c.packageManager.getLaunchIntentForPackage(pkg)?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } catch (e: Exception) { DKLog.d("प्राइवेसी: ऐप नहीं खुला: ${e.message}") }
    }
}

/** प्राइवेसी से छिपे notification (सिर्फ़ memory) - island में "🔒 ऐप • N नए" */
object PrivacyLocked {
    private class Rec(val key: String, val pkg: String, val app: String, var count: Int, var at: Long, var pi: PendingIntent?, val seen: MutableSet<String>)
    private val map = LinkedHashMap<String, Rec>()

    @Synchronized fun add(key: String, pkg: String, app: String, text: String, pi: PendingIntent?) {
        val r = map.getOrPut(pkg) { Rec(key, pkg, app, 0, 0L, null, HashSet()) }
        // वही notification (update) दोबारा = गिनती नहीं बढ़ती
        if (r.seen.add("$key|${text.take(80)}")) r.count++
        r.at = System.currentTimeMillis(); if (pi != null) r.pi = pi
    }
    @Synchronized fun opened(pkg: String) { if (map.remove(pkg) != null) DKIsland.poke() }
    @Synchronized fun clear() { map.clear(); DKIsland.poke() }
    @Synchronized fun isEmpty() = map.isEmpty()
    @Synchronized fun intentFor(pkg: String): PendingIntent? = map[pkg]?.pi
    @Synchronized fun list(): List<IslandCore.Locked> = map.values.sortedByDescending { it.at }.map { IslandCore.Locked(it.key, it.pkg, it.app, it.count, it.at) }
}

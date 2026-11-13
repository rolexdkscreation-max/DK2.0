package com.dk.assistant

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * DK Privacy - मालिक (12 नवंबर 2026; 13 नवंबर: नाम "DK Privacy") - नियम [PrivacyCore] में.
 * 13 नवंबर 2026 (मालिक): "स्क्रीन वाला हटा दो, सिर्फ़ नोटिफिकेशन वाला रखो" - अब सिर्फ़ notification का ताला:
 * - सभी ऐप या सिर्फ़ चुने ऐप का notification आते ही ताले में: ऊपर (status bar / popup) से नहीं दिखता, DK उसे हटा देता है.
 * - island में "🔒 ऐप • N नए" - टैप करें या वह ऐप खुद खोलें, तभी अंदर दिखता है (ताला खुल जाता है).
 * - कॉल / गाना / चलते (ongoing) / DK के अपने / सिस्टम के notification कभी नहीं छिपते.
 */
object PrivacyScreen {
    private const val P_ON = "privacy_on"
    private const val P_SCOPE = "privacy_notif_scope"   // 13 नवंबर 2026: नया - पुरानी स्क्रीन-परत वाला "पूरा फ़ोन" अपने आप सभी ऐप का ताला न बने
    private const val P_APPS = "privacy_apps"

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE)

    fun on(c: Context) = sp(c).getBoolean(P_ON, false)
    fun setOn(c: Context, v: Boolean) { sp(c).edit().putBoolean(P_ON, v).apply(); if (!v) PrivacyLocked.clear() }
    fun scope(c: Context) = PrivacyCore.Scope.of(sp(c).getString(P_SCOPE, null))
    fun setScope(c: Context, s: PrivacyCore.Scope) = sp(c).edit().putString(P_SCOPE, s.id).apply()
    fun apps(c: Context): Set<String> = PrivacyCore.parse(sp(c).getString(P_APPS, ""))
    fun appOn(c: Context, pkg: String) = pkg in apps(c)
    fun setAppOn(c: Context, pkg: String, v: Boolean) {
        val s = apps(c).toMutableSet(); if (v) s.add(pkg) else s.remove(pkg)
        setApps(c, s)
    }
    /** एक साथ: सारे ऐप चुनें / कोई नहीं */
    fun setApps(c: Context, s: Set<String>) = sp(c).edit().putString(P_APPS, PrivacyCore.join(s)).apply()

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

    // ------------------------------------------------------------ सामने वाला ऐप (ताला कब खुले)
    @Volatile private var svcRef: DKAccessibilityService? = null
    private val main = Handler(Looper.getMainLooper())

    fun start(svc: DKAccessibilityService) { svcRef = svc }
    fun stop() { svcRef = null }

    /** accessibility: खिड़की बदली = सामने वाला ऐप बदला हो सकता है - वही ऐप खोल लिया तो उसका ताला खुले */
    fun onEvent(svc: DKAccessibilityService, e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (PrivacyLocked.isEmpty()) return
        main.removeCallbacks(recheck); main.postDelayed(recheck, 250)
    }

    private val recheck = Runnable {
        val s = svcRef ?: return@Runnable
        val p = try { s.rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
        // सिस्टम की पट्टी / कीबोर्ड = ऐप नहीं बदला
        if (!p.isNullOrBlank() && p != "com.android.systemui" && !p.contains("inputmethod") && !p.contains("keyboard"))
            PrivacyLocked.opened(p)       // वही ऐप खोल लिया = उसका 🔒 हटाओ (अब ऐप के अंदर दिखेगा)
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
        if (PrivacyCore.lockNotif(true, scope(c), sbn.packageName, c.packageName, apps(c), ongoing, isCall, isMedia)) {
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

/** DK Privacy से छिपे notification (सिर्फ़ memory) - island में "🔒 ऐप • N नए" */
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

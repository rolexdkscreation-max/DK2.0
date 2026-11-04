package com.dk.assistant

import android.app.AppOpsManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * नवंबर 2026 (मालिक): पेमेंट ऐप खुलते ही DK का फ़ोन कंट्रोल (accessibility) अपने-आप बंद, बाहर आते ही अपने-आप चालू.
 * नियम [PayGuardRules]. बंद: WRITE_SECURE_SETTINGS हो तो सूची से हटाओ, वरना disableSelf().
 * चालू: WRITE_SECURE_SETTINGS ज़रूरी (Android का नियम) - न हो तो एक टैप वाली notification.
 * बाहर आना पहचानना: "Usage access" (कौन सा ऐप सामने है) - न हो तो notification से.
 */
object PayGuard {
    private const val P_ON = "pay_guard_on"
    private const val P_PAUSED = "pay_guard_paused"
    private const val P_PKG = "pay_guard_pkg"
    private const val CH = "dk_payguard"
    private const val ID = 4311
    const val ACTION_RESUME = "com.dk.assistant.PAY_GUARD_RESUME"
    private const val REFRESH_MS = 10 * 60_000L
    private const val WATCH_MS = 800L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchJob: Job? = null
    @Volatile private var apps: List<PayGuardRules.App> = emptyList()
    @Volatile private var launchers: Set<String> = emptySet()
    @Volatile private var scannedAt = 0L
    @Volatile private var scanning = false
    @Volatile private var pausing = false

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences("dk_prefs", Context.MODE_PRIVATE)
    fun on(c: Context) = sp(c).getBoolean(P_ON, true)
    fun setOn(c: Context, v: Boolean) = sp(c).edit().putBoolean(P_ON, v).apply()
    fun appOn(c: Context, pkg: String) = sp(c).getBoolean("pay_guard_app_$pkg", PayGuardRules.defaultOn(pkg))
    fun setAppOn(c: Context, pkg: String, v: Boolean) = sp(c).edit().putBoolean("pay_guard_app_$pkg", v).apply()
    fun paused(c: Context) = sp(c).getBoolean(P_PAUSED, false)

    /** फ़ोन में पेमेंट वाले ऐप (भारी काम - main thread पर नहीं) */
    fun scan(c: Context): List<PayGuardRules.App> {
        val pm = c.packageManager
        val upi = try {
            pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("upi://pay")), 0).map { it.activityInfo.packageName }.toSet()
        } catch (_: Exception) { emptySet() }
        val all = try {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { PayGuardRules.App(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.activityInfo.packageName in upi) }
        } catch (_: Exception) { emptyList() }
        val extra = upi.filter { p -> all.none { it.pkg == p } }.map { p ->
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() } catch (_: Exception) { p }
            PayGuardRules.App(p, label, true)
        }
        launchers = try {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0).map { it.activityInfo.packageName }.toSet()
        } catch (_: Exception) { emptySet() }
        apps = PayGuardRules.detect(all + extra, c.packageName)
        scannedAt = System.currentTimeMillis()
        DKLog.d("पेमेंट ऐप मिले: ${apps.size} (${apps.joinToString { it.label }.take(200)})")
        return apps
    }

    fun detected(): List<PayGuardRules.App> = apps

    private fun refreshSoon(c: Context) {
        if (scanning || System.currentTimeMillis() - scannedAt < REFRESH_MS) return
        scanning = true
        val app = c.applicationContext
        scope.launch { try { scan(app) } catch (e: Exception) { DKLog.d("पेमेंट ऐप खोज: ${e.message}") } finally { scanning = false } }
    }

    fun guarded(c: Context): Set<String> = apps.filter { appOn(c, it.pkg) }.map { it.pkg }.toSet()

    /** accessibility सेवा जुड़ी (फिर से): रुकावट खत्म, notification हटाओ */
    fun onServiceBack(svc: Context) {
        pausing = false
        if (paused(svc)) DKLog.d("पेमेंट के बाद फ़ोन कंट्रोल फिर चालू")
        sp(svc).edit().putBoolean(P_PAUSED, false).apply()
        cancelNote(svc)
        scannedAt = 0L; refreshSoon(svc)
    }

    /** accessibility का हर event (सिर्फ़ खिड़की बदली / होम पर टैप) - बहुत हल्का */
    fun onEvent(svc: DKAccessibilityService, e: AccessibilityEvent) {
        if (pausing || !on(svc)) return
        refreshSoon(svc)
        val pkg = e.packageName?.toString() ?: return
        val g = guarded(svc)
        if (g.isEmpty()) return
        val target = when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> pkg.takeIf { PayGuardRules.shouldPause(true, it, g) }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> if (pkg in launchers) {
                val labels = apps.filter { it.pkg in g }.associate { it.pkg to it.label }
                PayGuardRules.tappedPkg(e.text?.joinToString(" ")?.takeIf { it.isNotBlank() } ?: e.contentDescription?.toString(), labels)
            } else null
            else -> null
        } ?: return
        pause(svc, target)
    }

    private fun pause(svc: DKAccessibilityService, pkg: String) {
        pausing = true
        val app = svc.applicationContext
        sp(app).edit().putBoolean(P_PAUSED, true).putString(P_PKG, pkg).apply()
        DKLog.d("पेमेंट ऐप ($pkg) - फ़ोन कंट्रोल कुछ देर के लिए बंद")
        note(app, waiting = true)
        watch(app, pkg)
        if (!removeSelf(app)) try { svc.disableSelf() } catch (e: Exception) {
            DKLog.d("disableSelf: ${e.message}")
            pausing = false; sp(app).edit().putBoolean(P_PAUSED, false).apply(); watchJob?.cancel(); cancelNote(app)
        }
    }

    private fun component(c: Context) = ComponentName(c, DKAccessibilityService::class.java).flattenToString()

    private fun removeSelf(c: Context): Boolean {
        if (!Setup.canWriteSecure(c)) return false
        return try {
            val cr = c.contentResolver
            val list = (Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
                .split(':').filter { it.isNotBlank() && !it.startsWith(c.packageName + "/") }
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":")); true
        } catch (e: Exception) { DKLog.d("पेमेंट: सूची से हटाना नाकाम ${e.message}"); false }
    }

    fun usageOk(c: Context): Boolean = try {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
            else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }

    fun openUsageAccess(c: Context) = try {
        c.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: Exception) { false }

    /** [from] के बाद सबसे आख़िर में सामने आया ऐप (और कब) */
    private fun lastForeground(c: Context, from: Long, to: Long): Pair<String, Long>? = try {
        val um = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val ev = um.queryEvents(from, to); val e = UsageEvents.Event()
        var last: Pair<String, Long>? = null
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            @Suppress("DEPRECATION")
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) last = e.packageName to e.timeStamp
        }
        last
    } catch (_: Exception) { null }

    /** ऐप / फ़ोन शुरू, हर 15 मिनट वाली जाँच: रुका हुआ है पर देखने वाला नहीं चल रहा (process फिर शुरू हुई) = फिर देखो */
    fun recover(c: Context) {
        if (!paused(c)) return
        if (DKAccessibilityService.instance != null) { onServiceBack(c); return }
        if (watchJob?.isActive != true) watch(c.applicationContext, sp(c).getString(P_PKG, null))
    }

    private fun watch(app: Context, payPkg: String?) {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            if (apps.isEmpty()) try { scan(app) } catch (_: Exception) {}
            var fg: String? = payPkg; var since = System.currentTimeMillis()
            var from = since - 2_000L
            var sawOff = DKAccessibilityService.instance == null
            while (isActive && paused(app)) {
                delay(WATCH_MS)
                // सेवा बंद होकर फिर जुड़ी (आपने खुद चालू की) = रुकावट खत्म; बंद होने से पहले वाली गिनती नहीं
                if (DKAccessibilityService.instance == null) sawOff = true
                else if (sawOff) { onServiceBack(app); break } else continue
                if (!usageOk(app)) continue          // notification से (टैप करके) चालू होगा
                val now = System.currentTimeMillis()
                lastForeground(app, from, now)?.let { (p, t) -> if (p != fg) { fg = p; since = t } }
                from = now - 1_000L
                if (PayGuardRules.shouldResume(fg, since, System.currentTimeMillis(), guarded(app), app.packageName)) {
                    resume(app, "पेमेंट ऐप से बाहर ($fg)"); break
                }
            }
        }
    }

    /** फिर से चालू. WRITE_SECURE_SETTINGS न हो तो एक टैप वाली notification (Android खुद चालू नहीं करने देता) */
    fun resume(c: Context, why: String) {
        val app = c.applicationContext
        if (Setup.canWriteSecure(app) && Setup.selfEnableAccessibility(app)) {
            DKLog.d("$why - फ़ोन कंट्रोल अपने-आप फिर चालू")
            sp(app).edit().putBoolean(P_PAUSED, false).apply()
            pausing = false; cancelNote(app)
        } else {
            DKLog.d("$why - अपने-आप चालू की इजाज़त नहीं, notification")
            sp(app).edit().putBoolean(P_PAUSED, false).apply()
            pausing = false; note(app, waiting = false)
        }
    }

    private fun note(c: Context, waiting: Boolean) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null)
                nm.createNotificationChannel(NotificationChannel(CH, "DK पेमेंट सुरक्षा", NotificationManager.IMPORTANCE_DEFAULT))
            val auto = Setup.canWriteSecure(c)
            val pi = if (auto) PendingIntent.getBroadcast(c, ID, Intent(c, PayGuardReceiver::class.java).setAction(ACTION_RESUME),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                else PendingIntent.getActivity(c, ID, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val text = UiLang.s(PayGuardText.note(waiting, auto, usageOk(c)))
            val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(c, CH)
                else @Suppress("DEPRECATION") android.app.Notification.Builder(c)
            nm.notify(ID, b.setSmallIcon(R.drawable.ic_dk)
                .setContentTitle(UiLang.s(if (waiting) "पेमेंट के लिए DK फ़ोन कंट्रोल बंद" else "DK फ़ोन कंट्रोल फिर चालू करें"))
                .setContentText(text)
                .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                .setOnlyAlertOnce(true)
                .setContentIntent(pi).setAutoCancel(true).build())
        } catch (e: Exception) { DKLog.d("पेमेंट notification: ${e.message}") }
    }

    private fun cancelNote(c: Context) = try { c.getSystemService(NotificationManager::class.java)?.cancel(ID); Unit } catch (_: Exception) {}
}

/** notification के शब्द (pure) */
object PayGuardText {
    fun note(waiting: Boolean, auto: Boolean, usage: Boolean): String = when {
        waiting && auto && usage -> "पेमेंट ऐप खुला है, इसलिए DK का फ़ोन कंट्रोल कुछ देर के लिए बंद है। पेमेंट करके बाहर आते ही अपने-आप चालू होगा। (अभी चालू करना हो तो टैप करें)"
        waiting && auto -> "पेमेंट ऐप खुला है, इसलिए DK का फ़ोन कंट्रोल बंद है। पेमेंट के बाद यहाँ टैप करें - DK फिर चालू होगा। (अपने-आप के लिए DK में \"Usage access\" दें)"
        waiting -> "पेमेंट ऐप खुला है, इसलिए DK का फ़ोन कंट्रोल बंद है। पेमेंट के बाद यहाँ टैप करके DK चालू करें।"
        else -> "पेमेंट हो गया। DK का फ़ोन कंट्रोल फिर चालू करने के लिए टैप करें, फिर DK पर ON करें।"
    }
}

/** notification टैप (WRITE_SECURE_SETTINGS हो तब): तुरंत फिर चालू */
class PayGuardReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) {
        if (i?.action == PayGuard.ACTION_RESUME) PayGuard.resume(c, "notification टैप")
    }
}

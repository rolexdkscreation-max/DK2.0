package com.dk.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

/**
 * 21 नवंबर 2026 (मालिक): DK का अपना ऐप लॉक. DK की accessibility सेवा पहले से हर "नया ऐप सामने आया" event पाती है
 * (TYPE_WINDOW_STATE_CHANGED) - वहीं से ताले वाला ऐप सामने आते ही [AppLockActivity] ऊपर. कोई नई हमेशा-चलने वाली
 * सेवा / timer नहीं = बैटरी पर असर लगभग शून्य. नियम [AppLockRules], सेटिंग्स [AppLockStore].
 */
object AppLock {
    val session = AppLockRules.Session()
    private var recv: BroadcastReceiver? = null
    @Volatile private var shownFor: String? = null
    @Volatile private var shownAt = 0L

    fun start(svc: DKAccessibilityService) {
        val app = svc.applicationContext
        session.graceMs = AppLockStore.grace(app)
        if (recv != null) return
        try {
            val r = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, i: Intent?) { if (i?.action == Intent.ACTION_SCREEN_OFF) session.screenOff() }
            }
            ContextCompat.registerReceiver(app, r, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
            recv = r
        } catch (e: Exception) { Quiet.note(e) }
    }

    fun stop(c: Context) {
        try { recv?.let { c.applicationContext.unregisterReceiver(it) } } catch (e: Exception) { Quiet.note(e) }
        recv = null
    }

    fun onEvent(svc: DKAccessibilityService, e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val app = svc.applicationContext
        if (!AppLockStore.on(app) || !AppLockStore.hasPin(app)) return
        val pkg = AppLockRules.front(e.packageName?.toString(), e.className?.toString(), app.packageName) ?: return
        session.graceMs = AppLockStore.grace(app)
        if (session.onFront(pkg, AppLockStore.locked(app, pkg), System.currentTimeMillis())) show(app, pkg)
    }

    /** ताला खोलना सफल */
    fun unlocked(c: Context, pkg: String) {
        session.unlocked(pkg)
        shownFor = null
        AppLockStore.success(c)
    }

    private fun show(c: Context, pkg: String) {
        val now = SystemClock.elapsedRealtime()
        if (shownFor == pkg && now - shownAt < 1_200L) return     // एक ही ऐप के कई event - एक ही ताला
        shownFor = pkg; shownAt = now
        try {
            c.startActivity(Intent(c, AppLockActivity::class.java).putExtra(AppLockActivity.EXTRA_PKG, pkg).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
        } catch (e: Exception) { DKLog.d("ऐप लॉक: ताला स्क्रीन नहीं खुली (${e.message})") }
    }

    /** ताले की स्क्रीन बिना खोले बंद हुई (back / home) - अगली बार फिर ताला */
    fun dismissed(pkg: String) { if (shownFor == pkg) shownFor = null }
}

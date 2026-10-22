package com.dk.assistant

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.io.File

/**
 * Poore app ki shuruaat:
 *  - koi crash ho to uski report file me save (Settings → गड़बड़ी रिपोर्ट me dikhti hai)
 *  - DK ki apni screen khuli hai ya nahi (tab chhota orb bubble chhupa rehta hai)
 */
class DKApp : Application() {

    override fun onCreate() {
        super.onCreate()
        DKState.init(this)
        Prefs.loadLang(this)
        removeLegacyAntiTheftState()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                File(filesDir, "last_crash.txt").writeText(
                    java.text.SimpleDateFormat("dd MMM HH:mm:ss", java.util.Locale.US).format(java.util.Date()) +
                        " [${t.name}] version ${Updater.currentName(this)}\n" + e.stackTraceToString().take(4000))
            } catch (_: Exception) {}
            // Background thread ki galti se poora app (aur Accessibility) band na ho.
            // Baar-baar crash hone par Android khud Accessibility OFF kar deta tha.
            if (t !== android.os.Looper.getMainLooper().thread) {
                DKLog.d("Background galti sambhali: ${e.message}")
                return@setDefaultUncaughtExceptionHandler
            }
            prev?.uncaughtException(t, e)
        }
        // Main thread par galti: crash ki jagah log karke chalte raho (max 20 baar)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            var saved = 0
            while (true) {
                try { android.os.Looper.loop() } catch (e: Throwable) {
                    saved++
                    try {
                        File(filesDir, "last_crash.txt").writeText(
                            java.text.SimpleDateFormat("dd MMM HH:mm:ss", java.util.Locale.US).format(java.util.Date()) +
                                " [main, sambhala gaya] version ${Updater.currentName(this)}\n" + e.stackTraceToString().take(4000))
                    } catch (_: Exception) {}
                    if (saved > 20) throw e
                }
            }
        }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { AppVisible.set(true) }
            override fun onActivityPaused(a: Activity) { AppVisible.set(false) }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    /** Existing installs may still have the old guard's device-admin flag and notification channel. */
    private fun removeLegacyAntiTheftState() {
        getSharedPreferences("dk_prefs", MODE_PRIVATE).edit().remove("anti_theft_guard_on").apply()
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                getSystemService(android.app.NotificationManager::class.java)
                    ?.deleteNotificationChannel("anti_theft_guard_channel")
            }
        } catch (_: Exception) {}
        try {
            val oldAdmin = android.content.ComponentName(
                packageName, "$packageName.antitheft.GuardDeviceAdminReceiver"
            )
            val dpm = getSystemService(android.app.admin.DevicePolicyManager::class.java)
            if (dpm?.isAdminActive(oldAdmin) == true) dpm.removeActiveAdmin(oldAdmin)
        } catch (_: Exception) {}
    }

    companion object {
        fun lastCrash(c: android.content.Context): String =
            try { File(c.filesDir, "last_crash.txt").takeIf { it.exists() }?.readText() ?: "" } catch (e: Exception) { "" }
        fun clearCrash(c: android.content.Context) { try { File(c.filesDir, "last_crash.txt").delete() } catch (_: Exception) {} }
    }
}

/** DK ki koi screen saamne hai? (ek screen se dusri par jaate waqt jhapak na ho, isliye thoda ruk kar) */
object AppVisible {
    @Volatile var visible = false
        private set
    private val h = android.os.Handler(android.os.Looper.getMainLooper())
    var onChange: ((Boolean) -> Unit)? = null
    private var pending: Runnable? = null

    fun set(v: Boolean) {
        pending?.let { h.removeCallbacks(it) }
        val r = Runnable { if (visible != v) { visible = v; onChange?.invoke(v) } }
        pending = r
        if (v) h.post(r) else h.postDelayed(r, 350)
    }
}

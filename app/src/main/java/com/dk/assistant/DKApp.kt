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
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                File(filesDir, "last_crash.txt").writeText(
                    java.text.SimpleDateFormat("dd MMM HH:mm:ss", java.util.Locale.US).format(java.util.Date()) +
                        " [${t.name}] version ${Updater.currentName(this)}\n" + e.stackTraceToString().take(4000))
            } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
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

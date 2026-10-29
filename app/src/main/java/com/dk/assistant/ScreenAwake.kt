package com.dk.assistant

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.WindowManager

/**
 * v4.1: DK ऐप सामने खुला हो तब तक स्क्रीन बंद न हो.
 * सिर्फ़ खिड़की का FLAG_KEEP_SCREEN_ON - कोई wake lock नहीं. ऐप पीछे गई / दूसरा ऐप खुला / बंद हुई = flag हटा,
 * फिर फ़ोन अपने आम समय पर स्क्रीन बंद करेगा. हर Activity पर अपने-आप (नई screen जुड़े तो भी).
 */
object ScreenAwake : Application.ActivityLifecycleCallbacks {
    /** JVM test के लिए: कौन-सी हालत में flag लगे */
    fun keepOn(event: String): Boolean = event == "resumed"

    private fun set(a: Activity, on: Boolean) {
        try {
            val w = a.window ?: return
            if (on) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Exception) {}
    }

    override fun onActivityResumed(a: Activity) = set(a, keepOn("resumed"))
    override fun onActivityPaused(a: Activity) = set(a, keepOn("paused"))
    override fun onActivityStopped(a: Activity) = set(a, keepOn("stopped"))
    override fun onActivityDestroyed(a: Activity) = set(a, keepOn("destroyed"))
    override fun onActivityCreated(a: Activity, b: Bundle?) {}
    override fun onActivityStarted(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
}

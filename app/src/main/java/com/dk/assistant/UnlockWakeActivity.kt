package com.dk.assistant

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/**
 * नवंबर 2026 (मालिक: "अनलॉक बोला तो 1-2 सेकंड में खुल जाए"): आवाज़ से अनलॉक के लिए छोटी पारदर्शी स्क्रीन।
 *
 * - लॉक स्क्रीन के ऊपर खुलती है और स्क्रीन तुरंत जगाती है (पहले सिर्फ़ wake lock - Android 14 पर कई फ़ोन में
 *   स्क्रीन जगती ही नहीं थी, तो अंधेरी स्क्रीन पर swipe बेकार जाता और PIN वाला हिस्सा नहीं खुलता)।
 * - Android से खुद PIN / पैटर्न वाला हिस्सा माँगती है (requestDismissKeyguard) - swipe का अंदाज़ा नहीं।
 *   PIN सेव हो तो [PhoneUnlocker] डालता है; न हो तो मालिक फ़िंगरप्रिंट / PIN खुद डालें।
 * - फ़ोन खुले / मालिक रद्द करे / 12 सेकंड - अपने-आप बंद, पीछे वही ऐप जो पहले खुला था।
 * - कुछ नहीं दिखाती, कुछ सेव नहीं करती।
 */
class UnlockWakeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var asked = false

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        main.postDelayed({ done() }, MAX_MS)
    }

    override fun onResume() {
        super.onResume()
        if (asked) return
        asked = true
        val km = getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isKeyguardLocked) { done(); return }
        try {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { done() }
                override fun onDismissCancelled() { done() }
                override fun onDismissError() { DKLog.d("फ़ोन अनलॉक: Android ने PIN वाला हिस्सा नहीं खोला"); done() }
            })
        } catch (e: Exception) { DKLog.d("फ़ोन अनलॉक: ${e.message}"); done() }
    }

    private fun done() {
        main.removeCallbacksAndMessages(null)
        if (!isFinishing) finish()
        overridePendingTransitionCompat()
    }

    @Suppress("DEPRECATION")
    private fun overridePendingTransitionCompat() { try { overridePendingTransition(0, 0) } catch (_: Exception) {} }

    override fun onDestroy() { main.removeCallbacksAndMessages(null); super.onDestroy() }

    companion object {
        /** इतनी देर बाद अपने-आप बंद (PIN डलने / मालिक के फ़िंगरप्रिंट का समय) */
        const val MAX_MS = 12_000L

        /** true = स्क्रीन खुल गई (Android PIN वाला हिस्सा दिखाएगा); false = नहीं खुली, पुराना swipe वाला रास्ता */
        fun open(c: Context): Boolean = try {
            c.startActivity(Intent(c, UnlockWakeActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_USER_ACTION))
            true
        } catch (e: Exception) { DKLog.d("फ़ोन अनलॉक: जगाने वाली स्क्रीन नहीं खुली (${e.message})"); false }
    }
}

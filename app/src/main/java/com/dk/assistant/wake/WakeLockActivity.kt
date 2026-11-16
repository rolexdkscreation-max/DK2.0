package com.dk.assistant.wake

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.dk.assistant.DKLog
import com.dk.assistant.DKState

/**
 * 16 नवंबर 2026 (मालिक: "फ़ोन लॉक / स्क्रीन बंद हो तब भी "हे डीके" पर जागे"):
 * लॉक स्क्रीन के ऊपर छोटी पारदर्शी स्क्रीन - स्क्रीन जगाती है (showWhenLocked / turnScreenOn), नीचे एक छोटी लाइन.
 * फ़ोन अनलॉक नहीं करती. Live खत्म / फ़ोन अनलॉक / 2 मिनट = अपने-आप बंद.
 * निजी काम (मैसेज पढ़ना वगैरह) लॉक में नहीं - [WakeRules.lockedPrivate].
 */
class WakeLockActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val startedAt = System.currentTimeMillis()

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val d = resources.displayMetrics.density
        val tv = TextView(this).apply {
            text = NOTE
            setTextColor(Color.WHITE); textSize = 13f
            setBackgroundColor(0x99000000.toInt())
            setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
        }
        setContentView(FrameLayout(this).apply {
            addView(tv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (48 * d).toInt() })
        })
        tick()
    }

    private fun tick() {
        val km = getSystemService(KeyguardManager::class.java)
        val unlocked = km?.isKeyguardLocked == false
        if (unlocked) WakeWord.sessionLocked = false
        if (!DKState.live || unlocked || System.currentTimeMillis() - startedAt > MAX_MS) { done(); return }
        main.postDelayed({ tick() }, 500)
    }

    @Suppress("DEPRECATION")
    private fun done() {
        main.removeCallbacksAndMessages(null)
        if (!isFinishing) finish()
        try { overridePendingTransition(0, 0) } catch (e: Exception) { com.dk.assistant.Quiet.note(e) }
    }

    override fun onDestroy() { main.removeCallbacksAndMessages(null); super.onDestroy() }

    companion object {
        const val MAX_MS = 120_000L
        const val NOTE = "डीके जुड़ा है • फ़ोन लॉक है - निजी काम के लिए अनलॉक करें"

        fun start(c: Context) = try {
            c.startActivity(Intent(c, WakeLockActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_USER_ACTION))
            true
        } catch (e: Exception) { DKLog.d("वेक वर्ड: लॉक स्क्रीन पर नहीं खुला (${e.message})"); false }
    }
}

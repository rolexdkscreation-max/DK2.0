package com.dk.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference

/**
 * नवंबर 2026: [FindPhone] बजते समय - लॉक स्क्रीन के ऊपर स्क्रीन जलाकर "मैं इधर हूँ बॉस".
 * कहीं भी छुएँ / कोई भी बटन (volume / power) = बजना बंद. फ़ोन नहीं खुलता (PIN वैसे ही).
 */
class FindPhoneActivity : Activity() {

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!FindPhone.active) { finish(); return }
        current = WeakReference(this)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setBackgroundColor(Color.BLACK)
            setOnClickListener { stop("स्क्रीन छुई") }
        }
        fun tv(t: String, size: Float, color: Int) = TextView(this).apply {
            text = UiLang.s(t); textSize = size; setTextColor(color); gravity = Gravity.CENTER
            val p = (24 * resources.displayMetrics.density).toInt(); setPadding(p, p / 2, p, p / 2)
        }
        box.addView(tv("मैं इधर हूँ बॉस", 34f, Color.WHITE))
        box.addView(tv("कहीं भी छूकर या कोई भी बटन दबाकर बंद करें", 16f, 0xCCFFFFFF.toInt()))
        setContentView(box)
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.action == MotionEvent.ACTION_DOWN) stop("स्क्रीन छुई")
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event?.action == KeyEvent.ACTION_DOWN) { stop("बटन (${event.keyCode})"); return true }
        return super.dispatchKeyEvent(event)
    }

    private fun stop(why: String) {
        FindPhone.stop(why)
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var current: WeakReference<FindPhoneActivity>? = null

        fun show(c: Context) {
            try {
                c.startActivity(Intent(c, FindPhoneActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS))
            } catch (e: Exception) { DKLog.d("फ़ोन ढूँढो: स्क्रीन नहीं खुली (${e.message})") }
        }

        fun closeAll() {
            val a = current?.get() ?: return
            a.runOnUiThread { if (!a.isFinishing) a.finish() }
        }
    }
}

package com.dk.assistant

import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * नवंबर 2026 (मालिक): "DK कोई भी काम कर रहा हो - किसी भी ऐप में - तब तक स्क्रीन बंद नहीं होनी चाहिए,
 * और स्क्रीन पर दिखना चाहिए कि DK क्या कर रहा है (ऐसा नहीं कि बोले 'बैकग्राउंड में कर रहा हूँ').
 * सिर्फ़ बात कर रहा हो तो यूज़र चाहे तो स्क्रीन बंद कर सकता है - DK पीछे चलता रहेगा."
 *
 * इसलिए: जब तक DK फ़ोन पर सच में काम कर रहा है (AgentRunner / सोशल रिव्यू / WORKING हालत), हर ऐप के ऊपर
 * एक पतली पट्टी "DK काम कर रहा है • <अभी का कदम>" - और उसी खिड़की पर FLAG_KEEP_SCREEN_ON (wake lock नहीं:
 * पट्टी हटी = flag हटा, फिर फ़ोन अपने आम समय पर). पट्टी छूने लायक नहीं (FLAG_NOT_TOUCHABLE) - नीचे का ऐप
 * वैसे ही चलता है; DK का screen-reader भी इसे नहीं पढ़ता (सिर्फ़ TYPE_APPLICATION खिड़कियाँ पढ़ता है).
 * सिर्फ़ बातचीत (सुनना/बोलना) = पट्टी नहीं, स्क्रीन आम तरीके से बंद हो सकती है.
 */
class WorkAwake(private val svc: DKAccessibilityService) {
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var root: TextView? = null
    private var keepOnly: View? = null
    @Volatile private var released = false
    private var modeOnlySince = 0L
    private var busySince = 0L
    private val density get() = svc.resources.displayMetrics.density
    private fun px(dp: Float) = (dp * density).roundToInt()

    private val tick = object : Runnable {
        override fun run() {
            if (released) return
            try { sync() } catch (e: Exception) { DKLog.d("WorkAwake: ${e.javaClass.simpleName}") }
            main.postDelayed(this, 900)
        }
    }

    fun start() { main.post(tick) }

    fun release() {
        released = true
        main.removeCallbacks(tick)
        removeBanner(); removeKeep()
    }

    private fun phoneJobs(): List<TaskStatus.Job> = TaskStatus.all().filter { it.active && it.key !in WorkAwakeRules.REMOTE_KEYS }

    private fun sync() {
        val jobs = phoneJobs()
        val busy = try { svc.agent.isBusy } catch (_: Exception) { false }
        val now = android.os.SystemClock.uptimeMillis()
        val modeOnly = !busy && jobs.isEmpty() && DKState.mode == Mode.WORKING
        if (!modeOnly) modeOnlySince = 0L else if (modeOnlySince == 0L) modeOnlySince = now
        if (!busy) busySince = 0L else if (busySince == 0L) busySince = now
        val working = WorkAwakeRules.working(busy, DKState.mode, jobs.size, if (modeOnly) now - modeOnlySince else 0L, if (busy) now - busySince else 0L)
        val fg = try { svc.currentPackage() } catch (_: Exception) { "" }
        if (!working) { removeBanner(); removeKeep(); return }
        if (WorkAwakeRules.showBanner(true, fg, svc.packageName)) {
            removeKeep()
            val text = WorkAwakeRules.label(jobs.firstOrNull()?.last.orEmpty(), DKState.hintText)
            val r = root ?: showBanner() ?: return
            if (r.text != text) r.text = text
        } else {
            // DK का अपना ऐप सामने: पट्टी नहीं, पर स्क्रीन जगी (ऐप का ScreenAwake भी यही करता है - यह पक्का करने को)
            removeBanner(); if (keepOnly == null) showKeep()
        }
    }

    private fun flags(touchable: Boolean = false) =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

    @SuppressLint("SetTextI18n")
    private fun showBanner(): TextView? {
        val tv = TextView(svc).apply {
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(px(14f), px(6f), px(14f), px(6f))
            background = GradientDrawable().apply { cornerRadius = px(18f).toFloat(); setColor(0xD9152A5C.toInt()); setStroke(px(1f), 0xFF5B8CFF.toInt()) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            text = "DK काम कर रहा है…"
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags(), PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = px(30f)
        }
        return try { wm.addView(tv, p); root = tv; tv } catch (e: Exception) { DKLog.d("काम की पट्टी नहीं लगी: ${e.javaClass.simpleName}"); null }
    }

    /** सिर्फ़ स्क्रीन जगी रखने को 1px की अदृश्य खिड़की */
    private fun showKeep() {
        val v = View(svc).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        val p = WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags(), PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.START }
        try { wm.addView(v, p); keepOnly = v } catch (_: Exception) {}
    }

    private fun removeBanner() { root?.let { try { wm.removeView(it) } catch (_: Exception) {} }; root = null }
    private fun removeKeep() { keepOnly?.let { try { wm.removeView(it) } catch (_: Exception) {} }; keepOnly = null }
}

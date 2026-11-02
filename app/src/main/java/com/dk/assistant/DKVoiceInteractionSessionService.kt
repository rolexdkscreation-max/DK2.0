package com.dk.assistant

import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.os.Bundle

class DKVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onCreate() {
        super.onCreate()
        try {
            android.util.Log.e("DKAssist", "DKVoiceInteractionSessionService onCreate")
        } catch (e: Exception) {
            android.util.Log.e("DKAssist", "Error in DKVoiceInteractionSessionService onCreate", e); com.dk.assistant.DKBug.report("assistant", e, "Error in DKVoiceInteractionSessionService onCreate", alsoLog = true)
        }
    }

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        try {
            android.util.Log.e("DKAssist", "DKVoiceInteractionSessionService onNewSession")
            return DKVoiceInteractionSession(this)
        } catch (e: Exception) {
            android.util.Log.e("DKAssist", "Error in DKVoiceInteractionSessionService onNewSession", e); com.dk.assistant.DKBug.report("assistant", e, "Error in DKVoiceInteractionSessionService onNewSession", alsoLog = true)
            return DKVoiceInteractionSession(this)
        }
    }
}

class DKVoiceInteractionSession(context: android.content.Context) : VoiceInteractionSession(context) {

    private var orbView: OrbView? = null
    private val h = android.os.Handler(android.os.Looper.getMainLooper())
    private var shownAt = 0L
    private var liveUpAt = 0L
    private var leaving = false
    private val watch = Runnable { checkLeave() }
    private val onState: () -> Unit = { checkLeave() }
    private var micButton: android.widget.Button? = null

    override fun onCreateContentView(): android.view.View {
        val context = context
        val container = android.widget.FrameLayout(context).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            isClickable = false
            isFocusable = false
        }

        val innerLayout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                bottomMargin = 100
            }
            isClickable = false
            isFocusable = false
        }

        val topRow = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // नवंबर 2026 (मालिक): popup पर ✕ नहीं - अपने-आप animation से जाता है
        innerLayout.addView(topRow)

        orbView = OrbView(context).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(300, 300).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            setOnClickListener {
                try {
                    val intent = android.content.Intent(context, VoiceActivity::class.java).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    android.util.Log.e("DKAssist", "Error starting VoiceActivity from orb", e); com.dk.assistant.DKBug.report("assistant", e, "Error starting VoiceActivity from orb", alsoLog = true)
                }
            }
        }
        innerLayout.addView(orbView)

        micButton = android.widget.Button(context).apply {
            text = "Mic Permission"
            visibility = android.view.View.GONE
            setOnClickListener {
                try {
                    val intent = android.content.Intent(context, VoiceActivity::class.java).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    android.util.Log.e("DKAssist", "Error opening voice activity for mic", e); com.dk.assistant.DKBug.report("assistant", e, "Error opening voice activity for mic", alsoLog = true)
                }
            }
        }
        innerLayout.addView(micButton)

        container.addView(innerLayout)
        return container
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // v5.1 (मालिक): power बटन से बुलाया = DK Live उसी पल पीछे से जुड़ना शुरू, ओर्ब दिखने के साथ-साथ
        // (UI के किसी कदम का इंतज़ार नहीं). माइक की इजाज़त न हो = Live नहीं, नीचे वाला बटन दिखता है.
        // Accessibility यहाँ नई चालू नहीं की जाती - सिर्फ़ पहले से चालू (Not working) वाली फिर जोड़ी जाती है.
        // नवंबर 2026: पहले से चालू पर "Not working" वाली सेवा फिर जोड़ो (सिर्फ़ adb इजाज़त हो तो; नई इजाज़त नहीं)
        try { Setup.healAccessibility(context) } catch (_: Exception) {}
        try { DKAccessibilityService.instance?.invokedNow("power बटन / assistant") }
        catch (e: Exception) { android.util.Log.e("DKAssist", "Live शुरू नहीं हुआ", e); com.dk.assistant.DKBug.report("assistant", e, "Live शुरू नहीं हुआ", alsoLog = true) }
        try {
            android.util.Log.e("DKAssist", "DKVoiceInteractionSession onShow")
            val window = window?.window
            if (window != null) {
                window.setLayout(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
                window.setGravity(android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL)
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                )
                window.setBackgroundDrawableResource(android.R.color.transparent)
            }

            val hasMic = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (hasMic) {
                micButton?.visibility = android.view.View.GONE
            } else {
                micButton?.visibility = android.view.View.VISIBLE
            }

            // नवंबर 2026 (मालिक): power बटन = popup अच्छे animation से नीचे से आए, बात चलने तक रहे, Live जुड़ने के
            // थोड़ी देर बाद नीचे की ओर animation से चला जाए. popup के रहते छोटा तैरता ORB नहीं (दो ORB नहीं); popup जाते ही
            // ऐप बाहर हो तो छोटा ORB नीचे बीच में आता है. ✕ नहीं.
            val px = (Prefs.floatOrbDp(context) * 1.6f * context.resources.displayMetrics.density).toInt()
            orbView?.let { o -> o.layoutParams = o.layoutParams.apply { width = px; height = px }; o.visibility = android.view.View.VISIBLE }
            OrbRules.popupShowing = true
            DKAccessibilityService.instance?.let { it.refreshOrb() }   // Settings का switch जैसा है वैसा (ज़बरदस्ती चालू नहीं)
            shownAt = System.currentTimeMillis(); liveUpAt = 0L; leaving = false
            enterAnim()
            DKState.listen(onState)
            h.removeCallbacks(watch); h.postDelayed(watch, 400)
        } catch (e: Exception) {
            android.util.Log.e("DKAssist", "Error in DKVoiceInteractionSession onShow", e); com.dk.assistant.DKBug.report("assistant", e, "Error in DKVoiceInteractionSession onShow", alsoLog = true)
        }
    }

    /** नीचे से ऊपर, छोटे से अपने आकार में, धुँधले से साफ़ - हल्का उछाल */
    private fun enterAnim() {
        val o = orbView ?: return
        val d = context.resources.displayMetrics.density
        o.alpha = 0f; o.scaleX = 0.55f; o.scaleY = 0.55f; o.translationY = 140f * d
        o.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setDuration(460)
            .setInterpolator(android.view.animation.OvershootInterpolator(0.9f)).start()
    }

    /** बात चल रही हो तो रुको; Live जुड़ा + थोड़ी देर = नीचे की ओर फिसलकर जाओ */
    private fun checkLeave() {
        if (leaving || shownAt == 0L) return
        val now = System.currentTimeMillis()
        if (OrbRules.liveUp(DKState.linkStatus)) { if (liveUpAt == 0L) liveUpAt = now } else liveUpAt = 0L
        val up = if (liveUpAt == 0L) 0L else now - liveUpAt
        if (OrbRules.popupShouldLeave(now - shownAt, up, DKState.shownMode(), DKState.level)) leave()
        else { h.removeCallbacks(watch); h.postDelayed(watch, 400) }
    }

    private fun leave() {
        leaving = true
        h.removeCallbacks(watch)
        val o = orbView
        val done = { try { hide() } catch (_: Exception) {} }
        if (o == null) { done(); return }
        val d = context.resources.displayMetrics.density
        o.animate().alpha(0f).scaleX(0.7f).scaleY(0.7f).translationY(160f * d).setDuration(380)
            .setInterpolator(android.view.animation.AccelerateInterpolator()).withEndAction { done() }.start()
    }

    private fun cleanup() {
        h.removeCallbacks(watch)
        DKState.unlisten(onState)
        shownAt = 0L
        if (OrbRules.popupShowing) { OrbRules.popupShowing = false; DKAccessibilityService.instance?.refreshOrb() }
    }

    override fun onHide() {
        cleanup()
        super.onHide()
        try {
            android.util.Log.e("DKAssist", "DKVoiceInteractionSession onHide")
        } catch (e: Exception) {
            android.util.Log.e("DKAssist", "Error in DKVoiceInteractionSession onHide", e); com.dk.assistant.DKBug.report("assistant", e, "Error in DKVoiceInteractionSession onHide", alsoLog = true)
        }
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
        try {
            android.util.Log.e("DKAssist", "DKVoiceInteractionSession onDestroy")
        } catch (e: Exception) {
            android.util.Log.e("DKAssist", "Error in DKVoiceInteractionSession onDestroy", e); com.dk.assistant.DKBug.report("assistant", e, "Error in DKVoiceInteractionSession onDestroy", alsoLog = true)
        }
    }
}
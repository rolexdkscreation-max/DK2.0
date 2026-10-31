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
            android.util.Log.e("DKAssist", "Error in DKVoiceInteractionSessionService onCreate", e)
        }
    }

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        try {
            android.util.Log.e("DKAssist", "DKVoiceInteractionSessionService onNewSession")
            return DKVoiceInteractionSession(this)
        } catch (e: Exception) {
            android.util.Log.e("DKAssist", "Error in DKVoiceInteractionSessionService onNewSession", e)
            return DKVoiceInteractionSession(this)
        }
    }
}

/**
 * v5.1 (मालिक): DK default assistant (power बटन) से बुलाई = Live उसी पल पीछे से जुड़ना शुरू, साथ-साथ DK की स्क्रीन (ओर्ब).
 * UI के किसी कदम का इंतज़ार नहीं. माइक की इजाज़त न हो तो Live नहीं (स्क्रीन पर इजाज़त का बटन).
 * Accessibility यहाँ कभी चालू नहीं की जाती - service न चल रही हो तो सिर्फ़ स्क्रीन खुलती है और वहीं साफ़ बताया जाता है.
 */
class DKVoiceInteractionSession(context: android.content.Context) : VoiceInteractionSession(context) {
    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        // अपनी खाली खिड़की नहीं - DK की स्क्रीन (VoiceActivity) ही दिखती है
        try { setUiEnabled(false) } catch (_: Exception) {}
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // 1) Live पहले (पीछे से, तुरंत)
        try { DKAccessibilityService.instance?.invokedNow("power बटन / assistant") }
        catch (e: Exception) { android.util.Log.e("DKAssist", "Live शुरू नहीं हुआ", e) }
        // 2) साथ-साथ DK की स्क्रीन
        val i = android.content.Intent(context, VoiceActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try { startAssistantActivity(i) }
        catch (e: Exception) {
            android.util.Log.e("DKAssist", "assistant activity नहीं खुली", e)
            try { context.startActivity(i) } catch (_: Exception) {}
        }
    }
}
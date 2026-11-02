package com.dk.assistant

import android.service.voice.VoiceInteractionService

import android.util.Log

class DKVoiceInteractionService : VoiceInteractionService() {
    override fun onCreate() {
        try {
            super.onCreate()
        } catch (e: Exception) {
            Log.e("DKAssist", "Error in DKVoiceInteractionService.onCreate", e); com.dk.assistant.DKBug.report("assistant", e, "Error in DKVoiceInteractionService.onCreate", alsoLog = true)
        }
    }

    override fun onReady() {
        try {
            super.onReady()
            // नवंबर 2026: assistant सेवा Android खुद जोड़ता है - DK की रुकी Accessibility (Not working) यहीं से फिर जोड़ो
            // boot पर Android Accessibility अपने-आप जोड़ रहा होता है - तुरंत off/on करने से वह टूटे. थोड़ा रुककर, तब भी न जुड़ी हो तभी
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                { try { Setup.healAccessibility(this) } catch (_: Exception) {} }, Setup.HEAL_DELAY_MS)
        } catch (e: Exception) {
            Log.e("DKAssist", "Error in DKVoiceInteractionService.onReady", e); com.dk.assistant.DKBug.report("assistant", e, "Error in DKVoiceInteractionService.onReady", alsoLog = true)
        }
    }
}
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
            // 16 नवंबर 2026 (मालिक: "पहले Android का अपना hotword आज़माओ, न चले तो हमारा मॉडल"):
            // जाँच करके नतीजा Settings में दिखाओ. डिफ़ॉल्ट असिस्टेंट होने पर Android 14+ में भी माइक वाली सेवा यहीं से शुरू हो सकती है
            probeHotword()
            if (com.dk.assistant.wake.WakeWord.on(this) && !Prefs.micUserOff(this)) {
                try { Setup.startKeepAlive(this) } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e("DKAssist", "Error in DKVoiceInteractionService.onReady", e); com.dk.assistant.DKBug.report("assistant", e, "Error in DKVoiceInteractionService.onReady", alsoLog = true)
        }
    }

    private var hotword: android.service.voice.AlwaysOnHotwordDetector? = null

    /** Android का अपना (DSP) hotword: सिर्फ़ फ़ोन बनाने वाले के सिस्टम ऐप को मिलता है - आम ऐप पर ज़्यादातर "उपलब्ध नहीं" */
    @Suppress("DEPRECATION")
    private fun probeHotword() {
        val cb = object : android.service.voice.AlwaysOnHotwordDetector.Callback() {
            override fun onAvailabilityChanged(status: Int) {
                com.dk.assistant.wake.WakeWord.setHotwordState(this@DKVoiceInteractionService, com.dk.assistant.wake.WakeRules.hotwordState(status))
            }
            override fun onDetected(eventPayload: android.service.voice.AlwaysOnHotwordDetector.EventPayload) {}
            override fun onError() { com.dk.assistant.wake.WakeWord.setHotwordState(this@DKVoiceInteractionService, "error") }
            override fun onRecognitionPaused() {}
            override fun onRecognitionResumed() {}
        }
        try {
            hotword = createAlwaysOnHotwordDetector("Hey DK", java.util.Locale.forLanguageTag("hi-IN"), cb)
        } catch (t: Throwable) {
            com.dk.assistant.wake.WakeWord.setHotwordState(this, "denied")
            DKLog.d("Android का अपना hotword: नहीं मिला (${t.javaClass.simpleName}) - DK का अपना मॉडल चलेगा")
        }
    }
}

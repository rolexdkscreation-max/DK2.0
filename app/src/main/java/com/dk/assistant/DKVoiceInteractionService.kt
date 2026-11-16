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
                { try { Setup.healAccessibility(this) } catch (e: Exception) { Quiet.note(e) } }, Setup.HEAL_DELAY_MS)
            // 16 नवंबर 2026 (मालिक: "पहले Android का अपना hotword आज़माओ, न चले तो हमारा मॉडल"):
            // जाँच करके नतीजा Settings में दिखाओ. डिफ़ॉल्ट असिस्टेंट होने पर Android 14+ में भी माइक वाली सेवा यहीं से शुरू हो सकती है
            probeHotword()
            if (com.dk.assistant.wake.WakeWord.on(this) && !Prefs.micUserOff(this)) {
                try { Setup.startKeepAlive(this) } catch (e: Exception) { Quiet.note(e) }
            }
        } catch (e: Exception) {
            Log.e("DKAssist", "Error in DKVoiceInteractionService.onReady", e); com.dk.assistant.DKBug.report("assistant", e, "Error in DKVoiceInteractionService.onReady", alsoLog = true)
        }
    }

    /**
     * Android का अपना (DSP) hotword (AlwaysOnHotwordDetector) Android 11+ में @SystemApi है - public SDK में यह class ही नहीं दिखती,
     * सिर्फ़ फ़ोन बनाने वाले के system ऐप (जैसे Google ऐप) को मिलता है. इसलिए आम ऐप DK के लिए यह "उपलब्ध नहीं" - हमारा मॉडल चलता है.
     */
    private fun probeHotword() {
        com.dk.assistant.wake.WakeWord.setHotwordState(this, com.dk.assistant.wake.WakeRules.hotwordState(-1))
        DKLog.d("Android का अपना hotword: आम ऐप के लिए उपलब्ध नहीं (SystemApi) - DK का अपना मॉडल (फ़ोन पर ही) चलेगा")
    }
}

package com.dk.assistant

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * नवंबर 2026 (Live जाँच #1, जड़): speaker पर DK की आवाज़ USAGE_MEDIA पर + फ़ोन normal mode में = ज़्यादातर फ़ोनों का
 * hardware echo-cancel (AEC) DK की अपनी आवाज़ को माइक से नहीं हटाता. इसीलिए DK बोलते समय Gemini को खामोशी जाती थी
 * और बीच में टोकना caption के शब्दों पर टिका था (देर से रुकना / TV-पंखे पर गलत रुकना).
 *
 * "कॉल-mode स्पीकर" (सेटिंग, डिफ़ॉल्ट बंद): Live के समय फ़ोन MODE_IN_COMMUNICATION + बात का डिवाइस = फ़ोन का speaker,
 * DK की आवाज़ USAGE_VOICE_COMMUNICATION पर ([CallVoice.usage]) = AEC को DK की आवाज़ का reference मिलता है.
 * Bluetooth headset / असली फ़ोन कॉल = [BtAudio] / फ़ोन का अपना रास्ता, यह हाथ नहीं लगाता.
 */
object CallSpeakerRule {
    /** कॉल-mode स्पीकर लगाएँ? */
    fun want(prefOn: Boolean, bt: Boolean, phoneCall: Boolean): Boolean = prefOn && !bt && !phoneCall

    /**
     * DK बोलते समय भी माइक की असली आवाज़ (full-duplex) ठीक है? सिर्फ़ जब कॉल-mode सच में लगा और फ़ोन का AEC चालू -
     * वरना DK की अपनी आवाज़ Gemini तक जाकर उसे खुद रोक देती (v3.9 वाली दिक्कत).
     */
    fun duplexOk(active: Boolean, aecOn: Boolean, disabledThisSession: Boolean): Boolean = active && aecOn && !disabledThisSession
}

object CallSpeaker {
    @Volatile var active = false
        private set
    private var prevMode = AudioManager.MODE_NORMAL
    private var prevSpeaker = false

    private fun log(m: String) = DKLog.d("कॉल-mode स्पीकर: $m")

    fun prefOn(c: Context): Boolean = Prefs.bool(c, LiveSmooth.PREF_CALL_SPEAKER, false)

    private fun phoneCall(m: AudioManager) = try { m.mode == AudioManager.MODE_IN_CALL || m.mode == AudioManager.MODE_RINGTONE } catch (_: Exception) { false }

    /** Live शुरू / Bluetooth बदला / कॉल खत्म - जो सही हो वही लगाओ. लौटाता: अभी कॉल-mode स्पीकर चालू? */
    @Synchronized @Suppress("DEPRECATION")
    fun apply(c: Context, bt: Boolean): Boolean {
        val m = c.applicationContext.getSystemService(AudioManager::class.java) ?: return false
        val want = CallSpeakerRule.want(prefOn(c), bt, phoneCall(m))
        if (want == active) return active
        // Bluetooth ने रास्ता ले लिया ([BtAudio] खुद कॉल वाला तरीका लगाता है) - उसका लगाया mode/डिवाइस मत छेड़ो
        if (!want && bt) { active = false; log("Bluetooth headset पर - कॉल-mode स्पीकर रुका"); return false }
        if (!want) { release(c); return false }
        try {
            prevMode = m.mode; prevSpeaker = m.isSpeakerphoneOn
            m.mode = AudioManager.MODE_IN_COMMUNICATION
            val ok = if (Build.VERSION.SDK_INT >= 31) {
                m.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { m.setCommunicationDevice(it) } == true
            } else { m.isSpeakerphoneOn = true; true }
            if (!ok) { m.mode = prevMode; log("फ़ोन ने speaker को बात का डिवाइस नहीं बनाने दिया - पहले जैसा (media)"); return false }
            active = true
            log("चालू - echo-cancel पक्का (फ़ोन का माइक + speaker कॉल वाले तरीके पर)")
        } catch (e: Exception) {
            log("नहीं लगा: ${e.message}"); try { m.mode = prevMode } catch (_: Exception) {}
            active = false
        }
        return active
    }

    /** असली फ़ोन कॉल आया: फ़ोन अपना रास्ता लगाता है - हम कुछ न छेड़ें, कॉल के बाद [apply] फिर से */
    @Synchronized fun pause() { if (active) { active = false; log("फ़ोन कॉल - रुका (कॉल के बाद फिर)") } }

    @Synchronized @Suppress("DEPRECATION")
    fun release(c: Context) {
        if (!active) return
        active = false
        val m = c.applicationContext.getSystemService(AudioManager::class.java) ?: return
        if (phoneCall(m)) return
        try {
            if (Build.VERSION.SDK_INT >= 31) m.clearCommunicationDevice() else m.isSpeakerphoneOn = prevSpeaker
            if (m.mode == AudioManager.MODE_IN_COMMUNICATION) m.mode = if (prevMode == AudioManager.MODE_IN_COMMUNICATION) AudioManager.MODE_NORMAL else prevMode
        } catch (_: Exception) {}
        log("बंद - आवाज़ वापस normal (media)")
    }
}

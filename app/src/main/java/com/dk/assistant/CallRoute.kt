package com.dk.assistant

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * 14 नवंबर 2026 (मालिक): "फ़ोन कॉल - Bluetooth जुड़ा हो तो Bluetooth पर, Bluetooth न हो तो speaker पर ही रहे"।
 * DK जो कॉल उठाता/लगाता है: Bluetooth headset जुड़ा = आवाज़ headset में (speaker ज़बरदस्ती नहीं),
 * नहीं जुड़ा = अपने-आप speaker. (कॉल के बीच "speaker on/off" बोलने पर वही होता है जो बोला.)
 */
object CallRoute {
    /** कॉल की आवाज़ ले जाने वाले Bluetooth प्रकार: SCO headset, BLE headset (LE Audio), A2DP (उसी headset का) */
    val BT_TYPES = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, 26 /* TYPE_BLE_HEADSET */)

    /** pure (JVM test): जुड़े डिवाइसों के प्रकार -> Bluetooth है? */
    fun hasBt(types: Collection<Int>): Boolean = types.any { it in BT_TYPES }

    /** pure (JVM test): कॉल उठाते/लगाते समय speaker चाहिए? Bluetooth = नहीं, वरना हाँ */
    fun wantSpeaker(btConnected: Boolean): Boolean = !btConnected

    /** अभी कोई Bluetooth headset जुड़ा है? */
    fun btConnected(c: Context): Boolean = try {
        val am = c.getSystemService(AudioManager::class.java)
        val types = ArrayList<Int>()
        am?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.forEach { types.add(it.type) }
        if (Build.VERSION.SDK_INT >= 31) am?.availableCommunicationDevices?.forEach { types.add(it.type) }
        hasBt(types)
    } catch (_: Exception) { false }

    /** Bluetooth जुड़ा हो तो कॉल की आवाज़ उसी पर (speaker हटाकर) */
    @Suppress("DEPRECATION")
    fun toBt(c: Context) {
        val am = c.getSystemService(AudioManager::class.java) ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == 26 }
                    ?.let { am.setCommunicationDevice(it) }
            }
            if (am.isSpeakerphoneOn) am.isSpeakerphoneOn = false
        } catch (e: Exception) { DKLog.d("call bt: ${e.message}") }
    }
}

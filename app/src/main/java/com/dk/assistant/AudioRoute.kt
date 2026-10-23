package com.dk.assistant

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * v3.39: DK लाइव की आवाज़ का रास्ता - Bluetooth जुड़ा हो तो "फ़ोन कॉल वाला तरीका", नहीं तो speaker वाला (पहले जैसा).
 *
 *  - Bluetooth headset (माइक वाला) जुड़ा -> फ़ोन "कॉल" मोड (MODE_IN_COMMUNICATION) + बात का डिवाइस = headset.
 *    तब माइक headset का, DK की आवाज़ headset में, और फ़ोन का गूँज हटाना ठीक से चलता है.
 *  - Bluetooth हटा / बंद -> मोड सामान्य, माइक फ़ोन का, आवाज़ speaker से (जैसा अब तक).
 *  - बीच में जुड़े/हटे -> अपने-आप बदलता है (onChange: माइक/आवाज़ दोबारा खुलती है).
 *
 * सिर्फ़ DK लाइव की बातचीत के समय चालू (wake word सुनते समय नहीं - वरना headset का गाना हर समय "कॉल" quality में रहता).
 */
class AudioRoute(
    private val c: Context,
    private val log: (String) -> Unit,
    /** true = अब Bluetooth, false = अब speaker */
    private val onChange: (Boolean) -> Unit,
) {
    private val am = c.getSystemService(AudioManager::class.java)
    private val h = Handler(Looper.getMainLooper())

    /** अभी Bluetooth वाले रास्ते पर? */
    @Volatile var bluetooth = false
        private set
    /** Bluetooth headset का माइक (AudioRecord.setPreferredDevice के लिए) */
    @Volatile var btInput: AudioDeviceInfo? = null
        private set
    @Volatile private var running = false
    private var oldMode = AudioManager.MODE_NORMAL

    private val recheck = Runnable { apply() }
    private val cb = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { soon() }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { soon() }
    }

    /** headset जुड़ने/हटने की ख़बर कई बार आती है - 500ms रुककर एक बार जाँचो */
    private fun soon() { if (running) { h.removeCallbacks(recheck); h.postDelayed(recheck, 500) } }

    fun start() {
        if (am == null || running) return
        running = true
        oldMode = try { am.mode } catch (_: Exception) { AudioManager.MODE_NORMAL }
        try { am.registerAudioDeviceCallback(cb, h) } catch (e: Exception) { log("Bluetooth पर नज़र नहीं रख पाया: ${e.message}") }
        apply()
    }

    fun stop() {
        if (!running) return
        running = false
        h.removeCallbacks(recheck)
        try { am?.unregisterAudioDeviceCallback(cb) } catch (_: Exception) {}
        if (bluetooth) leave(notify = false)
    }

    /** Bluetooth headset (माइक वाला) - बात करने के डिवाइसों में से */
    private fun findBt(): AudioDeviceInfo? {
        val m = am ?: return null
        return try {
            val list: List<AudioDeviceInfo> = if (Build.VERSION.SDK_INT >= 31) m.availableCommunicationDevices
                else m.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
            list.getOrNull(BtPick.pick(list.map { it.type }))
        } catch (_: Exception) { null }
    }

    /** उस headset का माइक (इनपुट) - setPreferredDevice के लिए */
    private fun inputFor(d: AudioDeviceInfo): AudioDeviceInfo? = try {
        am?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.let { ins ->
            ins.firstOrNull { it.type == d.type && it.address == d.address } ?: ins.firstOrNull { it.type == d.type }
        }
    } catch (_: Exception) { null }

    @Synchronized private fun apply() {
        if (!running) return
        val d = findBt()
        when {
            d != null && !bluetooth -> enter(d)
            d == null && bluetooth -> { log("Bluetooth हट गया - फ़ोन के माइक और speaker पर वापस"); leave(notify = true) }
        }
    }

    @Suppress("DEPRECATION")
    private fun enter(d: AudioDeviceInfo) {
        val m = am ?: return
        val ok = try {
            m.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) m.setCommunicationDevice(d)
            else { m.startBluetoothSco(); m.isBluetoothScoOn = true; true }
        } catch (e: Exception) { log("Bluetooth पर नहीं ले जा पाया: ${e.message}"); false }
        if (!ok) { try { m.mode = oldMode } catch (_: Exception) {}; log("Bluetooth headset ने बात का रास्ता नहीं लिया - फ़ोन का माइक/speaker"); return }
        bluetooth = true
        btInput = inputFor(d)
        log("Bluetooth जुड़ा (${d.productName}) - कॉल वाला तरीका: माइक और आवाज़ headset पर")
        onChange(true)
    }

    @Suppress("DEPRECATION")
    private fun leave(notify: Boolean) {
        val m = am
        try {
            if (Build.VERSION.SDK_INT >= 31) m?.clearCommunicationDevice()
            else { m?.isBluetoothScoOn = false; m?.stopBluetoothSco() }
        } catch (_: Exception) {}
        try { m?.mode = AudioManager.MODE_NORMAL } catch (_: Exception) {}
        bluetooth = false
        btInput = null
        if (notify) onChange(false)
    }
}

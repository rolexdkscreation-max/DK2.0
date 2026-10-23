package com.dk.assistant

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * v3.39: DK के माइक/आवाज़ का रास्ता - एक ही जगह, सब (DK लाइव, wake word) इसी को इस्तेमाल करते हैं.
 *
 *  - Bluetooth headset (माइक वाला) जुड़ा -> फ़ोन "कॉल वाला तरीका" (MODE_IN_COMMUNICATION) + बात का डिवाइस = headset.
 *    माइक headset का, DK की आवाज़ headset में, फ़ोन का गूँज हटाना ठीक से चलता है.
 *  - Bluetooth हटा / बंद -> मोड सामान्य, माइक फ़ोन का, आवाज़ speaker से (पहले जैसा).
 *  - बीच में जुड़ा/हटा -> अपने-आप बदलता है (हर इस्तेमाल करने वाले को onChange: माइक/आवाज़ दोबारा खोलो).
 *
 * गिनती (acquire/release): जब तक कोई भी (लाइव या wake word) इस्तेमाल कर रहा है, रास्ता बना रहता है - एक के छोड़ने से
 * दूसरे का Bluetooth नहीं कटता. असली फ़ोन कॉल चल रहा हो (MODE_IN_CALL / घंटी) तो मोड को कभी हाथ नहीं लगाते.
 * (PR #48 में Accessibility शुरू होते ही पूरा फ़ोन हमेशा "कॉल" मोड में चला जाता था - वह हटाया.)
 */
object BtAudio {
    private val h = Handler(Looper.getMainLooper())
    private val users = LinkedHashMap<Any, (Boolean) -> Unit>()
    private var am: AudioManager? = null
    private var watching = false

    /** अभी Bluetooth वाले रास्ते पर? */
    @Volatile var bluetooth = false
        private set
    /** Bluetooth headset का माइक (AudioRecord.setPreferredDevice के लिए) */
    @Volatile var btInput: AudioDeviceInfo? = null
        private set

    private fun log(m: String) = DKLog.d("आवाज़ का रास्ता: $m")

    private val recheck = Runnable { apply() }
    private val cb = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { soon() }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { soon() }
    }

    /** headset जुड़ने/हटने की ख़बर कई बार आती है - 500ms रुककर एक बार जाँचो */
    private fun soon() { h.removeCallbacks(recheck); h.postDelayed(recheck, 500) }

    /**
     * @param owner कौन इस्तेमाल कर रहा है (लाइव / Ear)
     * @param onChange true = अब Bluetooth, false = अब फ़ोन का माइक/speaker
     * @return अभी Bluetooth पर?
     */
    @Synchronized fun acquire(c: Context, owner: Any, onChange: (Boolean) -> Unit): Boolean {
        if (am == null) am = c.applicationContext.getSystemService(AudioManager::class.java)
        users[owner] = onChange
        if (!watching) {
            watching = true
            try { am?.registerAudioDeviceCallback(cb, h) } catch (e: Exception) { log("Bluetooth पर नज़र नहीं रख पाया: ${e.message}") }
        }
        apply(notify = false)
        return bluetooth
    }

    @Synchronized fun release(owner: Any) {
        if (users.remove(owner) == null || users.isNotEmpty()) return
        watching = false
        h.removeCallbacks(recheck)
        try { am?.unregisterAudioDeviceCallback(cb) } catch (_: Exception) {}
        if (bluetooth) leave()
    }

    /** असली फ़ोन कॉल / घंटी = फ़ोन का अपना रास्ता, DK हाथ नहीं लगाता */
    private fun phoneCall(m: AudioManager) = try { m.mode == AudioManager.MODE_IN_CALL || m.mode == AudioManager.MODE_RINGTONE } catch (_: Exception) { false }

    /** Bluetooth headset (माइक वाला) - बात करने के डिवाइसों में से */
    private fun findBt(m: AudioManager): AudioDeviceInfo? = try {
        val list: List<AudioDeviceInfo> = if (Build.VERSION.SDK_INT >= 31) m.availableCommunicationDevices
            else m.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        list.getOrNull(BtPick.pick(list.map { it.type }))
    } catch (_: Exception) { null }

    /** उस headset का माइक (इनपुट) */
    private fun inputFor(m: AudioManager, d: AudioDeviceInfo): AudioDeviceInfo? = try {
        m.getDevices(AudioManager.GET_DEVICES_INPUTS).let { ins ->
            ins.firstOrNull { it.type == d.type && it.address == d.address } ?: ins.firstOrNull { it.type == d.type }
        }
    } catch (_: Exception) { null }

    @Synchronized private fun apply(notify: Boolean = true) {
        val m = am ?: return
        if (users.isEmpty() || phoneCall(m)) return
        val d = findBt(m)
        val was = bluetooth
        when {
            d != null && !bluetooth -> enter(m, d)
            d == null && bluetooth -> { log("Bluetooth हट गया - फ़ोन के माइक और speaker पर वापस"); leave() }
        }
        if (notify && was != bluetooth) users.values.toList().forEach { try { it(bluetooth) } catch (_: Exception) {} }
    }

    @Suppress("DEPRECATION")
    private fun enter(m: AudioManager, d: AudioDeviceInfo) {
        val ok = try {
            m.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) m.setCommunicationDevice(d)
            else { m.startBluetoothSco(); m.isBluetoothScoOn = true; true }
        } catch (e: Exception) { log("Bluetooth पर नहीं ले जा पाया: ${e.message}"); false }
        if (!ok) { try { m.mode = AudioManager.MODE_NORMAL } catch (_: Exception) {}; log("Bluetooth headset ने बात का रास्ता नहीं लिया - फ़ोन का माइक/speaker"); return }
        bluetooth = true
        btInput = inputFor(m, d)
        log("Bluetooth जुड़ा (${d.productName}) - कॉल वाला तरीका: माइक और आवाज़ headset पर")
    }

    @Suppress("DEPRECATION")
    private fun leave() {
        val m = am
        bluetooth = false
        btInput = null
        if (m == null || phoneCall(m)) return
        try {
            if (Build.VERSION.SDK_INT >= 31) m.clearCommunicationDevice()
            else { m.isBluetoothScoOn = false; m.stopBluetoothSco() }
        } catch (_: Exception) {}
        try { if (m.mode == AudioManager.MODE_IN_COMMUNICATION) m.mode = AudioManager.MODE_NORMAL } catch (_: Exception) {}
    }
}

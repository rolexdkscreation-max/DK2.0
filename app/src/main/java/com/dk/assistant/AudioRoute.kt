package com.dk.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

    // v3.6: SCO सच में जुड़ा? (SDK < 31 पर फ़ोन की ख़बर से; 31+ पर communicationDevice से)
    @Volatile private var scoState = -1
    private var appCtx: Context? = null
    private var scoWatch: BroadcastReceiver? = null
    /** जिस headset ने रास्ता नहीं लिया - वह हटने/दोबारा जुड़ने तक फिर उसी पर ज़बरदस्ती नहीं */
    @Volatile private var refused: String? = null
    private var verifyTry = 0
    private val verify = Runnable { verifyRoute() }

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
        if (appCtx == null) appCtx = c.applicationContext
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
        h.removeCallbacks(recheck); h.removeCallbacks(verify)
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
        if (d == null) refused = null                       // headset हटा = अगली बार फिर कोशिश
        val was = bluetooth
        when {
            d != null && !bluetooth && refused == id(d) -> {}  // यह headset रास्ता नहीं ले पाया - फ़ोन के speaker पर ही
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
        btDev = d
        log("Bluetooth जुड़ा (${d.productName}) - कॉल वाला तरीका: माइक और आवाज़ headset पर")
        // v3.6: फ़ोन ने हाँ कहा, पर सच में headset पर गया? जाँचो - नहीं तो आवाज़ earpiece (ऊपर वाले छोटे स्पीकर) से आती थी
        if (Build.VERSION.SDK_INT < 31) watchSco()
        verifyTry = 0
        h.removeCallbacks(verify); h.postDelayed(verify, BtPick.VERIFY_AT_MS[0])
    }

    private var btDev: AudioDeviceInfo? = null
    private fun id(d: AudioDeviceInfo) = "${d.type}|${d.address}|${d.productName}"

    @Suppress("DEPRECATION")
    private fun routedNow(m: AudioManager): Boolean = try {
        if (Build.VERSION.SDK_INT >= 31) BtPick.routedToHeadset(m.communicationDevice?.type)
        else BtPick.scoConnected(scoState) || m.isBluetoothScoOn && scoState == -1
    } catch (_: Exception) { true }

    @Synchronized private fun verifyRoute() {
        val m = am ?: return
        if (!bluetooth || users.isEmpty() || phoneCall(m)) return
        if (routedNow(m)) { log("✓ पक्का: आवाज़ और माइक Bluetooth headset पर"); return }
        val d = btDev
        verifyTry++
        if (verifyTry < BtPick.VERIFY_AT_MS.size && d != null) {
            log("headset ने अभी रास्ता नहीं लिया - एक बार फिर कोशिश")
            retryRoute(m, d)
            h.postDelayed(verify, BtPick.VERIFY_AT_MS[verifyTry] - BtPick.VERIFY_AT_MS[verifyTry - 1]); return
        }
        log("Bluetooth headset ने कॉल वाला रास्ता नहीं लिया - आवाज़ earpiece में न जाए, इसलिए फ़ोन के माइक और speaker पर")
        refused = d?.let { id(it) }
        leave()
        users.values.toList().forEach { try { it(false) } catch (_: Exception) {} }
    }

    @Suppress("DEPRECATION")
    private fun retryRoute(m: AudioManager, d: AudioDeviceInfo) {
        try { if (Build.VERSION.SDK_INT >= 31) m.setCommunicationDevice(d) else m.startBluetoothSco() } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun watchSco() {
        val c = appCtx ?: return
        if (scoWatch != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, i: Intent?) {
                scoState = i?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) ?: -1
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) c.registerReceiver(r, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED), Context.RECEIVER_NOT_EXPORTED)
            else c.registerReceiver(r, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
            scoWatch = r
        } catch (_: Exception) {}
    }

    @Suppress("DEPRECATION")
    private fun leave() {
        val m = am
        bluetooth = false
        btInput = null
        btDev = null
        h.removeCallbacks(verify)
        if (m == null || phoneCall(m)) return
        try {
            if (Build.VERSION.SDK_INT >= 31) m.clearCommunicationDevice()
            else { m.isBluetoothScoOn = false; m.stopBluetoothSco() }
        } catch (_: Exception) {}
        try { if (m.mode == AudioManager.MODE_IN_COMMUNICATION) m.mode = AudioManager.MODE_NORMAL } catch (_: Exception) {}
    }
}

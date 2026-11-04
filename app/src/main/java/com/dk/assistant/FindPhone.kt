package com.dk.assistant

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * नवंबर 2026 (मालिक): "Hey DK किधर हो" - फ़ोन कहीं भी रखा हो, कोई भी पूछे -> 60 सेकंड तक:
 *  - पूरी आवाज़ में ringtone (alarm वाली आवाज़ - silent / vibrate में भी बजती है; DND की इजाज़त हो तो DND भी हटे),
 *  - बीच-बीच में DK खुद बोले "मैं इधर हूँ बॉस, इधर हूँ बॉस" (पहले से बनी DK की आवाज़ - इंटरनेट नहीं; न हो तो फ़ोन की TTS),
 *  - flashlight टिमटिमाए, vibrate, स्क्रीन जले ([FindPhoneActivity] - लॉक स्क्रीन के ऊपर).
 * रुकना: स्क्रीन छुएँ / कोई बटन / "Hey DK मिल गया" / 60 s. रुकते ही आवाज़ / DND / flashlight पहले जैसे.
 * Bluetooth headphone लगा हो तब भी alarm वाली आवाज़ फ़ोन के speaker से भी बजती है.
 */
object FindPhone {
    const val RING_MS = 60_000L
    const val SAY = "मैं इधर हूँ बॉस, इधर हूँ बॉस"
    /** हर इतने ms पर DK बोले (बीच में ringtone) */
    const val SAY_EVERY_MS = 7_000L
    const val BLINK_MS = 300L

    @Volatile var active = false
        private set
    @Volatile var startedAt = 0L
        private set
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** कोई भी बोले ("किधर हो") - मालिक की आवाज़ ज़रूरी नहीं. पहले से बज रहा = कुछ नहीं */
    @Synchronized
    fun start(c: Context, why: String): String {
        val app = c.applicationContext
        if (active) return "फ़ोन पहले से बज रहा है"
        active = true; startedAt = System.currentTimeMillis()
        DKLog.d("फ़ोन ढूँढो शुरू ($why) - ${RING_MS / 1000} s तक पूरी आवाज़ + flashlight")
        job = scope.launch {
            val st = State()
            try { ring(app, st) } catch (e: Exception) { DKLog.d("फ़ोन ढूँढो: ${e.javaClass.simpleName} ${e.message}") }
            finally { restore(app, st); active = false; FindPhoneActivity.closeAll(); DKLog.d("फ़ोन ढूँढो बंद") }
        }
        return "मैं इधर हूँ बॉस"
    }

    /** स्क्रीन छुई / बटन / "मिल गया" */
    fun stop(why: String) {
        if (!active) return
        DKLog.d("फ़ोन ढूँढो रोका: $why")
        job?.cancel()
    }

    private class State {
        var alarmVol = -1
        var filter = -1
        var player: MediaPlayer? = null
        var track: AudioTrack? = null
        var tts: TextToSpeech? = null
        var torchId: String? = null
        var vib: Vibrator? = null
        var screenOff: android.content.BroadcastReceiver? = null
    }

    /** lazy - JVM test में [active] पढ़ने पर Android वाला हिस्सा न बने */
    private val ALARM: AudioAttributes by lazy {
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    }

    private suspend fun ring(c: Context, st: State) {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // 1. पूरी आवाज़ (alarm वाली - silent में भी), DND हटे (इजाज़त हो तो)
        try {
            st.alarmVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: Exception) {}
        try {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (nm != null && nm.isNotificationPolicyAccessGranted && nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                st.filter = nm.currentInterruptionFilter
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
        } catch (_: Exception) {}
        // 2. स्क्रीन जले - लॉक स्क्रीन के ऊपर "मैं यहाँ हूँ" (छूते ही बंद). power बटन (स्क्रीन बंद) = बजना बंद
        FindPhoneActivity.show(c)
        try {
            val r = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context?, i: Intent?) { if (System.currentTimeMillis() - startedAt > 1_500) stop("power बटन") }
            }
            androidx.core.content.ContextCompat.registerReceiver(c, r, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            st.screenOff = r
        } catch (_: Exception) {}
        // 3. vibrate
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) c.getSystemService(VibratorManager::class.java)?.defaultVibrator
                else @Suppress("DEPRECATION") (c.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0), ALARM)
            st.vib = v
        } catch (_: Exception) {}
        // 4. flashlight टिमटिमाए
        val cm = c.getSystemService(CameraManager::class.java)
        st.torchId = try {
            cm?.cameraIdList?.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        } catch (_: Exception) { null }
        val blink = st.torchId?.let { id ->
            scope.launch {
                var on = false
                while (isActive) {
                    on = !on
                    try { cm?.setTorchMode(id, on) } catch (_: Exception) {}      // कैमरा कोई और चला रहा हो - छोड़ो
                    delay(BLINK_MS)
                }
            }
        }
        // 5. ringtone (लगातार) + बीच-बीच में DK की आवाज़
        st.player = try {
            val uri = RingtoneManager.getActualDefaultRingtoneUri(c, RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            MediaPlayer().apply { setAudioAttributes(ALARM); setDataSource(c, uri); isLooping = true; prepare(); start() }
        } catch (_: Exception) { null }
        val clip = com.dk.assistant.voice.HeyDk.clipPcm(c, SAY)
        if (clip == null) st.tts = phoneTts(c)
        try {
            val end = startedAt + RING_MS
            var nextSay = System.currentTimeMillis() + 600
            while (System.currentTimeMillis() < end) {
                if (System.currentTimeMillis() >= nextSay) {
                    st.player?.setVolume(0.25f, 0.25f)          // DK की बात साफ़ सुनाई दे
                    say(c, st, clip)
                    st.player?.setVolume(1f, 1f)
                    nextSay = System.currentTimeMillis() + SAY_EVERY_MS
                }
                delay(100)
            }
        } finally { blink?.cancel() }
    }

    /** DK की बनी आवाज़ (alarm वाली आवाज़ में) - न हो तो फ़ोन की अपनी TTS (इंटरनेट नहीं) */
    private suspend fun say(c: Context, st: State, clip: ByteArray?) {
        if (clip != null && clip.size > 1000) {
            val rate = com.dk.assistant.voice.engine.VoiceEngine.RATE
            val tr = try {
                AudioTrack.Builder().setAudioAttributes(ALARM)
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(clip.size).build()
            } catch (_: Exception) { null }
            if (tr != null) {
                st.track?.release(); st.track = tr
                try {
                    tr.write(clip, 0, clip.size); tr.play()
                    delay(clip.size * 1000L / (rate * 2) + 200)
                    tr.stop()
                } catch (_: Exception) {}
                return
            }
        }
        val t = st.tts ?: return
        try {
            t.speak(SAY, TextToSpeech.QUEUE_FLUSH, null, "dk_find")
            delay(2_600)
        } catch (_: Exception) {}
    }

    private fun phoneTts(c: Context): TextToSpeech? = try {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(c) { ok ->
            if (ok == TextToSpeech.SUCCESS) {
                tts?.setAudioAttributes(ALARM)
                tts?.language = Locale("hi", "IN")
            }
        }
        tts
    } catch (_: Exception) { null }

    private fun restore(c: Context, st: State) {
        st.screenOff?.let { try { c.unregisterReceiver(it) } catch (_: Exception) {} }
        try { st.player?.stop() } catch (_: Exception) {}
        try { st.player?.release() } catch (_: Exception) {}
        try { st.track?.release() } catch (_: Exception) {}
        try { st.tts?.stop(); st.tts?.shutdown() } catch (_: Exception) {}
        try { st.vib?.cancel() } catch (_: Exception) {}
        st.torchId?.let { id -> try { c.getSystemService(CameraManager::class.java)?.setTorchMode(id, false) } catch (_: Exception) {} }
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (st.alarmVol >= 0) try { am.setStreamVolume(AudioManager.STREAM_ALARM, st.alarmVol, 0) } catch (_: Exception) {}
        if (st.filter >= 0) try { c.getSystemService(NotificationManager::class.java)?.setInterruptionFilter(st.filter) } catch (_: Exception) {}
    }
}

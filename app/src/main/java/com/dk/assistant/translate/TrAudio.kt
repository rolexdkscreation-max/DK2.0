package com.dk.assistant.translate

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import com.dk.assistant.DKLog
import com.dk.assistant.GeminiLive
import com.dk.assistant.Quiet
import java.util.concurrent.LinkedBlockingQueue

/**
 * 25 नवंबर 2026 (मालिक: "सुनना हमेशा फ़ोन के माइक से, ब्लूटूथ माइक से नहीं. हिंदी अनुवाद ईयरफोन / ब्लूटूथ में;
 * ब्लूटूथ नहीं तो फ़ोन स्पीकर. सामने वाले की भाषा फ़ोन के स्पीकर से. बीच में जुड़े / कटे तो रास्ता अपने-आप बदले"):
 *
 * - माइक: AudioRecord + setPreferredDevice(फ़ोन का अपना माइक). Bluetooth SCO / कॉल वाला mode कभी चालू नहीं होता
 *   (DK Live वाला BtAudio नहीं) - इसलिए Bluetooth का माइक इस्तेमाल ही नहीं होता.
 * - दो आवाज़-रास्ते: [hindi] = ईयरफोन / Bluetooth (A2DP / LE) / तार वाला हेडफ़ोन, न हो तो फ़ोन स्पीकर.
 *   [partner] = हमेशा फ़ोन का स्पीकर.
 * - AudioDeviceCallback: Bluetooth जुड़ा / कटा = दोनों रास्ते तुरंत दोबारा ([route]), ऐप बंद किए बिना.
 */
class TrAudio(private val c: Context, private val onRoute: (String) -> Unit) {
    companion object {
        const val OUT_RATE = GeminiLive.OUT_RATE
        const val IN_RATE = GeminiLive.IN_RATE

        /** हेडफ़ोन जैसा रास्ता? (हिंदी यहाँ सुनाई दे - सामने वाले को नहीं) */
        fun isHeadset(type: Int): Boolean = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || type == AudioDeviceInfo.TYPE_USB_HEADSET || type == 26 /* BLE_HEADSET */ ||
            type == 27 /* BLE_SPEAKER */ || type == 30 /* BLE_BROADCAST */ || type == AudioDeviceInfo.TYPE_HEARING_AID

        /** कई headset हों तो पहले Bluetooth / LE, फिर तार वाला */
        fun rank(type: Int): Int = when (type) {
            26, 27, 30, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_HEARING_AID -> 0
            else -> 1
        }
    }

    private val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())

    /** एक आवाज़-रास्ता: अपना AudioTrack + कतार + बजाने वाला thread */
    inner class Out(val name: String) {
        @Volatile var track: AudioTrack? = null
        private val q = LinkedBlockingQueue<ByteArray>()
        @Volatile private var endAt = 0L
        @Volatile private var alive = true
        @Volatile var gen = 0
            private set
        private val th = Thread({ loop() }, "dk-tr-$name").apply { isDaemon = true }

        fun start() { track = build(); th.start() }

        private fun build(): AudioTrack? = try {
            val min = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(OUT_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(min * 2, OUT_RATE * 2 / 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build().also { if (it.state == AudioTrack.STATE_INITIALIZED) it.play() }
        } catch (e: Exception) { DKLog.d("Translator: AudioTrack ($name) नहीं बना: ${e.message}"); null }

        fun play(pcm: ByteArray) { if (pcm.isNotEmpty()) q.offer(pcm) }

        /** बज रहा है / बजने वाला है? */
        fun busy(): Boolean = q.isNotEmpty() || System.currentTimeMillis() < endAt

        /** जो बचा है फेंको (हिंदी निकली - मत सुनाओ) */
        fun flush() {
            gen++; q.clear(); endAt = 0
            try { track?.let { it.pause(); it.flush(); it.play() } } catch (e: Exception) { Quiet.note(e) }
        }

        fun prefer(d: AudioDeviceInfo?) { try { track?.setPreferredDevice(d) } catch (e: Exception) { Quiet.note(e) } }

        private fun loop() {
            while (alive) {
                val b = try { q.take() } catch (_: InterruptedException) { break }
                val t = track ?: continue
                val g = gen
                val now = System.currentTimeMillis()
                endAt = maxOf(endAt, now) + b.size * 1000L / (OUT_RATE * 2) + 120
                var off = 0
                while (off < b.size && alive && g == gen) {
                    val n = try { t.write(b, off, minOf(4096, b.size - off)) } catch (e: Exception) { Quiet.note(e); -1 }
                    if (n <= 0) break
                    off += n
                }
            }
        }

        fun release() {
            alive = false; q.clear(); th.interrupt()
            try { track?.stop() } catch (e: Exception) { Quiet.note(e) }
            try { track?.release() } catch (e: Exception) { Quiet.note(e) }
            track = null
        }
    }

    val hindi = Out("hindi")
    val partner = Out("partner")
    @Volatile var headset: AudioDeviceInfo? = null
        private set

    private val devCb = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = route()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = route()
    }

    fun start() {
        hindi.start(); partner.start()
        route()
        try { am.registerAudioDeviceCallback(devCb, main) } catch (e: Exception) { Quiet.note(e) }
    }

    fun stop() {
        try { am.unregisterAudioDeviceCallback(devCb) } catch (e: Exception) { Quiet.note(e) }
        hindi.release(); partner.release()
    }

    private fun outputs(): List<AudioDeviceInfo> = try { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() } catch (e: Exception) { Quiet.note(e); emptyList() }

    fun speaker(): AudioDeviceInfo? = outputs().firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    /** अभी के जुड़े यंत्र देखकर दोनों रास्ते तय करो */
    fun route() {
        val hs = outputs().filter { isHeadset(it.type) }.minByOrNull { rank(it.type) }
        headset = hs
        hindi.prefer(hs ?: speaker())
        partner.prefer(speaker())
        val label = if (hs != null) "हिंदी: ${hs.productName.toString().ifBlank { "ईयरफोन" }} • सामने वाला: फ़ोन स्पीकर"
            else "हिंदी + सामने वाला: फ़ोन स्पीकर (ईयरफोन / Bluetooth जोड़ें तो हिंदी उसमें)"
        DKLog.d("Translator: रास्ता - $label")
        onRoute(label)
    }

    /** फ़ोन का अपना माइक (Bluetooth / तार वाले हेडसेट का नहीं) */
    @SuppressLint("MissingPermission")
    fun openMic(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, IN_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, IN_RATE))
        } catch (e: Exception) { DKLog.d("Translator: माइक नहीं खुला: ${e.message}"); return null }
        if (r.state != AudioRecord.STATE_INITIALIZED) { try { r.release() } catch (e: Exception) { Quiet.note(e) }; return null }
        val mic = try { am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC } } catch (e: Exception) { Quiet.note(e); null }
        if (mic != null) try { r.setPreferredDevice(mic) } catch (e: Exception) { Quiet.note(e) }
        return r
    }
}

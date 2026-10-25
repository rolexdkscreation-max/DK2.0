package com.dk.assistant.voice.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.widget.LinearLayout

/**
 * v3.68: बोलने वाला हिस्सा (आवाज़ इंजन) - जवाब लिखने वाले (Gemini Live) से अलग.
 *
 * हर इंजन अपनी अलग फ़ाइल में एक `object` है और [VoiceEngines] उसे नाम से ढूँढता है
 * (Class.forName). कोई इंजन हटाना हो तो सिर्फ़ उसकी फ़ाइल हटाओ - ऐप बाकी इंजनों से चलता रहेगा.
 *
 * आवाज़ हमेशा PCM 16-bit, mono, [RATE] Hz (Gemini Live जैसी) - ताकि सारे इंजन एक ही कतार/एक ही
 * AudioTrack पर बिना झटके के एक के बाद एक बजें.
 */
interface VoiceEngine {
    /** पक्का नाम (Prefs में यही) */
    val id: String
    /** सेटिंग्स में दिखने वाला नाम */
    val title: String
    /** छोटा परिचय / सीमा (जैसे "भावना के निर्देश नहीं मानता") */
    val note: String
    /** true = Gemini Live सिर्फ़ जवाब लिखे, यह इंजन बोले. false = Live खुद लिखे और खुद बोले */
    val split: Boolean
    /** कुछ न चुना हो तो यही चालू */
    val defaultOn: Boolean

    /** (आवाज़ का पक्का नाम, दिखने वाला नाम) */
    fun voices(): List<Pair<String, String>>
    fun voice(c: Context): String
    fun setVoice(c: Context, v: String)

    /** null = चल सकता है; नहीं तो वजह (जैसे "Worker का पता नहीं डाला") */
    fun notReady(c: Context): String? = null

    /** कनेक्शन पहले से खोलकर रखो (गर्म) - हर बार नया न खुले. सस्ता होना चाहिए (बार-बार बुलाया जाता है) */
    fun warm(c: Context) {}

    /**
     * text बोलो: आवाज़ के टुकड़े जैसे-जैसे बनें, [out] में (पूरा बनने का इंतज़ार नहीं).
     * ग़लती = exception (कतार तब Gemini TTS पर लौटती है). [stop] के बाद चुपचाप लौटे.
     */
    fun speak(c: Context, text: String, voice: String, out: PcmOut)

    /** चल रहा सब बनाना/बोलना तुरंत रोको */
    fun stop()

    /**
     * नमूना सुनाओ (▶ सुनो). लौटाता है: बटन दबाने से पहली आवाज़ तक ms.
     * डिफ़ॉल्ट: वही [speak], सीधे फ़ोन के speaker पर.
     */
    fun preview(c: Context, text: String, voice: String): Long = PreviewPlayer.play(c) { out -> speak(c, text, voice, out) }

    /** इस इंजन की अपनी सेटिंग्स (स्लाइडर, स्टाइल का डिब्बा...) - [VoiceEngineCard] बुलाता है */
    fun settings(c: Context, box: LinearLayout, ui: VoiceUi) {}

    companion object {
        const val RATE = 24000
    }
}

/** PCM (16-bit LE mono, 24 kHz) के टुकड़े आगे भेजो */
fun interface PcmOut { fun write(pcm: ByteArray) }

/** सेटिंग्स में इंजन अपने controls इसी से बनाते हैं (एक जैसा रूप) */
interface VoiceUi {
    fun label(box: LinearLayout, t: String)
    fun hint(box: LinearLayout, t: String)
    fun field(box: LinearLayout, title: String, value: String, hint: String, secret: Boolean = false,
              multiLine: Boolean = false, onSave: (String) -> Unit)
    /** min..max (पूर्णांक), बदलते ही onChange */
    fun slider(box: LinearLayout, title: String, min: Int, max: Int, value: Int, fmt: (Int) -> String, onChange: (Int) -> Unit)
}

/** ▶ सुनो: एक अलग, सीधा player (Live की कतार से अलग) - पहली आवाज़ का समय नापता है */
object PreviewPlayer {
    @Volatile private var cur: AudioTrack? = null

    fun stop() { cur?.let { try { it.pause(); it.flush(); it.stop() } catch (_: Exception) {} } }

    /** synth को PcmOut दो, जो आए तुरंत बजाओ. लौटाता: शुरू से पहली आवाज़ तक ms (-1 = कुछ नहीं बजा) */
    fun play(c: Context, synth: (PcmOut) -> Unit): Long {
        stop()
        val t0 = System.currentTimeMillis()
        val min = AudioTrack.getMinBufferSize(VoiceEngine.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val tr = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(VoiceEngine.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(min * 2, VoiceEngine.RATE * 2 / 5))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        cur = tr
        var first = -1L
        var frames = 0L
        try {
            tr.play()
            val even = PcmEven()
            synth(PcmOut { b ->
                val p = even.take(b) ?: return@PcmOut
                if (first < 0) first = System.currentTimeMillis() - t0
                tr.write(p, 0, p.size); frames += p.size / 2
                VoiceLevel.schedule(p, System.currentTimeMillis())
            })
            // बचा हुआ बज जाए
            val end = System.currentTimeMillis() + 15_000
            while (cur === tr && System.currentTimeMillis() < end) {
                val head = tr.playbackHeadPosition.toLong() and 0xffffffffL
                if (head >= frames) break
                Thread.sleep(40)
            }
        } finally {
            try { tr.stop() } catch (_: Exception) {}
            try { tr.release() } catch (_: Exception) {}
            if (cur === tr) cur = null
        }
        return first
    }
}

/** network से आए टुकड़े विषम (odd) बाइट के हो सकते हैं - 16-bit sample न टूटे */
class PcmEven {
    private var carry: Byte? = null
    fun take(b: ByteArray): ByteArray? {
        if (b.isEmpty()) return null
        val src = carry?.let { byteArrayOf(it) + b } ?: b
        carry = null
        if (src.size % 2 == 0) return src
        carry = src[src.size - 1]
        return if (src.size > 1) src.copyOf(src.size - 1) else null
    }
}

/** दूसरी sample-rate (जैसे 16/22.05 kHz) -> 24 kHz (सीधा linear, बोली के लिए काफ़ी) */
object Resample {
    fun to24k(pcm: ByteArray, from: Int): ByteArray {
        if (from == VoiceEngine.RATE || from <= 0) return pcm
        val n = pcm.size / 2
        if (n == 0) return pcm
        val outN = (n.toLong() * VoiceEngine.RATE / from).toInt()
        val o = ByteArray(outN * 2)
        for (i in 0 until outN) {
            val pos = i.toDouble() * from / VoiceEngine.RATE
            val a = pos.toInt().coerceAtMost(n - 1); val b = (a + 1).coerceAtMost(n - 1); val f = pos - a
            val va = ((pcm[a * 2].toInt() and 0xff) or (pcm[a * 2 + 1].toInt() shl 8)).toShort().toInt()
            val vb = ((pcm[b * 2].toInt() and 0xff) or (pcm[b * 2 + 1].toInt() shl 8)).toShort().toInt()
            val v = (va + (vb - va) * f).toInt()
            o[i * 2] = (v and 0xff).toByte(); o[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
        }
        return o
    }
}

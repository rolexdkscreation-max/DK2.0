package com.dk.assistant.voice.st3

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * 19 नवंबर 2026: 44.1 kHz, mono, 16-bit आवाज़ को धारा (stream) में बजाने वाला छोटा player.
 * [write] रुककर (blocking) लिखता है - इसे IO धागे पर बुलाओ. [stop] किसी भी धागे से - तुरंत चुप.
 */
class St3Player(private val rate: Int = St3Config.SAMPLE_RATE) {
    @Volatile private var track: AudioTrack? = null
    @Volatile private var stopped = false

    private fun open(): AudioTrack {
        track?.let { return it }
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes((min * 4).coerceAtLeast(rate / 2))     // ~0.25 सेकंड+ - छोटे झटके न अटकें
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play(); track = t
        return t
    }

    /** false = रोका गया */
    fun write(pcm: ByteArray): Boolean {
        if (stopped) return false
        val t = try { open() } catch (e: Exception) { com.dk.assistant.Quiet.note(e); return false }
        var off = 0
        while (off < pcm.size && !stopped) {
            val n = t.write(pcm, off, minOf(8192, pcm.size - off))
            if (n <= 0) return false
            off += n
        }
        return !stopped
    }

    /** जो लिखा जा चुका, उसके पूरा बजने तक रुको (टेस्ट वाक्य का आख़िरी हिस्सा कटे नहीं) */
    fun drain(totalBytes: Long) {
        val t = track ?: return
        val frames = totalBytes / 2
        var guard = 0
        while (!stopped && t.playbackHeadPosition.toLong() < frames && guard++ < 600) Thread.sleep(20)
    }

    fun stop() {
        stopped = true
        val t = track; track = null
        try { t?.pause(); t?.flush(); t?.stop() } catch (ignored: Exception) {}
        try { t?.release() } catch (ignored: Exception) {}
    }
}

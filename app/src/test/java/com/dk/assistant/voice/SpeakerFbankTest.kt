package com.dk.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.round
import kotlin.math.sin

/** v3.66: fbank = kaldi-native-fbank (sherpa-onnx वाली सेटिंग) - वरना पहचान बिगड़ेगी */
class SpeakerFbankTest {
    private fun signal(): FloatArray = FloatArray(8000) { t ->
        val v = 3000 * sin(2 * PI * 440 * t / 16000) + 1500 * sin(2 * PI * 1234 * t / 16000) +
            800 * sin(2 * PI * 3100 * t / 16000) * sin(2 * PI * 3 * t / 16000)
        (round(v) / 32768.0).toFloat()
    }

    @Test fun frameCountMatchesKaldiNoSnip() {
        assertEquals(50, SpeakerFbank.numFrames(8000))
        assertEquals(100, SpeakerFbank.numFrames(16000))
        assertEquals(0, SpeakerFbank.numFrames(0))
    }

    @Test fun valuesMatchKaldiNativeFbank() {
        val f = SpeakerFbank.compute(signal())
        assertEquals(50 * 80, f.size)
        // python: kaldi_native_fbank, नमूने -1..1 (dither 0, snip_edges False, 80 bins, 20-7600 Hz) - global mean घटाकर
        val ref = listOf(Triple(0, 0, 6.426210), Triple(3, 10, -0.196193), Triple(10, 5, -0.278101),
            Triple(25, 40, -0.540881), Triple(49, 79, 5.381190), Triple(30, 20, -0.387403))
        for ((i, j, v) in ref) assertEquals("frame $i bin $j", v, f[i * 80 + j].toDouble(), 2e-3)
    }

    @Test fun pcm16LittleEndian() {
        val b = byteArrayOf(0x00, 0x40, 0x00, 0xC0.toByte(), 0xFF.toByte(), 0x7F)
        val x = SpeakerFbank.pcm16(b)
        assertEquals(0.5f, x[0], 1e-6f); assertEquals(-0.5f, x[1], 1e-6f); assertEquals(32767 / 32768f, x[2], 1e-6f)
    }
}

package com.dk.assistant.voice.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** नवंबर 2026: Piper के इफ़ेक्ट - डिफ़ॉल्ट = कोई बदलाव नहीं; बास / रिवर्ब सच में असर करें; आवाज़ कभी न फटे */
class PiperFxTest {
    private val rate = 22050
    private fun sine(hz: Double, n: Int = rate, amp: Double = 0.5) = FloatArray(n) { (amp * sin(2 * PI * hz * it / rate)).toFloat() }
    private fun rms(x: FloatArray, from: Int = 0) = sqrt(x.drop(from).sumOf { it.toDouble() * it } / (x.size - from))

    @Test fun defaultsAreIdentity() {
        val fx = PiperFx(rate)
        assertTrue(fx.identity)
        val x = sine(220.0)
        assertSame(x, fx.process(x))                 // वही array - एक भी sample नहीं बदला
        assertEquals(0, fx.tail().size)               // रिवर्ब बंद = कोई गूँज नहीं
        assertEquals(1f, PiperFx.genSpeed(1f, 1f), 0f)
        assertEquals(rate, PiperFx.srcRate(rate, 1f))
        assertEquals(140, PiperFx.gap(140, 100))
    }

    @Test fun bassBoostsLowsNotHighs() {
        val lo = sine(80.0, amp = 0.2); val hi = sine(3000.0, amp = 0.2)
        val l = rms(PiperFx(rate, bassDb = 12f).process(lo), 2000) / rms(lo, 2000)
        val h = rms(PiperFx(rate, bassDb = 12f).process(hi), 2000) / rms(hi, 2000)
        assertTrue("बास: $l", l > 1.8)
        assertTrue("ऊँची आवाज़ लगभग वैसी: $h", h in 0.6..1.1)
    }

    @Test fun reverbAddsTailAndChangesSignal() {
        val fx = PiperFx(rate, reverb = 0.5f)
        val x = FloatArray(4000).also { it[0] = 0.9f }      // एक ताली
        val y = fx.process(x)
        assertTrue(y.drop(1500).any { abs(it) > 1e-4 })       // ताली के बाद भी गूँज
        val t = fx.tail()
        assertEquals(rate * PiperFx.TAIL_MS / 1000, t.size)
        assertTrue(t.any { abs(it) > 1e-6 })
    }

    @Test fun neverClips() {
        val y = PiperFx(rate, bassDb = 12f, reverb = 1f).process(sine(60.0, amp = 1.0))
        assertTrue(y.all { abs(it) <= 1f })
        assertEquals(0.5, PiperFx.soft(0.5), 0.0)
        assertTrue(PiperFx.soft(5.0) <= 1.0 && PiperFx.soft(-5.0) >= -1.0 && PiperFx.soft(0.95) < 0.95)
    }

    @Test fun pitchKeepsSpeed_pauseScales() {
        // पिच 1.2: 1/1.2 रफ़्तार पर बनाओ, 1.2 गुना rate मानो -> लंबाई वही, सुर ऊँचा
        assertEquals(1f / 1.2f, PiperFx.genSpeed(1f, 1.2f), 1e-4f)
        assertEquals(26460, PiperFx.srcRate(rate, 1.2f))
        assertEquals(1.5f, PiperFx.genSpeed(1.5f, 1f), 1e-6f)
        assertEquals(0, PiperFx.gap(140, 0)); assertEquals(280, PiperFx.gap(140, 200)); assertEquals(420, PiperFx.gap(140, 999))
        val pcm = PiperFx.pcm16(floatArrayOf(0f, 1f, -1f, 2f))
        assertEquals(8, pcm.size)
        assertEquals(32767, ((pcm[2].toInt() and 0xff) or (pcm[3].toInt() shl 8)).toShort().toInt())
        assertEquals(32767, ((pcm[6].toInt() and 0xff) or (pcm[7].toInt() shl 8)).toShort().toInt())
    }
}

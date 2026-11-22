package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** 22 नवंबर 2026: स्क्रीन की रंगीन रोशनी वाली लाइव जाँच ([FlashLive]) - वीडियो / फ़ोटो पास न हों, असली चेहरा हो */
class FlashLiveTest {
    /** [faceGain] / [ringGain] = स्क्रीन के रंग से चेहरे / आस-पास में कितना बदलाव. [lag] = कैमरे की देर */
    private fun sim(faceGain: Float, ringGain: Float, lag: Long = 120, noise: Double = 0.004, seed: Long = 1, gapMs: Long = 50,
                    seq: IntArray = FlashLive.randomSeq(kotlin.random.Random(seed))): FlashLive.Result {
        val f = FlashLive(seq); val rnd = Random(seed)
        f.start(1_000L)
        var t = 1_000L
        while (!f.done(t)) {
            val c = f.color(t - lag)
            f.sample(t, 0.15f + faceGain * c + (rnd.nextGaussian() * noise).toFloat(), 0.08f + ringGain * c + (rnd.nextGaussian() * noise).toFloat())
            t += gapMs
        }
        return f.result()
    }

    @Test fun realFaceNearScreenIsLive() {
        for (seed in 1L..40L) assertTrue("seed $seed", sim(0.05f, 0.01f, seed = seed).live)
        // कैमरे की अलग-अलग देर
        for (lag in listOf(0L, 90L, 200L, 300L)) assertTrue("lag $lag", sim(0.04f, 0.005f, lag = lag).live)
        // धीमा कैमरा (~12 fps)
        assertTrue(sim(0.05f, 0.01f, gapMs = 80).live)
    }

    @Test fun videoOnScreenNeverLive() {
        // वीडियो वाली स्क्रीन: रंग की चमक चेहरे और आस-पास (उसी शीशे) पर बराबर
        for (seed in 1L..200L) assertFalse("seed $seed", sim(0.03f, 0.03f, seed = seed).live)
        // पहले से रिकॉर्ड - कोई रंग नहीं
        for (seed in 1L..200L) assertFalse("seed $seed", sim(0f, 0f, seed = seed).live)
    }

    @Test fun replayWithItsOwnFlashingFailsWrongOrder() {
        // वीडियो में भी रंग बदलते दिखें (पिछली बार रिकॉर्ड किया) - पर क्रम इस बार का नहीं
        val now = FlashLive.randomSeq(kotlin.random.Random(3))
        val old = now.map { -it }.toIntArray()
        val f = FlashLive(now); f.start(1_000L)
        val rnd = Random(9); var t = 1_000L
        while (!f.done(t)) {
            val i = ((t - 1_100) / FlashLive.SLOT_MS).toInt()
            val c = if (i in old.indices) old[i] else 0
            f.sample(t, 0.15f + 0.05f * c + (rnd.nextGaussian() * 0.003).toFloat(), 0.08f + (rnd.nextGaussian() * 0.003).toFloat())
            t += 50
        }
        assertFalse(f.result().live)
    }

    @Test fun brightSunNoSignalIsNotLive() {
        assertFalse(sim(0.004f, 0.001f).live)
    }

    @Test fun randomSequenceBalancedAndNotPlainAlternating() {
        repeat(200) {
            val s = FlashLive.randomSeq()
            assertEquals(FlashLive.SLOTS, s.size)
            assertEquals(FlashLive.SLOTS / 2, s.count { it > 0 })
            assertTrue(s.toList().zipWithNext().any { (a, b) -> a == b })
        }
        val many = (1..200).map { FlashLive.randomSeq().joinToString() }.toSet()
        assertTrue("हर बार अलग क्रम", many.size > 10)
    }

    @Test fun colorTimeline() {
        val f = FlashLive(intArrayOf(1, 1, -1, -1, 1, -1))
        assertEquals(0, f.color(5))
        f.start(100)
        assertEquals(1, f.color(100)); assertEquals(-1, f.color(100 + 2 * FlashLive.SLOT_MS)); assertEquals(0, f.color(100 + 6 * FlashLive.SLOT_MS))
        assertFalse(f.done(100 + 6 * FlashLive.SLOT_MS))
        assertTrue(f.done(100 + 6 * FlashLive.SLOT_MS + FlashLive.LAG_MAX + FlashLive.SETTLE_MS))
    }

    @Test fun trackBreaksOnSwapOrSecondFace() {
        val f = FlashLive(); f.start(1)
        assertTrue(f.track(10, 1, 100f, 100f, 80f))
        assertTrue(f.track(60, 1, 104f, 101f, 82f))
        assertFalse(f.track(110, 1, 400f, 100f, 80f))
        val g = FlashLive(); g.start(1)
        assertFalse(g.track(10, 2, 100f, 100f, 80f))
        val h = FlashLive(); h.start(1)
        assertTrue(h.track(10, 1, 100f, 100f, 80f))
        assertTrue(h.track(300, 0, 0f, 0f, 0f))
        assertFalse(h.track(800, 0, 0f, 0f, 0f))
    }

    @Test fun measureFaceVersusRing() {
        val w = 200; val h = 200
        // बीच में लाल-सा चेहरा, बाहर धूसर
        val px = IntArray(w * h) { i -> val x = i % w; val y = i / w
            if (x in 70..130 && y in 70..130) 0xFFC08070.toInt() else 0xFF808080.toInt() }
        val m = FlashLive.measure(px, w, h, 60f, 60f, 80f, 80f)
        assertNotNull(m)
        assertTrue(m!![0] > 0.1f)
        assertEquals(0f, m[1], 0.02f)
        assertEquals(null, FlashLive.measure(px, w, h, 0f, 0f, 0f, 0f))
    }
}

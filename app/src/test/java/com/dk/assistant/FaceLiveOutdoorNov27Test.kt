package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * 27 नवंबर 2026 (मालिक: "आउटडोर में धूप रहते रंग वाली फ़ेस जाँच नहीं होती - एक्यूरेट करो, फ़ोटो / वीडियो से नहीं, रियल इंसान से ही").
 * ① रंग वाली जाँच ([FlashLive]) धूप के छोटे असर पर भी (आँकड़ों वाला रास्ता), वीडियो / फ़ोटो पर कभी नहीं.
 * ② सच में धूप हो और रंग न दिखे = धूप वाली random सिर-घुमाना जाँच ([SunTurns]) - बारी-बारी घूमता वीडियो कभी नहीं.
 */
class FaceLiveOutdoorNov27Test {
    private fun src(name: String) = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()

    private fun sim(faceGain: Float, ringGain: Float, seed: Long, lag: Long = 120, noise: Double = 0.004, gapMs: Long = 50,
                    replayOf: IntArray? = null): FlashLive.Result {
        val seq = FlashLive.randomSeq(kotlin.random.Random(seed))
        val f = FlashLive(seq); val rnd = Random(seed)
        f.start(1_000L)
        var t = 1_000L
        while (!f.done(t)) {
            val c = if (replayOf != null) { val i = ((t - lag - 1_000L) / FlashLive.SLOT_MS).toInt(); if (t - lag >= 1_000L && i in replayOf.indices) replayOf[i] else 0 } else f.color(t - lag)
            f.sample(t, 0.15f + faceGain * c + (rnd.nextGaussian() * noise).toFloat(), 0.08f + ringGain * c + (rnd.nextGaussian() * noise).toFloat(), 0.7f)
            t += gapMs
        }
        return f.result()
    }

    // ---------------------------------------------------------------- ① रंग वाली जाँच
    @Test fun sunWeakSignalNowPassesMostly() {
        // धूप: चेहरे पर सिर्फ़ ~0.8% असर (पहले की सख़्त शर्त पर लगभग हमेशा फ़ेल)
        val pass = (1L..200L).count { sim(0.008f, 0.001f, it).live }
        assertTrue("धूप में पास $pass/200", pass >= 140)
        assertTrue((1L..100L).count { sim(0.012f, 0.002f, it).live } >= 95)
        // घर के अंदर वाला बड़ा असर - हमेशा
        for (s in 1L..100L) assertTrue("seed $s", sim(0.05f, 0.01f, s).live)
    }

    @Test fun videoPhotoNeverPassFlashEvenWithStatPath() {
        for (s in 1L..600L) {
            assertFalse("वीडियो स्क्रीन seed $s", sim(0.03f, 0.03f, s).live)
            assertFalse("कोई रंग नहीं seed $s", sim(0f, 0f, s).live)
        }
        // ज़्यादा शोर वाला कैमरा
        for (s in 1L..300L) assertFalse(sim(0.03f, 0.03f, s, noise = 0.008).live)
    }

    @Test fun replayWithOldOrderIsAgainstAndFails() {
        var against = 0
        for (s in 1L..100L) {
            val now = FlashLive.randomSeq(kotlin.random.Random(s))
            val r = sim(0.05f, 0.01f, s, replayOf = now.map { -it }.toIntArray())
            assertFalse("seed $s", r.live)
            if (r.against) against++
        }
        assertTrue("उल्टा क्रम पकड़ा $against/100", against >= 90)
    }

    @Test fun tenSlotsBalancedRandom() {
        assertEquals(10, FlashLive.SLOTS)
        repeat(200) { val s = FlashLive.randomSeq(); assertEquals(5, s.count { it > 0 }); assertEquals(10, s.size) }
        assertTrue(FlashLive.tStat(listOf(0.10f, 0.11f, 0.10f), listOf(0.02f, 0.03f, 0.02f)) > 6f)
        assertTrue(FlashLive.tStat(listOf(0.10f, 0.02f, 0.06f), listOf(0.09f, 0.03f, 0.05f)) < 1f)
        assertEquals(0f, FlashLive.tStat(listOf(0.1f), listOf(0.2f, 0.3f)))
    }

    @Test fun measureGivesFaceBrightness() {
        val w = 200; val h = 200
        val px = IntArray(w * h) { i -> val x = i % w; val y = i / w; if (x in 70..130 && y in 70..130) 0xFFC0C0C0.toInt() else 0xFF404040.toInt() }
        val m = FlashLive.measure(px, w, h, 60f, 60f, 80f, 80f)
        assertNotNull(m)
        assertEquals(0xC0 / 255f, m!![2], 0.02f)
    }

    // ---------------------------------------------------------------- ② धूप वाली सिर-घुमाना जाँच
    /** असली इंसान: माँगी तरफ़ ~500ms में घूमे, फिर सीधे */
    private fun human(st: SunTurns, t0: Long): Pair<SunTurns.State, Long> {
        var t = t0; var r = SunTurns.State.WAIT
        fun f(y: Float) { if (r == SunTurns.State.WAIT) r = st.update(t, y); t += 100 }
        repeat(st.steps.size) {
            val s = st.side
            f(0f); repeat(4) { f(0.02f * s) }; repeat(3) { f(0.15f * s) }; repeat(3) { f(0f) }
        }
        return r to t
    }

    @Test fun sunTurnsRealHumanPasses() {
        for (seed in 1L..50L) {
            val st = SunTurns(SunTurns.randomSteps(kotlin.random.Random(seed)))
            assertEquals("seed $seed", SunTurns.State.DONE, human(st, 0L).first)
        }
    }

    @Test fun sunTurnsStepsAlwaysHaveRepeatAndBothSides() {
        repeat(300) {
            val s = SunTurns.randomSteps()
            assertEquals(SunTurns.STEPS, s.size)
            assertTrue(s.any { it > 0 } && s.any { it < 0 })
            assertTrue(s.toList().zipWithNext().any { (a, b) -> a == b })
        }
    }

    @Test fun sunTurnsAlternatingVideoNeverPasses() {
        // रिकॉर्ड किया वीडियो: सिर बार-बार बाएँ-दाएँ (अलग-अलग रफ़्तार), माँगे क्रम को नहीं जानता
        for (period in listOf(800L, 1_200L, 1_600L, 2_400L, 3_000L)) for (phase in 0L until period step 100L) for (seed in 1L..4L) {
            val st = SunTurns(SunTurns.randomSteps(kotlin.random.Random(seed)))
            var t = 0L; var r = SunTurns.State.WAIT
            while (r == SunTurns.State.WAIT && t < 20_000L) {
                val x = kotlin.math.sin(2 * Math.PI * (t + phase) / period).toFloat() * 0.18f
                r = st.update(t, x); t += 100
            }
            assertFalse("period $period phase $phase seed $seed", r == SunTurns.State.DONE)
        }
        // सीधा देखता वीडियो / फ़ोटो - कभी घूमता ही नहीं
        val st = SunTurns(intArrayOf(1, 1, -1)); var r = SunTurns.State.WAIT; var t = 0L
        while (r == SunTurns.State.WAIT && t < 20_000L) { r = st.update(t, 0.01f); t += 100 }
        assertEquals(SunTurns.State.FAIL, r)
    }

    @Test fun sunTurnsWrongSideOrTooEarlyFails() {
        val a = SunTurns(intArrayOf(1, 1, -1))
        a.update(0, 0f); assertEquals(SunTurns.State.FAIL, a.update(400, -0.12f))
        val b = SunTurns(intArrayOf(1, 1, -1))
        b.update(0, 0f); assertEquals(SunTurns.State.FAIL, b.update(100, 0.15f))
    }

    // ---------------------------------------------------------------- गेट: धूप में ही दूसरी जाँच
    private var t = 0L
    private fun fr(score: Float? = 0.8f, real: Float? = 0.95f, yaw: Float? = 0f, lux: Float? = 30_000f, cx: Float = 100f) =
        QuickFaceGate.Frame(t.also { t += 80 }, 1, cx, 100f, 80f, yaw, score, null, real, true, lux)

    /** मिलान + AI पास करके रंग वाली जाँच तक, फिर रंग का [faceGain] असर (धूप = लगभग 0) */
    private fun toFlashEnd(g: QuickFaceGate, faceGain: Float, lux: Float?, inverted: Boolean = false): QuickFaceGate.Verdict {
        var v = g.feed(fr(lux = lux)); var n = 0
        while (!v.flash && n++ < 20) v = g.feed(fr(lux = lux))
        assertTrue(v.flash)
        val fl = g.flash!!; fl.start(t)
        val rnd = Random(5)
        v = g.feed(fr(score = null, real = null, lux = lux))
        while (v.state == QuickFaceGate.State.WAIT && g.flashing) {
            val c = fl.color(t - 90) * (if (inverted) -1 else 1)
            fl.sample(t, 0.15f + faceGain * c + (rnd.nextGaussian() * 0.003).toFloat(), 0.10f + (rnd.nextGaussian() * 0.003).toFloat())
            v = g.feed(fr(score = null, real = null, lux = lux))
        }
        return v
    }

    private fun doTurns(g: QuickFaceGate, real: Float = 0.9f, lux: Float? = 30_000f): QuickFaceGate.Verdict {
        var v: QuickFaceGate.Verdict = QuickFaceGate.Verdict(QuickFaceGate.State.WAIT, "")
        fun f(y: Float, s: Float? = null) { if (v.state == QuickFaceGate.State.WAIT) v = g.feed(fr(score = s, real = real, yaw = y, lux = lux)) }
        var guard = 0
        while (g.sun != null && v.state == QuickFaceGate.State.WAIT && guard++ < 10) {
            val st = g.sun!!
            if (st.index >= st.steps.size) { repeat(8) { f(0f, 0.8f) }; break }
            val s = st.side
            f(0f, 0.8f); repeat(5) { f(0.02f * s) }; repeat(3) { f(0.15f * s) }; repeat(2) { f(0f, 0.8f) }
        }
        return v
    }

    @Test fun sunnyNoFlashSignalGoesToTurnsAndRealOwnerPasses() {
        val g = QuickFaceGate(side = 1, allowTurn = false)
        val v = toFlashEnd(g, 0f, lux = 40_000f)
        assertEquals(QuickFaceGate.State.WAIT, v.state)
        assertTrue(v.turn)
        assertNotNull(g.sun)
        assertEquals(QuickFaceGate.State.PASS, doTurns(g).state)
    }

    @Test fun indoorNoFlashSignalStillFails() {
        // घर के अंदर (300 lux) रंग न दिखे = वीडियो / फ़ोटो - पहले जैसा FAIL, कोई दूसरी जाँच नहीं
        val g = QuickFaceGate(side = 1)
        val v = toFlashEnd(g, 0f, lux = 300f)
        assertEquals(QuickFaceGate.State.FAIL, v.state)
        assertEquals(QuickFaceGate.FLASH_FAIL, v.why)
        assertEquals(null, g.sun)
        // sensor नहीं + चेहरा धूप जैसा चमकीला नहीं = भी FAIL
        val h = QuickFaceGate(side = 1)
        assertEquals(QuickFaceGate.State.FAIL, toFlashEnd(h, 0f, lux = null).state)
    }

    @Test fun sunnyReplayWithWrongColorsFailsNoTurns() {
        val g = QuickFaceGate(side = 1)
        assertEquals(QuickFaceGate.State.FAIL, toFlashEnd(g, 0.05f, lux = 40_000f, inverted = true).state)
        assertEquals(null, g.sun)
    }

    @Test fun sunnyVideoFailsTurnsOrAi() {
        // AI ने फ़ोटो / स्क्रीन पकड़ी = धूप वाली जाँच में भी FAIL
        val g = QuickFaceGate(side = 1)
        toFlashEnd(g, 0f, lux = 40_000f)
        var v = g.feed(fr(real = 0.1f, lux = 40_000f)); repeat(6) { if (v.state == QuickFaceGate.State.WAIT) v = g.feed(fr(real = 0.1f, lux = 40_000f)) }
        assertEquals(QuickFaceGate.State.FAIL, v.state)
        // बारी-बारी घूमता वीडियो (AI को धोखा भी दे) = FAIL
        for (seed in 1..20) {
            val h = QuickFaceGate(side = 1)
            toFlashEnd(h, 0f, lux = 40_000f)
            var w = QuickFaceGate.Verdict(QuickFaceGate.State.WAIT, ""); var k = 0
            val ph = seed * 137L
            while (w.state == QuickFaceGate.State.WAIT && k++ < 300) {
                val y = kotlin.math.sin(2 * Math.PI * (t + ph) / 1_500.0).toFloat() * 0.18f
                w = h.feed(fr(score = if (kotlin.math.abs(y) < 0.05f) 0.8f else null, real = 0.9f, yaw = y, lux = 40_000f))
            }
            assertFalse("seed $seed", w.state == QuickFaceGate.State.PASS)
        }
        // अजनबी धूप में = FAIL
        val s = QuickFaceGate(side = 1)
        toFlashEnd(s, 0f, lux = 40_000f)
        var x = s.feed(fr(score = 0.2f, lux = 40_000f)); repeat(4) { if (x.state == QuickFaceGate.State.WAIT) x = s.feed(fr(score = 0.2f, lux = 40_000f)) }
        assertEquals(QuickFaceGate.State.FAIL, x.state)
    }

    @Test fun wiringCameraLockAndLux() {
        val cam = src("FaceCam.kt")
        assertTrue(cam.contains("CONTROL_AWB_LOCK, on") && cam.contains("CONTROL_AE_LOCK, on"))
        val fs = src("FlashScreen.kt")
        assertTrue(fs.contains("cam?.lockLight(true)") && fs.contains("cam?.lockLight(false)"))
        assertTrue(fs.contains("Sensor.TYPE_LIGHT") && fs.contains("f.sample(now, it[0], it[1], it[2])"))
        for (a in listOf("AppLockActivity.kt", "GuardAlarmActivity.kt")) {
            val s = src(a)
            assertTrue(a, s.contains("lux = flashScr.lux()") && s.contains("flashScr.cam = cam") && s.contains("flashScr.stopLux()"))
        }
    }
}

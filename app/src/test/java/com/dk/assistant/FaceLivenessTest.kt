package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** नवंबर 2026 (मालिक): गार्ड मोड का सायरन फ़ोटो से नहीं, सिर्फ़ असली इंसान (सिर घुमाकर 3D जाँच) से रुके */
class FaceLivenessTest {
    // सीधा चेहरा: आँखें, नाक, होंठ के दो कोने (YuNet का क्रम)
    private val front = floatArrayOf(40f, 50f, 60f, 50f, 50f, 61f, 42f, 72f, 58f, 72f)

    private fun det(k: FloatArray): FaceMath.Det {
        val xs = k.filterIndexed { i, _ -> i % 2 == 0 }; val ys = k.filterIndexed { i, _ -> i % 2 == 1 }
        val x = xs.min() - 10f; val y = ys.min() - 15f
        return FaceMath.Det(0.9f, x, y, xs.max() - xs.min() + 20f, ys.max() - ys.min() + 25f, k)
    }

    /** फ़ोटो को हिलाना/घुमाना/पास-दूर/तिरछा करना = affine बदलाव */
    private fun affine(k: FloatArray, a: Float, b: Float, c: Float, d: Float, tx: Float, ty: Float) =
        FloatArray(k.size) { i -> if (i % 2 == 0) a * k[i] + b * k[i + 1] + tx else c * k[i - 1] + d * k[i] + ty }

    /** फ़ोटो को आगे-पीछे झुकाना (perspective, हल्का) */
    private fun perspective(k: FloatArray, g: Float, h: Float) = FloatArray(k.size) { i ->
        val x = if (i % 2 == 0) k[i] else k[i - 1]; val y = if (i % 2 == 0) k[i + 1] else k[i]
        val w = 1f + g * (x - 50f) + h * (y - 60f)
        if (i % 2 == 0) x / w else y / w
    }

    /** असली सिर का घुमाव (yaw θ): नाक आगे उभरी (z), आँखें/होंठ चेहरे की सतह पर */
    private fun turned(deg: Double): FloatArray {
        val t = Math.toRadians(deg)
        // (x, y, z) - z = कैमरे की ओर उभार
        val p = listOf(Triple(-10.0, 50.0, 0.0), Triple(10.0, 50.0, 0.0), Triple(0.0, 61.0, 12.0), Triple(-8.0, 72.0, 2.0), Triple(8.0, 72.0, 2.0))
        return p.flatMap { (x, y, z) -> listOf((50 + x * cos(t) + z * sin(t)).toFloat(), y.toFloat()) }.toFloatArray()
    }

    @Test fun yaw_frontIsZero_photoTransformsDontChangeIt() {
        assertEquals(0f, FaceLiveness.yaw(front)!!, 0.01f)
        // घुमाना (30°), पास-दूर, तिरछा (shear), खिसकाना - सपाट फ़ोटो पर नाप वही
        val r = Math.toRadians(30.0); val cr = cos(r).toFloat(); val sr = sin(r).toFloat()
        for (k in listOf(
            affine(front, cr, -sr, sr, cr, 30f, -12f),
            affine(front, 1.8f, 0f, 0f, 1.8f, -40f, 5f),
            affine(front, 0.6f, 0.25f, 0f, 1f, 3f, 3f),      // बगल से तिरछी फ़ोटो
            affine(front, 1f, 0f, 0.3f, 0.7f, 0f, 0f),
            perspective(front, 0.004f, 0f),                   // फ़ोटो एक तरफ़ झुकाई
            perspective(front, 0f, 0.004f),
        )) assertTrue("फ़ोटो का नाप ${FaceLiveness.yaw(k)}", abs(FaceLiveness.yaw(k)!!) < FaceLiveness.TURN)
        assertTrue(FaceLiveness.yaw(floatArrayOf(1f, 1f)) == null)
    }

    @Test fun yaw_realHeadTurnChangesClearly() {
        assertTrue(FaceLiveness.yaw(turned(25.0))!! >= FaceLiveness.TURN)
        assertTrue(FaceLiveness.yaw(turned(-25.0))!! <= -FaceLiveness.TURN)
        assertTrue(abs(FaceLiveness.yaw(turned(0.0))!!) <= FaceLiveness.CENTER)
    }

    @Test fun photo_movedAroundForLong_neverLive() {
        val l = FaceLiveness()
        var t = 0L; var live = false
        // हाथ में फ़ोटो: 100 फ़्रेम तक हिलाते, घुमाते, झुकाते, पास-दूर करते रहे
        for (i in 0 until 100) {
            val a = Math.toRadians((i % 20 - 10) * 2.0); val s = 1f + (i % 7) * 0.03f
            var k = affine(front, (cos(a) * s).toFloat(), (-sin(a) * s).toFloat(), (sin(a) * s).toFloat(), (cos(a) * s).toFloat(), (i % 5).toFloat(), (i % 3).toFloat())
            k = perspective(k, (i % 9 - 4) * 0.0008f, (i % 5 - 2) * 0.0008f)
            live = live || l.update(det(k), t); t += 150
        }
        assertFalse("फ़ोटो से सायरन नहीं रुकना चाहिए", live)
        assertTrue(l.flatCount() >= 12)
    }

    @Test fun realPerson_turnBothWays_thenCenter_isLive() {
        val l = FaceLiveness(); var t = 0L
        fun f(deg: Double) = l.update(det(turned(deg)), t.also { t += 150 })
        assertFalse(f(0.0))
        assertEquals(FaceLiveness.Step.TURN_ONE, l.step)
        assertFalse(f(8.0)); assertFalse(f(25.0))
        assertEquals(FaceLiveness.Step.TURN_OTHER, l.step)
        assertFalse(f(0.0)); assertFalse(f(-25.0))
        assertEquals(FaceLiveness.Step.LOOK_CENTER, l.step)
        assertTrue(f(0.0))
        assertEquals(FaceLiveness.Step.LIVE, l.step)
    }

    @Test fun oneSideOnly_notLive() {
        val l = FaceLiveness(); var t = 0L
        for (d in listOf(0.0, 25.0, 0.0, 28.0, 0.0, 25.0, 0.0)) assertFalse(l.update(det(turned(d)), t.also { t += 150 }))
    }

    @Test fun faceGap_orPhotoSwap_resets() {
        val l = FaceLiveness(); var t = 0L
        l.update(det(turned(0.0)), t); l.update(det(turned(25.0)), 150); l.update(det(turned(-25.0)), 300)
        // चेहरा 1 सेकंड गायब (फ़ोटो बदली) = फिर से शुरू
        l.update(null, 1_500)
        assertEquals(FaceLiveness.Step.NEED_FACE, l.step)
        assertFalse(l.update(det(front), 1_600))
        // एक फ़्रेम में एकदम दूसरी जगह (फ़ोटो बदली) = फिर से
        val l2 = FaceLiveness()
        l2.update(det(turned(0.0)), 0); l2.update(det(turned(25.0)), 150); l2.update(det(turned(-25.0)), 300)
        assertFalse(l2.update(det(affine(front, 1f, 0f, 0f, 1f, 200f, 0f)), 450))
        // बहुत धीरे (12 सेकंड से ज़्यादा) = फिर से
        val l3 = FaceLiveness()
        l3.update(det(turned(0.0)), 0); l3.update(det(turned(25.0)), 150); l3.update(det(turned(-25.0)), 300)
        assertFalse(l3.update(det(turned(0.0)), 13_000))
    }

    private fun src(name: String) = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()

    @Test fun alarmScreen_needsLivenessBeforeDisarm() {
        val a = src("GuardAlarmActivity.kt")
        assertTrue(a.contains("private val liveness = FaceLiveness()"))
        assertTrue(a.contains("val live = liveness.update(det, now)"))
        assertTrue(a.contains("if (owner && live && front)"))
        assertTrue(a.contains("if (s != null && s < TURNED_MIN) liveness.reset()"))
        // पुराना ज़रा-से-हिलना वाला तरीका अब सायरन बंद करने में अकेला नहीं
        assertFalse(a.contains("livenessTracker.processFrame"))
        assertNotNull(FaceLiveness().hint())
    }
}

package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * 15 नवंबर 2026 (मालिक):
 * 1) "चेहरा सेट करते समय कम से कम 30 सेकंड, इधर-उधर घुमाने को बोले, अच्छे से पूरा कैप्चर करे; Boss का चेहरा आए तो पूरा पक्का
 *    करे - असली है या फ़ोटो / वीडियो - फिर authorize".
 * 2) "टच मोड की फ़ोटो ऐप में दिखे (सिर्फ़ टाइम नहीं), कितनी भी बार खींची हो, और अच्छी क्लिक हुई फ़ोटो रहे".
 */
class FaceEnrollPhotoTest {

    /** 5 बिंदु (आँख, आँख, नाक, मुँह, मुँह) - [yawDeg] बाएँ(+)/दाएँ(-), [pitchDeg] + = ठोड़ी नीचे */
    private fun face(yawDeg: Double = 0.0, pitchDeg: Double = 0.0): FloatArray {
        val t = Math.toRadians(yawDeg); val p = Math.toRadians(pitchDeg)
        val pts = listOf(Triple(-10.0, 50.0, 0.0), Triple(10.0, 50.0, 0.0), Triple(0.0, 61.0, 12.0), Triple(-8.0, 72.0, 2.0), Triple(8.0, 72.0, 2.0))
        return pts.flatMap { (x, y, z) ->
            val y1 = 61 + (y - 61) * cos(p) + z * sin(p); val z1 = -(y - 61) * sin(p) + z * cos(p)
            listOf((50 + x * cos(t) + z1 * sin(t)).toFloat(), y1.toFloat())
        }.toFloatArray()
    }

    private fun frameFor(pose: FaceEnroll.Pose) = when (pose) {
        FaceEnroll.Pose.CENTER, FaceEnroll.Pose.FINAL -> face()
        FaceEnroll.Pose.LEFT -> face(yawDeg = 25.0)
        FaceEnroll.Pose.RIGHT -> face(yawDeg = -25.0)
        FaceEnroll.Pose.UP -> face(pitchDeg = -25.0)
        FaceEnroll.Pose.DOWN -> face(pitchDeg = 25.0)
    }

    @Test fun enroll_takesAtLeast30s_allSixPoses() {
        val e = FaceEnroll(); var t = 10_000L; var takes = 0; var last = FaceEnroll.Act.WAIT
        val seen = mutableSetOf<FaceEnroll.Pose>()
        while (t < 200_000L) {
            seen += e.pose
            last = e.feed(frameFor(e.pose), t)
            if (last == FaceEnroll.Act.TAKE) takes++
            if (last == FaceEnroll.Act.DONE) break
            t += 300
        }
        assertEquals(FaceEnroll.Act.DONE, last)
        assertTrue("कम से कम 30 s: ${e.elapsed(t)}", e.elapsed(t) >= FaceEnroll.MIN_TOTAL_MS)
        assertEquals(FaceEnroll.Pose.values().toSet(), seen)
        assertTrue(takes >= FaceEnroll.FRAMES_PER_POSE * 6)
        assertEquals(6, e.total())
        assertEquals(0, e.skipped)
    }

    @Test fun enroll_neverSkipsLeftRight_ifOnlyFrontShown() {
        // फ़ोटो हिलाकर नहीं सीखेगा: सिर्फ़ सीधा चेहरा = "बाएँ" पर ही अटका रहे
        val e = FaceEnroll(); var t = 0L
        repeat(400) { e.feed(face(), t); t += 300 }
        assertEquals(FaceEnroll.Pose.LEFT, e.pose)
        assertFalse(e.done())
    }

    @Test fun pitch_sign_chinDownIsBigger() {
        val c = FaceEnroll.pitch(face())!!
        assertTrue(FaceEnroll.pitch(face(pitchDeg = 25.0))!! - c >= FaceEnroll.TILT)
        assertTrue(c - FaceEnroll.pitch(face(pitchDeg = -25.0))!! >= FaceEnroll.TILT)
    }

    @Test fun spoofVote_sure_needsManyCleanFrames() {
        val v = FaceSpoof.Vote()
        repeat(FaceSpoof.SURE_FRAMES - 1) { v.add(0.95f) }
        assertFalse(v.sure())
        v.add(0.95f); assertTrue(v.sure())
        v.add(0.05f)   // बीच में एक भी "नकली" फ़्रेम (स्क्रीन पर वीडियो) = अभी नहीं
        assertFalse(v.sure())
        v.reset(); repeat(10) { v.add(0.55f) }; assertFalse("औसत कम", v.sure())
    }

    @Test fun alarm_needsSureVote() {
        val a = src("GuardAlarmActivity.kt")
        assertTrue(a.contains("spoof.real() && spoof.sure()"))
        assertTrue(src("GuardFaceSetup.kt").contains("FaceEnroll()"))
    }

    // ---------------------------------------------------------------- फ़ोटो
    @Test fun sharpness_blurIsLower() {
        val w = 64; val h = 64
        val sharp = IntArray(w * h) { i -> if (((i % w) / 4 + (i / w) / 4) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        val flat = IntArray(w * h) { 0xFF808080.toInt() }
        val soft = IntArray(w * h) { i -> val v = (128 + 20 * sin((i % w) / 10.0)).toInt(); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        assertTrue(GuardPhotos.sharpness(sharp, w, h) >= GuardPhotos.SHARP_MIN)
        assertTrue(GuardPhotos.sharpness(flat, w, h) < GuardPhotos.SHARP_MIN)
        assertTrue(GuardPhotos.sharpness(soft, w, h) < GuardPhotos.SHARP_MIN)
        assertEquals(0.0, GuardPhotos.sharpness(IntArray(4), 2, 2), 0.0)
    }

    @Test fun photo_sizeAndName() {
        assertEquals(1200 to 1600, GuardPhotos.fitSide(3000, 4000, 1600))
        assertEquals(720 to 960, GuardPhotos.fitSide(720, 960, 1600))
        assertEquals(1731650000000L, GuardPhotos.timeOf("intruder_1731650000000.jpg"))
        assertEquals(null, GuardPhotos.timeOf("x.jpg"))
        assertTrue(GuardPhotos.QUALITY >= 90)
    }

    @Test fun photos_visibleInApp_andBetterShots() {
        val s = src("SettingsGuard.kt")
        assertTrue("सारी फ़ोटो की पट्टी", s.contains("GuardPhotos.strip(") && s.contains("आख़िरी फ़ोटो देखें"))
        val g = src("GuardPhotos.kt")
        assertTrue(g.contains("fun viewer(") && g.contains("← पिछली") && g.contains("अगली →") && g.contains("मिटाएँ"))
        assertTrue(src("Guard.kt").contains("GuardPhotos.QUALITY"))
        val a = src("GuardAlarmActivity.kt")
        assertTrue(a.contains("capture = photo") && a.contains("c.snap {") && a.contains("GuardPhotos.sharpness("))
        assertTrue(src("FaceCam.kt").contains("CAPTURE_MODE_MAXIMIZE_QUALITY"))
    }

    private fun src(name: String) = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()
}

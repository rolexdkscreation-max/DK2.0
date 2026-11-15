package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * 15 नवंबर 2026 (मालिक): futuristic स्कैन, BOSS AUTHORIZED / DECLINED, फ़ोटो-वीडियो से न खुले (AI anti-spoof + random क्रम),
 * DK लेंस में कई चेहरे + दोस्त/परिवार का रिश्ता.
 */
class FaceScanHoloTest {
    private val front = floatArrayOf(40f, 50f, 60f, 50f, 50f, 61f, 42f, 72f, 58f, 72f)

    private fun det(k: FloatArray): FaceMath.Det {
        val xs = k.filterIndexed { i, _ -> i % 2 == 0 }; val ys = k.filterIndexed { i, _ -> i % 2 == 1 }
        return FaceMath.Det(0.9f, xs.min() - 10f, ys.min() - 15f, xs.max() - xs.min() + 20f, ys.max() - ys.min() + 25f, k)
    }

    private fun turned(deg: Double): FloatArray {
        val t = Math.toRadians(deg)
        val p = listOf(Triple(-10.0, 50.0, 0.0), Triple(10.0, 50.0, 0.0), Triple(0.0, 61.0, 12.0), Triple(-8.0, 72.0, 2.0), Triple(8.0, 72.0, 2.0))
        return p.flatMap { (x, y, z) -> listOf((50 + x * cos(t) + z * sin(t)).toFloat(), y.toFloat()) }.toFloatArray()
    }

    private fun run(l: FaceLiveness, seq: List<FloatArray>): Boolean {
        var t = 1_000L; var live = false
        for (k in seq) { live = l.update(det(k), t); t += 300 }
        return live
    }

    // ---------------------------------------------------------------- random क्रम
    @Test fun challenge_wrongSideFirst_isIgnored() {
        val l = FaceLiveness(); l.challenge(1)          // पहले बाएँ (yaw > 0)
        assertTrue(l.hint().isNotBlank()); assertTrue(l.guide().contains("बाएँ"))
        // गलत क्रम: पहले दाएँ, फिर बाएँ, फिर सामने = पास नहीं (रिकॉर्ड किया वीडियो)
        assertFalse(run(l, listOf(front, turned(-20.0), turned(20.0), front)))
        val ok = FaceLiveness(); ok.challenge(1)
        assertTrue(run(ok, listOf(front, turned(20.0), turned(-20.0), front)))
        val r = FaceLiveness(); r.challenge(-1)
        assertTrue(r.guide().indexOf("दाएँ") < r.guide().indexOf("बाएँ"))
        assertTrue(run(r, listOf(front, turned(-20.0), turned(20.0), front)))
        // पुराना (default) - कोई भी क्रम चलता है
        assertTrue(run(FaceLiveness(), listOf(front, turned(-20.0), turned(20.0), front)))
        assertEquals(FaceLiveness.GUIDE, FaceLiveness().guide())
        assertTrue(FaceLiveness.randomSide() in setOf(-1, 1))
    }

    @Test fun challenge_survivesReset() {
        val l = FaceLiveness(); l.challenge(-1); l.reset()
        assertEquals(-1, l.first)
    }

    // ---------------------------------------------------------------- anti-spoof गणित
    @Test fun spoof_softmaxAndScore() {
        val p = FaceSpoof.softmax(floatArrayOf(0f, 3f, 0f))
        assertEquals(1f, p.sum(), 1e-5f); assertTrue(p[FaceSpoof.REAL] > 0.9f)
        val real = FaceSpoof.realScore(listOf(floatArrayOf(-2f, 4f, -2f), floatArrayOf(-1f, 3f, -1f)))!!
        assertTrue(real > 0.9f)
        val screen = FaceSpoof.realScore(listOf(floatArrayOf(0f, -2f, 5f), floatArrayOf(4f, -2f, 0f)))!!
        assertTrue(screen < 0.1f)
        assertNull(FaceSpoof.realScore(emptyList()))
        assertNull(FaceSpoof.realScore(listOf(floatArrayOf(1f, 2f))))
    }

    @Test fun spoof_voteNeedsSeveralFrames() {
        val v = FaceSpoof.Vote()
        v.add(0.95f); assertFalse(v.real()); assertFalse(v.fake())     // एक फ़्रेम से फ़ैसला नहीं
        v.add(0.9f); v.add(0.85f); assertTrue(v.real())
        v.reset(); repeat(3) { v.add(0.05f) }; assertTrue(v.fake()); assertFalse(v.real())
        // एक फ़्रेम की गलती (फ़ोटो पर 0.9) औसत में दब जाती है
        v.reset(); listOf(0.05f, 0.9f, 0.05f, 0.1f).forEach { v.add(it) }; assertFalse(v.real())
        // पुराने फ़्रेम खिड़की से बाहर
        val w = FaceSpoof.Vote(3); listOf(0f, 0f, 0f, 1f, 1f, 1f).forEach { w.add(it) }; assertTrue(w.real())
    }

    @Test fun spoof_cropBoxAndInput() {
        // Silent-Face _get_new_box: 2.7x, बीच से, फ़्रेम के अंदर
        val b = FaceSpoof.cropBox(640, 480, 300f, 200f, 60f, 80f, 2.7f)
        assertEquals(330 - 81, b[0]); assertEquals(240 - 108, b[1]); assertEquals(330 + 81, b[2]); assertEquals(240 + 108, b[3])
        // किनारे पर: अंदर खिसका
        val e = FaceSpoof.cropBox(640, 480, 0f, 0f, 60f, 80f, 4f)
        assertEquals(0, e[0]); assertEquals(0, e[1]); assertTrue(e[2] <= 639 && e[3] <= 479)
        // बहुत बड़ा चेहरा: पैमाना फ़्रेम जितना
        val g = FaceSpoof.cropBox(100, 100, 10f, 10f, 80f, 80f, 4f)
        assertTrue(g[2] - g[0] <= 99 && g[3] - g[1] <= 99)
        // BGR, 0-255 (÷255 नहीं)
        val px = IntArray(80 * 80) { 0xFF102030.toInt() }
        val f = FaceSpoof.input(px)
        assertEquals(3 * 80 * 80, f.size); assertEquals(0x30.toFloat(), f[0]); assertEquals(0x20.toFloat(), f[6400]); assertEquals(0x10.toFloat(), f[12800])
    }

    @Test fun spoof_models_pinnedAndDownloaded() {
        assertEquals(64, FaceAI.SPOOF_V2.sha256.length); assertEquals(64, FaceAI.SPOOF_V1SE.sha256.length)
        assertTrue(FaceAI.SPOOF_V2.urls.all { it.startsWith("https://") && it.contains("584d4421d7ac42c59e640796f46e886b0095367a") })
        assertTrue(FaceAI.ALL.containsAll(listOf(FaceAI.DETECTOR, FaceAI.RECOGNIZER, FaceAI.SPOOF_V2, FaceAI.SPOOF_V1SE)))
        val g = src("GuardAlarmActivity.kt")
        assertTrue(g.contains("spoof.real()")); assertTrue(g.contains("spoof.fake()")); assertTrue(g.contains("liveness.challenge(FaceLiveness.randomSide())"))
        assertTrue(g.contains("HoloScanView.AUTHORIZED")); assertTrue(g.contains("HoloScanView.DECLINED"))
        assertTrue(src("GuardFaceSetup.kt").contains("FaceAI.ALL.sumOf"))
    }

    // ---------------------------------------------------------------- कई चेहरे (YuNet decode + NMS)
    @Test fun decodeAll_findsSeveralFaces_oneBoxEach() {
        val out = HashMap<Int, Array<FloatArray>>()
        for (s in FaceMath.STRIDES) {
            val n = (FaceMath.DET / s) * (FaceMath.DET / s)
            out[s] = arrayOf(FloatArray(n), FloatArray(n), FloatArray(n * 4), FloatArray(n * 10))
        }
        fun put(s: Int, r: Int, c: Int, sc: Float, wLog: Float) {
            val i = r * (FaceMath.DET / s) + c; val o = out[s]!!
            o[0][i] = sc; o[1][i] = sc; o[2][i * 4 + 2] = wLog; o[2][i * 4 + 3] = wLog
        }
        put(32, 5, 5, 0.95f, 1f)    // चेहरा 1
        put(32, 5, 6, 0.85f, 1f)    // उसी का दूसरा डिब्बा (पास में) -> हटे
        put(32, 15, 15, 0.9f, 1f)   // चेहरा 2
        put(16, 2, 30, 0.8f, 1f)    // चेहरा 3 (छोटा)
        put(8, 70, 70, 0.3f, 1f)    // कमज़ोर -> नहीं
        val all = FaceMath.decodeAll(out, 1f)
        assertEquals(3, all.size)
        assertTrue(all[0].score >= all[1].score && all[1].score >= all[2].score)
        assertEquals(FaceMath.decode(out, 1f)!!.score, all[0].score, 1e-6f)     // पुराना decode वही सबसे अच्छा
        assertEquals(2, FaceMath.decodeAll(out, 1f, max = 2).size)
        val a = FaceMath.Det(1f, 0f, 0f, 10f, 10f, FloatArray(10)); val b = FaceMath.Det(1f, 5f, 0f, 10f, 10f, FloatArray(10))
        assertEquals(50f / 150f, FaceMath.iou(a, b), 1e-5f)
    }

    // ---------------------------------------------------------------- रिश्ता + कई लोग
    @Test fun people_relation_backwardCompatible() {
        val old = "[{\"n\":\"राम\",\"v\":[0,1,0]}]"
        val r = FacePeople.decode(old).single()
        assertEquals("", r.kind); assertEquals("", r.relation())
        val list = listOf(FacePeople.Person("राहुल", floatArrayOf(0f, 1f, 0f), FacePeople.FRIEND),
            FacePeople.Person("सीमा", floatArrayOf(0f, 0f, 1f), FacePeople.FAMILY, "मम्मी"))
        val back = FacePeople.decode(FacePeople.encode(list))
        assertEquals("दोस्त", back[0].relation()); assertEquals("मम्मी", back[1].relation()); assertEquals(FacePeople.FAMILY, back[1].kind)
        assertEquals("आपकी मम्मी", FacePeople.relationPhrase(FacePeople.FAMILY, "मम्मी"))
        assertEquals("आपके भाई", FacePeople.relationPhrase(FacePeople.FAMILY, "भाई"))
        assertEquals("आपके दोस्त", FacePeople.relationPhrase(FacePeople.FRIEND, ""))
        val w = FacePeople.identify(floatArrayOf(0f, 0.02f, 0.99f), floatArrayOf(1f, 0f, 0f), back, 0.45f)
        assertEquals("सीमा", w.name); assertEquals("मम्मी", w.rel)
        assertTrue(w.say("").contains("आपकी मम्मी"))
        assertTrue(FacePeople.FAMILY_RELS.containsAll(listOf("मम्मी", "पापा", "भाई", "बहन")))
    }

    @Test fun people_assign_multiFace_uniqueNames() {
        val boss = floatArrayOf(1f, 0f, 0f)
        val ps = listOf(FacePeople.Person("राहुल", floatArrayOf(0f, 1f, 0f), FacePeople.FRIEND))
        val ws = FacePeople.assign(listOf(floatArrayOf(0.99f, 0.05f, 0f), floatArrayOf(0.05f, 0.99f, 0f), floatArrayOf(0f, 0f, 1f), null), boss, ps, 0.45f)
        assertEquals(4, ws.size)
        assertTrue(ws[0].boss); assertEquals("राहुल", ws[1].name); assertEquals("दोस्त", ws[1].relation())
        assertNull(ws[2].name); assertFalse(ws[2].boss); assertNull(ws[3].name)
        // दो चेहरे एक ही व्यक्ति से मिलें - ज़्यादा मिलने वाले को नाम, दूसरा अनजान
        val two = FacePeople.assign(listOf(floatArrayOf(0.1f, 0.9f, 0f), floatArrayOf(0.02f, 0.99f, 0f)), boss, ps, 0.45f)
        assertNull(two[0].name); assertEquals("राहुल", two[1].name)
    }

    @Test fun lens_rules_labelsAndNote() {
        val boss = LensFaces.Rules.Seen(FacePeople.Who(null, true, 0.8f, true), fake = false, real = true)
        val friend = LensFaces.Rules.Seen(FacePeople.Who("राहुल", false, 0.7f, true, FacePeople.FRIEND), fake = false, real = true)
        val unknown = LensFaces.Rules.Seen(FacePeople.Who(null, false, 0.1f, false), fake = false, real = true)
        assertTrue(LensFaces.Rules.title(boss, "देव").startsWith("BOSS"))
        assertTrue(LensFaces.Rules.sub(friend).contains("दोस्त"))
        assertEquals("UNKNOWN", LensFaces.Rules.title(unknown, ""))
        assertEquals(HoloScanView.OK, LensFaces.Rules.tone(boss)); assertEquals(HoloScanView.WARN, LensFaces.Rules.tone(unknown))
        val n = LensFaces.Rules.note(listOf(boss, friend, unknown), "देव")!!
        assertTrue(n.startsWith("SYSTEM NOTE")); assertTrue(n.contains("Boss")); assertTrue(n.contains("राहुल (आपके दोस्त)")); assertTrue(n.contains("1 unknown"))
        assertNull(LensFaces.Rules.note(listOf(unknown), ""))
        val fake = LensFaces.Rules.Seen(FacePeople.Who(null, true, 0.8f, true), fake = true, real = false)
        assertTrue(LensFaces.Rules.note(listOf(fake), "")!!.contains("PHOTO/SCREEN"))
        assertEquals(HoloScanView.BAD, LensFaces.Rules.tone(fake))
        // क्रम बदलने से नोट दोबारा नहीं
        assertEquals(LensFaces.Rules.noteKey(listOf(boss, friend)), LensFaces.Rules.noteKey(listOf(friend, boss)))
        val v = src("VoiceActivity.kt")
        assertTrue(v.contains("LensFaces(") && v.contains("HoloScanView(") && v.contains("feed2.onFace"))
        assertTrue(src("LiveVision.kt").contains("fun facesNote("))
    }

    // ---------------------------------------------------------------- 3D जाल
    @Test fun holoMesh_fitsLandmarks() {
        val src = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
        val dst = FloatArray(8) { i -> if (i % 2 == 0) 2 * src[i] + 5 else 3 * src[i] - 1 }
        val m = HoloMesh.affine(src, dst)!!
        assertEquals(2f, m[0], 1e-4f); assertEquals(5f, m[2], 1e-4f); assertEquals(3f, m[4], 1e-4f); assertEquals(-1f, m[5], 1e-4f)
        val k = floatArrayOf(140f, 150f, 200f, 150f, 170f, 185f, 147f, 215f, 193f, 215f)
        val fit = HoloMesh.fit(k); assertNotNull(fit)
        val lines = HoloMesh.lines(k)
        assertTrue(lines.size > 400 && lines.size % 4 == 0)
        // जाल चेहरे के आस-पास ही
        val xs = lines.filterIndexed { i, _ -> i % 2 == 0 }
        assertTrue(xs.average() in 140.0..200.0)
        assertEquals(0, HoloMesh.lines(FloatArray(4)).size)
    }

    @Test fun viewMap_fillCenter() {
        val m = FaceMath.viewMap(480, 640, 240, 240)
        assertEquals(0.5f, m[0], 1e-5f); assertEquals(0f, m[1], 1e-5f); assertEquals(-40f, m[2], 1e-5f)
    }

    private fun src(name: String) = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()
}

package com.dk.assistant

import com.dk.assistant.dkai.DkaiCore
import com.dk.assistant.stt.CtcText
import com.dk.assistant.stt.IndicMel
import com.dk.assistant.stt.SherpaStt
import com.dk.assistant.stt.SpeechSegmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * 21 नवंबर 2026 (मालिक: "Sherpa-ONNX हटाकर दूसरा open-source मॉडल - हिंदी + English सही लिखे, ऐप का MB घटे,
 * Live जुड़ने के बाद LISTENING लिखे"): IndicConformer का pure हिस्सा + Live बैज.
 */
class IndicSttTest {
    private fun root(): File = listOf(File("src/main/java/com/dk/assistant"), File("app/src/main/java/com/dk/assistant")).first { it.isDirectory }
    private fun src(p: String) = File(root(), p).readText()

    /** वही आवाज़ जो Python (numpy) वाली नकल को दी थी - वहाँ से निकले मान (उस नकल का नतीजा sherpa-onnx जैसा था) */
    private fun probe(): FloatArray = FloatArray(8000) { n ->
        (0.3 * sin(2 * PI * 440 * n / 16000) + 0.1 * sin(2 * PI * 1234.5 * n / 16000 + 0.5) * (n / 8000.0)).toFloat()
    }

    @Test fun mel_matchesPythonReference() {
        val (f, t) = IndicMel.features(probe())
        assertEquals(51, t)
        assertEquals(IndicMel.MELS * 51, f.size)
        val golden = listOf(
            Triple(0, 0, 4.935765266418457), Triple(5, 10, -0.29298627376556396), Triple(20, 25, -0.19983424246311188),
            Triple(40, 3, -0.28755974769592285), Triple(79, 50, 4.756279945373535), Triple(60, 30, -0.25445815920829773),
        )
        for ((m, x, v) in golden) assertEquals("mel $m frame $x", v, f[m * t + x].toDouble(), 2e-3)
        // हर mel की औसत ~0 (per_feature)
        for (m in 0 until IndicMel.MELS) assertEquals(0.0, (0 until t).sumOf { f[m * t + it].toDouble() } / t, 1e-3)
    }

    @Test fun melBank_slaney() {
        val b = IndicMel.bank
        assertEquals(80, b.size); assertEquals(257, b[0].size)
        assertEquals(0.03305284127500556, b[10].sum(), 1e-6)
        assertEquals(0.022534560750290804, b[0][1], 1e-6)
        assertEquals(0.0032521483035324464, b[79].max(), 1e-7)
        assertEquals(55, b[40].indices.maxByOrNull { b[40][it] })
    }

    @Test fun fft_knownTone() {
        val n = 512
        val re = DoubleArray(n) { kotlin.math.cos(2 * PI * 8 * it / n) }; val im = DoubleArray(n)
        IndicMel.fft(re, im)
        assertEquals(n / 2.0, re[8], 1e-6); assertEquals(n / 2.0, re[n - 8], 1e-6)
        assertTrue(abs(re[3]) < 1e-6 && abs(im[8]) < 1e-6)
    }

    @Test fun frames_likeNemo() {
        assertEquals(1, IndicMel.frames(0)); assertEquals(101, IndicMel.frames(16_000)); assertEquals(51, IndicMel.frames(8_000))
    }

    @Test fun ctc_greedyCollapsesRepeatsAndBlank() {
        val toks = listOf("<unk>", "▁मम्मी", "▁को", "▁कॉल", "<blk>")
        val v = toks.size
        // frames: मम्मी मम्मी blank को को कॉल blank कॉल
        val best = intArrayOf(1, 1, 4, 2, 2, 3, 4, 3)
        val lp = FloatArray(best.size * v) { -10f }
        best.forEachIndexed { f, k -> lp[f * v + k] = 0f }
        assertEquals("मम्मी को कॉल कॉल", CtcText.decode(lp, best.size, v, toks))
        // सिर्फ़ blank (चुप्पी) = कुछ नहीं
        val sil = FloatArray(3 * v) { if (it % v == v - 1) 0f else -5f }
        assertEquals("", CtcText.decode(sil, 3, v, toks))
        // <unk> नहीं लिखते
        val unk = FloatArray(v) { if (it == 0) 0f else -5f }
        assertEquals("", CtcText.decode(unk, 1, v, toks))
    }

    @Test fun tokens_parsedById() {
        val t = CtcText.parseTokens(listOf("<unk> 0", "▁क 1", "य़ 2", "", "<blk> 3"))
        assertEquals(listOf("<unk>", "▁क", "य़", "<blk>"), t)
    }

    @Test fun segmenter_endpointAfterSilence() {
        val s = SpeechSegmenter()
        repeat(10) { s.push(100, voiced = false) }
        assertFalse(s.hasSpeech); assertFalse(s.endpoint)
        assertTrue(s.trimIdle()); assertEquals(SpeechSegmenter.PREROLL_MS, s.segMs)
        s.push(100, true); assertFalse(s.hasSpeech)          // 100 ms = अभी बोली नहीं मानी
        repeat(7) { s.push(100, true) }
        assertTrue(s.hasSpeech); assertTrue(s.wantPartial())
        s.decoded(100); assertFalse(s.wantPartial())
        repeat(7) { s.push(100, false) }; assertFalse(s.endpoint)
        s.push(100, false); assertTrue(s.endpoint)
        assertFalse(s.wantPartial())
        s.reset(); assertFalse(s.endpoint); assertFalse(s.hasSpeech)
    }

    @Test fun segmenter_longSpeechForcedEndpoint_andSlowPhoneWaitsLonger() {
        val s = SpeechSegmenter()
        repeat(SpeechSegmenter.MAX_SEG_MS / 100 - 1) { s.push(100, true) }
        assertFalse(s.endpoint)
        s.push(100, true); assertTrue(s.endpoint)
        val slow = SpeechSegmenter()
        repeat(10) { slow.push(100, true) }
        slow.decoded(900)                                   // धीमा फ़ोन: पिछली पढ़ाई 0.9 s
        repeat(12) { slow.push(100, true) }; assertFalse(slow.wantPartial())
        repeat(6) { slow.push(100, true) }; assertTrue(slow.wantPartial())
    }

    @Test fun rmsFallback() {
        assertFalse(SherpaStt.rmsVoiced(FloatArray(1600)))
        assertTrue(SherpaStt.rmsVoiced(FloatArray(1600) { if (it % 2 == 0) 0.1f else -0.1f }))
        assertFalse(SherpaStt.rmsVoiced(FloatArray(0)))
    }

    @Test fun engine_noSherpaLibrary_usesAppOnnxRuntime() {
        val e = src("stt/SherpaStt.kt")
        assertFalse(e.contains("com.k2fsa")); assertTrue(e.contains("ai.onnxruntime.OrtSession"))
        assertTrue(e.contains("\"audio_signal\"")); assertTrue(e.contains("IndicMel.features"))
        assertEquals("IndicConformer", SherpaStt.NAME)
        for (f in root().walkTopDown().filter { it.extension == "kt" }) assertFalse(f.path, f.readText().contains("import com.k2fsa"))
    }

    @Test fun liveBadge_listeningWhenDkReallyListens() {
        // Gemini Live जुड़ा
        assertTrue(DkaiCore.voiceUp(live = true, liveReady = true, splitTalking = false, backup = false))
        // अलग तरीका / बैकअप: Gemini Live नहीं जुड़ता, फिर भी सुन रहा है
        assertTrue(DkaiCore.voiceUp(true, liveReady = false, splitTalking = true, backup = false))
        assertTrue(DkaiCore.voiceUp(true, liveReady = false, splitTalking = false, backup = true))
        assertFalse(DkaiCore.voiceUp(true, false, false, false))
        assertFalse(DkaiCore.voiceUp(live = false, liveReady = true, splitTalking = true, backup = true))
        val m = Mode.LISTENING
        assertEquals(DkaiCore.Status.LISTENING, DkaiCore.voiceStatus(m, live = true, muted = false, work = false, connected = DkaiCore.voiceUp(true, false, true, false)))
        assertEquals(DkaiCore.Status.CONNECTING, DkaiCore.voiceStatus(m, true, false, false, connected = DkaiCore.voiceUp(true, false, false, false)))
        // जुड़ते / टूटते ही स्क्रीन को ख़बर, बैज अलग तरीके / बैकअप को भी गिनता है
        val g = src("GeminiLive.kt")
        assertTrue(g.contains("set(v) { if (field != v) { field = v; DKState.linkChanged() } }"))
        assertTrue(src("dkai/DkaiVoice.kt").contains("voiceUp()"))
        val s = src("DKAccessibilityService.kt")
        assertTrue(s.contains("splitTalking = true; DKState.linkChanged()"))
        assertTrue(s.contains("finally { splitTalking = false; DKState.linkChanged() }"))
    }
}

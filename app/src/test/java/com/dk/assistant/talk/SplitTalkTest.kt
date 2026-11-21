package com.dk.assistant.talk

import com.dk.assistant.plus.models.ModelCatalog
import com.dk.assistant.plus.models.ModelManager
import com.dk.assistant.stt.SherpaStt
import com.dk.assistant.stt.SttRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 18 नवंबर 2026: "सुनना अलग, सोचना-बोलना अलग" - VAD, पहचान का चुनाव, मॉडल, डाउनलोड रोकना (21 नवंबर: Whisper की जगह Sherpa-ONNX) */
class SplitTalkTest {

    private fun Endpointer.run(vararg parts: Pair<Float, Int>): List<Endpointer.Ev> =
        parts.flatMap { (s, n) -> List(n) { feed(s) } }.filter { it != Endpointer.Ev.NONE }

    @Test fun endpointer_endsAfterConfiguredSilence() {
        val e = Endpointer(silenceMs = 760)
        // 1 s बोली, फिर 18 टुकड़े (720 ms) चुप्पी = अभी खत्म नहीं
        assertEquals(listOf(Endpointer.Ev.START), e.run(0.9f to 25, 0.05f to 18))
        // 19वाँ टुकड़ा (760 ms) = खत्म
        assertEquals(Endpointer.Ev.END, e.feed(0.05f))
        assertFalse(e.started)
    }

    @Test fun endpointer_shortPausesInsideSpeechDoNotEnd() {
        val e = Endpointer(silenceMs = 750)
        val evs = e.run(0.9f to 10, 0.1f to 10, 0.9f to 10, 0.1f to 12, 0.9f to 10, 0.1f to 19)
        assertEquals(listOf(Endpointer.Ev.START, Endpointer.Ev.END), evs)
    }

    @Test fun endpointer_clickIsNotSpeech() {
        val e = Endpointer()
        assertTrue(e.run(0.95f to 3, 0f to 40).isEmpty())          // 120 ms खटका = शुरू ही नहीं
    }

    @Test fun endpointer_tooShortIsDiscarded() {
        val e = Endpointer(silenceMs = 400)
        // 200 ms पर शुरू, फिर तुरंत चुप्पी - कुल बोली 200 ms < 300 ms
        assertEquals(listOf(Endpointer.Ev.START, Endpointer.Ev.DISCARD), e.run(0.9f to 5, 0f to 10))
    }

    @Test fun endpointer_strictWhileDkSpeaks() {
        val e = Endpointer().apply { strict = true }
        assertTrue(e.run(0.6f to 30).isEmpty())                     // गूँज जैसी आधी आवाज़ = टोकना नहीं
        assertEquals(listOf(Endpointer.Ev.START), e.run(0.9f to 8))  // 320 ms पक्की बोली = टोकना
    }

    @Test fun endpointer_maxLength() {
        val e = Endpointer(maxMs = 2_000)
        assertEquals(listOf(Endpointer.Ev.START, Endpointer.Ev.END), e.run(0.9f to 52))
    }

    @Test fun rmsFallback() {
        assertEquals(1f, Endpointer.rmsScore(2000.0, 200.0))
        assertEquals(0f, Endpointer.rmsScore(300.0, 200.0))
        assertEquals(0.4f, Endpointer.rmsScore(700.0, 200.0), 0.001f)
        assertEquals(0f, Endpointer.rmsScore(1000.0, 800.0), 0.001f)       // शोर वाले कमरे में वही आवाज़ = बोली नहीं
        assertEquals(100.0, Endpointer.nextNoise(100.0, 3000.0, speech = true), 0.0)
        assertTrue(Endpointer.nextNoise(100.0, 300.0, speech = false) > 100.0)
    }

    @Test fun silencePref_clamped() {
        assertEquals(400L, TalkPrefs.clampSilence(10))
        assertEquals(1500L, TalkPrefs.clampSilence(9_000))
        assertEquals(750L, TalkPrefs.clampSilence(770))
        assertEquals(750L, TalkPrefs.SILENCE_DEF)
    }

    @Test fun router_sherpaUntilModel_thenAndroidFallback() {
        val r = SttRouter()
        val noModel = r.pick(prefSherpa = true, sherpaReady = false, whyNot = "Sherpa-ONNX मॉडल अभी डाउनलोड नहीं हुआ", androidOk = true)
        assertEquals(SttRouter.Engine.ANDROID, noModel.engine)
        assertTrue(noModel.notice.contains("Android"))
        assertEquals(SttRouter.Engine.SHERPA, r.pick(true, true, "", true).engine)
        assertEquals(SttRouter.Engine.ANDROID, r.pick(false, true, "", true).engine)
        assertEquals("", r.pick(false, true, "", true).notice)
    }

    @Test fun router_twoSlowStrikes_switchToAndroidForSession() {
        val r = SttRouter()
        assertFalse(r.sherpaResult(ok = false, slow = true))
        assertTrue(r.sherpaResult(ok = false, slow = true))
        assertEquals(SttRouter.Engine.ANDROID, r.pick(true, true, "", true).engine)
        // Android न हो = Sherpa-ONNX ही (कुछ तो चले)
        assertEquals(SttRouter.Engine.SHERPA, r.pick(true, true, "", false).engine)
        r.reset()
        assertEquals(SttRouter.Engine.SHERPA, r.pick(true, true, "", true).engine)
    }

    @Test fun router_successResetsStrikes() {
        val r = SttRouter()
        r.sherpaResult(ok = false, slow = false)
        r.sherpaResult(ok = true, slow = false)
        assertFalse(r.sherpaResult(ok = false, slow = false))
        assertFalse(r.sherpaOff)
    }

    @Test fun router_none_hasHindiReason() {
        val p = SttRouter().pick(prefSherpa = false, sherpaReady = false, whyNot = "", androidOk = false)
        assertEquals(SttRouter.Engine.NONE, p.engine)
        assertTrue(p.notice.contains(SttRouter.NAME))
    }

    @Test fun router_timeouts() {
        assertEquals(6_000L, SttRouter.timeoutMs(1_000))
        assertEquals(15_000L, SttRouter.timeoutMs(20_000))
        assertTrue(SttRouter.slow(2_000, 5_000))
        assertFalse(SttRouter.slow(3_000, 2_500))
    }

    @Test fun sttModel_pinnedHuggingFace_indicConformer() {
        // 21 नवंबर 2026: betterflow (Sherpa-ONNX) की जगह AI4Bharat IndicConformer हिंदी int8 - ~134 MB, सिर्फ़ Wi-Fi
        val e = ModelCatalog.STT_ENCODER; val t = ModelCatalog.STT_TOKENS
        assertTrue(e in ModelCatalog.ALL && t in ModelCatalog.ALL)
        assertEquals(listOf(e, t), ModelCatalog.STT_SHERPA)
        for (a in ModelCatalog.STT_SHERPA) { assertEquals(ModelCatalog.Kind.STT, a.kind); assertTrue(ModelCatalog.wifiOnly(a)) }
        val base = "https://huggingface.co/ngiftsun/indicconformer-hi-int8-sherpa/resolve/7c3806fe01eb9b50784f09fe22cfeabf642c218e/"
        assertEquals(listOf(base + "model.int8.onnx"), e.urls)
        assertEquals("fc0c7db7c0e0c11d4ed540dc2cddca03a42fcba3568a0e0607c3631d87fa9f8a", e.sha256)
        assertEquals(140_451_556L, e.bytes)
        assertEquals(listOf(base + "tokens.txt"), t.urls)
        assertEquals("ee60967630213f31951817ac8b402b92ec18cce80718a24a49b388e56672dfb2", t.sha256)
        assertEquals(67_605L, t.bytes)
        assertTrue(ModelCatalog.STT_SHERPA.sumOf { it.bytes } < 150L * 1024 * 1024)
        // पुराने मॉडल की फ़ाइलें फ़ोन से मिटें
        assertTrue(ModelCatalog.REMOVED_FILES.containsAll(ModelCatalog.OLD_STT_FILES))
        assertEquals(emptyList<String>(), ModelCatalog.problems())
    }

    @Test fun download_pausedStateFromPartFile() {
        assertEquals(ModelManager.St.Ready, ModelManager.restState(true, 0, 100))
        assertEquals(ModelManager.St.Paused(40, 100), ModelManager.restState(false, 40, 100))
        assertEquals(40, (ModelManager.restState(false, 40, 100) as ModelManager.St.Paused).pct)
        assertEquals(ModelManager.St.None, ModelManager.restState(false, 0, 100))
        assertEquals(ModelManager.St.None, ModelManager.restState(false, 100, 100))   // पूरी पर जाँच नहीं हुई = फिर से
    }

    @Test fun sherpa_threads() {
        assertEquals(2, SherpaStt.threads(8))
        assertEquals(1, SherpaStt.threads(4))
        assertEquals(1, SherpaStt.threads(0))
    }

    @Test fun sherpa_pcmToFloat() {
        val f = SherpaStt.toFloat(byteArrayOf(9, 9, 0, 0, 0xff.toByte(), 0x7f, 0, 0x80.toByte()), 2, 6)
        assertEquals(3, f.size)
        assertEquals(0f, f[0]); assertEquals(32767 / 32768f, f[1]); assertEquals(-1f, f[2])
    }

    @Test fun sherpa_cleanText() {
        assertEquals("व्हाट्सऐप खोलो", SherpaStt.clean("  व्हाट्सऐप   खोलo "))
        assertEquals("मुझे को", SherpaStt.clean("मुझे कo"))
        assertEquals("open youtube", SherpaStt.clean("open youtube"))      // अंग्रेज़ी शब्द वैसे ही
        assertEquals("", SherpaStt.clean("   "))
    }

    @Test fun sherpa_cpuCheck() {
        assertTrue(SherpaStt.abiOk(listOf("arm64-v8a", "armeabi-v7a")))
        assertFalse(SherpaStt.abiOk(listOf("armeabi-v7a")))
        assertFalse(SherpaStt.abiOk(listOf("x86_64")))
        assertFalse(SherpaStt.abiOk(emptyList()))
        assertTrue(SherpaStt.libError(UnsatisfiedLinkError("x")))
        assertTrue(SherpaStt.libError(NoClassDefFoundError("x")))
        assertFalse(SherpaStt.libError(IllegalArgumentException("x")))
    }
}

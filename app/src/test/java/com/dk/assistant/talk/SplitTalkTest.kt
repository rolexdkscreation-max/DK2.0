package com.dk.assistant.talk

import com.dk.assistant.plus.models.ModelCatalog
import com.dk.assistant.plus.models.ModelManager
import com.dk.assistant.stt.SttRouter
import com.dk.assistant.stt.WhisperNative
import com.dk.assistant.stt.WhisperStt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 18 नवंबर 2026: "सुनना अलग, सोचना-बोलना अलग" - VAD, पहचान का चुनाव, Whisper मॉडल, डाउनलोड रोकना */
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

    @Test fun router_whisperUntilModel_thenAndroidFallback() {
        val r = SttRouter()
        val noModel = r.pick(prefWhisper = true, whisperReady = false, whyNot = "Whisper मॉडल अभी डाउनलोड नहीं हुआ", androidOk = true)
        assertEquals(SttRouter.Engine.ANDROID, noModel.engine)
        assertTrue(noModel.notice.contains("Android"))
        assertEquals(SttRouter.Engine.WHISPER, r.pick(true, true, "", true).engine)
        assertEquals(SttRouter.Engine.ANDROID, r.pick(false, true, "", true).engine)
        assertEquals("", r.pick(false, true, "", true).notice)
    }

    @Test fun router_twoSlowStrikes_switchToAndroidForSession() {
        val r = SttRouter()
        assertFalse(r.whisperResult(ok = false, slow = true))
        assertTrue(r.whisperResult(ok = false, slow = true))
        assertEquals(SttRouter.Engine.ANDROID, r.pick(true, true, "", true).engine)
        // Android न हो = Whisper ही (कुछ तो चले)
        assertEquals(SttRouter.Engine.WHISPER, r.pick(true, true, "", false).engine)
        r.reset()
        assertEquals(SttRouter.Engine.WHISPER, r.pick(true, true, "", true).engine)
    }

    @Test fun router_successResetsStrikes() {
        val r = SttRouter()
        r.whisperResult(ok = false, slow = false)
        r.whisperResult(ok = true, slow = false)
        assertFalse(r.whisperResult(ok = false, slow = false))
        assertFalse(r.whisperOff)
    }

    @Test fun router_none_hasHindiReason() {
        val p = SttRouter().pick(prefWhisper = false, whisperReady = false, whyNot = "", androidOk = false)
        assertEquals(SttRouter.Engine.NONE, p.engine)
        assertTrue(p.notice.contains("Whisper"))
    }

    @Test fun router_timeouts() {
        assertEquals(6_000L, SttRouter.timeoutMs(1_000))
        assertEquals(15_000L, SttRouter.timeoutMs(20_000))
        assertTrue(SttRouter.slow(2_000, 5_000))
        assertFalse(SttRouter.slow(3_000, 2_500))
    }

    @Test fun whisperModel_officialHuggingFace_pinned() {
        val a = ModelCatalog.WHISPER_SMALL
        assertTrue(a in ModelCatalog.ALL)
        assertEquals(ModelCatalog.Kind.STT, a.kind)
        assertEquals(listOf("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin"), a.urls)
        assertEquals("ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb", a.sha256)
        assertEquals(190_085_487L, a.bytes)
        assertEquals(emptyList<String>(), ModelCatalog.problems())
    }

    @Test fun download_pausedStateFromPartFile() {
        assertEquals(ModelManager.St.Ready, ModelManager.restState(true, 0, 100))
        assertEquals(ModelManager.St.Paused(40, 100), ModelManager.restState(false, 40, 100))
        assertEquals(40, (ModelManager.restState(false, 40, 100) as ModelManager.St.Paused).pct)
        assertEquals(ModelManager.St.None, ModelManager.restState(false, 0, 100))
        assertEquals(ModelManager.St.None, ModelManager.restState(false, 100, 100))   // पूरी पर जाँच नहीं हुई = फिर से
    }

    @Test fun whisper_audioWindow() {
        assertEquals(512, WhisperStt.audioCtx(1_000))
        assertEquals(1500, WhisperStt.audioCtx(40_000))
        val mid = WhisperStt.audioCtx(10_000)
        assertTrue(mid in 600..640 && mid % 64 == 0)
    }

    @Test fun whisper_threads() {
        assertEquals(4, WhisperStt.threads(8))
        assertEquals(3, WhisperStt.threads(4))
        assertEquals(1, WhisperStt.threads(0))
    }

    @Test fun whisper_pcmToFloat() {
        val f = WhisperStt.toFloat(byteArrayOf(0, 0, 0xff.toByte(), 0x7f, 0, 0x80.toByte()))
        assertEquals(0f, f[0]); assertEquals(32767 / 32768f, f[1]); assertEquals(-1f, f[2])
    }

    @Test fun whisper_cleanText() {
        assertEquals("व्हाट्सऐप खोलो", WhisperStt.clean("  [संगीत] व्हाट्सऐप   खोलो "))
        assertEquals("", WhisperStt.clean("(music)"))
        assertEquals("", WhisperStt.clean("सब्सक्राइब करें"))
        assertEquals("धन्यवाद", WhisperStt.clean("धन्यवाद"))          // मालिक सच में बोल सकता है
        assertEquals("", WhisperStt.clean(WhisperStt.PROMPT))
    }

    @Test fun whisper_cpuCheck() {
        val good = "processor\t: 0\nFeatures\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp\n"
        assertTrue(WhisperNative.cpuOk(good, listOf("arm64-v8a", "armeabi-v7a")))
        assertFalse(WhisperNative.cpuOk(good, listOf("armeabi-v7a")))
        assertFalse(WhisperNative.cpuOk("Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32\n", listOf("arm64-v8a")))
        assertFalse(WhisperNative.cpuOk("", listOf("arm64-v8a")))
    }
}

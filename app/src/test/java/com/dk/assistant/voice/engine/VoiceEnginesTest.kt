package com.dk.assistant.voice.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.68: एक समय में एक इंजन; जोड़ (Joint); PCM सहायक */
class VoiceEnginesTest {
    private fun src(name: String): String = listOf(java.io.File("src/main/java/com/dk/assistant/$name"), java.io.File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()
    private val all = setOf(VoiceEngines.GEMINI_TTS, VoiceEngines.EDGE_TTS, VoiceEngines.LIVE_NATIVE)

    @Test fun switchingOnOneTurnsOthersOff() {
        assertEquals(VoiceEngines.EDGE_TTS, VoiceEngines.toggle(VoiceEngines.GEMINI_TTS, VoiceEngines.EDGE_TTS, true, available = all))
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceEngines.toggle(VoiceEngines.EDGE_TTS, VoiceEngines.LIVE_NATIVE, true, available = all))
    }

    @Test fun turningOffActiveFallsBackToDkLive() {
        // v3.5+: डिफ़ॉल्ट DK लाइव (Gemini Live सीधी आवाज़), दूसरे नंबर पर Gemini TTS
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceEngines.toggle(VoiceEngines.EDGE_TTS, VoiceEngines.EDGE_TTS, false, available = all))
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceEngines.toggle(VoiceEngines.GEMINI_TTS, VoiceEngines.GEMINI_TTS, false, available = all))
        // डिफ़ॉल्ट (DK लाइव) को बंद करो = दूसरा नंबर Gemini TTS (कोई इंजन न हो ऐसा नहीं)
        assertEquals(VoiceEngines.GEMINI_TTS, VoiceEngines.toggle(VoiceEngines.LIVE_NATIVE, VoiceEngines.LIVE_NATIVE, false, available = all))
        // किसी बंद वाले को बंद = कुछ नहीं बदलता
        assertEquals(VoiceEngines.EDGE_TTS, VoiceEngines.toggle(VoiceEngines.EDGE_TTS, VoiceEngines.LIVE_NATIVE, false, available = all))
        // सिर्फ़ एक इंजन बचा = वही रहता है
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceEngines.toggle(VoiceEngines.LIVE_NATIVE, VoiceEngines.LIVE_NATIVE, false, available = setOf(VoiceEngines.LIVE_NATIVE)))
    }

    @Test fun oldGeminiTtsDefaultMovesToDkLiveOnce() {
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceEngines.migrated(VoiceEngines.GEMINI_TTS, done = false))
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceEngines.migrated(null, done = false))
        // Edge खुद चुना था = वैसा ही
        assertEquals(VoiceEngines.EDGE_TTS, VoiceEngines.migrated(VoiceEngines.EDGE_TTS, done = false))
        // एक बार हो गया = बाद में यूज़र ने Gemini TTS चुना तो वही रहे
        assertEquals(VoiceEngines.GEMINI_TTS, VoiceEngines.migrated(VoiceEngines.GEMINI_TTS, done = true))
    }

    @Test fun dkLiveIsTheDefaultEngine() {
        val native = src("voice/engine/LiveNativeEngine.kt"); val tts = src("voice/engine/GeminiTtsEngine.kt")
        assertTrue(native.contains("override val defaultOn = true"))
        assertTrue(tts.contains("override val defaultOn = false"))
        assertTrue(native.contains("override val title = \"DK लाइव"))
        // स्क्रीन पर क्रम: DK लाइव पहले, Gemini TTS दूसरे
        val list = src("voice/engine/VoiceEngines.kt")
        assertTrue(list.indexOf("engine.LiveNativeEngine\"") in 0 until list.indexOf("engine.GeminiTtsEngine\""))
    }

    @Test fun removedEngineCannotBeChosen() {
        val noEdge = all - VoiceEngines.EDGE_TTS
        assertEquals(VoiceEngines.GEMINI_TTS, VoiceEngines.toggle(VoiceEngines.GEMINI_TTS, VoiceEngines.EDGE_TTS, true, available = noEdge))
    }

    @Test fun failureMemoryExpires() {
        VoiceEngines.markFailed("x", "टूटा")
        assertTrue(VoiceEngines.isFailed("x"))
        assertTrue(!VoiceEngines.isFailed("x", System.currentTimeMillis() + VoiceEngines.FAIL_MS + 1))
        VoiceEngines.clearFailed("x"); assertNull(VoiceEngines.lastError("x"))
    }

    private fun tone(ms: Int, amp: Int): ByteArray {
        val n = 24 * ms; val b = ByteArray(n * 2)
        for (i in 0 until n) { val v = if (amp == 0) 0 else (amp * kotlin.math.sin(i * 0.2)).toInt(); b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte() }
        return b
    }

    @Test fun jointTrimsLeadingSilenceAndAddsFixedTail() {
        val j = Joint()
        val out = j.push(tone(200, 0)) + j.push(tone(100, 8000)) + j.push(tone(300, 0))
        val tail = j.finish(SpeechPipeline.pauseMs("नमस्ते।"))
        val total = (out.sumOf { it.size } + tail.sumOf { it.size }) / 48
        // 15 ms पहले + 100 ms आवाज़ + 40 ms + 140 ms ठहराव (क़रीब)
        assertTrue("total=$total", total in 250..310)
    }

    @Test fun pauseByPunctuation() {
        assertEquals(140, SpeechPipeline.pauseMs("ठीक है।"))
        assertEquals(60, SpeechPipeline.pauseMs("सुनो,"))
        assertEquals(20, SpeechPipeline.pauseMs("और"))
    }

    @Test fun pcmEvenKeepsSampleAlignment() {
        val e = PcmEven()
        val a = e.take(byteArrayOf(1, 2, 3))!!
        val b = e.take(byteArrayOf(4, 5, 6, 7))
        assertEquals(2, a.size); assertEquals(listOf<Byte>(3, 4, 5, 6), b!!.toList())
    }

    @Test fun resampleTo24k() {
        val in16 = ByteArray(16000 * 2)
        assertEquals(24000 * 2, Resample.to24k(in16, 16000).size)
    }
}

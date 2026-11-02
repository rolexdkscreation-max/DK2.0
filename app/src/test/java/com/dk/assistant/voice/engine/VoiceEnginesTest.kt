package com.dk.assistant.voice.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.68: एक समय में एक इंजन; जोड़ (Joint); PCM सहायक */
class VoiceEnginesTest {
    private fun src(name: String): String = listOf(java.io.File("src/main/java/com/dk/assistant/$name"), java.io.File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()
    private val all = setOf(VoiceEngines.GEMINI_TTS, VoiceEngines.KOKORO_TTS, VoiceEngines.LIVE_NATIVE)

    @Test fun switchingOnOneTurnsOthersOff() {
        assertEquals(VoiceEngines.KOKORO_TTS, VoiceEngines.toggle(VoiceEngines.GEMINI_TTS, VoiceEngines.KOKORO_TTS, true, available = all))
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceEngines.toggle(VoiceEngines.KOKORO_TTS, VoiceEngines.LIVE_NATIVE, true, available = all))
    }

    @Test fun turningOffActiveFallsBackToDkLive() {
        // v3.5+: डिफ़ॉल्ट DK लाइव (Gemini Live सीधी आवाज़), दूसरे नंबर पर Gemini TTS
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceEngines.toggle(VoiceEngines.KOKORO_TTS, VoiceEngines.KOKORO_TTS, false, available = all))
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceEngines.toggle(VoiceEngines.GEMINI_TTS, VoiceEngines.GEMINI_TTS, false, available = all))
        // डिफ़ॉल्ट (DK लाइव) को बंद करो = दूसरा नंबर Gemini TTS (कोई इंजन न हो ऐसा नहीं)
        assertEquals(VoiceEngines.GEMINI_TTS, VoiceEngines.toggle(VoiceEngines.LIVE_NATIVE, VoiceEngines.LIVE_NATIVE, false, available = all))
        // किसी बंद वाले को बंद = कुछ नहीं बदलता
        assertEquals(VoiceEngines.KOKORO_TTS, VoiceEngines.toggle(VoiceEngines.KOKORO_TTS, VoiceEngines.LIVE_NATIVE, false, available = all))
        // सिर्फ़ एक इंजन बचा = वही रहता है
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceEngines.toggle(VoiceEngines.LIVE_NATIVE, VoiceEngines.LIVE_NATIVE, false, available = setOf(VoiceEngines.LIVE_NATIVE)))
    }

    @Test fun oldGeminiTtsDefaultMovesToDkLiveOnce() {
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceEngines.migrated(VoiceEngines.GEMINI_TTS, done = false))
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceEngines.migrated(null, done = false))
        // कोई और इंजन खुद चुना था (जैसे पुराना Edge) = यह migration उसे नहीं छूता (Kokoro वाला अलग है, नीचे)
        assertEquals(VoiceEngines.OLD_EDGE_ID, VoiceEngines.migrated(VoiceEngines.OLD_EDGE_ID, done = false))
        // एक बार हो गया = बाद में यूज़र ने Gemini TTS चुना तो वही रहे
        assertEquals(VoiceEngines.GEMINI_TTS, VoiceEngines.migrated(VoiceEngines.GEMINI_TTS, done = true))
    }

    /** नवंबर 2026 (मालिक): पहले "DK लाइव डिफ़ॉल्ट" था - अब Kokoro डिफ़ॉल्ट (पहले Piper), DK लाइव = Direct Gemini Live Voice (बंद) */
    @Test fun kokoroIsTheDefaultEngine() {
        val native = src("voice/engine/LiveNativeEngine.kt"); val tts = src("voice/engine/GeminiTtsEngine.kt")
        val kokoro = src("voice/engine/KokoroTtsEngine.kt")
        assertTrue(kokoro.contains("override val defaultOn = true"))
        assertTrue(native.contains("override val defaultOn = false"))
        assertTrue(tts.contains("override val defaultOn = false"))
        assertTrue(native.contains("override val title = \"DK लाइव"))
        assertTrue(native.contains("Direct Gemini Live Voice"))
        // स्क्रीन पर क्रम: Kokoro, DK लाइव, Gemini TTS
        val list = src("voice/engine/VoiceEngines.kt")
        assertTrue(list.indexOf("engine.KokoroTtsEngine\"") in 0 until list.indexOf("engine.LiveNativeEngine\""))
        assertTrue(list.indexOf("engine.LiveNativeEngine\"") in 0 until list.indexOf("engine.GeminiTtsEngine\""))
    }

    @Test fun kokoroBecomesDefaultOnceThenUserChoiceStays() {
        // पहली बार: जो भी था -> Kokoro (फ़ोन पर चल सके तो)
        assertEquals(VoiceEngines.KOKORO_TTS, VoiceEngines.kokoroMigrated(VoiceEngines.LIVE_NATIVE, done = false, kokoroOk = true))
        assertEquals(VoiceEngines.KOKORO_TTS, VoiceEngines.kokoroMigrated(null, done = false, kokoroOk = true))
        // बाद में यूज़र ने Direct Live / Gemini TTS चुना = वही रहे
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceEngines.kokoroMigrated(VoiceEngines.LIVE_NATIVE, done = true, kokoroOk = true))
        assertEquals(VoiceEngines.GEMINI_TTS, VoiceEngines.kokoroMigrated(VoiceEngines.GEMINI_TTS, done = true, kokoroOk = true))
        // Kokoro इस फ़ोन पर नहीं चलता = जो था वही; हटाया Edge = DK लाइव (चुप नहीं)
        assertEquals(VoiceEngines.GEMINI_TTS, VoiceEngines.kokoroMigrated(VoiceEngines.GEMINI_TTS, done = false, kokoroOk = false))
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceEngines.kokoroMigrated(VoiceEngines.OLD_EDGE_ID, done = false, kokoroOk = false))
        assertEquals(VoiceEngines.KOKORO_TTS, VoiceEngines.kokoroMigrated(VoiceEngines.OLD_EDGE_ID, done = true, kokoroOk = true))
        // Piper हटाया: पुरानी Piper पसंद = Kokoro (न चले तो DK लाइव)
        assertEquals(VoiceEngines.KOKORO_TTS, VoiceEngines.kokoroMigrated(VoiceEngines.OLD_PIPER_ID, done = true, kokoroOk = true))
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceEngines.kokoroMigrated(VoiceEngines.OLD_PIPER_ID, done = true, kokoroOk = false))
        assertEquals("kokoro_tts", VoiceEngines.KOKORO_TTS)
        assertTrue(VoiceEngines.OLD_PIPER_ID != VoiceEngines.KOKORO_TTS)
    }

    @Test fun directLiveSwitchGoesBackToKokoro() {
        val p = VoiceEngines.KOKORO_TTS; val l = VoiceEngines.LIVE_NATIVE; val g = VoiceEngines.GEMINI_TTS
        assertEquals(l, VoiceModeController.next(p, l, true, all))          // Direct चालू
        assertEquals(p, VoiceModeController.next(l, l, false, all))         // Direct बंद = Kokoro
        assertEquals(p, VoiceModeController.next(g, g, false, all))         // Gemini TTS बंद = Kokoro
        assertEquals(l, VoiceModeController.next(p, p, false, all))         // Kokoro बंद = Direct (कोई तो बोले)
        assertEquals(g, VoiceModeController.next(p, g, true, all))
        // Kokoro इस फ़ोन पर नहीं = Direct बंद करने पर भी Direct ही (Kokoro पर नहीं जाता)
        assertEquals(l, VoiceModeController.next(l, l, false, setOf(l, g)))
        assertEquals(VoiceModeController.Mode.DIRECT_LIVE, VoiceModeController.modeOf(l))
        assertEquals(VoiceModeController.Mode.KOKORO, VoiceModeController.modeOf(p))
        assertEquals(VoiceModeController.Mode.GEMINI_TTS, VoiceModeController.modeOf(g))
    }

    @Test fun removedEngineCannotBeChosen() {
        // नवंबर 2026: Edge हटाया - उसका नाम अब सूची में नहीं, चुनने पर कुछ नहीं बदलता
        assertEquals(VoiceEngines.GEMINI_TTS, VoiceEngines.toggle(VoiceEngines.GEMINI_TTS, VoiceEngines.OLD_EDGE_ID, true, available = all))
        assertFalse(src("voice/engine/VoiceEngines.kt").contains("engine.EdgeTtsEngine"))
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

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
    private val all = setOf(VoiceEngines.GEMINI_TTS, VoiceEngines.LIVE_NATIVE)

    @Test fun switchingOnOneTurnsOthersOff() {
        assertEquals(VoiceEngines.GEMINI_TTS, VoiceEngines.toggle(VoiceEngines.LIVE_NATIVE, VoiceEngines.GEMINI_TTS, true, available = all))
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceEngines.toggle(VoiceEngines.GEMINI_TTS, VoiceEngines.LIVE_NATIVE, true, available = all))
    }

    @Test fun turningOffActiveFallsBackToDkLive() {
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceEngines.toggle(VoiceEngines.GEMINI_TTS, VoiceEngines.GEMINI_TTS, false, available = all))
        // डिफ़ॉल्ट (DK लाइव) को बंद करो = दूसरा नंबर Gemini TTS (कोई इंजन न हो ऐसा नहीं)
        assertEquals(VoiceEngines.GEMINI_TTS, VoiceEngines.toggle(VoiceEngines.LIVE_NATIVE, VoiceEngines.LIVE_NATIVE, false, available = all))
        // किसी बंद वाले को बंद = कुछ नहीं बदलता
        assertEquals(VoiceEngines.GEMINI_TTS, VoiceEngines.toggle(VoiceEngines.GEMINI_TTS, VoiceEngines.LIVE_NATIVE, false, available = all))
        // सिर्फ़ एक इंजन बचा = वही रहता है
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceEngines.toggle(VoiceEngines.LIVE_NATIVE, VoiceEngines.LIVE_NATIVE, false, available = setOf(VoiceEngines.LIVE_NATIVE)))
    }

    @Test fun oldGeminiTtsDefaultMovesToDkLiveOnce() {
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceEngines.migrated(VoiceEngines.GEMINI_TTS, done = false))
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceEngines.migrated(null, done = false))
        assertEquals(VoiceEngines.OLD_EDGE_ID, VoiceEngines.migrated(VoiceEngines.OLD_EDGE_ID, done = false))
        assertEquals(VoiceEngines.GEMINI_TTS, VoiceEngines.migrated(VoiceEngines.GEMINI_TTS, done = true))
    }

    /** नवंबर 2026 (मालिक): Kokoro पूरी तरह हटाया - डिफ़ॉल्ट Direct Gemini Live Voice (DK लाइव), बैकअप Gemini TTS */
    @Test fun directLiveIsTheDefaultEngine_kokoroGone() {
        val native = src("voice/engine/LiveNativeEngine.kt"); val tts = src("voice/engine/GeminiTtsEngine.kt")
        assertTrue(native.contains("override val defaultOn = true"))
        assertTrue(tts.contains("override val defaultOn = false"))
        assertTrue(native.contains("override val title = \"DK लाइव"))
        assertTrue(native.contains("Direct Gemini Live Voice"))
        val list = src("voice/engine/VoiceEngines.kt")
        assertFalse(list.contains("engine.KokoroTtsEngine\""))
        assertTrue(list.indexOf("engine.LiveNativeEngine\"") in 0 until list.indexOf("engine.GeminiTtsEngine\""))
        for (f in listOf("src/main/java/com/dk/assistant/voice/engine/KokoroTtsEngine.kt", "app/src/main/java/com/dk/assistant/voice/engine/KokoroTtsEngine.kt"))
            assertFalse(f, java.io.File(f).exists())
        // APK में Kokoro model / sherpa-onnx नहीं (साइज़ ~140 MB की असली वजह)
        val gradle = listOf(java.io.File("build.gradle.kts"), java.io.File("app/build.gradle.kts")).first { it.exists() }.readText()
        for (bad in listOf("sherpa", "kokoro_model", "model.onnx", "dkFetch")) assertFalse(bad, gradle.contains(bad))
        assertFalse(gradle.contains("implementation(files(sherpaAar))"))
        assertFalse(gradle.contains("kokoroAssets"))
    }

    @Test fun directLiveBecomesDefaultOnceThenUserChoiceStays() {
        // पहली बार: जो भी था -> Direct Live
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceEngines.directMigrated(VoiceEngines.OLD_KOKORO_ID, done = false))
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceEngines.directMigrated(VoiceEngines.GEMINI_TTS, done = false))
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceEngines.directMigrated(null, done = false))
        // बाद में यूज़र ने Gemini TTS चुना = वही रहे
        assertEquals(VoiceEngines.GEMINI_TTS, VoiceEngines.directMigrated(VoiceEngines.GEMINI_TTS, done = true))
        assertEquals(VoiceEngines.LIVE_NATIVE, VoiceEngines.directMigrated(VoiceEngines.LIVE_NATIVE, done = true))
        // हटाए इंजन (Kokoro / Piper / Edge) = हमेशा Direct Live
        for (old in listOf(VoiceEngines.OLD_KOKORO_ID, VoiceEngines.OLD_PIPER_ID, VoiceEngines.OLD_EDGE_ID, VoiceEngines.OLD_SUPERTONIC_ID))
            assertEquals(old, VoiceEngines.LIVE_NATIVE, VoiceEngines.directMigrated(old, done = true))
        assertEquals("kokoro_tts", VoiceEngines.OLD_KOKORO_ID)
    }

    @Test fun directLiveSwitchOffGoesToGeminiTts() {
        val l = VoiceEngines.LIVE_NATIVE; val g = VoiceEngines.GEMINI_TTS
        assertEquals(l, VoiceModeController.next(g, l, true, all))          // Direct चालू
        assertEquals(g, VoiceModeController.next(l, l, false, all))         // Direct बंद = Gemini TTS (बैकअप)
        assertEquals(l, VoiceModeController.next(g, g, false, all))         // Gemini TTS बंद = Direct
        assertEquals(g, VoiceModeController.next(l, g, true, all))
        assertEquals(l, VoiceModeController.next(l, l, false, setOf(l)))    // अकेला = वही
        assertEquals(VoiceModeController.Mode.DIRECT_LIVE, VoiceModeController.modeOf(l))
        assertEquals(VoiceModeController.Mode.DIRECT_LIVE, VoiceModeController.modeOf(VoiceEngines.OLD_KOKORO_ID))
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

    /** नवंबर 2026 (मालिक: "पहला Supertonic / Piper पूरी तरह हटाओ, बैकअप भी नहीं"): कोड में कुछ न बचे, फ़ोन की पुरानी चीज़ें मिटें */
    @Test fun oldOfflineTtsFullyRemoved() {
        assertEquals("supertonic_hi", VoiceEngines.OLD_SUPERTONIC_ID)
        assertEquals(null, VoiceEngines.byId(VoiceEngines.OLD_SUPERTONIC_ID))
        for (n in listOf("supertonic", "piper", "piper.gone.1731400000000", "supertonic.part")) assertTrue(n, VoiceEngines.isOldOffline(n))
        // नया TTS अलग फ़ोल्डर में रहे तो उसे न मिटाए; आवाज़-पहचान का मॉडल भी नहीं
        for (n in listOf("supertonic3", "voice", "wallpaper", "files")) assertFalse(n, VoiceEngines.isOldOffline(n))
        val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.exists() }
        for (f in listOf("com/dk/assistant/voice/engine/SupertonicTtsEngine.kt", "com/dk/assistant/voice/engine/SupertonicStore.kt",
            "com/dk/assistant/voice/engine/SupertonicDownload.kt", "com/dk/assistant/voice/engine/HumanVoice.kt",
            "com/dk/assistant/SettingsSupertonic.kt", "com/k2fsa/sherpa/onnx/Tts.kt"))
            assertFalse(f, java.io.File(root, f).exists())
        val code = root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }
            .joinToString("\n") { f -> f.readLines().filterNot { it.trimStart().let { t -> t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") } }.joinToString("\n") }
        for (bad in listOf("SupertonicTtsEngine", "SupertonicStore", "PiperTtsEngine", "PiperStore", "com.k2fsa.sherpa.onnx.OfflineTts", "HumanVoice"))
            assertFalse(bad, code.contains(bad))
    }
}

package com.dk.assistant.voice.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** नवंबर 2026: Edge TTS हटाकर Piper TTS (फ़ोन में). Android / .so के बिना जाँचने वाले हिस्से */
class PiperTtsTest {
    private fun root(): File = listOf(File("."), File("..")).first { File(it, "app/build.gradle.kts").exists() }
    private fun main(): File = File(root(), "app/src/main/java/com/dk/assistant")

    @Test fun floatToPcm16() {
        val b = PiperTtsEngine.pcm16(floatArrayOf(0f, 1f, -1f, 2f, 0.5f))
        fun s(i: Int) = ((b[i * 2].toInt() and 0xff) or (b[i * 2 + 1].toInt() shl 8)).toShort().toInt()
        assertEquals(10, b.size)
        assertEquals(0, s(0)); assertEquals(32767, s(1)); assertEquals(-32767, s(2))
        assertEquals(32767, s(3))                      // 1 से ऊपर = कटकर 1 (फटी आवाज़ नहीं)
        assertEquals(16383, s(4))
    }

    @Test fun voiceNameAndSpeaker() {
        assertEquals("hi_IN-pratham-medium", PiperTtsEngine.voiceDir("hi_IN-pratham-medium"))
        assertEquals(0, PiperTtsEngine.speakerId("hi_IN-pratham-medium"))
        assertEquals("multi", PiperTtsEngine.voiceDir("multi#3")); assertEquals(3, PiperTtsEngine.speakerId("multi#3"))
        assertEquals(0, PiperTtsEngine.speakerId("multi#x")); assertEquals(0, PiperTtsEngine.speakerId("multi#-2"))
    }

    @Test fun styleWordsChangePaceOnly_calmByDefault() {
        // शांत स्वभाव: सामान्य से थोड़ा धीमा (CalmPersona -8%)
        assertEquals(0.92f, PiperTtsEngine.speed(""), 0.001f)
        assertTrue(PiperTtsEngine.speed("धीरे, आराम से") < PiperTtsEngine.speed(""))
        assertTrue(PiperTtsEngine.speed("तेज़, जल्दी") > PiperTtsEngine.speed(""))
        assertTrue(PiperTtsEngine.speed("तेज़ जल्दी जोश fast quick excited") <= 2f)
    }

    @Test fun defaultsAndVoicesMatchBuild() {
        assertEquals("hi_IN-priyamvada-medium", PiperTtsEngine.DEFAULT_VOICE)
        assertTrue(PiperTtsEngine.split && PiperTtsEngine.defaultOn)
        val g = File(root(), "app/build.gradle.kts").readText()
        val block = g.substringAfter("val piperVoices = linkedMapOf(").substringBefore(")\n")
        val built = Regex("\"([A-Za-z_-]+)\" to \"[0-9a-f]{64}\"").findAll(block).map { it.groupValues[1] }.toList()
        assertEquals(PiperTtsEngine.VOICES.map { it.first }, built)
        assertEquals(PiperTtsEngine.VOICES.map { it.first }.toSet().size, PiperTtsEngine.VOICES.size)
    }

    @Test fun buildFetchesVerifiedSherpaWithEspeakAndStaticOnnx() {
        val g = File(root(), "app/build.gradle.kts").readText()
        // 2.x में espeak-ng नहीं (Piper की हिंदी नहीं चलती) - 1.13.8 पक्का; ONNX Runtime अंदर जुड़ा (हमारे onnxruntime-android से टकराव नहीं)
        assertTrue(g.contains("val sherpaVersion = \"1.13.8\""))
        assertTrue(g.contains("sherpa-onnx-static-link-onnxruntime-\$sherpaVersion.aar"))
        assertTrue(g.contains("implementation(files(sherpaAar))"))
        assertTrue(g.contains("if (got != sha) throw GradleException"))       // बिना जाँचे फ़ाइल नहीं
        assertTrue(g.contains("sourceSets.getByName(\"main\").assets.srcDir(piperAssets)"))
        for (abi in listOf("x86", "x86_64", "armeabi-v7a")) assertTrue(abi, g.contains("\"lib/$abi/libsherpa-onnx-jni.so\""))
        // .dk-cache repo में नहीं
        assertTrue(File(root(), ".gitignore").readText().lines().contains(".dk-cache/"))
        val pro = File(root(), "app/proguard-rules.pro").readText()
        assertTrue(pro.contains("-keep class com.k2fsa.sherpa.onnx.** { *; }"))
        assertTrue(pro.contains("-keep class com.dk.assistant.voice.engine.PiperTtsEngine { *; }"))
    }

    /** मालिक: "Edge TTS पूरी तरह हटाओ, Gemini TTS और Direct Live वैसे ही रहें" */
    @Test fun edgeTtsIsGone_geminiTtsAndLiveStay() {
        assertFalse(File(main(), "voice/engine/EdgeTtsEngine.kt").exists())
        assertFalse(File(main(), "voice/engine/EdgeStyle.kt").exists())
        val all = main().walkTopDown().filter { it.extension == "kt" }.map { it.name to it.readText() }.toList()
        for ((n, t) in all) {
            assertFalse(n, t.contains("EdgeTtsEngine"))
            assertFalse(n, t.contains("speech.platform.bing.com"))
            assertFalse(n, t.contains("TrustedClientToken"))
            assertFalse(n, t.contains("edgeOnly"))
        }
        assertTrue(File(main(), "voice/engine/GeminiTtsEngine.kt").exists())
        assertTrue(File(main(), "voice/engine/LiveNativeEngine.kt").exists())
        val list = File(main(), "voice/engine/VoiceEngines.kt").readText()
        for (e in listOf("PiperTtsEngine", "LiveNativeEngine", "GeminiTtsEngine")) assertTrue(e, list.contains("engine.$e\""))
    }

    @Test fun pipelineAndBackupUsePiper() {
        val sp = File(main(), "Speaker.kt").readText()
        assertTrue(sp.contains("com.dk.assistant.voice.engine.PiperTtsEngine"))
        // बैकअप मोड: Piper न चले = फ़ोन की आवाज़ (चुप नहीं)
        assertTrue(sp.contains("else if (piperOnly && !aborted && !paused) {"))
        val e = File(main(), "voice/engine/PiperTtsEngine.kt").readText()
        // espeak-ng thread-safe नहीं -> एक समय में एक बनाना; stop() पर अगला टुकड़ा नहीं
        assertTrue(e.contains("synchronized(lock) {"))
        assertTrue(e.contains("if (my != gen) return@generateWithCallback 0"))
        // .so न हो तो ऐप गिरे नहीं
        assertTrue(e.contains("catch (t: Throwable)"))
        assertTrue(e.contains("OfflineTts(assetManager = c.assets, config = cfg)"))
        // दूसरा इंजन चुना = Piper की याद ख़ाली
        assertTrue(File(main(), "voice/engine/VoiceModeController.kt").readText().contains("PiperTtsEngine.releaseNow()"))
    }
}

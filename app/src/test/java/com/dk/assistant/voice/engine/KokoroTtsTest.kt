package com.dk.assistant.voice.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * नवंबर 2026: Edge TTS हटाकर Piper TTS, फिर (मालिक) Piper पूरी तरह हटाकर Kokoro-82M डिफ़ॉल्ट.
 * Android / .so के बिना जाँचने वाले हिस्से. (पहले PiperTtsTest - वही जाँचें अब Kokoro पर)
 */
class KokoroTtsTest {
    private fun root(): File = listOf(File("."), File("..")).first { File(it, "app/build.gradle.kts").exists() }
    private fun main(): File = File(root(), "app/src/main/java/com/dk/assistant")

    @Test fun floatToPcm16() {
        val b = KokoroTtsEngine.pcm16(floatArrayOf(0f, 1f, -1f, 2f, 0.5f))
        fun s(i: Int) = ((b[i * 2].toInt() and 0xff) or (b[i * 2 + 1].toInt() shl 8)).toShort().toInt()
        assertEquals(10, b.size)
        assertEquals(0, s(0)); assertEquals(32767, s(1)); assertEquals(-32767, s(2))
        assertEquals(32767, s(3))                      // 1 से ऊपर = कटकर 1 (फटी आवाज़ नहीं)
        assertEquals(16383, s(4))
    }

    /** voices.bin (sherpa kokoro-multi-lang-v1_0) में नंबर: hf_alpha 31, hf_beta 32, hm_omega 33, hm_psi 34 */
    @Test fun voiceNameAndSpeaker() {
        assertEquals(31, KokoroTtsEngine.speakerId("hf_alpha"))
        assertEquals(32, KokoroTtsEngine.speakerId("hf_beta"))
        assertEquals(33, KokoroTtsEngine.speakerId("hm_omega"))
        assertEquals(34, KokoroTtsEngine.speakerId("hm_psi"))
        assertEquals(31, KokoroTtsEngine.speakerId("af_heart"))             // बाकी कोई = Alpha (सिर्फ़ 4 भरी हैं)
        assertEquals(31, KokoroTtsEngine.speakerId("hi_IN-pratham-medium"))
    }

    /** मालिक: सिर्फ़ 4 आवाज़ें, 2 समूह - [Female voices: Alpha, Beta] | [Male voices: Omega, Psi] */
    @Test fun onlyFourVoicesInTwoGroups_alphaDefault() {
        assertEquals(listOf("hf_alpha", "hf_beta", "hm_omega", "hm_psi"), KokoroTtsEngine.VOICES.map { it.first })
        assertEquals(listOf("Alpha", "Beta", "Omega", "Psi"), KokoroTtsEngine.VOICES.map { it.second })
        assertEquals(listOf("Alpha", "Beta"), KokoroTtsEngine.VOICE_LIST.filter { KokoroTtsEngine.voiceGroup(it.id) == "Female voices" }.map { it.label })
        assertEquals(listOf("Omega", "Psi"), KokoroTtsEngine.VOICE_LIST.filter { KokoroTtsEngine.voiceGroup(it.id) == "Male voices" }.map { it.label })
        assertEquals(2, KokoroTtsEngine.VOICE_LIST.map { it.group }.distinct().size)
        assertEquals("hf_alpha", KokoroTtsEngine.DEFAULT_VOICE)
        assertEquals("hi", KokoroTtsEngine.LANG)                            // सिर्फ़ हिंदी (+ हिंग्लिश)
        assertEquals(null, KokoroTtsEngine.voiceGroup("hi_IN-priyamvada-medium"))
    }

    /** orb / लहर: Kokoro की आवाज़ उसी कतार से -> VoiceLevel.schedule असली बजने के समय पर (अलग रास्ता नहीं) */
    @Test fun visualizerFollowsKokoroAudio() {
        val pipe = File(main(), "voice/engine/SpeechPipeline.kt").readText()
        assertTrue(pipe.contains("VoiceLevel.schedule(pcm, startAt)"))
        assertTrue(File(main(), "OrbView.kt").readText().contains("VoiceLevel.mouth()"))
        val e = File(main(), "voice/engine/KokoroTtsEngine.kt").readText()
        assertTrue(e.contains("out.write("))
        assertFalse(e.contains("AudioTrack("))                              // अपना अलग player नहीं
    }

    @Test fun styleWordsChangePaceOnly_calmByDefault() {
        // शांत स्वभाव: सामान्य से थोड़ा धीमा (CalmPersona -8%)
        assertEquals(0.92f, KokoroTtsEngine.speed(""), 0.001f)
        assertTrue(KokoroTtsEngine.speed("धीरे, आराम से") < KokoroTtsEngine.speed(""))
        assertTrue(KokoroTtsEngine.speed("तेज़, जल्दी") > KokoroTtsEngine.speed(""))
        assertTrue(KokoroTtsEngine.speed("तेज़ जल्दी जोश fast quick excited") <= 2f)
    }

    @Test fun defaultsAndVoicesMatchBuild() {
        assertEquals("hf_alpha", KokoroTtsEngine.DEFAULT_VOICE)
        assertTrue(KokoroTtsEngine.split && KokoroTtsEngine.defaultOn)
        val g = File(root(), "app/build.gradle.kts").readText()
        val block = g.substringAfter("val kokoroSpeakers = linkedMapOf(").substringBefore(")\n")
        val built = Regex("\"([a-z_]+)\" to (\\d+)").findAll(block).map { it.groupValues[1] to it.groupValues[2].toInt() }.toList()
        assertEquals(KokoroTtsEngine.VOICE_LIST.map { it.id to it.sid }, built)
        assertEquals(KokoroTtsEngine.VOICES.map { it.first }.toSet().size, KokoroTtsEngine.VOICES.size)
        assertTrue(g.contains("val kokoroName = \"kokoro-int8-multi-lang-v1_0\""))
        assertTrue(Regex("val kokoroSha = \"[0-9a-f]{64}\"").containsMatchIn(g))
    }

    /** मालिक: "Piper पूरी तरह हटाओ - फ़ाइलें, model, config, कोई टूटा हवाला न बचे" */
    @Test fun piperIsCompletelyGone() {
        assertFalse(File(main(), "voice/engine/PiperTtsEngine.kt").exists())
        val all = main().walkTopDown().filter { it.extension == "kt" }.map { it.name to it.readText() }.toList()
        for ((n, t) in all) {
            assertFalse(n, t.contains("PiperTtsEngine"))
            assertFalse(n, t.contains("piperOnly"))
            assertFalse(n, t.contains("PIPER_TTS"))
            assertFalse(n, t.contains("vits-piper"))
        }
        val g = File(root(), "app/build.gradle.kts").readText()
        for (x in listOf("piperVoices", "preparePiper", "piperAssets", "hi_IN-priyamvada", "hi_IN-pratham", "en_US-lessac")) assertFalse(x, g.contains(x))
        val pro = File(root(), "app/proguard-rules.pro").readText()
        assertFalse(pro.contains("PiperTtsEngine"))
        for (w in listOf("build.yml", "check.yml")) assertFalse(w, File(root(), ".github/workflows/$w").readText().contains("dk-piper-"))
        // पुराने फ़ोन की बची Piper फ़ाइलें भी साफ़ होती हैं
        assertTrue(File(main(), "voice/engine/KokoroTtsEngine.kt").readText().contains("File(c.filesDir, \"piper_models\").deleteRecursively()"))
    }

    /** voices.bin पूरी लंबाई (sherpa model से गिनती मिलाता है) पर सिर्फ़ 4 भरी; model.onnx बिना zip-दबाव */
    @Test fun buildKeepsOnlyFourVoicesAndDoesNotCompressModel() {
        val g = File(root(), "app/build.gradle.kts").readText()
        assertTrue(g.contains("val kokoroStyleBytes = 510 * 256 * 4"))
        assertTrue(g.contains("for (sid in kokoroSpeakers.values) System.arraycopy(all, sid * kokoroStyleBytes, keep, sid * kokoroStyleBytes, kokoroStyleBytes)"))
        assertTrue(g.contains("noCompress += listOf(\"onnx\")"))
        assertTrue(g.contains("val espeakDicts = setOf(\"hi_dict\", \"en_dict\")"))
        assertFalse(g.contains("lexicon-zh"))                               // चीनी फ़ाइलें नहीं
    }

    @Test fun buildFetchesVerifiedSherpaWithEspeakAndStaticOnnx() {
        val g = File(root(), "app/build.gradle.kts").readText()
        // 2.x में espeak-ng नहीं (Kokoro की हिंदी उसी से) - 1.13.8 पक्का; ONNX Runtime अंदर जुड़ा (हमारे onnxruntime-android से टकराव नहीं)
        assertTrue(g.contains("val sherpaVersion = \"1.13.8\""))
        assertTrue(g.contains("sherpa-onnx-static-link-onnxruntime-\$sherpaVersion.aar"))
        assertTrue(g.contains("implementation(files(sherpaAar))"))
        assertTrue(g.contains("if (got != sha) throw GradleException"))       // बिना जाँचे फ़ाइल नहीं
        assertTrue(g.contains("sourceSets.getByName(\"main\").assets.srcDir(kokoroAssets)"))
        for (abi in listOf("x86", "x86_64", "armeabi-v7a")) assertTrue(abi, g.contains("\"lib/$abi/libsherpa-onnx-jni.so\""))
        // .dk-cache repo में नहीं
        assertTrue(File(root(), ".gitignore").readText().lines().contains(".dk-cache/"))
        val pro = File(root(), "app/proguard-rules.pro").readText()
        assertTrue(pro.contains("-keep class com.k2fsa.sherpa.onnx.** { *; }"))
        assertTrue(pro.contains("-keep class com.dk.assistant.voice.engine.KokoroTtsEngine { *; }"))
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
        for (e in listOf("KokoroTtsEngine", "LiveNativeEngine", "GeminiTtsEngine")) assertTrue(e, list.contains("engine.$e\""))
    }

    @Test fun pipelineAndBackupUseKokoro() {
        val sp = File(main(), "Speaker.kt").readText()
        assertTrue(sp.contains("com.dk.assistant.voice.engine.KokoroTtsEngine"))
        // बैकअप मोड: Kokoro न चले = फ़ोन की आवाज़ (चुप नहीं)
        assertTrue(sp.contains("else if (localOnly && !aborted && !paused) {"))
        val e = File(main(), "voice/engine/KokoroTtsEngine.kt").readText()
        // espeak-ng thread-safe नहीं -> एक समय में एक बनाना; stop() पर अगला टुकड़ा नहीं
        assertTrue(e.contains("synchronized(lock) {"))
        assertTrue(e.contains("if (my != gen) return@generateWithCallback 0"))
        // .so न हो तो ऐप गिरे नहीं
        assertTrue(e.contains("catch (t: Throwable)"))
        assertTrue(e.contains("OfflineTts(assetManager = c.assets, config = cfg)"))
        assertTrue(e.contains("kokoro = OfflineTtsKokoroModelConfig("))
        assertTrue(e.contains("lang = LANG"))
        // हिंग्लिश: बोलने से पहले रोमन हिंदी -> देवनागरी
        assertTrue(e.contains("HinglishText.toSpeakable(text)"))
        // Kokoro के टुकड़े छोटे पहले (TextChunker.LOCAL)
        assertEquals(TextChunker.LOCAL, SpeechPipeline.profileFor(VoiceEngines.KOKORO_TTS))
        // दूसरा इंजन चुना = Kokoro की याद ख़ाली
        assertTrue(File(main(), "voice/engine/VoiceModeController.kt").readText().contains("KokoroTtsEngine.releaseNow()"))
    }
}

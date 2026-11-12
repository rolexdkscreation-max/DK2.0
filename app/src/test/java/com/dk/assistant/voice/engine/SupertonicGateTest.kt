package com.dk.assistant.voice.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * नवंबर 2026 (मालिक: "पहले से जो है उसमें कोई छेड़छाड़ नहीं"): Supertonic 3 (Piper की जगह, वही सेटअप) सिर्फ़ स्विच ON + डाउनलोड पूरा होने पर.
 * OFF = वही इंजन, वही सेव पसंद, वही टुकड़े, वही ठहराव, वही Gemini TTS बैकअप.
 */
class SupertonicGateTest {
    private fun src(rel: String): String = listOf(File("src/main/java/com/dk/assistant/$rel"), File("app/src/main/java/com/dk/assistant/$rel"))
        .first { it.exists() }.readText()

    @Test fun onlyOnAndDownloaded() {
        assertTrue(SupertonicTtsEngine.gate(on = true, installed = true))
        assertFalse(SupertonicTtsEngine.gate(on = false, installed = true))      // डिफ़ॉल्ट: OFF
        assertFalse(SupertonicTtsEngine.gate(on = true, installed = false))      // मॉडल हटाया
        assertFalse(SupertonicTtsEngine.gate(on = false, installed = false))
        assertTrue(SupertonicTtsEngine.split)
        assertFalse(SupertonicTtsEngine.defaultOn)
    }

    @Test fun oldEngineListAndChoiceUntouched() {
        // Supertonic सूची (CLASSES) में नहीं - "बोलने वाला इंजन" card वही दो इंजन दिखाता है
        val list = src("voice/engine/VoiceEngines.kt")
        assertFalse(list.contains("engine.SupertonicTtsEngine\""))
        assertTrue(list.contains("fun active(c: Context): VoiceEngine? = SupertonicTtsEngine.override(c) ?: byId(activeId(c))"))
        // Supertonic की पसंद अलग prefs में, स्विच डिफ़ॉल्ट OFF; पुरानी "active" पसंद कभी नहीं लिखता
        val store = src("voice/engine/SupertonicStore.kt")
        assertTrue(store.contains("const val PREFS = \"dk_supertonic\""))
        assertTrue(store.contains("getBoolean(\"on\", false)"))
        for (f in listOf("voice/engine/SupertonicStore.kt", "voice/engine/SupertonicTtsEngine.kt", "voice/engine/SupertonicDownload.kt", "SettingsSupertonic.kt")) {
            val s = src(f)
            assertFalse(f, s.contains("dk_voice_engine"))
            assertFalse(f, s.contains("setActive("))
        }
        assertEquals("supertonic_hi", SupertonicTtsEngine.ID)
        assertTrue(SupertonicTtsEngine.ID != VoiceEngines.OLD_PIPER_ID)        // हटाए पुराने Piper की migration से टकराव नहीं
    }

    @Test fun pipelineSameForOtherEngines() {
        assertEquals(TextChunker.FAST_NET, SpeechPipeline.profileFor(VoiceEngines.GEMINI_TTS))
        assertEquals(TextChunker.STANDARD, SpeechPipeline.profileFor(VoiceEngines.LIVE_NATIVE))
        assertEquals(SupertonicTtsEngine.CHUNKS, SpeechPipeline.profileFor(SupertonicTtsEngine.ID))
        val p = src("voice/engine/SpeechPipeline.kt")
        assertTrue(p.contains("if (it.id != chosen.id && chosen.id != SupertonicTtsEngine.ID) add(it)"))   // Supertonic फ़ेल = Live की आवाज़
        assertTrue(p.contains("fun stopAll()"))
        // पहला वाक्य: पूर्ण विराम / ? / ! पर कटे
        val c = TextChunker(); c.reset(SupertonicTtsEngine.CHUNKS)
        assertEquals(listOf("मैं ठीक हूँ।"), c.feed("मैं ठीक हूँ। आप"))
        c.reset(SupertonicTtsEngine.CHUNKS)
        assertEquals(listOf("क्या सच में?"), c.feed("क्या सच में? हाँ"))
        c.reset(SupertonicTtsEngine.CHUNKS)
        assertEquals(listOf("जी बिल्कुल,"), c.feed("जी बिल्कुल, मैं"))           // पहला छोटा = जल्दी आवाज़
    }

    @Test fun modelNotInsideApk() {
        val gradle = listOf(File("build.gradle.kts"), File("app/build.gradle.kts")).first { it.exists() }.readText()
        for (bad in listOf("sherpa", "piper", "supertonic", "model.onnx")) assertFalse(bad, gradle.lowercase().contains(bad))
        val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.exists() }
        assertTrue(assets.walkTopDown().none { it.name.startsWith("hi_IN-") || it.name.startsWith("libsherpa") || it.name == "espeak-ng-data" || it.name == "tokens.txt" ||
            it.name.endsWith(".int8.onnx") || it.name == "voice.bin" || it.name == "unicode_indexer.bin" || it.name.startsWith("supertonic") })
        val jni = listOf(File("src/main/jniLibs"), File("app/src/main/jniLibs"))
        assertTrue(jni.none { it.exists() && it.walkTopDown().any { f -> f.name.contains("sherpa") } })
    }

    @Test fun settingsCardAdded() {
        val v = src("SettingsVoice.kt")
        assertTrue(v.contains("card(\"Supertonic 3 आवाज़\", "))
        assertFalse(v.contains("card(\"Piper आवाज़\", "))
        val s = src("SettingsSupertonic.kt")
        for (t in listOf("डाउनलोड करें", "दोबारा कोशिश करें", "मॉडल हटाएँ", "स्पीड", "पिच", "रिवर्ब", "बास", "ठहराव", "टेस्ट")) assertTrue(t, s.contains(t))
    }

    @Test fun speakableCleansText() {
        assertEquals("नमस्ते दोस्त", SupertonicTtsEngine.speakable("**नमस्ते** दोस्त \uD83D\uDE00"))
        assertEquals("", SupertonicTtsEngine.speakable("  ### "))
    }
}

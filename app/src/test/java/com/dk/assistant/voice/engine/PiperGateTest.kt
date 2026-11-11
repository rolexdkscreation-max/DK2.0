package com.dk.assistant.voice.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * नवंबर 2026 (मालिक: "पहले से जो है उसमें कोई छेड़छाड़ नहीं"): Piper सिर्फ़ स्विच ON + डाउनलोड पूरा होने पर.
 * OFF = वही इंजन, वही सेव पसंद, वही टुकड़े, वही ठहराव, वही Gemini TTS बैकअप.
 */
class PiperGateTest {
    private fun src(rel: String): String = listOf(File("src/main/java/com/dk/assistant/$rel"), File("app/src/main/java/com/dk/assistant/$rel"))
        .first { it.exists() }.readText()

    @Test fun onlyOnAndDownloaded() {
        assertTrue(PiperTtsEngine.gate(on = true, installed = true))
        assertFalse(PiperTtsEngine.gate(on = false, installed = true))      // डिफ़ॉल्ट: OFF
        assertFalse(PiperTtsEngine.gate(on = true, installed = false))      // मॉडल हटाया
        assertFalse(PiperTtsEngine.gate(on = false, installed = false))
        assertTrue(PiperTtsEngine.split)
        assertFalse(PiperTtsEngine.defaultOn)
    }

    @Test fun oldEngineListAndChoiceUntouched() {
        // Piper सूची (CLASSES) में नहीं - "बोलने वाला इंजन" card वही दो इंजन दिखाता है
        val list = src("voice/engine/VoiceEngines.kt")
        assertFalse(list.contains("engine.PiperTtsEngine\""))
        assertTrue(list.contains("fun active(c: Context): VoiceEngine? = PiperTtsEngine.override(c) ?: byId(activeId(c))"))
        // Piper की पसंद अलग prefs में, स्विच डिफ़ॉल्ट OFF; पुरानी "active" पसंद कभी नहीं लिखता
        val store = src("voice/engine/PiperStore.kt")
        assertTrue(store.contains("const val PREFS = \"dk_piper\""))
        assertTrue(store.contains("getBoolean(\"on\", false)"))
        for (f in listOf("voice/engine/PiperStore.kt", "voice/engine/PiperTtsEngine.kt", "voice/engine/PiperDownload.kt", "SettingsPiper.kt")) {
            val s = src(f)
            assertFalse(f, s.contains("dk_voice_engine"))
            assertFalse(f, s.contains("setActive("))
        }
        assertEquals("piper_hi", PiperTtsEngine.ID)
        assertTrue(PiperTtsEngine.ID != VoiceEngines.OLD_PIPER_ID)        // हटाए पुराने Piper की migration से टकराव नहीं
    }

    @Test fun pipelineSameForOtherEngines() {
        assertEquals(TextChunker.FAST_NET, SpeechPipeline.profileFor(VoiceEngines.GEMINI_TTS))
        assertEquals(TextChunker.STANDARD, SpeechPipeline.profileFor(VoiceEngines.LIVE_NATIVE))
        assertEquals(PiperTtsEngine.CHUNKS, SpeechPipeline.profileFor(PiperTtsEngine.ID))
        val p = src("voice/engine/SpeechPipeline.kt")
        assertTrue(p.contains("if (it.id != chosen.id && chosen.id != PiperTtsEngine.ID) add(it)"))   // Piper फ़ेल = Live की आवाज़
        assertTrue(p.contains("fun stopAll()"))
        // पहला वाक्य: पूर्ण विराम / ? / ! पर कटे
        val c = TextChunker(); c.reset(PiperTtsEngine.CHUNKS)
        assertEquals(listOf("मैं ठीक हूँ।"), c.feed("मैं ठीक हूँ। आप"))
        c.reset(PiperTtsEngine.CHUNKS)
        assertEquals(listOf("क्या सच में?"), c.feed("क्या सच में? हाँ"))
        c.reset(PiperTtsEngine.CHUNKS)
        assertEquals(listOf("जी बिल्कुल,"), c.feed("जी बिल्कुल, मैं"))           // पहला छोटा = जल्दी आवाज़
    }

    @Test fun modelNotInsideApk() {
        val gradle = listOf(File("build.gradle.kts"), File("app/build.gradle.kts")).first { it.exists() }.readText()
        for (bad in listOf("sherpa", "piper", "model.onnx")) assertFalse(bad, gradle.lowercase().contains(bad))
        val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.exists() }
        assertTrue(assets.walkTopDown().none { it.name.startsWith("hi_IN-") || it.name.startsWith("libsherpa") || it.name == "espeak-ng-data" || it.name == "tokens.txt" })
        val jni = listOf(File("src/main/jniLibs"), File("app/src/main/jniLibs"))
        assertTrue(jni.none { it.exists() && it.walkTopDown().any { f -> f.name.contains("sherpa") } })
    }

    @Test fun settingsCardAdded() {
        val v = src("SettingsVoice.kt")
        assertTrue(v.contains("card(\"Piper आवाज़\", "))
        val s = src("SettingsPiper.kt")
        for (t in listOf("डाउनलोड करें", "दोबारा कोशिश करें", "मॉडल हटाएँ", "स्पीड", "पिच", "रिवर्ब", "बास", "ठहराव", "टेस्ट")) assertTrue(t, s.contains(t))
    }

    @Test fun speakableCleansText() {
        assertEquals("नमस्ते दोस्त", PiperTtsEngine.speakable("**नमस्ते** दोस्त \uD83D\uDE00"))
        assertEquals("", PiperTtsEngine.speakable("  ### "))
    }
}

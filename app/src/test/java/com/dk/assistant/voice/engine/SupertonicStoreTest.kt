package com.dk.assistant.voice.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** नवंबर 2026 (नया Supertonic 3): फ़ाइलें / पते / ABI / आवाज़ें / सिर्फ़ स्पीड-पिच - सब बिना Android के जाँच */
class SupertonicStoreTest {
    private fun src(rel: String): String = listOf(File("src/main/java/com/dk/assistant/$rel"), File("app/src/main/java/com/dk/assistant/$rel"))
        .first { it.exists() }.readText()

    @Test fun fiveFemaleVoicesDefaultFirst() {
        // मालिक: "5-6 अलग-अलग फ़ीमेल वॉइस" - मॉडल में महिला sid पाँच ही (0-4), पाँचों
        assertEquals(5, SupertonicStore.VOICES.size)
        assertEquals(listOf(0, 1, 2, 3, 4), SupertonicStore.VOICES.map { it.sid })
        assertEquals(5, SupertonicStore.VOICES.map { it.id }.toSet().size)
        assertTrue(SupertonicStore.VOICES.all { it.title.contains("महिला") && it.id.startsWith("st3-f") })
        assertEquals("st3-f1-meera", SupertonicStore.DEFAULT_VOICE)
        assertEquals("st3-f5-kavya", SupertonicStore.pickDefault("st3-f5-kavya"))
        assertEquals(SupertonicStore.DEFAULT_VOICE, SupertonicStore.pickDefault(null))
        // पहले वाले Supertonic की आवाज़ों के नाम नहीं पढ़े जाते (नई पहचान)
        assertEquals(SupertonicStore.DEFAULT_VOICE, SupertonicStore.pickDefault("hi-hq-f3-siya"))
        assertEquals(4, SupertonicStore.voiceOf("st3-f5-kavya").sid)
        assertEquals(0, SupertonicStore.voiceOf("nahi").sid)
    }

    @Test fun onlySpeedAndPitch() {
        assertEquals(50, SupertonicStore.SPEED_MIN); assertEquals(200, SupertonicStore.SPEED_MAX)
        assertEquals(70, SupertonicStore.PITCH_MIN); assertEquals(140, SupertonicStore.PITCH_MAX)
        val store = src("voice/engine/SupertonicStore.kt")
        val card = src("SettingsSupertonic.kt")
        for (bad in listOf("reverb", "bass", "pause", "breath")) assertFalse(bad, store.lowercase().contains(bad))
        for (bad in listOf("रिवर्ब", "बास", "ठहराव", "साँस", "resetTune")) assertFalse(bad, card.contains(bad))
        for (t in listOf("डाउनलोड करें", "दोबारा कोशिश करें", "रोकें", "मॉडल हटाएँ", "\"स्पीड\"", "\"पिच\"", "▶ सुनो"))
            assertTrue(t, card.contains(t))
        assertFalse(File(listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }, "com/dk/assistant/voice/engine/SupertonicFx.kt").exists())
    }

    @Test fun pitchAndSpeedMath() {
        assertEquals(1f, SupertonicAudio.genSpeed(1f, 1f), 0.0001f)
        assertEquals(1f, SupertonicAudio.genSpeed(1.2f, 1.2f), 0.0001f)        // पिच ऊँची = बनाते समय उतनी धीमी, सुनने में वही रफ़्तार
        assertEquals(2f, SupertonicAudio.genSpeed(2f, 1f), 0.0001f)
        assertEquals(44100, SupertonicAudio.srcRate(44100, 1f))
        assertEquals(52920, SupertonicAudio.srcRate(44100, 1.2f))
        assertEquals(30870, SupertonicAudio.srcRate(44100, 0.5f))               // 0.7 तक सीमित
        val b = SupertonicAudio.pcm16(floatArrayOf(0f, 1f, -1f, 2f))
        assertEquals(8, b.size)
        assertEquals(0x7f, b[3].toInt() and 0xff); assertEquals(0x7f, b[7].toInt() and 0xff)
        val lp = SoftLowpass.forRates(44100, VoiceEngine.RATE)
        val y = lp.process(FloatArray(2000) { 0.5f })
        assertEquals(0.5f, y.last(), 0.01f)                                      // धीमी (DC) आवाज़ जस की तस
    }

    @Test fun modelFilesPinnedAndFromInternet() {
        assertEquals(7, SupertonicStore.FILES.size)
        assertTrue(SupertonicStore.FILES.all { it.sha256.length == 64 && it.size > 0 })
        assertTrue(SupertonicStore.MODEL_SIZE in 140_000_000L..150_000_000L)
        assertEquals(40, SupertonicStore.REV.length)
        assertEquals("https://huggingface.co/${SupertonicStore.REPO}/resolve/${SupertonicStore.REV}/voice.bin", SupertonicStore.fileUrl("voice.bin"))
        assertEquals("a%2Bb", SupertonicStore.enc("a+b"))
        assertEquals("supertonic3", SupertonicStore.DIR)
        assertEquals("dk_supertonic3", SupertonicStore.PREFS)
    }

    @Test fun abiFromAppNativeDirFirst() {
        assertEquals("arm64-v8a", SupertonicStore.abiFor("/data/app/x/lib/arm64", listOf("x86_64", "arm64-v8a"))?.abi)
        assertEquals("x86_64", SupertonicStore.abiFor("/data/app/x/lib/x86_64", listOf("x86_64"))?.abi)
        assertEquals("armeabi-v7a", SupertonicStore.abiFor("/data/app/x/lib/arm", listOf("armeabi-v7a"))?.abi)
        assertEquals("arm64-v8a", SupertonicStore.abiFor(null, listOf("arm64-v8a", "armeabi-v7a"))?.abi)
        assertNull(SupertonicStore.abiFor(null, listOf("x86", "mips")))
        for (l in SupertonicStore.LIBS) { assertEquals(64, l.sha256.length); assertTrue(l.zipEnd in l.size / 3..SupertonicStore.AAR_SIZE) }
        assertTrue(SupertonicStore.AAR_URL.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/"))
    }

    @Test fun modelNotInsideApk() {
        val gradle = listOf(File("build.gradle.kts"), File("app/build.gradle.kts")).first { it.exists() }.readText()
        for (bad in listOf("sherpa", "piper", "supertonic", "model.onnx")) assertFalse(bad, gradle.lowercase().contains(bad))
        val assets = listOf(File("src/main/assets"), File("app/src/main/assets")).first { it.exists() }
        assertTrue(assets.walkTopDown().none { it.name.startsWith("libsherpa") || it.name.endsWith(".int8.onnx") || it.name == "voice.bin" ||
            it.name == "unicode_indexer.bin" || it.name.startsWith("supertonic") })
        val jni = listOf(File("src/main/jniLibs"), File("app/src/main/jniLibs"))
        assertTrue(jni.none { it.exists() && it.walkTopDown().any { f -> f.name.contains("sherpa") } })
        // Android 14+: System.load से पहले read-only
        val e = src("voice/engine/SupertonicTtsEngine.kt")
        assertTrue(e.indexOf("setReadOnly()") in 1 until e.indexOf("System.load(f.absolutePath)"))
    }
}

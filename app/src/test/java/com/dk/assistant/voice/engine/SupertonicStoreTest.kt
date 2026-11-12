package com.dk.assistant.voice.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** नवंबर 2026: Supertonic 3 की फ़ाइलें / पते / ABI / आवाज़ें - सब बिना Android के जाँच (Piper पूरी तरह हटाया) */
class SupertonicStoreTest {
    private fun src(rel: String): String = listOf(File("src/main/java/com/dk/assistant/$rel"), File("app/src/main/java/com/dk/assistant/$rel"))
        .first { it.exists() }.readText()

    @Test fun fourNaturalFemaleVoicesDefaultFirst() {
        // मालिक: "फ़ीमेल वॉइस, नेचुरल - तीन चार डाल दो"
        assertEquals(4, SupertonicStore.VOICES.size)
        assertEquals(SupertonicStore.VOICES.size, SupertonicStore.VOICES.map { it.id }.toSet().size)
        assertEquals(SupertonicStore.VOICES.size, SupertonicStore.VOICES.map { it.sid }.toSet().size)
        assertTrue(SupertonicStore.VOICES.all { it.sid in 0..4 && it.title.contains("महिला") })     // sid 0-4 = महिला (F1-F5)
        assertEquals("hi-hq-f1-meera", SupertonicStore.DEFAULT_VOICE)
        assertEquals(100, SupertonicStore.SPEED_DEF); assertEquals(100, SupertonicStore.PITCH_DEF)
        assertEquals(0, SupertonicStore.BASS_DEF); assertEquals(100, SupertonicStore.PAUSE_DEF)
    }

    @Test fun savedChoiceKeptRemovedPiperVoiceFallsBack() {
        assertEquals("hi-hq-f3-siya", SupertonicStore.pickDefault("hi-hq-f3-siya"))
        assertEquals("hi-hq-f4-tara", SupertonicStore.pickDefault("hi-hq-f4-tara"))
        assertEquals(SupertonicStore.DEFAULT_VOICE, SupertonicStore.pickDefault(null))
        assertEquals(SupertonicStore.DEFAULT_VOICE, SupertonicStore.pickDefault("hi_IN-priyamvada-medium"))   // हटाई Piper आवाज़
        assertEquals("hi-hq-f2-anaya", SupertonicStore.carryVoice("hi-hq-f2-anaya"))
        assertNull(SupertonicStore.carryVoice("hi_IN-rohan-medium"))
        assertNull(SupertonicStore.carryVoice(null))
        // पुराना स्विच ON सिर्फ़ तब, जब Supertonic सच में फ़ोन में था
        assertTrue(SupertonicStore.carryOn(oldOn = true, installed = true))
        assertFalse(SupertonicStore.carryOn(oldOn = true, installed = false))
        assertFalse(SupertonicStore.carryOn(oldOn = false, installed = true))
    }

    @Test fun modelFilesPinnedAndFromInternet() {
        assertEquals(7, SupertonicStore.FILES.size)
        assertTrue(SupertonicStore.FILES.all { it.sha256.length == 64 && it.size > 0 })
        assertTrue(SupertonicStore.MODEL_SIZE in 140_000_000L..150_000_000L)
        assertEquals(40, SupertonicStore.REV.length)
        assertEquals("https://huggingface.co/${SupertonicStore.REPO}/resolve/${SupertonicStore.REV}/voice.bin", SupertonicStore.fileUrl("voice.bin"))
        assertEquals("a%2Bb", SupertonicStore.enc("a+b"))
        assertEquals("supertonic-3", SupertonicStore.MODEL_DIR)
    }

    @Test fun abiFromAppNativeDirFirst() {
        // x86_64 emulator पर arm64 ऐप (translation) = arm64 वाली .so
        assertEquals("arm64-v8a", SupertonicStore.abiFor("/data/app/x/lib/arm64", listOf("x86_64", "arm64-v8a"))?.abi)
        assertEquals("x86_64", SupertonicStore.abiFor("/data/app/x/lib/x86_64", listOf("x86_64"))?.abi)
        assertEquals("armeabi-v7a", SupertonicStore.abiFor("/data/app/x/lib/arm", listOf("armeabi-v7a"))?.abi)
        assertEquals("arm64-v8a", SupertonicStore.abiFor(null, listOf("arm64-v8a", "armeabi-v7a"))?.abi)
        assertEquals("armeabi-v7a", SupertonicStore.abiFor("", listOf("x86", "armeabi-v7a"))?.abi)
        assertNull(SupertonicStore.abiFor(null, listOf("x86", "mips")))
        for (l in SupertonicStore.LIBS) { assertEquals(64, l.sha256.length); assertTrue(l.zipEnd in l.size / 3..SupertonicStore.AAR_SIZE) }
        assertTrue(SupertonicStore.AAR_URL.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/"))
    }

    @Test fun piperFullyRemoved() {
        // मालिक: "Piper पर TTS को पूरी तरह से हटा दो"
        val dir = listOf(File("src/main/java/com/dk/assistant"), File("app/src/main/java/com/dk/assistant")).first { it.exists() }
        for (n in listOf("PiperStore", "PiperTtsEngine", "PiperDownload", "PiperSpeed", "PiperTiming", "PiperFx"))
            assertFalse(n, File(dir, "voice/engine/$n.kt").exists())
        assertFalse(File(dir, "SettingsPiper.kt").exists())
        val store = src("voice/engine/SupertonicStore.kt"); val dl = src("voice/engine/SupertonicDownload.kt"); val e = src("voice/engine/SupertonicTtsEngine.kt")
        // सिर्फ़ कोड (टिप्पणी में "Piper हटाया" वाला इतिहास ठीक है)
        fun code(s: String) = s.lines().map { it.trim() }.filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
            .joinToString("\n") { it.substringBefore(" // ") }
        for (bad in listOf("espeak-ng-data", "vits-piper", "OfflineTtsVitsModelConfig", "tokens.txt", "priyamvada"))
            for ((n, s) in listOf("store" to store, "download" to dl, "engine" to e)) assertFalse("$n: $bad", code(s).contains(bad))
        assertTrue(e.contains("supertonic = OfflineTtsSupertonicModelConfig("))
        // पहले से उतरा Supertonic मॉडल नई जगह (दोबारा डाउनलोड नहीं), बाकी Piper फ़ाइलें मिटें
        assertTrue(store.contains("oldModel.renameTo(modelDir(app))"))
        assertTrue(store.contains("gone.deleteRecursively()"))
        // Settings card / खोज में Piper नहीं
        assertFalse(src("SettingsSupertonic.kt").contains("\"Piper"))
        assertFalse(src("SettingsSearch.kt").contains("\"Piper आवाज़\""))
    }
}

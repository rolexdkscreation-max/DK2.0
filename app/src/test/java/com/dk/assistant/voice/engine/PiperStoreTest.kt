package com.dk.assistant.voice.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** नवंबर 2026: Piper की फ़ाइलें / पते / ABI - सब बिना Android के जाँच */
class PiperStoreTest {
    @Test fun defaultIsHindiMedium() {
        assertEquals("hi_IN-priyamvada-medium", PiperStore.DEFAULT_VOICE)
        assertTrue(PiperStore.VOICES.all { it.id.startsWith("hi_IN-") && it.id.endsWith("-medium") })
        assertTrue(PiperStore.VOICES.all { it.rev.length == 40 && it.onnxSha256.length == 64 && it.onnxSize > 50_000_000 })
        assertEquals(3, PiperStore.VOICES.map { it.id }.toSet().size)
        assertEquals(100, PiperStore.SPEED_DEF); assertEquals(100, PiperStore.PITCH_DEF)
        assertEquals(0, PiperStore.BASS_DEF); assertEquals(100, PiperStore.PAUSE_DEF)
    }

    @Test fun espeakKeepsOnlyHindiAndEnglishDicts() {
        assertTrue(PiperStore.keepEspeak("espeak-ng-data/hi_dict"))
        assertTrue(PiperStore.keepEspeak("espeak-ng-data/en_dict"))
        assertFalse(PiperStore.keepEspeak("espeak-ng-data/fr_dict"))
        assertFalse(PiperStore.keepEspeak("espeak-ng-data/cmn_dict"))
        assertTrue(PiperStore.keepEspeak("espeak-ng-data/phontab"))
        assertTrue(PiperStore.keepEspeak("espeak-ng-data/lang/inc/hi"))
        assertTrue(PiperStore.keepEspeak("espeak-ng-data/voices/!v/Mr serious"))
        assertFalse(PiperStore.keepEspeak("hi_IN-priyamvada-medium.onnx"))
        assertFalse(PiperStore.keepEspeak("tokens.txt"))
    }

    @Test fun abiFromAppNativeDirFirst() {
        // x86_64 emulator पर arm64 ऐप (translation) = arm64 वाली .so
        assertEquals("arm64-v8a", PiperStore.abiFor("/data/app/x/lib/arm64", listOf("x86_64", "arm64-v8a"))?.abi)
        assertEquals("x86_64", PiperStore.abiFor("/data/app/x/lib/x86_64", listOf("x86_64"))?.abi)
        assertEquals("armeabi-v7a", PiperStore.abiFor("/data/app/x/lib/arm", listOf("armeabi-v7a"))?.abi)
        assertEquals("arm64-v8a", PiperStore.abiFor(null, listOf("arm64-v8a", "armeabi-v7a"))?.abi)
        assertEquals("armeabi-v7a", PiperStore.abiFor("", listOf("x86", "armeabi-v7a"))?.abi)
        assertNull(PiperStore.abiFor(null, listOf("x86", "mips")))
        for (l in PiperStore.LIBS) { assertEquals(64, l.sha256.length); assertTrue(l.zipEnd in l.size / 3..PiperStore.AAR_SIZE) }
        assertTrue(PiperStore.AAR_URL.startsWith("https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/"))
    }

    @Test fun urlsAreEncodedAndPinned() {
        val v = PiperStore.VOICES.first()
        assertEquals("https://huggingface.co/csukuangfj/vits-piper-hi_IN-priyamvada-medium/resolve/ec05e05b3eae80817ea62258d13f4d42e46cd1c2/espeak-ng-data/voices/%21v/Mr%20serious",
            PiperStore.fileUrl(v, "espeak-ng-data/voices/!v/Mr serious"))
        assertEquals("hi_IN-priyamvada-medium.onnx", v.onnxName)
        assertTrue(PiperStore.treeUrl(v).contains("/tree/${v.rev}/espeak-ng-data?recursive=true"))
        assertEquals("a%2Bb", PiperStore.enc("a+b"))
    }
}

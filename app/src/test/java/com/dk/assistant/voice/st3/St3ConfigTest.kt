package com.dk.assistant.voice.st3

import com.dk.assistant.plus.models.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 19 नवंबर 2026 (चरण 1): नई आवाज़ की डाउनलोड सूची - सिर्फ़ महिला आवाज़ें, हर URL तय commit पर, APK में कुछ नहीं */
class St3ConfigTest {
    @Test fun onlyFemaleVoices_F1toF5_defaultF1() {
        assertEquals(listOf("F1", "F2", "F3", "F4", "F5"), St3Config.VOICES.map { it.id })
        assertTrue(St3Config.ALL.none { a -> a.urls.any { Regex("""/M\d\.json""").containsMatchIn(it) } })
        assertTrue(St3Config.ALL.none { it.id.contains("_m") || it.fileName.contains("_m") })
        assertEquals("F1", St3Config.DEFAULT_VOICE)
        assertEquals("F1", St3Config.voice(null).id)
        assertEquals("F1", St3Config.cleanVoice("M1"))          // पुरुष आवाज़ माँगो तब भी नहीं
        assertEquals("F3", St3Config.cleanVoice("F3"))
    }

    @Test fun everyUrl_pinnedToOneCommit_https_withSha() {
        assertEquals(40, St3Config.COMMIT.length)
        for (a in St3Config.ALL) {
            assertEquals(a.id, 1, a.urls.size)
            assertTrue(a.urls[0], a.urls[0].startsWith("https://huggingface.co/Supertone/supertonic-3/resolve/${St3Config.COMMIT}/"))
            assertFalse(a.urls[0], a.urls[0].contains("/main/"))
            assertEquals(64, a.sha256.length)
            assertTrue(a.bytes > 0)
            assertEquals(ModelCatalog.Kind.TTS, a.kind)
        }
        assertEquals(emptyList<String>(), ModelCatalog.problems())
        assertTrue(St3Config.ALL.all { it in ModelCatalog.ALL })
    }

    @Test fun fileNames_neverWipedByOldCleanup() {
        // पुराने हटाए गए इंजन की सफ़ाई "supertonic"/"sherpa" से शुरू होने वाली चीज़ें मिटाती है - नई फ़ाइलें उनसे अलग
        for (a in St3Config.ALL) {
            assertTrue(a.fileName, a.fileName.startsWith("st3_"))
            assertFalse(com.dk.assistant.voice.engine.VoiceEngines.isOldOffline(a.fileName))
        }
        assertEquals(St3Config.ALL.size, St3Config.ALL.map { it.fileName }.toSet().size)
        assertEquals(St3Config.ALL.size, St3Config.ALL.map { it.id }.toSet().size)
    }

    @Test fun defaults_andLimits() {
        // 25 नवंबर 2026: मालिक ने spec बदली ("ऑफ़लाइन आवाज़ थोड़ा और फ़ास्ट") - 1.05 -> 1.12
        assertEquals(1.12f, St3Config.SPEED_DEF, 0f)
        assertEquals(8, St3Config.STEPS_DEF)
        assertEquals("hi", St3Config.LANG)
        assertEquals(44_100, St3Config.SAMPLE_RATE)
        assertEquals(St3Config.SPEED_MAX, St3Config.cleanSpeed(9f), 0f)
        assertEquals(St3Config.SPEED_MIN, St3Config.cleanSpeed(0.1f), 0f)
        assertEquals(St3Config.SPEED_DEF, St3Config.cleanSpeed(Float.NaN), 0f)
        assertEquals(St3Config.STEPS_MIN, St3Config.cleanSteps(0))
        assertEquals(St3Config.STEPS_MAX, St3Config.cleanSteps(99))
        assertTrue(St3Config.coreBytes() > 300L * 1024 * 1024)
    }

    @Test fun askOnMobileData_onlyForBigFiles() {
        assertTrue(St3Config.askBeforeDownload(metered = true, bytesLeft = St3Config.coreBytes()))
        assertFalse(St3Config.askBeforeDownload(metered = false, bytesLeft = St3Config.coreBytes()))
        assertFalse(St3Config.askBeforeDownload(metered = true, bytesLeft = 300_000))   // एक आवाज़ ~300 KB
    }

    @Test fun pcmAndSilence() {
        val b = St3Synth.pcm16(floatArrayOf(0f, 1f, -1f, 2f))
        assertEquals(8, b.size)
        assertEquals(0x7fff, (b[2].toInt() and 0xff) or (b[3].toInt() shl 8))
        assertEquals(-32767, ((b[4].toInt() and 0xff) or (b[5].toInt() shl 8)).toShort().toInt())
        assertEquals(St3Synth.pcm16(floatArrayOf(1f)).toList(), b.copyOfRange(6, 8).toList())   // 2.0 -> 1.0 पर रुका
        assertEquals(44_100 * 3 / 10 * 2, St3Synth.silence(300).size)
    }

    @Test fun noOldBannedNames() {
        val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.exists() }
        val code = java.io.File(root, "com/dk/assistant/voice/st3").listFiles()!!.joinToString("\n") { it.readText() }
        for (bad in listOf("SupertonicTtsEngine", "SupertonicStore", "HumanVoice", "com.k2fsa")) assertFalse(bad, code.contains(bad))
        assertFalse(code.contains("AIza"))                     // कोई API key नहीं
    }
}

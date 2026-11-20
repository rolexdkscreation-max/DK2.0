package com.dk.assistant.voice.st3

import com.dk.assistant.plus.models.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 20 नवंबर 2026: Android के लिए तेज़ मॉडल - तय commit, SHA, अलग फ़ाइल-नाम, पुराना कभी अपने-आप नहीं मिटता */
class St3FastTest {
    @Test fun fastAssets_pinned_withSha_ownNames() {
        assertEquals(40, St3Config.FAST_COMMIT.length)
        assertEquals(3, St3Config.FAST.size)
        for (a in St3Config.FAST) {
            assertEquals(1, a.urls.size)
            assertTrue(a.urls[0], a.urls[0].startsWith("https://huggingface.co/${St3Config.FAST_REPO}/resolve/${St3Config.FAST_COMMIT}/"))
            assertFalse(a.urls[0].contains("/main/"))
            assertEquals(64, a.sha256.length)
            assertTrue(a.fileName, a.fileName.startsWith("st3fast_"))
            assertFalse(com.dk.assistant.voice.engine.VoiceEngines.isOldOffline(a.fileName))
            assertTrue(a in ModelCatalog.ALL)
            assertEquals(ModelCatalog.Kind.TTS, a.kind)
        }
        assertEquals(emptyList<String>(), ModelCatalog.problems())
        // पुराने (धीमे) फ़ाइल-नामों से टकराव नहीं - दोनों साथ रह सकते हैं
        assertTrue(St3Config.FAST.none { f -> St3Config.CORE.any { it.fileName == f.fileName || it.id == f.id } })
    }

    @Test fun fastSet_smallerThanOld_sharesSmallFiles() {
        assertEquals(St3Config.SHARED + St3Config.FAST, St3Config.FAST_SET)
        assertTrue(St3Config.FAST_SET.sumOf { it.bytes } < 200L * 1024 * 1024)
        assertTrue(St3Config.FAST_SET.sumOf { it.bytes } < St3Config.coreBytes() / 2)
        assertEquals(St3Config.CORE.toSet(), (St3Config.SHARED + St3Config.SLOW).toSet())
    }

    @Test fun code_neverAutoDeletesOldModel_andFallsBack() {
        val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.exists() }
        val eng = java.io.File(root, "com/dk/assistant/voice/st3/St3Engine.kt").readText()
        assertTrue(eng.contains("St3Store.setFastBad(c, true)"))
        assertTrue(eng.contains("openFiles(St3Store.files(c, false), n, xnn)"))
        val voice = java.io.File(root, "com/dk/assistant/voice/engine/St3VoiceEngine.kt").readText()
        assertTrue(voice.contains("isActiveNetworkMetered"))
        assertFalse(voice.contains("ModelManager.delete"))
        assertFalse(eng.contains("ModelManager.delete"))
    }
}

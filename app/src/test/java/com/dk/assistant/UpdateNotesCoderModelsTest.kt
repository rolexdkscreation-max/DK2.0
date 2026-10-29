package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v3.6 (owner के नियम): अपडेट में सिर्फ़ असली बदलाव; हर 6 घंटे अपडेट जाँच + नोटिफ़िकेशन;
 * कोडिंग टीम - तेज़ मॉडल (gemini-3.8-flash) कोड वाले एजेंट, हल्का (gemini-3.5-flash-lite) बाकी; मैनेजर पहले सवालों का जवाब.
 */
class UpdateNotesCoderModelsTest {
    private fun src(name: String) = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()
    private fun root(name: String) = listOf(File("../$name"), File(name)).first { it.exists() }.readText()

    // ------------------------------------------------------------------ अपडेट notes
    @Test fun notesDropMergeTestsBackupAndFiles() {
        val raw = """
            • Merge pull request #130 from rolexdkscreation-max/feature/onekey-bt-volume-memory
            • GeminiLive: speaker boost का नाम spkBoost (पुराने MicBoost वाले boost से टकराव)
            • [software] DK लाइव डिफ़ॉल्ट + बीच में सवाल कतार
            • Tests: OneKeyAudioMemoryTest
            • backup/before-onekey-audio-memory
            • AudioFix.kt में SpeakerBoost जोड़ा
            • val x = foo()
            • DK लाइव डिफ़ॉल्ट + बीच में सवाल कतार
        """.trimIndent()
        val out = ReleaseNotes.clean(raw)
        assertFalse(out.contains("Merge pull request"))
        assertFalse(out.contains("Tests:"))
        assertFalse(out.contains("backup/"))
        assertFalse(out.contains(".kt"))
        assertFalse(out.contains("foo()"))
        assertFalse(out.contains("[software]"))
        assertEquals(1, out.lines().count { it.contains("DK लाइव डिफ़ॉल्ट") })   // दोहराव नहीं
        assertTrue(out.lines().all { it.startsWith("• ") })
    }

    @Test fun notesKeepUserLinesAndShortenLongTaskText() {
        assertEquals("एक key = सिर्फ़ बातचीत • Bluetooth पक्का", ReleaseNotes.line("• एक key = सिर्फ़ बातचीत • Bluetooth पक्का"))
        val long = "अपडेट स्क्रीन में सिर्फ़ असली बदलाव दिखेंगे। " + "और बहुत लंबा काम का विवरण ".repeat(10)
        val l = ReleaseNotes.line(long)!!
        assertTrue(l.length <= ReleaseNotes.MAX_LEN + 1)
        assertTrue(l.startsWith("अपडेट स्क्रीन में सिर्फ़ असली बदलाव दिखेंगे"))
        assertEquals(ReleaseNotes.FALLBACK, ReleaseNotes.clean("• Merge pull request #1 from x/y\n• Tests: abc"))
        assertEquals(ReleaseNotes.FALLBACK, ReleaseNotes.clean(""))
    }

    @Test fun appCleansNotesEverywhere() {
        val u = src("Updater.kt")
        assertTrue(u.contains("ReleaseNotes.clean(raw)"))
        assertTrue(u.contains("ReleaseNotes.clean(sp.getString(\"upd_notes_"))
        assertTrue(src("MainActivity.kt").contains("ReleaseNotes.clean("))
    }

    @Test fun releaseWorkflowUsesRealNotes() {
        val wf = root(".github/workflows/build.yml")
        assertTrue(wf.contains(".github/scripts/release_notes.py"))
        assertFalse(wf.contains("git log --pretty='• %s' \$RANGE"))
        val py = root(".github/scripts/release_notes.py")
        assertTrue(py.contains("अपडेट में क्या नया"))
        assertTrue(py.contains("merge pull request"))
    }

    // ------------------------------------------------------------------ 6 घंटे + नोटिफ़िकेशन
    @Test fun notifyOncePerNewVersion() {
        assertTrue(ReleaseNotes.shouldNotify(336, 335, 0))
        assertFalse(ReleaseNotes.shouldNotify(336, 335, 336))      // पहले बता चुके
        assertFalse(ReleaseNotes.shouldNotify(335, 335, 0))        // नया नहीं
        assertTrue(ReleaseNotes.shouldNotify(337, 335, 336))
    }

    @Test fun backgroundCheckEverySixHours() {
        assertEquals(6L * 60 * 60 * 1000, UpdateJob.EVERY_MS)
        val j = src("UpdateJob.kt")
        assertTrue(j.contains("setPersisted(true)"))
        assertTrue(j.contains("Updater.notifyNew"))
        assertTrue(src("DKApp.kt").contains("UpdateJob.schedule(this)"))
        val m = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() }.readText()
        assertTrue(m.contains(".UpdateJob") && m.contains("BIND_JOB_SERVICE"))
        assertTrue(m.contains("RECEIVE_BOOT_COMPLETED") && m.contains("POST_NOTIFICATIONS"))
    }

    // ------------------------------------------------------------------ कोडिंग टीम के मॉडल
    @Test fun modelsPerAgent() {
        assertEquals("gemini-3.8-flash", GeminiModels.CODER)
        assertEquals("gemini-3.5-flash-lite", GeminiModels.LIGHT)
        assertEquals(GeminiModels.LIGHT, GeminiModels.LIGHT_CHAIN.first())
        for (a in listOf(Seven.Agent.CODER, Seven.Agent.DEBUGGER, Seven.Agent.PLANNER, Seven.Agent.REVIEWER))
            assertEquals("F", TeamModels.modelFor(a, "F", "L"))
        for (a in Seven.Agent.values().filterNot { TeamModels.fast(it) })
            assertEquals("L", TeamModels.modelFor(a, "F", "L"))
        assertFalse(TeamModels.fast(Seven.Agent.PROMPT))
        assertFalse(TeamModels.fast(Seven.Agent.MANAGER))
        assertTrue(src("Prefs.kt").contains("coder_light_model"))
        val r = src("SevenRunner.kt")
        assertTrue(r.contains("withModel(") && r.contains("Prefs.coderLightModel(c)"))
        assertTrue(src("CodingAgent.kt").contains("fun withModel("))
    }

    @Test fun managerAnswersQuestionsFirst() {
        assertTrue(Seven.Prompts.MANAGER.contains("\"reply\""))
        assertTrue(src("SevenTeam.kt").contains("reply = reply0"))
        val u = SevenPlus.Understanding(Seven.Kind.values().first(), "b", "समझा", "", emptyList(), emptyList(), emptyList(), emptyList(),
            reply = "हाँ, यह होगा")
        val h = u.hindi()
        assertTrue(h.startsWith("👔 मैनेजर — आपके सवालों का जवाब:\nहाँ, यह होगा"))
        assertTrue(SevenTeam.PROMPT_MAP_CHARS <= 30_000)
    }
}

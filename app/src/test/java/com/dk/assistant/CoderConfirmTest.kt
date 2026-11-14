package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 14 नवंबर 2026 (मालिक): कोडिंग एजेंट पहले समझे, बताए, हाँ के बाद काम; PR जाँच समझ से पहले नहीं */
class CoderConfirmTest {
    @Test fun neverStartsWithoutYes() {
        assertFalse(CoderConfirm.autoStart(solo = true, needsAnswer = false))
        assertFalse(CoderConfirm.autoStart(solo = true, needsAnswer = true))
        assertFalse(CoderConfirm.autoStart(solo = false, needsAnswer = false))
    }

    @Test fun repoDoctorKeywordsDoNotHijackSevenAgent() {
        assertFalse(CoderConfirm.keywordStatus(seven = true))
        assertTrue(CoderConfirm.keywordStatus(seven = false))
    }

    @Test fun understandingChoicesHaveYesChangeCancel() {
        val ch = FollowUp.forUnderstanding(emptyList(), emptyList())
        assertTrue(ch.first().label.contains("हाँ"))
        assertTrue(ch.any { it.act == FollowUp.Act.TYPE })
        assertTrue(ch.any { it.text == "रद्द" })
    }

    @Test fun popupMessage() {
        val m = CoderConfirm.message("होम पर नया बटन जोड़ना है", "", listOf("बटन", "रंग"), emptyList())
        assertTrue(m.startsWith("होम पर नया बटन"))
        assertTrue(m.contains("1. बटन") && m.contains("2. रंग"))
        assertTrue(m.contains("हाँ"))
        val q = CoderConfirm.message("", "brief", emptyList(), listOf("कौन सी स्क्रीन?"))
        assertTrue(q.startsWith("brief") && q.contains("कौन सी स्क्रीन?"))
        assertEquals("❓ काम से पहले एक बात साफ़ कीजिए", CoderConfirm.title(true))
    }

    @Test fun cloneNameParsing() {
        assertEquals("rolexdkscreation-max" to "DK2.0", RepoCloneCore.parse("https://github.com/rolexdkscreation-max/DK2.0"))
        assertEquals("a" to "b", RepoCloneCore.parse("a/b"))
        assertEquals("a" to "b", RepoCloneCore.parse("github.com/a/b.git"))
        assertEquals("a" to "b", RepoCloneCore.parse("git@github.com:a/b.git"))
        assertEquals("a" to "b", RepoCloneCore.parse("https://github.com/a/b/tree/main/src"))
        assertEquals(null, RepoCloneCore.parse("only"))
        assertEquals(null, RepoCloneCore.parse("../x"))
        assertEquals(null, RepoCloneCore.parse("a b/c"))
        assertEquals("DK2.0-rolexdkscreation-max", RepoCloneCore.folder("rolexdkscreation-max", "DK2.0"))
    }

    @Test fun cloneSummary() {
        val d = File(System.getProperty("java.io.tmpdir"), "rc_" + System.nanoTime()).apply { mkdirs() }
        try {
            File(d, "app/src").mkdirs()
            File(d, "app/src/A.kt").writeText("fun a() {}")
            File(d, "app/src/B.kt").writeText("fun b() {}")
            File(d, "README.md").writeText("# hi")
            File(d, ".dkai").mkdirs(); File(d, ".dkai/config.json").writeText("{}")
            val s = RepoCloneCore.summary(d)
            assertEquals(3, s.files)
            assertEquals("Kotlin" to 2, s.langs.first())
            assertEquals(listOf("app/", "README.md"), s.tops)
            val r = RepoCloneCore.report("a/b", s, d.path)
            assertTrue(r.contains("3 फ़ाइलें") && r.contains("Kotlin 2"))
            assertTrue(RepoCloneCore.checkTask("a/b", s).contains("कोई बदलाव नहीं"))
        } finally { d.deleteRecursively() }
    }
}

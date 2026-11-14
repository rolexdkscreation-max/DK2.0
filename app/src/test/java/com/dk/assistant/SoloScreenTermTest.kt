package com.dk.assistant

import com.dk.assistant.dkai.DkaiTermFix
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 14 नवंबर 2026 (मालिक): स्क्रीन देखो = तुरंत, टर्मिनल error खुद ठीक + हर कदम, Solo = सिर्फ़ "DK एजेंट" */
class SoloScreenTermTest {
    private fun src(p: String) = File("src/main/java/com/dk/assistant/$p").let { if (it.exists()) it else File("app/src/main/java/com/dk/assistant/$p") }.readText()

    // ------------------------------------------------------------ स्क्रीन
    @Test fun lookScreenAtOnceNoShareQuestion() {
        assertTrue(ScreenSeeRule.TEXT.contains("look_screen IMMEDIATELY"))
        assertTrue(ScreenSeeRule.TEXT.contains("NEVER ask"))
        assertFalse("पुराना नियम (पहले शेयर पूछो) हटा", src("BrainManager.kt").contains("and it is OFF, ask"))
        assertTrue(src("BrainManager.kt").contains("ScreenSeeRule.TEXT"))
        val setup = src("GeminiLiveSetup.kt")
        assertFalse(setup.contains("sharing is OFF, first ask"))
        assertTrue(setup.contains("call THIS immediately - never ask"))
        assertTrue(ScreenLook.asked("स्क्रीन देखो"))
        assertTrue(ScreenLook.asked("देखो क्या दिख रहा है"))
    }

    // ------------------------------------------------------------ टर्मिनल
    @Test fun jobContinuesWithoutNewYesButNotRisky() {
        val t = 1_000_000L
        val j = DkaiTermFix.Job(t)
        assertTrue(DkaiTermFix.canContinue(j, risky = false, now = t + 60_000))
        assertFalse("खतरनाक कमांड हर बार पूछे", DkaiTermFix.canContinue(j, risky = true, now = t + 60_000))
        assertFalse(DkaiTermFix.canContinue(null, false, t))
        assertFalse(DkaiTermFix.canContinue(j, false, t + DkaiTermFix.JOB_WINDOW_MS + 1))
        assertFalse(DkaiTermFix.canContinue(j.copy(fails = DkaiTermFix.MAX_FIXES), false, t))
        assertFalse(DkaiTermFix.canContinue(j.copy(steps = DkaiTermFix.MAX_STEPS), false, t))
        val f1 = DkaiTermFix.after(j, failed = true); assertEquals(1, f1.fails); assertEquals(1, f1.steps)
        assertEquals("सफल कदम = fail गिनती शून्य", 0, DkaiTermFix.after(f1, failed = false).fails)
    }

    @Test fun failureIsDiagnosed() {
        assertTrue(DkaiTermFix.failed(1, ""))
        assertTrue(DkaiTermFix.failed(0, "apk build: x में index.html नहीं. पहले index.html बनाएँ"))
        assertFalse(DkaiTermFix.failed(0, "a.txt\nb.txt\n"))
        assertTrue(DkaiTermFix.diagnose("apk build site", "apk build: site में index.html नहीं")!!.fix.contains("apk make"))
        assertTrue(DkaiTermFix.diagnose("npm install", "sh: npm: not found")!!.why.contains("npm"))
        assertTrue(DkaiTermFix.diagnose("cat x.txt", "cat: x.txt: No such file or directory")!!.fix.contains("ls"))
        assertTrue(DkaiTermFix.diagnose("./run.sh", "sh: ./run.sh: Permission denied")!!.fix.contains("sh script.sh"))
        assertNull(DkaiTermFix.diagnose("ls", "weird"))
    }

    @Test fun everyStepIsTold() {
        val j = DkaiTermFix.Job(0L, steps = 2, fails = 1)
        assertTrue(DkaiTermFix.stepNote("ls", false, j, null).contains("ONE short Hindi line"))
        val fail = DkaiTermFix.stepNote("npm i", true, j, DkaiTermFix.diagnose("npm i", "npm: not found"))
        assertTrue(fail.contains("try 1 of 3")); assertTrue(fail.contains("call terminal again"))
        assertTrue(DkaiTermFix.stepNote("x", true, j.copy(fails = 3), null).startsWith("STOP"))
        assertTrue(DkaiTermFix.stepLine(j, true, null).contains("ठीक कर रहा है"))
        assertTrue(DkaiTermFix.stepLine(j, false, null).startsWith("✓ कदम 2"))
        val shell = src("dkai/DkaiShell.kt")
        assertTrue(shell.contains("DkaiTermFix.canContinue(job, DkaiTerm.risky(cmd), now)"))
        assertTrue(shell.contains("DkaiTermFix.stepNote("))
    }

    // ------------------------------------------------------------ Solo = DK एजेंट
    @Test fun soloShowsOnlyDkAgent() {
        val roles = listOf("मैनेजर", "प्लानर", "कोडर", "रिव्यूअर", "टेस्टर", "टीम", "सौंप", "👔", "🎓", "🔎")
        val samples = listOf(
            "यह रिपो / PR / काम की हालत का सवाल है → ${Seven.INSPECTOR_HI} को सौंप रहा हूँ (वह GitHub खुद जाँचकर बताएगा)",
            "यह सवाल है — जवाब मैं (मैनेजर) दे रहा हूँ, कोड नहीं बदलेगा",
            "यह समझाने का काम है → 🎓 समझाने वाला को सौंपा (असली कोड पढ़कर गहराई से समझाएगा, कोड नहीं बदलेगा)",
            "आपकी मंज़ूरी मिली — मंज़ूर की हुई समझ के हिसाब से काम सौंप रहा हूँ",
            "टेस्टर (QA जाँच) ने कमी बताई — वही कोडर पूरा करेगा (राउंड 2)",
            "टेस्टर (QA जाँच) ने कमी बताई — प्लानर से सुधार करवा रहा हूँ (राउंड 1)",
            "प्लानर अटका — रणनीति बदलकर छोटा प्लान माँग रहा हूँ",
            "✗ a.kt: वापस कोडर को (कोशिश 2)",
            "कोडिंग टीम: काम पूरा",
            Seven.NO_ANSWER_HI,
            "👔 मैनेजर काम बाँट रहा है")
        for (s in samples) {
            val c = SoloVoice.clean(s)
            for (r in roles) assertFalse("'$r' बचा: $c", c.contains(r))
        }
        assertEquals("🧠 DK एजेंट: ✗ a.kt: फिर से ठीक कर रहा हूँ (कोशिश 2)", SoloVoice.line("✗ a.kt: वापस कोडर को (कोशिश 2)"))
        assertEquals("मालिक की बात / आम बात वैसी ही", "बटन जोड़ो", SoloVoice.clean("बटन जोड़ो"))
        assertTrue(src("SevenRunner.kt").contains("SoloVoice.line(t)"))
        assertTrue(src("CodingActivity.kt").contains("SoloVoice.clean(t0)"))
    }
}

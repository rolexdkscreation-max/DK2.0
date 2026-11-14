package com.dk.assistant

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

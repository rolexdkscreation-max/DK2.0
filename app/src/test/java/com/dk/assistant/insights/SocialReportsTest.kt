package com.dk.assistant.insights

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SocialReportsTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    @Test fun newestFirst_andOverflow() {
        val names = listOf("100.md", "300.md", "200.md", "bad.txt", "x.md")
        assertEquals(listOf("300.md", "200.md", "100.md"), SocialReports.Rules.newestFirst(names))
        assertEquals(listOf("100.md"), SocialReports.Rules.overflow(names, 2))
        assertEquals("123.md", SocialReports.Rules.fileName(123))
    }

    @Test fun title_skipsSummaryHeading() {
        assertEquals("इंस्टाग्राम: रीच गिरी", SocialReports.Rules.title("## सार\n**इंस्टाग्राम: रीच गिरी**\nबाकी"))
        assertEquals("सोशल रिव्यू", SocialReports.Rules.title("  \n"))
    }

    @Test fun safe_blocksPaths() {
        assertTrue(SocialReports.Rules.safe("1700000000000.md"))
        assertFalse(SocialReports.Rules.safe("../1.md"))
        assertFalse(SocialReports.Rules.safe("a.md"))
    }

    @Test fun reviewPrompt_hasNewSections_andNeverClaimsDone() {
        val p = src("insights/InsightsCore.kt")
        assertTrue(p.contains("### फ़ॉलोअर ट्रेंड"))
        assertTrue(p.contains("### सबसे अच्छी और सबसे कमज़ोर पोस्ट"))
        assertTrue(p.contains("### व्यूज़ कहाँ से आ रहे हैं"))
        assertTrue(p.contains("### गलतियाँ"))
        assertTrue(p.contains("क्या मैं इनमें से कुछ ठीक कर दूँ?"))
        assertTrue(p.contains("NEVER says something was changed"))
    }

    @Test fun savedReportsGoToSettingsList() {
        assertTrue(src("insights/InsightsAgent.kt").contains("SocialReports.add(c, report)"))
        val s = src("SettingsSocial.kt")
        assertTrue(s.contains("socialReportsCard()"))
        assertTrue(s.contains("SocialReports.delete"))
    }
}

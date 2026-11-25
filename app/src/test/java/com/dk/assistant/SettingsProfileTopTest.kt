package com.dk.assistant

import com.dk.assistant.ui.VerifiedBadge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 25 नवंबर 2026 (मालिक): Settings में सबसे ऊपर असली नाम + verified टिक, प्रोफ़ाइल में नाम लिखते ही टिक */
class SettingsProfileTopTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    @Test fun headerNameAndStatus() {
        assertEquals(VerifiedBadge.ADD_NAME, VerifiedBadge.headerName("  "))
        assertEquals("धीरज कुमार सिंह", VerifiedBadge.headerName(" धीरज कुमार सिंह "))
        assertEquals("Verified • Owner • मालिक (Boss)", VerifiedBadge.statusLine("धीरज", VerifiedBadge.Tier.OWNER))
        assertEquals("Verified • Premium • प्रीमियम", VerifiedBadge.statusLine("धीरज", VerifiedBadge.Tier.PREMIUM))
        assertEquals("Verified • Basic • बेसिक", VerifiedBadge.statusLine("धीरज", VerifiedBadge.Tier.BASIC))
        assertTrue(VerifiedBadge.statusLine("धीरज", null).contains("लाइसेंस डालें"))
        assertTrue(VerifiedBadge.statusLine("", VerifiedBadge.Tier.PREMIUM).contains("नाम लिखते ही verified टिक"))
        assertTrue(VerifiedBadge.statusLine("", null).startsWith("टैप करें"))
    }

    @Test fun profileCardIsFirstOnSettingsMain() {
        val s = src("SettingsActivity.kt")
        val top = s.indexOf("profileTopCard()")
        assertTrue("मुख्य पन्ने पर प्रोफ़ाइल पट्टी", top > 0)
        assertTrue("Theme पट्टी से पहले = सबसे ऊपर", top < s.indexOf("menuRow(\"theme\""))
        val p = src("SettingsSystem.kt")
        val body = p.substringAfter("internal fun SettingsActivity.secProfile() {")
        assertTrue("प्रोफ़ाइल पन्ने में पहला कार्ड असली नाम", body.trimStart().startsWith("realNameCard()"))
        val t = src("SettingsProfileTop.kt")
        assertTrue(t.contains("putString(\"real_name\""))           // वही key जो DKAI Profile शीट में
        assertTrue(t.contains("VerifiedBadge.show(v, tier)"))      // लिखते ही टिक
        assertTrue(t.contains("putExtra(\"section\", \"profile\")"))  // टैप = प्रोफ़ाइल
    }
}

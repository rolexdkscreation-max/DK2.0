package com.dk.assistant.onboard

import com.dk.assistant.AssistantBrand
import com.dk.assistant.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OnboardingTest {
    @Test fun onlyFreshInstallWithoutNameSeesIntro() {
        assertTrue(OnboardingRules.shouldShow(onboarded = false, freshInstall = true, hasUserName = false))
        // पुराने उपयोगकर्ता (अपडेट) - कभी नहीं
        assertFalse(OnboardingRules.shouldShow(onboarded = false, freshInstall = false, hasUserName = false))
        assertFalse(OnboardingRules.shouldShow(onboarded = false, freshInstall = true, hasUserName = true))
        assertFalse(OnboardingRules.shouldShow(onboarded = true, freshInstall = true, hasUserName = false))
    }

    @Test fun fourPagesThenDone() {
        assertEquals(4, OnboardingRules.PAGES)
        assertEquals(1, OnboardingRules.next(0))
        assertEquals(3, OnboardingRules.next(2))
        assertNull(OnboardingRules.next(3))
    }

    @Test fun greetingByTime() {
        assertEquals("सुप्रभात, धीरज", Greeting.of(7, " धीरज "))
        assertEquals("नमस्ते", Greeting.of(14, ""))
        assertEquals("शुभ संध्या", Greeting.of(19, " "))
        assertEquals("नमस्ते", Greeting.of(2, ""))
        for (h in 0..23) assertTrue(Greeting.word(h).isNotBlank())
    }

    @Test fun aboutShowsOwner() {
        assertEquals("Created by धीरज कुमार सिंह", AssistantBrand.CREATED_BY)
    }

    @Test fun newStringsHaveEnglish() {
        val f = listOf(File("src/main/assets/ui_en.tsv"), File("app/src/main/assets/ui_en.tsv")).first { it.exists() }
        val m = UiText.parse(f.readText())
        for (k in listOf("आगे →", "चलो शुरू करें", "छोड़ें", "ऐप के बारे में", "निजता (Privacy)", "सुप्रभात", "शुभ संध्या", "परिचय दोबारा देखें"))
            assertTrue("अनुवाद नहीं: $k", m.containsKey(k))
    }
}

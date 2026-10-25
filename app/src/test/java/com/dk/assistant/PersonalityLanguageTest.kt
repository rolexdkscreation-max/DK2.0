package com.dk.assistant

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DK AI ब्रीफ़ Part 02/03: बात करने का तरीका, भाषा पहचान, Auto भाषा, मालिक का नाम */
class PersonalityLanguageTest {

    @After fun reset() { Lang.current = Lang.HINGLISH; Lang.detected = Lang.HINGLISH }

    // v3.69: 10 पर्सनैलिटी हटाईं (बैकअप backup/v3.69-settings) - अब सिर्फ़ अपना लिखा "बात करने का तरीका"
    @Test fun personaLine_onlyUserText() {
        assertEquals("", personaLine(""))
        assertEquals("", personaLine("   "))
        assertEquals(" Extra style instructions from the user: जवाब छोटे रखो.", personaLine("  जवाब छोटे रखो "))
    }

    @Test fun everyLanguage_buildsAPrompt() {
        for ((code, _) in Lang.CHOICES) {
            val sys = Lang.sys("You are DK." + personaLine("धीरे बोलो"), code)
            assertTrue(sys.contains("धीरे बोलो"))
            assertTrue(sys.contains("LANGUAGE (final rule"))
        }
    }

    @Test fun detector_cases() {
        assertEquals(LanguageDetector.Detected.HINDI, LanguageDetector.detect("आज मौसम कैसा है"))
        assertEquals(LanguageDetector.Detected.HINDI, LanguageDetector.detect("YouTube खोलो"))
        assertEquals(LanguageDetector.Detected.HINGLISH, LanguageDetector.detect("kya haal hai yaar"))
        assertEquals(LanguageDetector.Detected.HINGLISH, LanguageDetector.detect("youtube kholo"))
        assertEquals(LanguageDetector.Detected.HINGLISH, LanguageDetector.detect("mujhe kal subah 6 baje uthana please remind me"))
        assertEquals(LanguageDetector.Detected.ENGLISH, LanguageDetector.detect("what's the weather"))
        assertEquals(LanguageDetector.Detected.ENGLISH, LanguageDetector.detect("please open the camera and take a photo for me"))
        assertEquals(LanguageDetector.Detected.UNKNOWN, LanguageDetector.detect(""))
        assertEquals(LanguageDetector.Detected.UNKNOWN, LanguageDetector.detect("   "))
        assertEquals(LanguageDetector.Detected.UNKNOWN, LanguageDetector.detect(null))
        assertEquals(LanguageDetector.Detected.UNKNOWN, LanguageDetector.detect("123 456"))
    }

    @Test fun autoLanguage_followsTheUser_turnByTurn() {
        assertEquals(Lang.AUTO, Lang.norm("auto"))
        assertEquals(4, Lang.CHOICES.size)
        assertEquals(Lang.ALL, Lang.CHOICES.take(3))          // पुराना क्रम/ID वैसे ही
        assertTrue(Lang.rule(Lang.AUTO).contains("SAME language the user used"))
        assertTrue(Lang.rule(Lang.AUTO).contains("Never translate"))
        assertEquals("Auto", Lang.short(Lang.AUTO))

        Lang.current = Lang.AUTO
        Lang.noteUser("please turn on the torch")
        assertEquals(Lang.ENGLISH, Lang.effective())
        assertEquals("Torch on", Lang.say("टॉर्च चालू"))
        Lang.noteUser("टॉर्च जलाओ")
        assertEquals(Lang.HINDI, Lang.effective())
        assertEquals("टॉर्च चालू", Lang.say("टॉर्च चालू"))
        Lang.noteUser("")                                         // खाली = पिछली भाषा ही
        assertEquals(Lang.HINDI, Lang.effective())
        Lang.noteUser("torch jalao yaar")
        assertEquals(Lang.HINGLISH, Lang.effective())
        assertEquals("a", Lang.pick("a", "b"))
    }

    @Test fun fixedLanguage_ignoresDetection() {
        Lang.current = Lang.HINDI
        Lang.noteUser("please turn on the torch")
        assertEquals(Lang.HINDI, Lang.effective())
        assertEquals("टॉर्च चालू", Lang.say("टॉर्च चालू"))
    }

    @Test fun owner_isDefinedOnce_andUsedInCreatorAnswer() {
        assertEquals("धीरज कुमार सिंह", AssistantBrand.OWNER_NAME)
        assertEquals("DK", AssistantBrand.NAME)
        assertEquals("DK AI", AssistantBrand.FULL_NAME)
        assertTrue(AssistantBrand.CREATOR_ANSWER.contains(AssistantBrand.OWNER_NAME))
        assertEquals("मुझे धीरज कुमार सिंह जी ने बनाया है। मैं धीरज सर के लिए काम करता हूँ, वही मेरे बॉस हैं।", AssistantBrand.CREATOR_ANSWER)
        assertEquals(AssistantBrand.CREATOR_ANSWER, AgentRunner.CREATOR_ANSWER)
        assertEquals("Created by धीरज कुमार सिंह", AssistantBrand.CREATED_BY)
        assertEquals("blocked by admin धीरज कुमार सिंह", AssistantBrand.blockedByAdmin())
    }
}

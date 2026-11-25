package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 25 नवंबर 2026 (मालिक): हिंदी / English / Hinglish + Custom (दुनिया की कोई भी भाषा) */
class LangChoiceTest {
    @Test fun norm() {
        assertEquals(UiLang.EN, UiLang.norm(null))
        assertEquals(UiLang.HI, UiLang.norm("hi"))
        assertEquals(UiLang.HINGLISH, UiLang.norm("hien"))
        assertEquals("fr", UiLang.norm("fr"))
        assertEquals("zh-TW", UiLang.norm("zh-TW"))
        assertEquals(UiLang.EN, UiLang.norm("French!"))
        assertTrue(UiLang.custom("ta")); assertFalse(UiLang.custom(UiLang.HINGLISH))
        assertEquals("Hinglish", UiLang.label(UiLang.HINGLISH))
    }

    @Test fun worldList() {
        assertTrue(WorldLangs.ALL.size > 100)
        assertTrue(WorldLangs.ALL.none { it.code == "hi" || it.code == "en" })
        assertEquals("fr", WorldLangs.search("fren").first().code)
        assertEquals("ta", WorldLangs.search("tamil").first().code)
        assertTrue(WorldLangs.search("zzzzqq").isEmpty())
        assertEquals("Japanese", WorldLangs.english("ja"))
    }
}

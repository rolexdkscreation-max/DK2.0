package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.30: भाषा - Hinglish (शुरुआती) / हिंदी / English, हर AI prompt पर एक ही नियम */
class LangTest {
    @Test fun defaultIsHinglish() {
        assertEquals(Lang.HINGLISH, Lang.norm(null))
        assertEquals(Lang.HINGLISH, Lang.norm("गलत"))
        assertEquals(Lang.ENGLISH, Lang.norm("en"))
        assertEquals(Lang.HINDI, Lang.norm("hi"))
        assertEquals(3, Lang.ALL.size)
        assertEquals(Lang.HINGLISH, Lang.ALL.first().first)
    }

    @Test fun ruleIsAddedOnceAndLast() {
        val a = Lang.sys("You are DK.", Lang.ENGLISH)
        assertTrue(a.startsWith("You are DK."))
        assertTrue(a.contains("simple English"))
        assertEquals("दो बार नहीं", a, Lang.sys(a, Lang.HINDI))
        assertTrue(Lang.sys("x", Lang.HINDI).contains("pure, simple Hindi"))
        assertTrue(Lang.sys("x", Lang.HINGLISH).contains("Hinglish"))
    }

    @Test fun formatsAndTagsStayExact() {
        assertTrue(Lang.rule(Lang.ENGLISH).contains("JSON keys"))
    }

    @Test fun fixedLinesFollowLanguage() {
        assertEquals("Torch on", Lang.say("टॉर्च चालू", Lang.ENGLISH))
        assertEquals("टॉर्च चालू", Lang.say("टॉर्च चालू", Lang.HINGLISH))
        assertEquals("अनजान", Lang.say("अनजान", Lang.ENGLISH))
        assertEquals("b", Lang.pick("a", "b", Lang.ENGLISH))
        assertEquals("a", Lang.pick("a", "b", Lang.HINDI))
    }
}

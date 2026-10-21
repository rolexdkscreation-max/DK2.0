package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryPlanTest {
    @Test fun partsByLength() {
        assertEquals(1400, StoryPlan.words(10))
        assertEquals(4, StoryPlan.parts(10))
        assertEquals(7, StoryPlan.parts(20))
        assertEquals(10, StoryPlan.parts(30))
        assertEquals(1, StoryPlan.twists(10)); assertEquals(2, StoryPlan.twists(20)); assertEquals(3, StoryPlan.twists(30))
    }

    @Test fun requestsTellPartAndEnding() {
        val first = StoryPlan.request("", 10, 1, 4)
        assertTrue(first.contains("शीर्षक")); assertTrue(first.contains("खुद")); assertTrue(first.contains("part 1 of 4"))
        assertTrue(StoryPlan.request("x", 10, 4, 4).contains("LAST part"))
        assertTrue(StoryPlan.request("x", 10, 2, 4).contains("Start directly with a dialogue"))
    }

    @Test fun cleanRemovesHeadingsAndMarkdown() {
        val t = StoryPlan.clean("**भाग 2**\n\n## रामू (रोते हुए): काका, अब क्या होगा?\n\n\n\nनरेशन।")
        assertFalse(t.contains("भाग 2")); assertFalse(t.contains("**")); assertFalse(t.contains("#"))
        assertTrue(t.startsWith("रामू (रोते हुए)"))
    }

    @Test fun titleSplit() {
        val (title, rest) = StoryPlan.splitTitle("शीर्षक: माँ का कंगन\n\nरामू (रोते हुए): माँ कहाँ हो?")
        assertEquals("माँ का कंगन", title)
        assertTrue(rest.startsWith("रामू"))
        assertEquals("", StoryPlan.splitTitle("रामू: हाँ।").first)
    }

    @Test fun halfSentenceIsCut() {
        assertEquals("रामू: चलो घर चलें। सीता: हाँ।", StoryPlan.trimToSentence("रामू: चलो घर चलें। सीता: हाँ। काका ने धीरे से"))
        assertEquals("ठीक है।", StoryPlan.trimToSentence("ठीक है।"))
        assertEquals("वो बोला \"चलो!\"", StoryPlan.trimToSentence("वो बोला \"चलो!\""))
    }

    @Test fun promptIsOwnAndComplete() {
        assertTrue(StoryPrompt.SYSTEM.startsWith("You are \"Story Writer Agent\""))
        assertTrue(StoryPrompt.SYSTEM.contains("8. Output Format"))
    }
}

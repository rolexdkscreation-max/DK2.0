package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 22 नवंबर 2026 (मालिक): चैट में { "response": "..." } न दिखे, हाल-चाल पर खोज न हो, "बताइए जी boss," बीच में न हो */
class ReplyShapeTest {

    @Test fun unwrapsResponseJson() {
        val raw = "{ \"response\": \"आज रविवार, 22 नवंबर 2026 है [1]। बताइए, आपके लिए क्या करूँ?\" }"
        assertEquals("आज रविवार, 22 नवंबर 2026 है [1]। बताइए, आपके लिए क्या करूँ?", ReplyShape.unwrap(raw))
    }

    @Test fun unwrapsFencedAndOtherKeys() {
        assertEquals("नमस्ते", ReplyShape.unwrap("```json\n{\"answer\": \"नमस्ते\"}\n```"))
        assertEquals("ठीक है", ReplyShape.unwrap("{\"reply\":\"ठीक है\"}"))
        assertEquals("हाँ", ReplyShape.unwrap("{\"text\": \"हाँ\", \"lang\": \"hi\"}"))
    }

    @Test fun keepsPhoneWorkJsonAndPlainText() {
        val work = "{\"say\": \"खोल रहा हूँ\", \"actions\": [], \"done\": false}"
        assertEquals(work, ReplyShape.unwrap(work))
        assertEquals("सादा जवाब {1}", ReplyShape.unwrap("सादा जवाब {1}"))
        val need = "{\"need_search\": true}"
        assertEquals(need, ReplyShape.unwrap(need))
    }

    @Test fun brokenJsonStillUnwrapped() {
        assertEquals("आज \"गो फॉर ए राइड डे\" है", ReplyShape.unwrap("{ \"response\": \"आज \"गो फॉर ए राइड डे\" है\" }"))
    }

    @Test fun streamingHidesWrapper() {
        assertEquals("", ReplyShape.live("{"))
        assertEquals("", ReplyShape.live("{ \"respo"))
        assertEquals("आज रविवार", ReplyShape.live("{ \"response\": \"आज रविवार"))
        assertEquals("नमस्ते", ReplyShape.live("नमस्ते"))
    }

    @Test fun smallTalkNeverSearches() {
        for (q in listOf("और क्या कर रहे हो?", "और क्या कर रहे हो? मतलब और सब बताओ", "और सब बताओ", "कैसे हो भाई")) {
            assertTrue(q, SearchPolicy.smallTalk(q))
            assertFalse(q, SearchPolicy.needsSearch(q))
        }
        assertFalse(SearchPolicy.smallTalk("आज का मौसम कैसा है"))
    }

    @Test fun midSentenceNameDropped() {
        val n = NameRule.names("धीरज", "")
        assertEquals("बताइए, आपके लिए क्या सहायता करूँ?", NameRule.tidy("बताइए जी boss, आपके लिए क्या सहायता करूँ?", n))
        assertEquals("हाँ जी सर, हो गया।", NameRule.tidy("हाँ जी सर, हो गया।", n))
        assertEquals("जी धीरज, मैं यहाँ हूँ।", NameRule.tidy("जी धीरज, मैं यहाँ हूँ।", n))
    }
}

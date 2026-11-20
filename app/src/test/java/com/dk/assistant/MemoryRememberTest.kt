package com.dk.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 20 नवंबर 2026 (मालिक: "कोई भी चीज़ बोलूँ कि याद करके रख लो, तो रख ले - और कभी भी पूछूँ, याद में से ढूँढकर बताए"):
 *  1. "याद रख लो", "याद करके रख लो", "नोट कर लो" पहले पकड़ में नहीं आते थे.
 *  2. DK लाइव (पुरानी बातचीत सिर्फ़ माँगने पर - डिफ़ॉल्ट) में सिर्फ़ 20 पक्की बातें जाती थीं, और search_history सिर्फ़
 *     बातचीत ढूँढता था - बाकी पक्की बातें कभी नहीं मिलती थीं. अब वह पक्की बातें भी ढूँढता है.
 */
class MemoryRememberTest {
    private fun rem(s: String) = Memory.parse(s)?.takeIf { it.kind == "remember" }?.text

    @Test fun newWaysToSayRemember() {
        assertEquals("मेरी बाइक का नंबर MH15 AB 1234 है", rem("याद रख लो कि मेरी बाइक का नंबर MH15 AB 1234 है"))
        assertEquals("मेरी गाड़ी B2 पार्किंग में है", rem("मेरी गाड़ी B2 पार्किंग में है, इसे याद करके रख लो"))
        assertEquals("पापा का जन्मदिन 5 मार्च को है", rem("पापा का जन्मदिन 5 मार्च को है, याद कर के रखना"))
        assertEquals("कल 5 बजे डॉक्टर के पास जाना है", rem("नोट कर लो कल 5 बजे डॉक्टर के पास जाना है"))
        assertEquals("दवा रात 9 बजे लेनी है", rem("दवा रात 9 बजे लेनी है याद रख लेना"))
        // पुराने तरीके वैसे ही
        assertEquals("मेरी बाइक का नंबर MH12 AB 1234 है", rem("याद रखो मेरी बाइक का नंबर MH12 AB 1234 है"))
        assertEquals("मेरा शहर नासिक है", rem("मेरा शहर नासिक है, ये याद रखो"))
    }

    @Test fun onlyFillerIsNotSaved() {
        assertNull(Memory.parse("हाँ इसे याद करके रख लो"))
        assertNull(Memory.parse("इसे याद रखो"))
        assertNull(Memory.parse("याद रख लो"))
        assertTrue(Memory.onlyFiller("हाँ जी इसे"))
        assertTrue(!Memory.onlyFiller("बाइक का नंबर"))
    }

    @Test fun searchHistoryAlsoReturnsSavedFacts() {
        val empty = HistoryOnDemand.reply(emptyList(), HistoryOnDemand.Req(emptyList(), 0, 1, null, 5, ""))
        assertEquals(empty, HistoryOnDemand.withFacts(empty, emptyList()))
        val o = JSONObject(HistoryOnDemand.withFacts(empty, listOf("मेरी बाइक का नंबर MH15 AB 1234 है")))
        assertEquals("मेरी बाइक का नंबर MH15 AB 1234 है", o.getJSONArray("saved_facts").getString(0))
        assertTrue(o.getString("note").startsWith("SAVED FACTS found"))
        assertEquals(0, o.getInt("count"))
        assertTrue(HistoryOnDemand.RULE.contains("it also searches every saved fact on the phone"))
        assertTrue(HistoryOnDemand.TOOL_DESC.contains("EVERY fact the user asked DK to remember"))
        // पुराने नियम वैसे ही
        assertTrue(HistoryOnDemand.RULE.contains("NEVER call search_history for normal questions"))
    }

    @Test fun wiredInLiveAndChat() {
        val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.exists() }
        fun src(n: String) = java.io.File(root, "com/dk/assistant/$n").readText()
        assertTrue(src("GeminiLiveTools.kt").contains("HistoryOnDemand.withFacts(r, f)"))
        assertTrue(src("GeminiLiveTools.kt").contains("LocalMemory.points(svc)"))
        assertTrue(src("GeminiLiveSetup.kt").contains("याद करके रख लो / नोट कर लो"))
        assertTrue(src("AgentRunner.kt").contains("say(if (Memory.addFact(svc, m.text))"))
    }
}

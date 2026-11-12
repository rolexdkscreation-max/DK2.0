package com.dk.assistant.agent.social

import org.junit.Assert.*
import org.junit.Test

/** नवंबर 2026: WhatsApp चैटिंग मोड - 2-3 लोग एक साथ = पहले पूछो किससे; चुने से ही बात; "खुद कर दो" = खुद */
class WaChatModeTest {
    private fun st() = WaChatMode.State(until = 10_000_000L)

    @Test fun onOffWords() {
        assertTrue(WaChatMode.saysOn("चैटिंग मोड ऑन करो"))
        assertTrue(WaChatMode.saysOn("whatsapp mode on"))
        assertFalse(WaChatMode.saysOn("चैटिंग मोड बंद करो"))
        assertTrue(WaChatMode.saysOff("chat mode off"))
        assertTrue(WaChatMode.ON_LINE.startsWith("ठीक है बॉस"))
    }

    @Test fun singleMessageAsksBossFirst() {
        val r = WaChatMode.route(st(), "Rahul", "kal milte hai?", false, false, 1000L)
        assertTrue(r is WaChatMode.Route.AskBoss)
        assertFalse((r as WaChatMode.Route.AskBoss).sensitive)
    }

    @Test fun twoPeopleTogetherAskWhoToTalkTo() {
        val s = st()
        WaChatMode.route(s, "Rahul", "hi", false, false, 1000L)
        val r = WaChatMode.route(s, "Amit", "hello", false, false, 2000L)
        assertTrue(r is WaChatMode.Route.AskPick)
        assertEquals(2, (r as WaChatMode.Route.AskPick).people.size)
        // वही लोग फिर लिखें = दोबारा नहीं पूछना
        assertTrue(WaChatMode.route(s, "Rahul", "suno", false, false, 3000L) is WaChatMode.Route.Skip)
    }

    @Test fun onlyPickedPersonGetsConversation() {
        val s = st()
        WaChatMode.route(s, "Rahul", "hi", false, false, 1000L)
        WaChatMode.route(s, "Amit", "hello", false, false, 2000L)
        assertEquals(listOf("Rahul"), WaChatMode.pick(s, "Rahul"))
        assertTrue(WaChatMode.route(s, "Rahul", "kya haal", false, false, 3000L) is WaChatMode.Route.AskBoss)
        // Amit चुना नहीं - सिर्फ़ बताना, जवाब नहीं
        assertTrue(WaChatMode.route(s, "Amit", "bolo", false, false, 4000L) is WaChatMode.Route.TellNew)
        assertTrue(WaChatMode.route(s, "Amit", "bolo na", false, false, 5000L) is WaChatMode.Route.Skip)
    }

    @Test fun allThreeAndSelfMode() {
        val s = st()
        WaChatMode.route(s, "Rahul", "hi", false, false, 1000L)
        WaChatMode.route(s, "Amit", "hello", false, false, 1500L)
        WaChatMode.route(s, "Neha", "hey", false, false, 2000L)
        assertTrue(WaChatMode.isAll("तीनों से"))
        assertEquals(3, WaChatMode.pick(s, "तीनों से").size)
        assertTrue(WaChatMode.route(s, "Neha", "kaha ho", false, false, 3000L) is WaChatMode.Route.AskBoss)
        WaChatMode.selfOn(s, "")
        assertTrue(WaChatMode.route(s, "Neha", "kab aaoge", false, false, 4000L) is WaChatMode.Route.Auto)
        // पैसे/OTP = खुद कभी नहीं
        val r = WaChatMode.route(s, "Neha", "OTP bhejo", false, false, 5000L)
        assertTrue(r is WaChatMode.Route.AskBoss && r.sensitive)
        WaChatMode.bossOn(s, "")
        assertFalse(s.selfAll)
    }

    @Test fun targetAndAnswered() {
        val s = st()
        WaChatMode.route(s, "Rahul", "hi", false, false, 1000L)
        assertEquals("rahul", WaChatMode.target(s, ""))
        WaChatMode.answered(s, "rahul", "Haan bhai, bolo")
        // अपना ही भेजा लौटकर आया = जवाब नहीं
        assertTrue(WaChatMode.route(s, "Rahul", "Haan bhai, bolo", false, false, 2000L) is WaChatMode.Route.Skip)
        assertEquals(1, s.replies)
    }

    @Test fun offOrExpiredSkips() {
        assertTrue(WaChatMode.route(null, "Rahul", "hi", false, false, 1L) is WaChatMode.Route.Skip)
        assertTrue(WaChatMode.route(WaChatMode.State(10L), "Rahul", "hi", false, false, 20L) is WaChatMode.Route.Skip)
        assertTrue(WaChatMode.route(st(), "Family", "hi", false, true, 20L) is WaChatMode.Route.Skip)
    }
}

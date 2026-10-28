package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.90: DK Live - बिना खोजे "जानकारी नहीं मिली" नहीं, वजह के साथ जवाब, बिना-key बचाव खोज, आम बात में Thinking नहीं, पहले काम फिर बात */
class LiveV390Test {
    @Test fun refusalWithoutToolMeansSearch() {
        assertEquals(LivePromise.Kind.SEARCH, LivePromise.kind("माफ़ करिए, मुझे जानकारी नहीं मिल पाई।", "लाडकी बहीण योजना की अगली किस्त कब आएगी"))
        assertEquals(LivePromise.Kind.SEARCH, LivePromise.kind("मुझे इसके बारे में पता नहीं चल पाया।", "नासिक में आज कौन सा मेला है"))
        assertEquals(LivePromise.Kind.SEARCH, LivePromise.kind("Sorry, I don't know that.", "who won the match yesterday"))
        // निजी बात / फ़ोन का काम / याद - इंटरनेट पर नहीं खोजना
        assertEquals(LivePromise.Kind.NONE, LivePromise.kind("मुझे नहीं पता, आपने बताया नहीं।", "मेरा नाम क्या है"))
        assertEquals(LivePromise.Kind.NONE, LivePromise.kind("माफ़ करिए, मुझे याद नहीं मिल रहा।", "कल हमने क्या बात की थी"))
        assertEquals(LivePromise.Kind.NONE, LivePromise.kind("बिल्कुल ठीक हूँ, आप बताइए।", "कैसे हो"))
    }

    @Test fun longRefusalStillCaught() {
        val r = "माफ़ करिए, अभी मेरे पास इस योजना की ताज़ा जानकारी नहीं है। " + "आप सरकारी वेबसाइट पर देख सकते हैं, वहाँ सब लिखा होता है। ".repeat(3)
        assertTrue(r.length > LivePromise.MAX_PROMISE_CHARS)
        assertEquals(LivePromise.Kind.SEARCH, LivePromise.kind(r, "पीएम किसान की अगली किस्त"))
    }

    @Test fun failLineGivesReason() {
        assertTrue(SearchGuard.userLine(SearchGuard.Fail.QUOTA).contains("सीमा"))
        assertTrue(SearchGuard.userLine(SearchGuard.Fail.NETWORK).contains("इंटरनेट"))
        assertTrue(SearchGuard.userLine(SearchGuard.Fail.BAD_KEY).contains("key"))
        assertTrue(LivePromise.searchFailNote("x", SearchGuard.Fail.TIMEOUT).contains("धीमा"))
        for (f in SearchGuard.Fail.values()) assertTrue(SearchGuard.userLine(f).contains("क्योंकि"))
    }

    private val rss = """<rss><channel><title>Google समाचार</title>
<item><title>पुरानी ख़बर: किस्त जारी - Prabhat Khabar</title><link>https://news.google.com/a</link><pubDate>Tue, 08 Sep 2026 07:00:00 GMT</pubDate><source url="https://www.prabhatkhabar.com">Prabhat Khabar</source></item>
<item><title>Ladki Bahin: नवंबर की किस्त 5 तारीख़ को &amp; ₹1,500 - Livemint</title><link>https://news.google.com/b</link><pubDate>Mon, 26 Oct 2026 10:00:00 GMT</pubDate><source url="https://www.livemint.com">Livemint</source></item>
</channel></rss>"""

    @Test fun newsParsedNewestFirst() {
        val n = OpenSearch.parseNews(rss)
        assertEquals(2, n.size)
        assertEquals("Ladki Bahin: नवंबर की किस्त 5 तारीख़ को & ₹1,500", n[0].title)
        assertEquals("Livemint", n[0].site); assertEquals("26 Oct 2026", n[0].date)
        val txt = OpenSearch.compose(n, emptyList())
        assertTrue(txt.startsWith("LATEST NEWS")); assertTrue(txt.indexOf("Livemint") < txt.indexOf("Prabhat"))
        assertEquals("https://www.livemint.com", OpenSearch.sources(n, emptyList())[0].second)
    }

    @Test fun wikiParsed() {
        val j = """{"query":{"pages":[{"pageid":3,"title":"नाशिक","index":1,"fullurl":"https://hi.wikipedia.org/wiki/x","extract":"नाशिक भारत के महाराष्ट्र राज्य का एक नगर है जो गोदावरी नदी के किनारे बसा है।"},{"title":"छोटा","index":2,"extract":"छोटा"}]}}"""
        val w = OpenSearch.parseWiki(j, "hi")
        assertEquals(1, w.size); assertEquals("नाशिक", w[0].title)
        assertTrue(OpenSearch.compose(emptyList(), w).contains("Wikipedia"))
        assertTrue(OpenSearch.parseWiki("not json", "hi").isEmpty())
        assertEquals("", OpenSearch.compose(emptyList(), emptyList()))
    }

    @Test fun urlsEncoded() {
        assertTrue(OpenSearch.newsUrl("आज की खबर").contains("hl=hi&gl=IN"))
        assertFalse(OpenSearch.wikiUrl("a b&c", "en").contains(" "))
    }

    @Test fun thinkingOnlyWhenWorking() {
        // Live, आम बात (कोई tool नहीं) = Listening -> सीधे Speaking
        assertEquals(ThreeState.LISTENING, ThreeState.label(Mode.THINKING, live = true, muted = false, work = false))
        assertEquals(Mode.LISTENING, ThreeState.look(Mode.THINKING, live = true, work = false))
        // खोज / सोच चल रही = Thinking
        assertEquals(ThreeState.THINKING, ThreeState.label(Mode.THINKING, live = true, muted = false, work = true))
        assertEquals(ThreeState.THINKING, ThreeState.label(Mode.WORKING, live = true, muted = false, work = false))
        // पुराना व्यवहार (चैट / default) वैसा ही
        assertEquals(ThreeState.THINKING, ThreeState.label(Mode.THINKING, false, false))
        assertEquals(Mode.THINKING, ThreeState.look(Mode.THINKING))
        assertEquals(Mode.THINKING, ThreeState.look(Mode.WORKING))
        assertNull(ThreeState.label(Mode.THINKING, live = true, muted = true, work = false))
    }

    @Test fun workFirstOnlyForClearCommands() {
        assertTrue(DoneGate.isCommand("यूट्यूब खोलो"))
        assertTrue(DoneGate.isCommand("राहुल को मैसेज भेज दो"))
        assertTrue(DoneGate.isCommand("टॉर्च बंद करो"))
        assertTrue(DoneGate.isCommand("open youtube"))
        // आम बात में "लगा/चला" - पुराना छोटा रोकना ही
        assertFalse(DoneGate.isCommand("मुझे लगा तुम सो गए"))
        assertFalse(DoneGate.isCommand("पता चला कल बारिश होगी"))
        assertFalse(DoneGate.isCommand("क्या तुमने मैसेज भेज दिया?"))
        assertEquals(DoneGate.PRE_TOOL_HOLD_MS, DoneGate.holdLimitMs(afterTool = false, command = true))
        assertEquals(DoneGate.HOLD_MAX_MS, DoneGate.holdLimitMs(afterTool = false, command = false))
        assertEquals(DoneGate.HOLD_MAX_MS, DoneGate.holdLimitMs(afterTool = true, command = true))
    }

    @Test fun promptsSayWorkFirst() {
        assertFalse(DoneGate.PROMPT.contains("2-word ack"))
        assertTrue(WorkChatMode.block(true).contains("say NOTHING"))
    }
}

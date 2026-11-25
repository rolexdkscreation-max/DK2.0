package com.dk.assistant

import com.dk.assistant.agent.master.BookRules
import com.dk.assistant.agent.master.ShowRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 25 नवंबर 2026 (मालिक): "दिखाओ तो पेज खोल के दिखाए" + "ऐप नहीं है तो ब्राउज़र में काम चालू हो" */
class ShowPageTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()
    private fun manifest() = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() }.readText()

    @Test fun showAsk_detected() {
        for (t in listOf("पेज दिखाओ", "वो पेज खोल के दो", "हाँ ठीक है पेज दिखाओ", "पेमेंट वाला पेज दिखाओ", "कहाँ है दिखाओ", "जो कर रहे हो वो दिखाओ",
                "show the page", "ब्राउज़र दिखाओ", "लास्ट वाला दिखा दो"))
            assertTrue(t, ShowRules.isShowAsk(t))
        for (t in listOf("मुझे शर्ट दिखाओ", "Instagram पेज दिखाओ", "मेरी वेबसाइट दिखाओ", "स्क्रीन शेयर करो", "पेज", "गैलरी की फोटो दिखाओ", "Flipkart खोलो"))
            assertFalse(t, ShowRules.isShowAsk(t))
    }

    @Test fun pick_latestFresh() {
        val now = 10_000_000L
        val a = ShowRules.Spot(ShowRules.Kind.APP, "com.x", "", now - 5_000)
        val b = ShowRules.Spot(ShowRules.Kind.DK_WEB, "https://irctc.co.in/pay", "pay", now - 1_000, pay = true)
        val old = ShowRules.Spot(ShowRules.Kind.WEB, "https://old", "", now - ShowRules.FRESH_MS - 1)
        var l = ShowRules.push(emptyList(), a)
        l = ShowRules.push(l, b); l = ShowRules.push(l, old)
        assertEquals(b, ShowRules.pick(l, now))
        assertNull(ShowRules.pick(listOf(old), now))
        assertEquals(2, ShowRules.push(l, a.copy(at = now)).count { it.kind == ShowRules.Kind.APP || it.kind == ShowRules.Kind.DK_WEB })
        assertTrue(ShowRules.shownNote(b).contains("pays himself"))
        assertTrue(ShowRules.NOTHING_NOTE.contains("Do NOT pretend"))
    }

    @Test fun noApp_websites() {
        assertEquals("https://www.flipkart.com/search?q=black%20shirt", ShowRules.shopWeb("flipkart", "black shirt"))
        assertNull(ShowRules.shopWeb("unknown", "x"))
        assertTrue(ShowRules.bookWeb(BookRules.Kind.TRAIN, null)!!.contains("irctc"))
        assertTrue(ShowRules.bookWeb(BookRules.Kind.BUS, "redBus")!!.contains("redbus"))
        assertNull(ShowRules.bookWeb(BookRules.Kind.OTHER, null))
        val t = BookRules.trip("कल नासिक से पुणे की ट्रेन 2 लोगों के लिए बुक करो")
        val n = BookRules.agentNote(t, emptyList())
        assertTrue(n.contains("open_url https://www.irctc.co.in")); assertTrue(n.contains("NEVER make the online payment"))
        val shop = src("ShopOpen.kt")
        assertTrue(shop.contains("SHOWING_WEB")); assertFalse(shop.contains("offer to open it in the browser/Play Store"))
    }

    @Test fun wiring_browserStaysAndShows() {
        val b = src("ComputerBrowser.kt")
        assertTrue(b.contains("PARK_MS")); assertTrue(b.contains("fun showIn(")); assertTrue(b.contains("fun hide("))
        assertTrue(b.contains("MutableContextWrapper")); assertTrue(b.contains("ShowPage.dkWeb("))
        assertTrue(b.contains("s != \"http\" && s != \"https\""))            // DK अकेले में upi:/intent: कभी नहीं
        assertTrue(b.contains("shown && r.hasGesture()"))                     // सिर्फ़ मालिक के अपने टैप पर
        assertTrue(src("ComputerAgent.kt").contains("ComputerBrowser.close()"))
        assertTrue(src("ComputerAgent.kt").contains("BrowserShowActivity::class.java"))
        assertTrue(manifest().contains(".BrowserShowActivity"))
        val tools = src("GeminiLiveTools.kt")
        assertTrue(tools.contains("\"show_page\" -> ShowPage.show(svc)"))
        assertTrue(tools.contains("ShowRules.isShowAsk("))
        assertTrue(src("GeminiLiveSetup.kt").contains("\"show_page\""))
        assertTrue("show_page" in LiveToolSlim.CORE); assertTrue("show_page" in LeakedCall.TOOLS)
        assertTrue(src("AgentRunner.kt").contains("ShowPage.appNow(svc, pay = true)"))
        assertTrue(src("GeminiLiveQueue.kt").contains("ShowPage.appNow(svc)"))
        assertTrue(src("ComputerActivity.kt").contains("smallBtn(\"पेज\")"))
    }
}

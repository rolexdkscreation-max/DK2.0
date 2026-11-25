package com.dk.assistant

import com.dk.assistant.agent.master.BrowserRules
import com.dk.assistant.agent.master.BrowserRules.Click
import com.dk.assistant.agent.master.MasterRules
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 25 नवंबर 2026 (मालिक: Universal AI Action Agent, चरण 2 - ब्राउज़र एजेंट) */
class BrowserAgentTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    @Test fun riskyButtons_needYes() {
        assertEquals(Click.PAY, BrowserRules.riskyClick("Pay ₹499"))
        assertEquals(Click.PAY, BrowserRules.riskyClick("Proceed to Pay"))
        assertEquals(Click.BUY, BrowserRules.riskyClick("Buy Now"))
        assertEquals(Click.BUY, BrowserRules.riskyClick("Place Order"))
        assertEquals(Click.BUY, BrowserRules.riskyClick("Add to Cart"))
        assertEquals(Click.BOOK, BrowserRules.riskyClick("Book Now"))
        assertEquals(Click.SUBMIT, BrowserRules.riskyClick("Submit"))
        assertEquals(Click.SUBMIT, BrowserRules.riskyClick("सबमिट करें"))
        assertEquals(Click.DELETE, BrowserRules.riskyClick("Delete"))
        assertEquals(Click.DELETE, BrowserRules.riskyClick("Unsubscribe"))
        assertEquals(Click.SEND, BrowserRules.riskyClick("Send"))
        // भुगतान वाले पेज पर "Continue" भी पैसे वाला
        assertEquals(Click.PAY, BrowserRules.riskyClick("Continue", "https://shop.in/checkout/step2"))
    }

    @Test fun normalButtons_noQuestion() {
        for (b in listOf("Search", "खोजें", "Next page", "Login", "Menu", "Posts", "Read more", "", "Continue", "Show prices"))
            assertNull(b, BrowserRules.riskyClick(b, "https://example.com/"))
    }

    @Test fun pageGuard_notesForPasswordOtpCaptchaPayment() {
        val g = BrowserRules.parseGuard("""{"pw":true,"otp":false,"card":false,"captcha":true,"pay":false}""")
        assertTrue(g.any)
        val n = BrowserRules.pageNote(g, "https://x.com/login")
        assertTrue(n.contains("password")); assertTrue(n.contains("CAPTCHA")); assertTrue(n.contains("must NOT fill"))
        assertEquals("", BrowserRules.pageNote(BrowserRules.parseGuard("{}"), "https://x.com"))
        assertFalse(BrowserRules.parseGuard("bad").any)
        assertTrue(BrowserRules.PAGE_GUARD_JS.contains("input[type=password]"))
        assertTrue(BrowserRules.PAGE_GUARD_JS.contains("recaptcha"))
    }

    @Test fun payAndDenyNotes_tellAiToStop() {
        assertTrue(BrowserRules.payNote("Pay").contains("DK never pays"))
        assertTrue(BrowserRules.deniedNote(Click.BUY, "Buy").contains("Do NOT click it again"))
        assertTrue(BrowserRules.confirmText(Click.BOOK, "Book Now", "https://irctc.co.in").contains("दबाऊँ?"))
    }

    @Test fun log_neverStoresTypedText() {
        val l = BrowserRules.logLine(JSONObject().put("type", JSONArray().put(4).put("मेरा पता 12345")))
        assertFalse(l.contains("12345")); assertTrue(l.contains("4"))
        assertTrue(BrowserRules.logLine(JSONObject().put("click", 7)).contains("7"))
    }

    @Test fun labelJs_readsButtonWithoutClicking() {
        val j = ComputerBrowser.labelJs(JSONObject().put("click", 7))!!
        assertTrue(j.contains("[data-dk-i=\"7\"]")); assertFalse(j.contains(".click()"))
        assertNotNull(ComputerBrowser.labelJs(JSONObject().put("enter", 3)))
        assertNull(ComputerBrowser.labelJs(JSONObject().put("scroll", "down")))
        // पुराने actions वैसे ही
        assertTrue(ComputerBrowser.actionJs(JSONObject().put("click", 7))!!.contains("e.click()"))
    }

    @Test fun wiring_onlyWhenMasterOn_oldPathSame() {
        val b = src("ComputerBrowser.kt")
        assertTrue(b.contains("guard: Guard? = null"))
        assertTrue(b.contains("if (guard != null)"))
        val a = src("ComputerAgent.kt")
        assertTrue(a.contains("DkMaster.on(c)) ComputerBrowser.Guard("))
        assertTrue(a.contains("BrowserRules.AGENT_NOTE"))
        assertTrue(BrowserRules.AGENT_NOTE.contains("never make a payment"))
    }

    @Test fun masterPlan_browserSteps() {
        val p = MasterRules.plan("यह वेबसाइट खोलकर पढ़ो")
        assertEquals(MasterRules.Specialist.BROWSER, p.specialist)
        assertTrue(MasterRules.agentNote(p).contains("read the page before acting"))
        assertTrue(p.steps.size >= 3)
    }
}

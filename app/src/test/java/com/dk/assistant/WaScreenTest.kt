package com.dk.assistant

import com.dk.assistant.agent.core.Box
import com.dk.assistant.agent.core.ScreenObservation
import com.dk.assistant.agent.core.UiElement
import com.dk.assistant.agent.social.WaScreen
import org.junit.Assert.*
import org.junit.Test

/** नवंबर 2026: WhatsApp स्क्रीन पढ़ना + मालिक की ओर से जवाब + अपने-आप बात के नियम */
class WaScreenTest {
    private var n = 0
    private fun el(text: String, id: String, top: Int, left: Int = 200, right: Int = 800, editable: Boolean = false, desc: String = "") =
        UiElement(n++, text, desc, "com.whatsapp:id/$id", "TextView", clickable = true, editable = editable, bounds = Box(left, top, right, top + 50))

    private fun list() = ScreenObservation(1, "com.whatsapp", listOf(
        el("Chats", "toolbar_title", 100),
        el("Rahul Sharma", "conversations_row_contact_name", 300), el("कल आ रहा है?", "single_msg_tv", 360), el("10:30", "conversations_row_date", 300, 850, 1000), el("3", "conversations_row_message_count", 360, 900, 960),
        el("+91 98765 43210", "conversations_row_contact_name", 500), el("Hello ji", "single_msg_tv", 560), el("Yesterday", "conversations_row_date", 500, 850, 1000),
        el("Mummy", "conversations_row_contact_name", 700), el("खाना खाया?", "single_msg_tv", 760), el("9:00", "conversations_row_date", 700, 850, 1000), el("1", "conversations_row_message_count", 760, 900, 960),
    ))

    @Test fun readsChatListRows() {
        val o = list()
        assertTrue(WaScreen.isChatList(o)); assertFalse(WaScreen.isChat(o))
        val r = WaScreen.rows(o)
        assertEquals(listOf("Rahul Sharma", "+91 98765 43210", "Mummy"), r.map { it.name })
        assertEquals(listOf(3, 0, 1), r.map { it.unread })
        assertEquals("कल आ रहा है?", r[0].preview)
        assertEquals("10:30", r[0].time)
        assertTrue(r[1].isNumber); assertFalse(r[0].isNumber)
    }

    @Test fun describeTellsNamesNumbersAndUnread() {
        val d = WaScreen.describe(WaScreen.rows(list()))
        assertTrue(d, d.contains("1. Rahul Sharma — 3 नए मैसेज: \"कल आ रहा है?\""))
        assertTrue(d, d.contains("+91 98765 43210 (सेव नहीं, नंबर)"))
        assertTrue(d, d.contains("Unread from: Rahul Sharma (3), Mummy (1)"))
        assertTrue(d, d.contains("ask whose chat"))
        assertTrue(WaScreen.describe(emptyList()).contains("कोई चैट नहीं"))
    }

    @Test fun picksSpokenRow() {
        val r = WaScreen.rows(list())
        assertEquals("Rahul Sharma", WaScreen.pick("rahul", r)?.name)
        assertEquals("Mummy", WaScreen.pick("mummy", r)?.name)
        assertEquals("+91 98765 43210", WaScreen.pick("43210", r)?.name)
        assertEquals("+91 98765 43210", WaScreen.pick("दूसरा", r)?.name)
        assertEquals("Rahul Sharma", WaScreen.pick("पहला वाला", r)?.name)
        assertEquals("Mummy", WaScreen.pick("3", r)?.name)
        assertNull(WaScreen.pick("Suresh", r))
        assertNull(WaScreen.pick("", r))
    }

    private fun chat() = ScreenObservation(2, "com.whatsapp", listOf(
        el("Rahul Sharma", "conversation_contact_name", 80),
        UiElement(n++, "भाई कल मैच देखने चलेगा?", "", "com.whatsapp:id/message_text", bounds = Box(40, 400, 600, 460)),
        UiElement(n++, "हाँ चलूँगा", "", "com.whatsapp:id/message_text", bounds = Box(500, 700, 1000, 760)),
        UiElement(n++, "", "Read", "com.whatsapp:id/status", bounds = Box(960, 720, 1000, 750)),
        UiElement(n++, "कितने बजे निकलना है?", "", "com.whatsapp:id/message_text", bounds = Box(40, 800, 600, 860)),
        el("", "entry", 2200, 40, 900, editable = true),
    ), 1080, 2400)

    @Test fun readsOpenChat() {
        val o = chat()
        assertTrue(WaScreen.isChat(o)); assertEquals("Rahul Sharma", WaScreen.chatTitle(o))
        val m = WaScreen.messages(o)
        assertEquals(listOf(false, true, false), m.map { it.mine })
        assertEquals("कितने बजे निकलना है?", m.last().text)
        val d = WaScreen.describeChat("Rahul Sharma", m)
        assertTrue(d, d.contains("Rahul Sharma: कितने बजे निकलना है?"))
        assertTrue(d, d.contains("आप: हाँ चलूँगा"))
        assertTrue(d, d.contains("waiting for a reply"))
    }

    @Test fun replyPromptStaysOnTopicAsOwner() {
        val p = WaScreen.replyPrompt("Rahul", WaScreen.messages(chat()))
        assertTrue(p, p.contains("Rahul: कितने बजे निकलना है?"))
        assertTrue(p, p.contains("Me: हाँ चलूँगा"))
        assertTrue(p.endsWith("Write my next reply."))
        assertTrue(WaScreen.replyPrompt("Rahul", emptyList(), "शाम 6 बजे").contains("\"शाम 6 बजे\""))
        assertTrue(WaScreen.SYSTEM.contains("ONLY what the other person last said"))
        assertTrue(WaScreen.SYSTEM.contains("never as an assistant"))
    }

    @Test fun cleanStripsLabelsAndQuotes() {
        assertEquals("शाम 6 बजे निकलेंगे", WaScreen.clean("Reply: \"शाम 6 बजे निकलेंगे\""))
        assertEquals("ok bhai", WaScreen.clean("  ok bhai "))
        assertNull(WaScreen.clean("  "))
        assertNull(WaScreen.clean(null))
        assertEquals(500, WaScreen.clean("a".repeat(900))!!.length)
    }

    @Test fun safetyChecks() {
        assertTrue(WaScreen.unsafe("मेरा OTP 482913 है"))
        assertTrue(WaScreen.unsafe("card 4111 1111 1111"))
        assertFalse(WaScreen.unsafe("शाम 6 बजे मिलते हैं"))
        assertTrue(WaScreen.needsOwner("भाई 5000 पैसे भेज दे"))
        assertTrue(WaScreen.needsOwner("OTP बताओ"))
        assertFalse(WaScreen.needsOwner("कितने बजे निकलना है?"))
    }

    @Test fun autoChatRules() {
        val now = 1_000_000L
        val any = WaScreen.Session("", now + 60_000)
        assertEquals(WaScreen.Decision.Reply, WaScreen.decide(any, "Rahul", "कहाँ हो?", false, false, true, now))
        assertEquals(WaScreen.Decision.Skip("off"), WaScreen.decide(null, "Rahul", "x", false, false, true, now))
        assertEquals(WaScreen.Decision.Skip("time over"), WaScreen.decide(any, "Rahul", "x", false, false, true, now + 61_000))
        assertEquals(WaScreen.Decision.Skip("group"), WaScreen.decide(any, "Family", "x", false, true, true, now))
        assertEquals(WaScreen.Decision.Skip("mine"), WaScreen.decide(any, "Rahul", "x", true, false, true, now))
        assertEquals(WaScreen.Decision.Skip("no reply action"), WaScreen.decide(any, "Rahul", "x", false, false, false, now))
        assertTrue(WaScreen.decide(any, "Rahul", "पैसे भेज दो", false, false, true, now) is WaScreen.Decision.AskOwner)
        // अपना भेजा जवाब लौटकर आया / बहुत जल्दी दोबारा
        WaScreen.sent(any, "Rahul", "बस 10 मिनट में", now)
        assertEquals(WaScreen.Decision.Skip("mine"), WaScreen.decide(any, "Rahul", "बस 10 मिनट में", false, false, true, now + 10_000))
        assertEquals(WaScreen.Decision.Skip("too soon"), WaScreen.decide(any, "Rahul", "ठीक है", false, false, true, now + 2_000))
        assertEquals(WaScreen.Decision.Reply, WaScreen.decide(any, "Rahul", "ठीक है", false, false, true, now + 10_000))
        assertEquals(1, any.replies)
        // सिर्फ़ एक व्यक्ति से (उसका ग्रुप नाम से कहा हो तो ग्रुप भी)
        val one = WaScreen.Session("rahul", now + 60_000)
        assertEquals(WaScreen.Decision.Skip("other person"), WaScreen.decide(one, "Mummy", "x", false, false, true, now))
        assertEquals(WaScreen.Decision.Reply, WaScreen.decide(one, "Rahul Sharma", "x", false, false, true, now))
        val cap = WaScreen.Session("", now + 60_000, maxReplies = 1, replies = 1)
        assertEquals(WaScreen.Decision.Skip("max replies"), WaScreen.decide(cap, "Rahul", "x", false, false, true, now))
    }

    @Test fun minutesDefaultAndCap() {
        assertEquals(30, WaScreen.minutes(0))
        assertEquals(15, WaScreen.minutes(15))
        assertEquals(180, WaScreen.minutes(500))
    }
}

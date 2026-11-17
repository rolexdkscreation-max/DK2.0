package com.dk.assistant.agent

import com.dk.assistant.agent.core.LiveWaGate
import com.dk.assistant.agent.core.OutsideText.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 16 नवंबर 2026 (ऑडिट A1): DK लाइव का WhatsApp भेजना / डिलीट = ऐप का अपना "हाँ / ना" कार्ड */
class LiveWaGateTest {
    private fun src(p: String) = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()

    @Test fun kinds() {
        for (a in listOf("send_message", "send", "message", "send_contact", "send_location", "send_live_location", "send_camera_photo",
            "smart_reply", "reply", "chat_reply")) assertEquals(a, Kind.SEND, LiveWaGate.kind(a))
        for (a in listOf("delete_message", "delete", "unsend", "delete_last", " Delete_Message ")) assertEquals(a, Kind.DELETE, LiveWaGate.kind(a))
        for (a in listOf("read_unread", "open_chat", "voice_call", "auto_chat_off", "")) assertEquals(a, Kind.NONE, LiveWaGate.kind(a))
    }

    @Test fun ownerAskedAndNothingReadNoCard() {
        assertFalse(LiveWaGate.sendNeedsCard("send_message", "राहुल को मैसेज भेजो कि मैं आ रहा हूँ", untrustedRecent = false))
        assertFalse(LiveWaGate.sendNeedsCard("send_location", "mummy ko location bhej do", untrustedRecent = false))
        assertFalse(LiveWaGate.sendNeedsCard("send", "राहुल को WhatsApp करो hi", untrustedRecent = false))
    }

    @Test fun afterReadingOthersAlwaysCard() {
        // मालिक ने "भेजो" कहा, पर अभी-अभी किसी का मैसेज/नोटिफ़िकेशन पढ़ा गया = फिर भी कार्ड
        assertTrue(LiveWaGate.sendNeedsCard("send_message", "राहुल को मैसेज भेजो", untrustedRecent = true))
        assertTrue(LiveWaGate.sendNeedsCard("smart_reply", "reply kar do", untrustedRecent = true))
    }

    @Test fun ownerNeverAskedToSendCard() {
        assertTrue(LiveWaGate.sendNeedsCard("send_message", "WhatsApp खोलो और पढ़ो", untrustedRecent = false))
        assertTrue(LiveWaGate.sendNeedsCard("send_contact", "", untrustedRecent = false))
    }

    @Test fun notSendNoCardHere() {
        // डिलीट का कार्ड WaDeleteRunner में; पढ़ना / कॉल पर यहाँ कार्ड नहीं
        assertFalse(LiveWaGate.sendNeedsCard("delete_message", "", untrustedRecent = true))
        assertFalse(LiveWaGate.sendNeedsCard("read_unread", "", untrustedRecent = true))
        assertTrue(LiveWaGate.readsOthers("read_unread")); assertTrue(LiveWaGate.readsOthers("open_chat"))
        assertFalse(LiveWaGate.readsOthers("send_message"))
    }

    @Test fun cardText() {
        assertEquals("WhatsApp पर राहुल को भेजूँ?", LiveWaGate.sendTitle(" राहुल "))
        assertEquals("WhatsApp पर किसी को भेजूँ?", LiveWaGate.sendTitle(""))
        assertTrue(LiveWaGate.sendDetail("send_message", "hi", true).contains("किसी और का लिखा"))
        assertFalse(LiveWaGate.sendDetail("send_message", "hi", false).contains("किसी और का लिखा"))
        assertTrue(LiveWaGate.deleteDetail("galat message", forMe = false).contains("सबके लिए"))
        assertTrue(LiveWaGate.deleteDetail("galat message", forMe = true).contains("सिर्फ़ आपके"))
    }

    @Test fun wired() {
        val live = src("GeminiLiveTools.kt")
        assertTrue(live.contains("waCardDenied(act, args) ?: when (act)"))
        assertTrue(live.contains("ConfirmGate.markUntrusted(); return null"))
        val del = src("agent/android/WaDeleteRunner.kt")
        val card = del.indexOf("ConfirmGate.check(")
        val press = del.indexOf("svc.swipe(")
        assertTrue("डिलीट का कार्ड मैसेज दबाने से पहले", card in 1 until press)
    }
}

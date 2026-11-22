package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 22 नवंबर 2026 (मालिक: "10, 12 काम एक साथ बोले तो DK ख़ुद सोचे पहले क्या - YouTube वीडियो पीछे नहीं चलता तो बाद में;
 * जितना बोला उतना ही, एक्स्ट्रा नहीं").
 */
class SmartTaskOrderTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    private val twelve = "अरिजीत का गाना चलाओ, फिर टॉर्च जलाओ, फिर राहुल को व्हाट्सऐप करो कि मैं आ रहा हूँ, फिर बैटरी कितनी है बताओ, " +
        "फिर नासिक का नेविगेशन लगाओ, फिर कैमरा खोलो, फिर अलार्म लगाओ 6 बजे का, फिर वाईफाई बंद करो, फिर इंस्टाग्राम पर followers चेक करो, " +
        "फिर ब्लूटूथ चालू करो, फिर मम्मी को कॉल करो, फिर वॉल्यूम बढ़ाओ"

    @Test fun twelveTasks_smartOrder_nothingExtraNothingLost() {
        val items = TaskQueue.split(twelve)
        assertEquals(items.joinToString { it.domain }, 12, items.size)
        val plan = TaskQueue.plan(items, twelve)
        assertEquals(items.toSet(), plan.toSet())            // जितना बोला उतना ही - कोई काम नहीं जुड़ा / छूटा
        assertEquals(12, plan.size)
        val d = plan.map { it.domain }
        assertEquals("youtube", d.last())                    // गाना / वीडियो सबसे आख़िर
        assertEquals("camera", d[d.size - 2])                // सिर्फ़ खोलना = गाने से ठीक पहले
        assertEquals(listOf("torch", "battery", "alarm", "wifi", "bluetooth", "volume"), d.take(6))   // तुरंत वाले पहले, बोले गए क्रम में
        assertEquals("maps", d[6])                           // नेविगेशन पीछे चलता रहता है
        assertTrue(d.indexOf("whatsapp") < d.indexOf("call") && d.indexOf("instagram") < d.indexOf("call"))
    }

    @Test fun openOnlyGoesLateButChecksDoNot() {
        assertTrue(TaskQueue.openOnly(TaskQueue.Item("कैमरा खोलो", "camera")))
        assertTrue(TaskQueue.openOnly(TaskQueue.Item("इंस्टाग्राम खोलो", "instagram")))
        assertTrue(TaskQueue.openOnly(TaskQueue.Item("Flipkart खोलो", "app:flipkart")))
        assertFalse(TaskQueue.openOnly(TaskQueue.Item("इंस्टाग्राम खोलो और followers चेक करो", "instagram")))
        assertFalse(TaskQueue.openOnly(TaskQueue.Item("Flipkart खोलो और जूते सर्च करो", "app:flipkart")))
        assertFalse(TaskQueue.openOnly(TaskQueue.Item("आखिरी फोटो डिलीट करो", "gallery", TaskQueue.Kind.DELETE)))
        val raw = "इंस्टाग्राम खोलो, फिर टॉर्च जलाओ, फिर गाना चलाओ"
        assertEquals(listOf("torch", "instagram", "youtube"), TaskQueue.plan(TaskQueue.split(raw), raw).map { it.domain })
        // मालिक ने ख़ुद "आख़िर में" कहा = वही सबसे आख़िर
        val raw2 = "आख़िर में कैमरा खोलो, फिर गाना चलाओ"
        assertEquals(listOf("youtube", "camera"), TaskQueue.plan(TaskQueue.split(raw2), raw2).map { it.domain })
    }

    @Test fun gate_userMultiTasks_oneQueueFromHisWords() {
        val u = "गाना चलाओ, फिर टॉर्च जलाओ, फिर राहुल को मैसेज करो कि आ रहा हूँ"
        // Gemini ने सिर्फ़ एक हिस्सा भेजा (गाना) = आपकी पूरी बात की कतार
        val p = MultiGate.decide("phone_task", "", "गाना चलाओ", u, "", 0L, 1_000L)
        assertEquals(MultiGate.Act.LEAD, p.act)
        assertEquals(u, p.request)
        // उसी बात का अगला tool (अलग से WhatsApp) = पहले से कतार में, दोबारा नहीं
        assertEquals(MultiGate.Act.DUP, MultiGate.decide("whatsapp_task", "send_message", "", u, u, 1_000L, 5_000L).act)
        assertEquals(MultiGate.Act.DUP, MultiGate.decide("phone_task", "", "टॉर्च जलाओ", u, u, 1_000L, 5_000L).act)
        // Gemini ने ख़ुद पूरी बात भेजी = उसी के शब्द
        val full = "play a song, then turn on torch, then message Rahul that I am coming"
        assertEquals(full, MultiGate.decide("phone_task", "", full, u, "", 0L, 1_000L).request)
        // Gemini ने बोले से ज़्यादा काम जोड़े = आपके शब्द (एक्स्ट्रा नहीं)
        val extra = "गाना चलाओ, फिर टॉर्च जलाओ, फिर राहुल को मैसेज करो, फिर वाईफाई बंद करो, फिर कैमरा खोलो"
        assertEquals(u, MultiGate.decide("phone_task", "", extra, u, "", 0L, 1_000L).request)
    }

    @Test fun gate_leavesSingleAndOtherToolsAlone() {
        assertEquals(MultiGate.Act.PASS, MultiGate.decide("phone_task", "", "टॉर्च जलाओ", "टॉर्च जलाओ", "", 0L, 1L).act)
        assertEquals(MultiGate.Act.PASS, MultiGate.decide("phone_task", "", "YouTube खोलो और अरिजीत का गाना चलाओ", "YouTube खोलो और अरिजीत का गाना चलाओ", "", 0L, 1L).act)
        assertEquals(MultiGate.Act.PASS, MultiGate.decide("web_search", "", "", "गाना चलाओ, फिर टॉर्च जलाओ", "", 0L, 1L).act)
        assertEquals(MultiGate.Act.PASS, MultiGate.decide("whatsapp_task", "read_unread", "", "गाना चलाओ, फिर टॉर्च जलाओ", "", 0L, 1L).act)
        assertEquals(MultiGate.Act.PASS, MultiGate.decide("phone_task", "", "रुको", "रुको", "", 0L, 1L).act)
        // वही बात 90 सेकंड बाद दोबारा = नया काम (DUP नहीं)
        val u = "गाना चलाओ, फिर टॉर्च जलाओ"
        assertEquals(MultiGate.Act.LEAD, MultiGate.decide("phone_task", "", "गाना चलाओ", u, u, 0L, MultiGate.DUP_MS + 1).act)
        assertFalse(MultiGate.isMulti("यार आज मूड ख़राब है"))
        assertTrue(MultiGate.isMulti(u))
    }

    @Test fun wired() {
        val end = src("GeminiLiveTurnEnd.kt")
        assertTrue(end.indexOf("MultiGate.isMulti(b)") in 0 until end.indexOf("val q = LiveFast.pick(b"))
        val tools = src("GeminiLiveTools.kt")
        assertTrue(tools.indexOf("multiRoute(rawName, rawArgs)") in 0 until tools.indexOf("svc.scope.launch(start = CoroutineStart.LAZY)"))
        assertTrue(tools.contains("MultiGate.DUP_TOOL -> MultiGate.dupNote()"))
        val q = src("GeminiLiveQueue.kt")
        assertTrue(q.contains("synchronized(multiLock)"))
        assertTrue(MultiGate.dupNote().contains("do not add anything he did not ask"))
    }
}

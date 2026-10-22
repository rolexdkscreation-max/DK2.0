package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.29: DK लाइव - सीधे काम तुरंत, Gemini का phone_task दोहराव नहीं, नतीजा हमेशा सच्चा */
class LiveFastTest {
    private val wake = listOf("डीके", "dk")

    @Test fun simpleCommandsArePickedInstantly() {
        assertEquals("youtube_play", LiveFast.pick("यूट्यूब पर अरिजीत सिंह के गाने चलाओ", wake)?.action?.optString("type"))
        assertEquals("torch", LiveFast.pick("टॉर्च जलाओ", wake)?.action?.optString("type"))
        assertNotNull(LiveFast.pick("आवाज़ 50 करो", wake))
    }

    @Test fun questionsAndMultiStepAreNotFastPath() {
        assertNull(LiveFast.pick("आज मौसम कैसा है", wake))
        assertNull(LiveFast.pick("राहुल को मैसेज भेजो कि मैं आ रहा हूँ", wake))
        assertNull(LiveFast.pick("", wake))
    }

    @Test fun memoStopsDoubleExecution() {
        val m = LiveFast.Memo(windowMs = 15_000)
        val q = LiveFast.pick("टॉर्च जलाओ", wake)!!
        assertTrue(m.begin(q, 1_000))
        assertFalse("वही काम दोबारा नहीं", m.begin(q, 2_000))
        assertNull("अभी चल रहा", m.same(q, 2_000))
        m.finish(q, "OK")
        assertEquals("OK", m.same(q, 3_000))
        assertEquals("15s बाद नया काम", LiveFast.Memo.NOT_SAME, m.same(q, 20_000))
        assertTrue(m.begin(q, 20_000))
    }

    @Test fun geminiRephrasedSameTypeCountsAsSame() {
        val m = LiveFast.Memo()
        val a = LiveFast.pick("यूट्यूब पर भजन चलाओ", wake)!!
        m.begin(a, 0); m.finish(a, "PLAYING")
        val b = LiveFast.pick("YouTube पर भजन play करो", wake)!!
        assertEquals("PLAYING", m.same(b, 1_000))
        assertEquals(LiveFast.Memo.NOT_SAME, m.same(null, 1_000))
    }

    @Test fun resultsAreHonest() {
        val q = LiveFast.pick("टॉर्च जलाओ", wake)!!
        assertTrue(LiveFast.quickResult(q, "OK", already = false).startsWith("DONE"))
        assertTrue(LiveFast.quickResult(q, "OK", already = true).startsWith("ALREADY DONE"))
        assertTrue(LiveFast.quickResult(q, "ERROR_NO_TORCH", already = false).startsWith("FAILED"))
        val yt = LiveFast.pick("यूट्यूब पर भजन चलाओ", wake)!!
        assertTrue("सीधा वीडियो नहीं चला = हो गया नहीं", LiveFast.quickResult(yt, "SEARCH_OPENED", false).startsWith("FAILED"))
        // पहले: 60s में पूरा न हो तब भी "done"
        val r = LiveFast.agentResult(finished = false, reply = null)
        assertTrue(r.contains("NOT finished") && !r.startsWith("FINISHED"))
        assertTrue(LiveFast.agentResult(true, "WhatsApp खोल दिया").contains("WhatsApp खोल दिया"))
        assertTrue(LiveFast.agentResult(true, null).contains("no confirmation"))
        assertTrue(LiveFast.lateNote("x", null).startsWith("SYSTEM NOTE"))
    }
}

package com.dk.assistant.plus

import com.dk.assistant.QuickCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PlusCommandsTest {
    private fun t(s: String) = PlusCommands.parse(s)?.action?.let { it.optString("type") to it.optBoolean("on") }

    @Test fun sos() {
        assertEquals(PlusCommands.SOS to true, t("DK, activate SOS"))
        assertEquals(PlusCommands.SOS to true, t("एसओएस भेजो"))
        assertEquals(PlusCommands.SOS to false, t("SOS बंद करो"))
    }

    @Test fun driving() {
        assertEquals(PlusCommands.DRIVING to true, t("DK, turn on driving mode"))
        assertEquals(PlusCommands.DRIVING to false, t("ड्राइविंग मोड बंद करो"))
    }

    @Test fun unrelated_null() {
        assertNull(t("आज मौसम कैसा है"))
        assertNull(t("व्हाट्सएप खोलो"))
    }

    @Test fun reachedThroughQuickCommands() {
        assertNotNull(QuickCommands.parse("DK, activate SOS", listOf("dk", "डीके")))
        assertEquals(PlusCommands.SOS, QuickCommands.parse("DK, activate SOS", listOf("dk", "डीके"))!!.action.optString("type"))
    }

    // 15 नवंबर 2026 (ऑडिट): सवाल / बातचीत में "SOS" आने पर गलती से 5 लोगों को SMS + कॉल नहीं
    @Test fun sos_question_doesNotTrigger() {
        for (q in listOf("SOS क्या होता है", "sos kya hai", "what is SOS", "SOS का मतलब बताओ", "SOS कैसे काम करता है",
                "SOS नंबर डालो", "SOS सेटिंग खोलो", "मेरा phone SOS वाला है", "SOS वाली फ़िल्म")) {
            val r = t(q)
            assertTrue("$q -> $r", r == null || r.second == false)
        }
    }

    @Test fun sos_clearCommands_stillWork() {
        for (q in listOf("SOS भेजो", "SOS चालू करो", "activate SOS", "send SOS", "SOS", "SOS SOS", "SOS मदद", "DK SOS",
                "एसओएस भेजो", "इमरजेंसी अलर्ट चालू करो", "SOS कर दो", "मदद चाहिए SOS भेजो, accident हुआ है"))
            assertEquals(q, PlusCommands.SOS to true, t(q))
    }

    @Test fun sos_on_isWholeWord() {
        assertFalse(PlusCommands.sosStart(" phone sos "))
        assertTrue(PlusCommands.sosStart(" sos on "))
    }

    @Test fun sos_stop_alwaysWins() {
        for (q in listOf("SOS रोको", "SOS बंद", "stop SOS", "cancel SOS", "SOS कैंसल करो"))
            assertEquals(q, PlusCommands.SOS to false, t(q))
    }
}

package com.dk.assistant.plus

import com.dk.assistant.QuickCommands
import org.junit.Assert.assertEquals
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
}

package com.dk.assistant.inspector

import com.dk.assistant.CoderAi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** 27 नवंबर 2026 (DKI-DA1B71): Coder की नेटवर्क वाली दोबारा-कोशिश "Critical crash" नहीं - LOW नेटवर्क (API) */
class InspectorCoderNetNov27Test {
    private val I = InspectorCore

    @Test fun coderRetryIsNetworkNotCrash() {
        for (l in listOf(
            "Coder: AI नेटवर्क दिक्कत (IOException) - अपने-आप दोबारा (3/3)",
            "Coder: AI नेटवर्क दिक्कत (UnknownHostException) - अपने-आप दोबारा (2/3)",
            "Coder: NVIDIA NIM (bigcode/starcoder2-15b, DK कंप्यूटर की key, दूसरा मॉडल) -> नेटवर्क दिक्कत (UnknownHostException), अगला API",
            "Coder: DK सर्वर (coder) -> नेटवर्क दिक्कत (UnknownHostException), अगला API",
        )) {
            val r = I.ruleFor(l)
            assertEquals(l, "ai_net", r?.id)
            assertEquals(InspectorCore.Severity.LOW, r?.severity)
            assertNotEquals(InspectorCore.Component.CRASH, r?.component)
        }
        // असली crash अब भी crash
        assertEquals(InspectorCore.Component.CRASH, I.ruleFor("java.lang.NullPointerException in ChatActivity")?.component)
        // Live वाली नेट गलती पहले जैसी
        assertNotEquals("ai_net", I.ruleFor("Live: UnknownHostException generativelanguage.googleapis.com")?.id)
    }

    @Test fun reasonNamesRealNetworkCause() {
        val wrapped = java.io.IOException("x", java.net.UnknownHostException("api"))
        assertEquals("UnknownHostException", CoderAi.netName(wrapped))
        assertEquals("नेटवर्क दिक्कत (UnknownHostException)", CoderAi.reason(wrapped))
        assertEquals("IOException", CoderAi.netName(java.io.IOException("plain")))
        assertEquals("SocketTimeoutException", CoderAi.netName(java.net.SocketTimeoutException("t")))
    }
}

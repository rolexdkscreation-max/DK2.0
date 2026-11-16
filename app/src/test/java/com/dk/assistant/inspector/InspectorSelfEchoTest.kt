package com.dk.assistant.inspector

import com.dk.assistant.inspector.InspectorCore as I
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 16 नवंबर 2026 (DKI-2FF8B8): Inspector का अपना "हटा दिया" जवाब नई गड़बड़ी नहीं बनता */
class InspectorSelfEchoTest {
    @Test fun ownReply_isNotABug() {
        assertNull(I.ruleFor("Live: tool dk_inspector 21 ms: REMOVED DKI-CCB3F8. Tell the owner: हटा दिया; अगर यह गड़बड़ी फिर आई तो मैं दोबारा बताऊँगा"))
        assertNull(I.ruleFor("Live: tool: dk_inspector {\"action\":\"dismiss\",\"id\":\"DKI-CCB3F8\"}"))
        assertNull(I.ruleFor("Live: dk_agent -> dk_inspector"))
    }

    @Test fun otherToolErrors_stillCaught() {
        assertEquals("generic_error", I.ruleFor("Live: tool email_send 300 ms: ERROR: login failed")?.id)
    }
}

package com.dk.assistant.agent.social

import org.junit.Assert.*
import org.junit.Test

/** नवंबर 2026: WhatsApp पर लोकेशन / नंबर / कैमरा फ़ोटो भेजना */
class WaShareTest {
    @Test fun locationAndContactText() {
        assertEquals("https://maps.google.com/?q=19.997500,73.789800", WaShare.mapsLink(19.9975, 73.7898))
        assertEquals("Meri current location: Nashik\nhttps://maps.google.com/?q=19.997500,73.789800", WaShare.locationText(19.9975, 73.7898, " Nashik "))
        assertTrue(WaShare.locationText(1.0, 2.0, "").startsWith("Meri current location\n"))
        assertEquals("Rahul ka number: +91 98765 43210", WaShare.contactText(" Rahul ", "+91 98765 43210"))
    }

    @Test fun buttonMatching() {
        val cam = WaShare.CAMERA_STEPS[0]
        assertTrue(WaShare.matches(cam, "com.whatsapp:id/camera_btn", "", ""))
        assertTrue(WaShare.matches(cam, "", "Camera", ""))
        assertFalse(WaShare.matches(cam, "com.whatsapp:id/send", "Send", ""))
        val live = WaShare.LIVE_LOCATION_STEPS[2]
        assertTrue(WaShare.matches(live, "", "", "Share live location"))
        assertEquals(4, WaShare.LIVE_LOCATION_STEPS.size)
    }
}

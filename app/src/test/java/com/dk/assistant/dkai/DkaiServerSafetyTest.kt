package com.dk.assistant.dkai

import org.junit.Assert.*
import org.junit.Test

/** ऑडिट 13 नवंबर 2026: Preview का लोकल सर्वर दूसरी वेबसाइटों को फ़ाइलें न दे */
class DkaiServerSafetyTest {
    @Test fun noOpenCorsHeader() {
        val h = DkaiServer.headerText(200, "OK", "text/html", 10)
        assertFalse(h, h.contains("Access-Control-Allow-Origin", ignoreCase = true))
        assertTrue(h.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(h.contains("Content-Length: 10\r\n"))
        assertTrue(h.endsWith("\r\n\r\n"))
    }

    @Test fun onlyLocalHostHeader() {
        assertTrue(DkaiServer.hostOk(null, 8080))
        assertTrue(DkaiServer.hostOk("localhost:8080", 8080))
        assertTrue(DkaiServer.hostOk("127.0.0.1:8080", 8080))
        assertTrue(DkaiServer.hostOk("LOCALHOST", 8080))
        assertFalse(DkaiServer.hostOk("evil.example.com", 8080))
        assertFalse(DkaiServer.hostOk("evil.example.com:8080", 8080))
        assertFalse(DkaiServer.hostOk("localhost:9999", 8080))
    }
}

package com.dk.assistant.plus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanHttpTest {
    @Test fun onlyHomeNetwork() {
        for (h in listOf("192.168.1.5", "10.0.0.2", "172.16.4.4", "172.31.0.1", "esp.local", "localhost")) assertTrue(h, LanHttp.isLan(h))
        for (h in listOf("8.8.8.8", "172.32.0.1", "example.com", "192.169.1.1", "300.1.1.1")) assertFalse(h, LanHttp.isLan(h))
        assertTrue(runCatching { LanHttp.send("http://8.8.8.8/on", false) }.isFailure)
    }
}

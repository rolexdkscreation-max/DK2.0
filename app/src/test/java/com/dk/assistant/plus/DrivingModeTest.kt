package com.dk.assistant.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivingModeTest {
    @Test fun fill_userName() {
        assertEquals("Hi, दीपक is driving", DrivingMode.fill("Hi, {user_name} is driving", "दीपक"))
        assertTrue(DrivingMode.fill("", "दीपक").contains("दीपक"))
    }

    @Test fun shouldSms_repeatGuard10Min() {
        assertTrue(DrivingMode.shouldSms("9876543210", 0, emptyMap()))
        assertFalse(DrivingMode.shouldSms("9876543210", 5 * 60_000L, mapOf("9876543210" to 0L)))
        assertTrue(DrivingMode.shouldSms("9876543210", 10 * 60_000L, mapOf("9876543210" to 0L)))
        assertFalse(DrivingMode.shouldSms("123", 0, emptyMap()))
    }
}

package com.dk.assistant.plus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartHomeTest {
    private val fan = SmartHome.Item("f", "पंखा", 75.0, "", "", hoursPerDay = 10.0)

    @Test fun billMath() {
        assertEquals(0.75, SmartHome.Bill.kwh(75.0, 10.0), 1e-9)
        assertEquals(6.0, SmartHome.Bill.cost(0.75, 8.0), 1e-9)
        // शुरुआत में बताया अनुमान: 0.75 यूनिट/दिन × 30 = 22.5 यूनिट; ₹8 = ₹180
        assertEquals(180.0, SmartHome.Bill.monthlyCost(listOf(fan), 8.0, 0L), 1e-6)
    }

    @Test fun toggled_tracksRunTime() {
        val on = SmartHome.Bill.toggled(fan, true, 1000L)
        assertTrue(on.isOn)
        val off = SmartHome.Bill.toggled(on, false, 1000L + 3_600_000L)
        assertEquals(1.0, SmartHome.Bill.runHours(off, 0L), 1e-9)
    }

    @Test fun findByName() {
        assertEquals(fan, SmartHome.Bill.find(listOf(fan), "पंखा"))
        assertNull(SmartHome.Bill.find(listOf(fan), "टीवी"))
    }
}

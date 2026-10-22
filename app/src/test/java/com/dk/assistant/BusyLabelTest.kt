package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Test

class BusyLabelTest {
    @Test fun dotsCycleZeroToThree() {
        assertEquals(BusyLabel.BASE, BusyLabel.text(0))
        assertEquals(BusyLabel.BASE + ".", BusyLabel.text(1))
        assertEquals(BusyLabel.BASE + "...", BusyLabel.text(3))
        assertEquals(BusyLabel.BASE, BusyLabel.text(4))
        assertEquals(BusyLabel.BASE + ".", BusyLabel.text(5))
    }
    @Test fun negativeStepSafe() {
        assertEquals(BusyLabel.BASE + "...", BusyLabel.text(-1))
    }
}

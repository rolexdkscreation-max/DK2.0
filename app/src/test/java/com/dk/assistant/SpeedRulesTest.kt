package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedRulesTest {
    @Test fun weatherFromInterim() {
        assertEquals(SpecIntent.Kind.WEATHER, SpecIntent.detect("हेलो डीके, आज का मौसम"))
        assertEquals(SpecIntent.Kind.WEATHER, SpecIntent.detect("aaj ka mausam batao"))
    }

    @Test fun searchNeedsAsk() {
        assertEquals(SpecIntent.Kind.SEARCH, SpecIntent.detect("आज की ताज़ा खबर बताओ"))
        assertEquals(SpecIntent.Kind.SEARCH, SpecIntent.detect("india ka match score kya hai"))
        assertNull(SpecIntent.detect("कल मैच देखा मज़ा आया"))
        assertNull(SpecIntent.detect("हाँ"))
        assertNull(SpecIntent.detect("मुझे एक कहानी सुनाओ"))
    }

    @Test fun sameTopic() {
        assertTrue(SpecIntent.sameTopic("India vs Australia cricket score", "india ka match score kya hai"))
        assertFalse(SpecIntent.sameTopic("bitcoin price", "आज की ताज़ा खबर बताओ"))
    }

    @Test fun netLevels() {
        assertEquals(NetSpeed.Level.SLOW, NetSpeed.classify(300, 0))
        assertEquals(NetSpeed.Level.SLOW, NetSpeed.classify(-1, 3_000))
        assertEquals(NetSpeed.Level.FAST, NetSpeed.classify(20_000, 400))
        assertEquals(NetSpeed.Level.FAST, NetSpeed.classify(-1, 0))
    }
}

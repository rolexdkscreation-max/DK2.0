package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DK AI: वही tool call दो बार नहीं + म्यूट बटन की हालत */
class MuteDedupTest {
    @Test fun sameIdRunsOnlyOnce() {
        val d = ToolCallDeduplicator()
        assertTrue(d.firstTime("call-1"))
        assertFalse(d.firstTime("call-1"))
        assertFalse(d.firstTime(" call-1 "))
        assertTrue(d.firstTime("call-2"))
    }

    @Test fun blankIdIsNeverBlocked() {
        val d = ToolCallDeduplicator()
        assertTrue(d.firstTime(""))
        assertTrue(d.firstTime(""))
        assertTrue(d.firstTime(null))
        assertEquals(0, d.size())
    }

    @Test fun boundedMemory_oldestForgottenFirst() {
        val d = ToolCallDeduplicator(max = 3)
        for (i in 1..4) assertTrue(d.firstTime("id$i"))
        assertEquals(3, d.size())
        assertTrue("सबसे पुराना भूल गया", d.firstTime("id1"))
        assertFalse(d.firstTime("id4"))
    }
}

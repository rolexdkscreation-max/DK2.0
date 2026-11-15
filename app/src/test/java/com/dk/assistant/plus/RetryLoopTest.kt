package com.dk.assistant.plus

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryLoopTest {
    @Test fun firstWorkingStrategyWins_onlyAfterVerify() = runBlocking {
        var done = false
        val r = RetryLoop.run(listOf(
            RetryLoop.Strategy("id") { false },
            RetryLoop.Strategy("text") { true },            // किया पर स्क्रीन पर नहीं दिखा
            RetryLoop.Strategy("tap") { done = true; true },
        ), verify = { done })
        assertTrue(r.ok); assertEquals("tap", r.by); assertEquals(3, r.steps.size)
    }

    @Test fun allFail_honestReport_withRounds() = runBlocking {
        var scrolls = 0
        val r = RetryLoop.run(listOf(RetryLoop.Strategy("id") { true }), verify = { false }, rounds = 3, between = { scrolls++ })
        assertFalse(r.ok); assertEquals(3, r.steps.size); assertEquals(2, scrolls)
        assertTrue(r.report().startsWith("नहीं हुआ"))
    }

    @Test fun alreadyDone_noAction() = runBlocking {
        var acted = false
        val r = RetryLoop.run(listOf(RetryLoop.Strategy("id") { acted = true; true }), verify = { true })
        assertTrue(r.ok); assertFalse(acted)
    }

    @Test fun exceptionInStrategy_isFailureNotCrash() = runBlocking {
        val r = RetryLoop.run(listOf(RetryLoop.Strategy("boom") { error("x") }), verify = { false }, rounds = 1)
        assertFalse(r.ok); assertFalse(r.steps.single().acted)
    }
}

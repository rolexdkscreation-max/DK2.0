package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 21 नवंबर 2026 (मालिक, DK Live #1 सत्र अटकना): goAway ~366s, अटके पर नई बातचीत, SYSTEM NOTE वाली माफ़ी */
class LiveSessionFixTest {
    private fun src(n: String): String =
        listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    @Test fun prewarmBeforeGoAwayAt366s() {
        assertEquals(300_000L, LiveSpare.PREPARE_AT_MS)
        assertTrue(LiveSpare.FREEZE_AT_MS < 366_000L)
        assertTrue(LiveSpare.due(goAway = false, ageMs = 300_000L))
        assertFalse(LiveSpare.due(goAway = false, ageMs = 299_000L))
    }

    @Test fun goAwayTurnSwapsFastWithoutWaitingZombie() {
        val end = 100_000L
        assertTrue(LiveSpare.goAwayTurnSwap(true, end + 1_500, end, end - 500, 0L, false, 800))
        assertFalse("goAway नहीं", LiveSpare.goAwayTurnSwap(false, end + 5_000, end, 0L, 0L, false, 800))
        assertFalse("server ने कुछ भेजा", LiveSpare.goAwayTurnSwap(true, end + 3_000, end, end, end + 200, false, 800))
        assertFalse("अभी जल्दी", LiveSpare.goAwayTurnSwap(true, end + 1_000, end, end - 500, 0L, false, 800))
        assertFalse("DK बोल रहा / काम", LiveSpare.goAwayTurnSwap(true, end + 3_000, end, end, 0L, true, 800))
        assertFalse("बहुत छोटी आवाज़", LiveSpare.goAwayTurnSwap(true, end + 3_000, end, end, 0L, false, 100))
        // goAway बात के बाद आया = goAway से गिनो
        assertFalse(LiveSpare.goAwayTurnSwap(true, end + 2_000, end, end + 1_000, 0L, false, 800))
        assertTrue(LiveSpare.GOAWAY_TURN_WAIT_MS < LiveRecover.ZOMBIE_MS)
    }

    @Test fun handleKeptUnlessSameHandleStuckAgain() {
        assertFalse("पहली बार", LiveRecover.dropHandle(false, "h1", null))
        assertFalse("फिर अटका पर नया handle", LiveRecover.dropHandle(true, "h2", "h1"))
        assertTrue("वही handle दोबारा अटका", LiveRecover.dropHandle(true, "h1", "h1"))
        assertFalse(LiveRecover.dropHandle(true, null, null))
    }

    @Test fun stuckSendsRealWordsNeverSystemNote() {
        assertEquals("शाम को अलार्म लगा दो", LiveRecover.stuckText("शाम को अलार्म लगा दो", "शाम को"))
        assertEquals("आज मौसम कैसा है", LiveRecover.stuckText(null, "आज मौसम कैसा है…"))
        assertNull(LiveRecover.stuckText(null, ""))
        assertFalse(LiveRecover.ASK_AGAIN_LINE.contains("SYSTEM"))
        val r = src("GeminiLiveRecover.kt")
        val body = r.substringAfter("internal fun GeminiLive.stuckNoSwap()").substringBefore("\n}\n")
        assertFalse(body.contains("sorryOk = true"))
        assertTrue(body.contains("askAgain()"))
        assertFalse(src("GeminiLive.kt").contains("if (again) handle = null"))
        assertFalse(r.contains("if (again) handle = null"))
    }
}

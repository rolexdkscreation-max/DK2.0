package com.dk.assistant.voice.st3

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 20 नवंबर 2026 (मालिक का लॉग: गर्म फ़ोन पर बनी 6362 ms बनाम आवाज़ 2075 ms): गर्म = कम थ्रेड + छोटे हिस्से, जल्दी */
class St3ThermalTest {
    @Before fun clean() { St3Load.reset() }
    @After fun after() { St3Load.reset() }

    @Test fun moderateHeatIsHot() {
        assertEquals(2, St3Load.HOT_STATUS)   // PowerManager.THERMAL_STATUS_MODERATE
        assertEquals(St3Load.Mode.LIGHT, St3Load.next(St3Load.Mode.NORMAL, 0, 0, 2))
        assertEquals(St3Load.Mode.NORMAL, St3Load.next(St3Load.Mode.NORMAL, 0, 0, 1))
    }

    @Test fun hotPhoneUsesFewerThreads() {
        assertEquals(St3Load.HOT_THREADS, St3Load.threadsFor(4, 2))
        assertEquals(St3Load.HOT_THREADS, St3Load.threadsFor(6, 4))
        assertEquals(1, St3Load.threadsFor(1, 3))
        assertEquals(4, St3Load.threadsFor(4, 1))
        assertEquals(4, St3Load.threadsFor(4, -1))   // पता नहीं = वैसा ही
    }

    @Test fun oneVerySlowPieceGoesLightAtOnce() {
        // लॉग जैसा: बनी 6362 ms, आवाज़ 2075 ms (~306%)
        val l = St3Load.onRow(St3Stats.Row(40, 6_362, 2_075, 5, 4))
        assertEquals(St3Load.Mode.LIGHT, St3Load.mode)
        assertTrue(l!!.contains("हल्का"))
        assertTrue(St3Load.leadFor(St3Load.mode, 3000) < 3000L)
    }

    @Test fun reopenOnlyAtAnswerStart() {
        assertTrue(St3Load.freshAnswer)
        St3Load.onRow(St3Stats.Row(40, 1_200, 2_400, 5, 4))
        assertFalse(St3Load.freshAnswer)
        St3Load.newAnswer(500)
        assertTrue(St3Load.freshAnswer)
        val eng = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.exists() }
            .resolve("com/dk/assistant/voice/st3/St3Engine.kt").readText()
        assertTrue(eng.contains("St3Load.threadsFor(threads(c), St3Load.heatStatus)"))
        assertTrue(eng.contains("openThreads != want && St3Load.freshAnswer"))
    }
}

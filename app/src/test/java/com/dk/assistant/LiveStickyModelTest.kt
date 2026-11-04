package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * मालिक (नवंबर 2026): DK लाइव अटके नहीं. जिस मॉडल से बात चल रही है उसी पे रहे - limit खत्म हो तभी उसी key का अगला
 * मॉडल, उस key के सारे मॉडल खत्म तो बैकअप key (फिर तीसरी). एक-key और कई-key दोनों पर. स्क्रीन पर लिखी बात का जवाब
 * आए - न आए तो वही बात एक बार दोबारा (डुप्लीकेट नहीं).
 */
class LiveStickyModelTest {
    private fun src(name: String): String =
        listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name")).first { it.exists() }.readText()

    private val m = listOf("m1", "m2", "m3")
    private fun all(keys: Int) = LiveRace.Health().order(m, keys, 1_000)

    @Test fun stickyFirstThenSameKeyModelsThenNextKey() {
        // 3 keys, बात key 1 के m2 से चल रही थी
        val o = LiveRace.stickyOrder(all(3), "0|m2").map { it.id }
        assertEquals(listOf("0|m2", "0|m1", "0|m3", "1|m1", "1|m2", "1|m3", "2|m1", "2|m2", "2|m3"), o)
        // बैकअप key 2 पर चल रही थी = पहले key 2 के, फिर key 1, फिर key 3
        assertEquals(listOf("1|m3", "1|m1", "1|m2", "0|m1", "0|m2", "0|m3", "2|m1", "2|m2", "2|m3"),
            LiveRace.stickyOrder(all(3), "1|m3").map { it.id })
        // एक key (all-rounder): उसी key के मॉडल, चल रहा पहले
        assertEquals(listOf("0|m3", "0|m1", "0|m2"), LiveRace.stickyOrder(all(1), "0|m3").map { it.id })
    }

    @Test fun limitHitModelGoneStillSameKeyFirst() {
        // key 1 का m1 limit से रुका (queue में नहीं) = key 1 के m2, m3 पहले, key 2 बाद में
        val q = all(2).filter { it.id != "0|m1" }
        assertEquals(listOf("0|m2", "0|m3", "1|m1", "1|m2", "1|m3"), LiveRace.stickyOrder(q, "0|m1").map { it.id })
        // key 1 के सारे मॉडल रुके = key 2
        val q2 = all(2).filter { it.keyIdx != 0 }
        assertEquals("1|m1", LiveRace.stickyOrder(q2, "0|m1").first().id)
        assertEquals(emptyList<LiveRace.Combo>(), LiveRace.stickyOrder(emptyList(), "0|m1"))
        // कोई इतिहास नहीं = key 1 के सारे मॉडल, फिर key 2
        assertEquals(listOf("0|m1", "0|m2", "0|m3", "1|m1", "1|m2", "1|m3"), LiveRace.stickyOrder(all(2), null).map { it.id })
    }

    @Test fun noParallelSwitchWhileStickyConnects() {
        assertTrue(LiveRace.holdForSticky(listOf("0|m1"), "0|m1"))
        assertFalse(LiveRace.holdForSticky(listOf("1|m1"), "0|m1"))
        assertFalse(LiveRace.holdForSticky(emptyList(), "0|m1"))
        assertFalse(LiveRace.holdForSticky(listOf("0|m1"), null))
        // नेट/timeout से एक बार चूकी = उसी पर एक बार और; limit/key/मॉडल-गलती पर नहीं
        assertTrue(LiveRace.retrySticky(true, LiveRace.Fail.NET, 0))
        assertTrue(LiveRace.retrySticky(true, null, 0))
        assertFalse(LiveRace.retrySticky(true, LiveRace.Fail.NET, LiveRace.STICKY_RETRIES))
        assertFalse(LiveRace.retrySticky(true, LiveRace.Fail.KEY, 0))
        assertFalse(LiveRace.retrySticky(true, LiveRace.Fail.MODEL, 0))
        assertFalse(LiveRace.retrySticky(false, LiveRace.Fail.NET, 0))
    }

    @Test fun raceUsesStickyRules() {
        val r = src("GeminiLiveRace.kt")
        assertTrue(r.contains("LiveRace.stickyOrder(queue - hung.toSet(), stickyId)"))
        assertTrue(r.contains("!LiveRace.holdForSticky(inFlight.map { it.c.id }, stickyId)"))
        assertTrue(r.contains("val stickyId = resumeId ?: health.lastWin.takeIf { it.isNotBlank() }"))
        val g = src("GeminiLive.kt")
        // बातचीत के बीच limit = पहले इसी key का अगला मॉडल, फिर बैकअप key
        val same = g.indexOf("इसी key ${'$'}{ki + 1} का अगला Live मॉडल")
        assertTrue(same > 0 && same < g.indexOf("if (switchKey(\"बातचीत के बीच इस key की limit खत्म\")) continue"))
    }

    @Test fun shownButUnansweredIsSentAgainOnce() {
        assertTrue(LiveRecover.unheardWorthSwap("मौसम कैसा है", 900))
        assertFalse(LiveRecover.unheardWorthSwap("", 2_000))
        assertFalse(LiveRecover.unheardWorthSwap("मौसम कैसा है", 100))
        val g = src("GeminiLive.kt")
        assertTrue(g.contains("if (swapForStuck(now, \"सुना पर जवाब अटका\")) { goAway = true; goAwayDrop = true; break }"))
        assertTrue(g.contains("if (swapForStuck(now, \"लिखा गया पर Gemini तक नहीं पहुँचा\")) { goAway = true; goAwayDrop = true; break }"))
        val rec = src("GeminiLiveRecover.kt")
        assertTrue(rec.contains("if (!zombieSwaps.tryTake(now))"))           // चक्कर नहीं
        assertTrue(rec.contains("if (utt.lastRealAt() > replayAt)"))        // नई बात बोल चुके = पुरानी दोबारा नहीं
        assertFalse(rec.contains("DKState.user("))                          // स्क्रीन पर दोबारा लाइन नहीं
        assertTrue(src("GeminiLiveTurnEnd.kt").contains("LiveRecover.unheardWorthSwap(caption?.text().orEmpty(), utt.speechMs())"))
    }
}

package com.dk.assistant.insights

import com.dk.assistant.agent.core.Box
import com.dk.assistant.agent.core.DriverResult
import com.dk.assistant.agent.core.ScreenDriver
import com.dk.assistant.agent.core.ScreenObservation
import com.dk.assistant.agent.core.UiElement
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** नवंबर 2026 (मालिक: "रिव्यू फटाफट करे"): बेकार इंतज़ार / सोच / कदम कम */
class InsightsSpeedTest {
    private fun src(n: String): String {
        val p = "src/main/java/com/dk/assistant/$n"
        return listOf(File(p), File("app/$p")).first { it.exists() }.readText()
    }

    private class TimedDriver(val screens: List<ScreenObservation>) : ScreenDriver {
        var i = 0
        var waited = 0L
        override suspend fun capture() = screens[i.coerceAtMost(screens.size - 1)]
        override suspend fun click(obs: ScreenObservation, e: UiElement): DriverResult { i++; return DriverResult.OK }
        override suspend fun tapAt(x: Int, y: Int) = DriverResult.OK
        override suspend fun setText(obs: ScreenObservation, e: UiElement, text: String) = DriverResult.UNSUPPORTED
        override suspend fun scroll(down: Boolean): DriverResult { i++; return DriverResult.OK }
        override suspend fun back(): DriverResult = DriverResult.OK
        override suspend fun pause(ms: Long) { waited += ms }
    }

    private fun scr(n: Long, vararg t: String) = ScreenObservation(n, "com.instagram.android",
        t.mapIndexed { k, s -> UiElement(k + 1, text = s, clickable = true, bounds = Box(0, 100 * k, 1080, 100 * k + 90)) })

    @Test fun afterTap_movesOnAsSoonAsScreenChanges() = runBlocking {
        val d = TimedDriver(listOf(scr(1, "Profile", "Professional dashboard"), scr(2, "Views 12.3K"), scr(3, "Reels")))
        val replies = ArrayDeque(listOf(
            """{"posts":[],"actions":[{"do":"click","id":2}],"done":false}""",
            """{"posts":[],"actions":[{"do":"scroll","dir":"down"}],"done":false}""",
            """{"posts":[],"actions":[],"done":true}""",
        ))
        ScreenCollector(d, { true }, { true }, { replies.removeFirstOrNull() }).collect(Platform.INSTAGRAM)
        // पहले: 2200 (ऐप) + 1100 + 1100 = 4400 ms. अब: ऐप 2000 + हर कदम (150 + 280)
        val perStep = InsightsSpeed.POLL_MS + InsightsSpeed.SETTLE_AFTER_MS
        assertEquals(InsightsSpeed.OPEN_SETTLE_MS + 2 * perStep, d.waited)
        assertTrue(d.waited < 4_400)
    }

    @Test fun stopsAsSoonAsEverythingIsCollected_withoutWaitingForDone() = runBlocking {
        val d = TimedDriver(listOf(scr(1, "a"), scr(2, "b"), scr(3, "c")))
        var calls = 0
        val full = """{"account":{"views":"12K"},"status_checked":true,"detail_of":"R%d",
            "posts":[{"title":"R%d a","views":"1"},{"title":"R%d b","views":"2"}],"actions":[{"do":"scroll"}],"done":false}"""
        val c = ScreenCollector(d, { true }, { true }, { calls++; full.replace("%d", calls.toString()) })
        c.detailTarget = 2; c.listTarget = 4
        val r = c.collect(Platform.INSTAGRAM)
        assertEquals(2, calls)
        assertEquals(4, r.posts.size)
    }

    @Test fun fewerSteps_andTargets() {
        assertTrue(InsightsSpeed.MAX_STEPS < 36)
        assertTrue(src("insights/InsightsAgent.kt").contains("collector.detailTarget = 3; collector.listTarget = 6"))
    }

    @Test fun screenshotOnlyWhenTextHasNoNumbers() {
        val numbers = "CURRENT SCREEN (package x):\n[1] Views 12.3K @y=10%\n[2] Reach 4,100 @y=20%\n[3] Likes 300 @y=30%\n[4] Saves 12 @y=40%\n[5] Back @y=5%\n\nA screenshot of the same screen may also be attached."
        assertFalse(InsightsSpeed.wantShot(numbers))
        val chart = "CURRENT SCREEN (package x):\n[1] Overview @y=10%\n[2] Last 30 days @y=20%\n[3] Content @y=30%\n[4] Audience @y=40%\n\nA screenshot"
        assertTrue(InsightsSpeed.wantShot(chart))
        assertTrue(InsightsSpeed.wantShot(numbers.replace("CURRENT SCREEN", "- WARNING: the screen has not changed for 2 steps\nCURRENT SCREEN")))
        assertTrue(InsightsSpeed.wantShot("कुछ और"))
    }

    @Test fun lessThinking_onGemini3_andRetryWithout() {
        assertEquals("minimal", InsightsSpeed.thinking("gemini-3.8-flash", report = false)!!.getString("thinkingLevel"))
        assertEquals("low", InsightsSpeed.thinking("gemini-3.5-flash-lite", report = true)!!.getString("thinkingLevel"))
        assertEquals(0, InsightsSpeed.thinking("gemini-2.5-flash", report = false)!!.getInt("thinkingBudget"))
        assertNull(InsightsSpeed.thinking("gemma-3-27b", report = false))
        val a = src("insights/InsightsAgent.kt")
        assertTrue(a.contains("GeminiModels.thinkingRejected(r.code, err, gc.has(\"thinkingConfig\"))"))
        assertTrue(a.contains("readTimeout(InsightsSpeed.STEP_CALL_S"))
    }

    @Test fun workingModelIsTriedFirst() {
        val ms = listOf("a", "b", "c")
        assertEquals(listOf("c", "a", "b"), InsightsSpeed.order(ms, "c"))
        assertEquals(ms, InsightsSpeed.order(ms, null))
        assertEquals(ms, InsightsSpeed.order(ms, "z"))
    }

    @Test fun trendsRunAlongside_notAtTheEnd() {
        val a = src("insights/InsightsAgent.kt")
        assertTrue(a.contains("trendJob = svc.scope.async(Dispatchers.IO)"))
        assertTrue(a.indexOf("startTrends()\n        }") in 1 until a.indexOf("val analyses = all.map"))
        assertTrue(a.contains("withTimeoutOrNull(InsightsSpeed.TREND_WAIT_MS)"))
    }
}

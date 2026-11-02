package com.dk.assistant

import com.dk.assistant.inspector.InspectorCore as I
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * नवंबर 2026 (DKI-70D747 / DKI-87912D / DKI-F0ACC6 / DKI-CCB3F8):
 * एक हुक्म टुकड़ों में न कटे, आधी बात पर बड़ा काम न चले, रद्द = गलती नहीं, बंद key बार-बार नहीं,
 * AI timeout crash नहीं, फ़ोन-काम वाली बारी देरी के औसत में नहीं.
 */
class LiveTurnSplitTest {
    private fun src(p: String) = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()

    // 1. बात बीच में न कटे
    @Test fun earlyEnd_notOnLongCommand_butQuestionsStayFast() {
        assertFalse(TurnEnd.earlyOk("मैप में सागर नर्सरी और मटका भंडार सर्च करो"))
        assertFalse(TurnEnd.earlyOk("मेरे लिए एक अच्छा सा गाना यूट्यूब पर चला दीजिए"))
        assertTrue(TurnEnd.earlyOk("टाइम बताओ"))                       // छोटा हुक्म तेज़ ही
        assertTrue(TurnEnd.earlyOk("आज मौसम कैसा है"))                  // सवाल तेज़ ही
        assertTrue(TurnEnd.earlyOk("दिल्ली की आबादी कितनी है?"))
        assertFalse(TurnEnd.earlyOk("सर्च करो और"))
        // पुराना complete() वैसा ही
        assertTrue(TurnEnd.complete("मैप में सागर नर्सरी और मटका भंडार सर्च करो"))
        assertTrue(src("GeminiLiveMic.kt").contains("TurnEnd.earlyOk(cap) && turnDet.forceEnd()"))
    }

    // 1. आधी बात पर बड़ा काम नहीं
    @Test fun fastTask_bigActionsWaitForSettle_smallStayInstant() {
        assertEquals(0L, LiveFast.settleMs("torch"))
        assertEquals(0L, LiveFast.settleMs("volume"))
        assertEquals(LiveFast.SETTLE_MS, LiveFast.settleMs("app_search"))
        assertEquals(LiveFast.SETTLE_MS, LiveFast.settleMs("open_app"))
        val g = LiveSrc.all()
        assertTrue(g.contains("val settle = LiveFast.settleMs(q.action.optString(\"type\"))"))
        assertTrue(g.indexOf("val settle = LiveFast.settleMs") < g.indexOf("if (!fastMemo.begin(q, System.currentTimeMillis()))"))
    }

    // 2. रद्द = गलती नहीं, और Gemini रद्द नतीजा न ले
    @Test fun cancelledTask_isNotError_andMemoForgetsIt() {
        assertTrue(LiveFast.cancelled("ERROR: StandaloneCoroutine was cancelled"))
        assertFalse(LiveFast.cancelled("OK opened"))
        val m = LiveFast.Memo()
        val q = QuickCommands.Quick(org.json.JSONObject().put("type", "app_search").put("q", "सागर नर्सरी"), "")
        assertTrue(m.begin(q, 0))
        m.finish(q, "ERROR: StandaloneCoroutine was cancelled")
        m.cancel(q)
        assertEquals(LiveFast.Memo.NOT_SAME, m.same(q, 10))
        val g = LiveSrc.all()
        assertTrue(g.contains("if (!isActive || LiveFast.cancelled(r))"))
        val line = "Live: तुरंत काम रद्द (app_search) - आप आगे बोले, पूरी बात पर फिर से (1771 ms)"
        assertTrue(I.analyze(I.parseLog("11:47:27.044 $line")).isEmpty())
    }

    // 3. "जवाब नहीं आया" सिर्फ़ असली इंतज़ार पर
    @Test fun stallCountedOnlyWhenReallyWaited() {
        assertTrue(LiveRecover.CONTINUE_MS in 1_500L..LiveRecover.ZOMBIE_MS)
        val g = LiveSrc.all()
        assertTrue(g.contains("else if (waited in 0 until LiveRecover.CONTINUE_MS)"))
        assertTrue(g.contains("इंतज़ार \${waited / 100 / 10.0}s"))
        assertTrue(src("GeminiLiveMic.kt").contains("speechStartAt = now"))
        // टुकड़े वाली लाइन Inspector में गलती नहीं
        assertTrue(I.analyze(I.parseLog("11:47:27.044 Live: बात आगे बोली (900 ms बाद) - टुकड़ा, जवाब पूरी बात पर (#1)")).isEmpty())
    }

    // 4. Google ने बंद की key बार-बार नहीं
    @Test fun deadKey_parkedForHours_withClearReason() {
        val e = AiException(403, "The bound service account is deleted or disabled. The service account bound to the API key must be active.")
        assertTrue(ProviderHealth.dead(e))
        assertFalse(ProviderHealth.dead(AiException(429, "Resource has been exhausted")))
        assertTrue(ProviderHealth.DEAD_MS >= 6 * 60 * 60_000L)
        assertTrue(ProviderHealth.short(e).contains("नई key"))
        // Inspector: साफ़ "key मना हुई" (आपको key बदलनी है), crash/generic नहीं
        val f = I.analyze(I.parseLog("11:47:26.042 Gemini (gemini-3.5-flash-lite) fail: ${e.message}"))
        assertEquals("api_auth", f.single().rule.id)
    }

    // 5. AI timeout crash नहीं
    @Test fun aiTimeout_isApiLow_notCrash() {
        val log = """
11:48:04.518 OpenRouter (openrouter/free) fail: timeout
11:48:04.521 AI error: InterruptedIOException timeout
""".trim()
        val f = I.analyze(I.parseLog(log))
        assertTrue(f.none { it.rule.id == "crash" })
        assertTrue(f.all { it.rule.id == "ai_timeout" })
        assertEquals(I.Severity.LOW, f.first().rule.severity)
        // असली exception अब भी crash
        assertTrue(I.analyze(I.parseLog("11:48:04.521 FATAL NullPointerException in Foo")).any { it.rule.id == "crash" })
    }

    // 6. फ़ोन-काम वाली बारी देरी के औसत में नहीं
    @Test fun latency_toolTurnsExcluded() {
        assertEquals(LiveRecover.TOOL_MARK, I.TOOL_MARK)
        val normal = "11:48:00.436 Live: देरी के हिस्से: कुल 3000ms = बोलना-खत्म-पहचान 500ms + generation(+network) 2400ms + playback 100ms [RTT~700ms, अटका 1KB] • सबसे बड़ा हिस्सा: generation/network"
        val tool = "11:49:00.436 Live: देरी के हिस्से: कुल 9517ms = बोलना-खत्म-पहचान 521ms + generation(+network) 8973ms + playback 23ms [RTT~745ms, अटका 1KB] • सबसे बड़ा हिस्सा: generation/network ${LiveRecover.TOOL_MARK}"
        val b = I.parseBreakdowns(I.parseLog("$normal\n$tool"))
        assertEquals(1, b.size); assertEquals(3000L, b.single().total)
        val p = src("GeminiLivePlayer.kt")
        assertTrue(p.contains("if (!connSplit && !withTool) LatencyBoard.record("))
        assertTrue(src("GeminiLiveServer.kt").contains("toolCallAt = lastServerAt"))
    }
}

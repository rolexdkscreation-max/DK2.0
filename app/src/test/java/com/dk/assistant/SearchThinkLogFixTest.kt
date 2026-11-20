package com.dk.assistant

import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 20 नवंबर 2026 (लॉग): बेवजह की खोज (16 s) नहीं + deep_think 41 s तक न रुके (हर कोशिश 8 s, पूरा 12 s) */
class SearchThinkLogFixTest {
    private fun src(p: String) = listOf("src/main/java/com/dk/assistant/$p", "app/src/main/java/com/dk/assistant/$p").map(::File).first { it.exists() }.readText()

    @Test fun chatAboutDk_isNotSearch() {
        // लॉग वाली बात: "बताओ, क्या कर रहे हो?" -> "आपसे बात कर रही हूँ" = खोज नहीं
        assertEquals(LivePromise.Kind.NONE, LivePromise.kind("बस आपसे बात कर रही हूँ।", "बताओ, क्या कर रहे हो?"))
        assertEquals(LivePromise.Kind.NONE, LivePromise.kind("कुछ नहीं, आपका इंतज़ार कर रहा हूँ।", "kya kar rahe ho"))
        assertEquals(LivePromise.Kind.NONE, LivePromise.kind("एक सेकंड, मैं ठीक हूँ।", "तुम कैसे हो"))
        assertTrue(LivePromise.chatWithDk("बताओ, क्या कर रहे हो?"))
    }

    @Test fun realPromisesStillWork() {
        assertEquals(LivePromise.Kind.SEARCH, LivePromise.kind("एक सेकंड, सर्च करके बताता हूँ।", "आज नासिक का मौसम कैसा है"))
        assertEquals(LivePromise.Kind.SEARCH, LivePromise.kind("हाँ, सर्च कर रहा हूँ।", "आज सोने का भाव क्या है"))
        assertEquals(LivePromise.Kind.ACTION, LivePromise.kind("हाँ, कर रहा हूँ", "क्रोम पे कोई वेबसाइट सर्च करो"))
        assertEquals(LivePromise.Kind.SEARCH, LivePromise.kind("माफ़ करिए, मुझे जानकारी नहीं मिल पाई।", "लाडकी बहीण योजना की अगली किस्त कब आएगी"))
    }

    @Test fun generalKnowledge_noSearchNoRetry() {
        val n = LivePromise.noSearchNote("सूरज पूर्व से क्यों उगता है")
        assertTrue(n.contains("no internet search is needed") && n.contains("do not call web_search"))
        val chk = src("GeminiLiveChecks.kt")
        assertTrue(chk.contains("kind == LivePromise.Kind.SEARCH && WebSearch.skip(user)"))
        // "no_search_needed" आए तो बचाव वाली दूसरी खोज नहीं
        assertTrue(chk.contains("noNeed = e.message == \"no_search_needed\""))
        assertTrue(chk.indexOf("if (noNeed)") < chk.indexOf("val fb = fallbackSearch(user, why, brain)"))
    }

    @Test fun thinkBudget() {
        assertEquals(8_000L, ThinkTime.STEP_MS)
        assertEquals(12_000L, ThinkTime.TOTAL_MS)
        assertEquals(8_000L, ThinkTime.stepMs(deadline = 12_000, now = 0))
        assertEquals(4_000L, ThinkTime.stepMs(deadline = 12_000, now = 8_000))
        assertEquals(0L, ThinkTime.stepMs(deadline = 12_000, now = 13_000))
    }

    @Test fun withinReturnsOnTime_evenIfWorkIsSlow() = runBlocking {
        val t0 = System.currentTimeMillis()
        val r = ThinkTime.within(200) { Thread.sleep(2_000); "late" }      // blocking call - फिर भी 200 ms पर छोड़ो
        assertNull(r)
        assertTrue(System.currentTimeMillis() - t0 < 1_500)
        assertEquals("ok", ThinkTime.within(1_000) { delay(10); "ok" })
        assertNull(ThinkTime.within(0) { "x" })
    }

    @Test fun deepThinkUsesBudgetEverywhere() {
        val tools = src("GeminiLiveTools.kt")
        assertTrue(tools.contains("val thinkEnd = System.currentTimeMillis() + ThinkTime.TOTAL_MS"))
        assertTrue(!tools.contains("withTimeoutOrNull(45_000) { BrainManager(svc)"))
        val ltb = src("LiveTextBrain.kt")
        assertTrue(ltb.contains("ThinkTime.within(ms)") && ltb.contains("- अगली API"))
    }
}

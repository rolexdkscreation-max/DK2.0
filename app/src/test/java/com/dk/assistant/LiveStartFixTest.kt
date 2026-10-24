package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveStartFixTest {

    // ---- 1. Live चालू करने पर wake loop रोको
    @Test fun restartLoop_onlyWhenWakeLoopRunning() {
        assertTrue(LiveStart.restartLoop(loopActive = true, wasLive = false))
        assertFalse(LiveStart.restartLoop(loopActive = false, wasLive = false))
        assertFalse(LiveStart.restartLoop(loopActive = true, wasLive = true))
    }

    @Test fun staleHeard_beforeLive_isNotCommand() {
        // log 07:51:36: Live बंद में सुनी बात, Live चालू होने के बाद लौटी -> command नहीं
        assertTrue(LiveStart.staleHeard(liveBefore = false, liveNow = true))
        assertFalse(LiveStart.staleHeard(liveBefore = true, liveNow = true))
        assertFalse(LiveStart.staleHeard(liveBefore = false, liveNow = false))
    }

    // ---- 2. Live models list का cache
    @Test fun modelCache_roundTrip() {
        val enc = LiveModelCache.encode(42, 1000L, listOf("gemini-3.8-live", "gemini-3.1-flash-live-preview"))
        assertEquals(Triple(42, 1000L, listOf("gemini-3.8-live", "gemini-3.1-flash-live-preview")), LiveModelCache.decode(enc))
        assertNull(LiveModelCache.decode(""))
        assertNull(LiveModelCache.decode("x|1|a"))
        assertNull(LiveModelCache.decode("1|2|"))
    }

    @Test fun modelCache_plan() {
        val now = 10_000_000_000L
        val c = Triple(7, now - 60_000L, listOf("m"))
        assertEquals("use", LiveModelCache.plan(c, 7, now))
        assertEquals("refresh", LiveModelCache.plan(c.copy(second = now - 2 * 3_600_000L), 7, now))
        assertEquals("fetch", LiveModelCache.plan(c.copy(second = now - 8 * 24 * 3_600_000L), 7, now))
        assertEquals("fetch", LiveModelCache.plan(c, 8, now))          // दूसरी key
        assertEquals("fetch", LiveModelCache.plan(null, 7, now))
    }

    // ---- 3. STT timeout + caption
    @Test fun groqTimeout_shortAndScaled() {
        assertEquals(4_000L, SttTiming.groqTimeoutMs(0))
        assertEquals(5_000L, SttTiming.groqTimeoutMs(5_000))
        assertEquals(9_000L, SttTiming.groqTimeoutMs(25_140))      // पहले 22s
        assertTrue(SttTiming.geminiTimeoutMs(25_140) <= 12_000L)
        assertTrue(SttTiming.groqTimeoutMs(25_140) + SttTiming.geminiTimeoutMs(25_140) < 27_878L)
    }

    @Test fun captionUsable() {
        assertTrue(SttTiming.captionUsable("मौसम कैसा है"))
        assertFalse(SttTiming.captionUsable("हाँ"))
        assertFalse(SttTiming.captionUsable("  "))
    }

    @Test fun wakeGate_skipsOnlyClearSpeechWithoutName() {
        val wake = listOf("डीके", "dk", "जार्विस")
        assertTrue(SttTiming.skipWakeStt("आज बाज़ार में बहुत भीड़ थी", wake))
        assertFalse(SttTiming.skipWakeStt("डीके मौसम बताओ ज़रा", wake))
        assertFalse(SttTiming.skipWakeStt("डी के मौसम बताओ ज़रा", wake))
        assertFalse(SttTiming.skipWakeStt("DK open youtube please", wake))
        assertFalse(SttTiming.skipWakeStt("जार्विस गाना चलाओ अभी", wake))
        // caption छोटा/खाली = पक्का नहीं, STT तय करे
        assertFalse(SttTiming.skipWakeStt("", wake))
        assertFalse(SttTiming.skipWakeStt("हाँ ठीक", wake))
    }

    // ---- 4. Coder: बंद मॉडल दोबारा नहीं
    @Test fun coder_skipsGoneModels() {
        val gone = setOf("gemini-2.5-pro")
        assertEquals(listOf("gemini-3.8-flash", "gemini-3.5-flash-lite"),
            GeminiModels.orderSkippingGone("gemini-2.5-pro", listOf("gemini-3.8-flash", "gemini-3.5-flash-lite")) { it in gone })
        // सब बंद = पूरा क्रम (असली गलती दिखे)
        assertEquals(listOf("gemini-2.5-pro"), GeminiModels.orderSkippingGone("gemini-2.5-pro", emptyList()) { true })
    }

    @Test fun coder_settingReplacedWhenGone() {
        assertEquals(GeminiModels.CODER, GeminiModels.replacementFor("gemini-2.5-pro") { it == "gemini-2.5-pro" })
        assertNull(GeminiModels.replacementFor("gemini-2.5-pro") { false })
        assertNull(GeminiModels.replacementFor(GeminiModels.CODER) { true })
        assertNull(GeminiModels.replacementFor("") { true })
        assertEquals("gemini-gone:abc123|gemini-2.5-pro", GeminiModels.goneKey("models/gemini-2.5-pro", "abc123"))
    }
}

package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v5.1: DK Live तेज़ जुड़ना - (मॉडल × key) की दौड़, गलत key/नाकाम जोड़ी से बचना, हल्का मोड, jitter buffer */
class LiveRaceTest {
    private fun src(name: String): String =
        listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name")).first { it.exists() }.readText()

    private val models = listOf("gemini-3.1-flash-live-preview", "gemini-2.5-flash-native-audio-preview-12-2025")

    @Test fun order_lastWinnerFirst_thenRecentSuccess_thenDefault() {
        val h = LiveRace.Health()
        // कोई इतिहास नहीं = मॉडल का क्रम, हर मॉडल पर हर key
        assertEquals(listOf("0|${models[0]}", "1|${models[0]}", "0|${models[1]}", "1|${models[1]}"), h.order(models, 2, 1_000).map { it.id })
        h.ok(LiveRace.Combo(models[1], 1), 900, now = 2_000)          // पहले यह जुड़ी थी
        h.ok(LiveRace.Combo(models[0], 1), 700, now = 3_000)          // आख़िरी जीत
        val o = h.order(models, 2, 4_000).map { it.id }
        assertEquals("1|${models[0]}", o[0])                          // पिछली जीती पहले
        assertEquals("1|${models[1]}", o[1])                          // फिर हाल में जुड़ी
        assertEquals(4, o.size)
    }

    @Test fun badKey_10min_failedCombo_2min_neverRetriedInLoop() {
        val h = LiveRace.Health()
        h.keyBad(0, now = 0)
        assertTrue(h.order(models, 2, 1_000).none { it.keyIdx == 0 })
        assertTrue(h.order(models, 2, LiveRace.KEY_BAD_MS - 1).none { it.keyIdx == 0 })
        assertTrue(h.order(models, 2, LiveRace.KEY_BAD_MS + 1).any { it.keyIdx == 0 })   // 10 मिनट बाद फिर
        val c = LiveRace.Combo(models[0], 1)
        h.comboFail(c, now = 0)
        assertFalse(h.order(models, 2, 60_000).contains(c))
        assertTrue(h.order(models, 2, LiveRace.COMBO_FAIL_MS + 1).contains(c))           // 2 मिनट बाद फिर
        assertEquals(10 * 60_000L, LiveRace.KEY_BAD_MS)
        assertEquals(2 * 60_000L, LiveRace.COMBO_FAIL_MS)
        // नेट वापस आया = नेट वाली रुकावट हटे, गलत key वाली नहीं
        h.comboFail(c, now = 0); h.keyBad(0, now = 0); h.clearComboFails()
        val o = h.order(models, 2, 1_000)
        assertTrue(o.contains(c)); assertTrue(o.none { it.keyIdx == 0 })
        // limit (Cooldown) वाली जोड़ी बाहर
        assertTrue(h.order(models, 2, 1_000) { it.model == models[0] }.all { it.model == models[1] })
    }

    @Test fun classify_keyVsModelVsConfigVsNet() {
        assertEquals(LiveRace.Fail.KEY, LiveRace.classify(1007, "API key not valid. Please pass a valid API key."))
        assertEquals(LiveRace.Fail.KEY, LiveRace.classify(1011, "You exceeded your current quota"))
        assertEquals(LiveRace.Fail.KEY, LiveRace.classify(429, ""))
        assertEquals(LiveRace.Fail.KEY, LiveRace.classify(403, "forbidden"))
        assertEquals(LiveRace.Fail.KEY, LiveRace.classify(401, ""))
        assertEquals(LiveRace.Fail.MODEL, LiveRace.classify(1008, "models/x is not found for API version v1beta"))
        assertEquals(LiveRace.Fail.CONFIG, LiveRace.classify(1007, "Request contains an invalid argument."))
        assertEquals(LiveRace.Fail.CONFIG, LiveRace.classify(1007, "Voice name Sulafat is not supported"))
        assertEquals(LiveRace.Fail.NET, LiveRace.classify(0, "SocketTimeoutException: timeout"))
        assertEquals(LiveRace.Fail.NET, LiveRace.classify(0, "UnknownHostException: Unable to resolve host"))
        assertFalse(LiveRace.isQuota(1000, "generate content"))        // "rate" शब्द के अंदर - limit नहीं
    }

    @Test fun stagger_1500ms_max3_failureLaunchesNextImmediately() {
        assertTrue(LiveRace.shouldLaunch(inFlight = 0, queued = 3, now = 0, lastLaunchAt = 0))
        assertFalse(LiveRace.shouldLaunch(inFlight = 1, queued = 3, now = 1_499, lastLaunchAt = 0))
        assertTrue(LiveRace.shouldLaunch(inFlight = 1, queued = 3, now = 1_500, lastLaunchAt = 0))
        assertTrue(LiveRace.shouldLaunch(inFlight = 2, queued = 3, now = 3_000, lastLaunchAt = 1_500))
        assertFalse(LiveRace.shouldLaunch(inFlight = 3, queued = 3, now = 9_000, lastLaunchAt = 3_000))   // 3 से ज़्यादा नहीं
        assertFalse(LiveRace.shouldLaunch(inFlight = 0, queued = 0, now = 9_000, lastLaunchAt = 0))
        assertTrue(LiveRace.shouldLaunch(inFlight = 1, queued = 2, now = 200, lastLaunchAt = 0L - LiveRace.STAGGER_MS))   // नाकाम = lastLaunch 0 -> अगली तुरंत
        assertEquals(1_500L, LiveRace.STAGGER_MS); assertEquals(3, LiveRace.MAX_PARALLEL)
    }

    @Test fun adaptiveTimeout_4sGood_upTo22sSlow() {
        assertEquals(LiveRace.DEFAULT_TIMEOUT_MS, LiveRace.timeoutMs(emptyList()))
        assertEquals(4_000L, LiveRace.timeoutMs(listOf(600, 800, 700)))            // अच्छा नेट
        assertEquals(15_000L, LiveRace.timeoutMs(listOf(4_000, 5_000, 6_000)))     // धीमा
        assertEquals(22_000L, LiveRace.timeoutMs(listOf(12_000, 15_000)))          // बहुत धीमा - 22s तक
    }

    @Test fun fastModelFirst_extendedThinkingLast() {
        val o = LiveRace.fastFirst(listOf("gemini-x-thinking-live", "gemini-3.1-flash-live-preview"))
        assertEquals("gemini-3.1-flash-live-preview", o.first())
    }

    @Test fun health_encodeDecode_keepsLastWinAndTimes() {
        val h = LiveRace.Health()
        h.ok(LiveRace.Combo(models[0], 2), 850, now = 5_000)
        h.keyBad(1, now = 5_000)
        val d = LiveRace.Health.decode(h.encode())
        assertEquals("2|${models[0]}", d.lastWin)
        assertEquals(listOf(850L), d.recentTimes())
        assertTrue(d.isKeyBad(1, 6_000))
        assertEquals("", LiveRace.Health.decode("कचरा\nx\ty").lastWin)            // खराब data = crash नहीं
    }

    @Test fun jitter_firstChunkNeverWaits_midReplyUnderrunWaits250() {
        assertFalse(LiveRace.Jitter.underrun(playedThisTurn = false, turnActive = true, now = 5_000, playEnd = 1_000))   // जवाब की शुरुआत - कोई देरी नहीं
        assertTrue(LiveRace.Jitter.underrun(playedThisTurn = true, turnActive = true, now = 5_000, playEnd = 1_000))
        assertFalse(LiveRace.Jitter.underrun(playedThisTurn = true, turnActive = true, now = 1_020, playEnd = 1_000))    // अभी बज रहा
        assertTrue(LiveRace.Jitter.keepFilling(100, 50)); assertFalse(LiveRace.Jitter.keepFilling(260, 50))
        assertFalse(LiveRace.Jitter.keepFilling(100, LiveRace.Jitter.MAX_WAIT_MS))
        assertTrue(LiveRace.Jitter.TARGET_MS in 200..300)
    }

    @Test fun keyNeverInLabelOrLogs() {
        val c = LiveRace.Combo("gemini-3.1-flash-live-preview", 0)
        assertEquals("gemini-3.1-flash-live-preview • key #1", c.label)
        val g = src("GeminiLive.kt")
        val raceStart = g.indexOf("private fun startRace(")
        val raceEnd = g.indexOf("private fun sendContext(")
        val race = g.substring(raceStart, raceEnd)
        // log / स्क्रीन वाली किसी लाइन में key नहीं (सिर्फ़ उसका नंबर)
        race.lines().filter { it.contains("log(") || it.contains("setLink(") }.forEach {
            assertFalse(it, it.contains("a.key") || it.contains("\${key}") || it.contains("\$key "))
        }
        assertTrue(race.contains("\"\$URL?key=\${a.key}\""))                         // key सिर्फ़ URL में
    }

    @Test fun wiring_raceLightModePrewarmInvokeContext() {
        val g = src("GeminiLive.kt")
        assertTrue(g.contains("private val http: OkHttpClient = LiveNet.http"))
        assertTrue(g.contains("startRace(keys, models, live = true"))
        assertTrue(g.contains("throw Fatal(\"धीमा नेट"))
        assertTrue(g.contains("light = true"))
        assertTrue(g.contains("liveSystemPrompt(withMemory = false)"))
        assertTrue(g.contains("sendContext(ws); ready = true; flushPending()"))
        assertTrue(g.contains("\"turnComplete\", false"))
        val net = src("LiveNet.kt")
        assertTrue(net.contains("ACTION_SCREEN_ON") && net.contains("ACTION_SCREEN_OFF") && net.contains("warmedThisScreen"))
        assertFalse(net.contains("newWebSocket") || net.contains("AudioRecord"))   // गरम करना = सिर्फ़ HTTPS, Live/माइक नहीं
        val s = src("DKAccessibilityService.kt")
        assertTrue(s.contains("LiveNet.register(this)") && s.contains("LiveNet.unregister(this)"))
        assertTrue(s.contains("enterLight(e.message"))
        assertTrue(s.contains("gemLive.backgroundRace("))
        assertTrue(s.contains("if (lightMode && DKState.live) return hearLight()"))
        val v = src("DKVoiceInteractionSessionService.kt")
        assertTrue(v.contains("override fun onShow(") && v.contains("invokedNow(") && v.contains("startAssistantActivity("))
        assertFalse(v.contains("selfEnableAccessibility"))                          // Accessibility कभी अपने-आप नहीं
        val inv = s.substring(s.indexOf("fun invokedNow("), s.indexOf("fun invokedNow(") + 400)
        assertTrue(inv.contains("Setup.hasMic(this)"))                              // इजाज़त नहीं = माइक नहीं
        val b = src("BrainManager.kt")
        assertTrue(b.contains("fun liveMemoryContext()") && b.contains("if (withMemory) append(Memory.factsBlock(ctx))"))
        val m = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() }.readText()
        assertFalse(m.contains("SYSTEM_ALERT_WINDOW"))
    }
}

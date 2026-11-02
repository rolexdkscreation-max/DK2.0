package com.dk.assistant

import com.dk.assistant.LiveFailWhy.Kind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * नवंबर 2026 (मालिक): Live टूटे = चुपचाप text मोड + Gemini TTS (न चले तो फ़ोन की आवाज़); पीछे सेहत-जाँच; लिमिट में कम से कम 60s;
 * ठीक होते ही अगली बात से फिर Live; Live और Text की कॉल / गलतियाँ अलग-अलग.
 */
class LiveHealthTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    @After fun clean() { ModeTrack.clear(); ModeTrack.sink = null }

    @Test fun quotaWaitsAtLeast60s_thenBacksOff_noRetryLoop() {
        assertEquals(60_000L, LiveHealth.cooldownMs(Kind.QUOTA, 0))
        assertEquals(60_000L, LiveHealth.cooldownMs(Kind.QUOTA, 1))
        assertEquals(120_000L, LiveHealth.cooldownMs(Kind.QUOTA, 2))
        assertEquals(240_000L, LiveHealth.cooldownMs(Kind.QUOTA, 3))
        assertEquals(480_000L, LiveHealth.cooldownMs(Kind.QUOTA, 4))
        assertEquals(600_000L, LiveHealth.cooldownMs(Kind.QUOTA, 5))
        assertEquals(600_000L, LiveHealth.cooldownMs(Kind.QUOTA, 50))
        for (s in 0..10) assertTrue(LiveHealth.cooldownMs(Kind.QUOTA, s) >= 60_000L)
        // जाँच ठीक हो तब भी 60s से पहले Live नहीं
        assertFalse(LiveHealth.open(1_000L + 59_999L, 1_000L, Kind.QUOTA, 1, LiveHealth.Probe.OK))
        assertTrue(LiveHealth.open(1_000L + 60_000L, 1_000L, Kind.QUOTA, 1, LiveHealth.Probe.OK))
        // पहली जाँच ही cooldown पूरा होने पर
        assertEquals(60_000L, LiveHealth.nextCheckInMs(0L, 0L, Kind.QUOTA, 1, 0L))
        assertTrue(LiveHealth.capMs(Kind.QUOTA, 1) >= 60_000L)
    }

    @Test fun strikesGrowOnRepeatedQuotaOnly() {
        assertEquals(1, LiveHealth.strikesAfter(null, Kind.QUOTA, 0))
        assertEquals(2, LiveHealth.strikesAfter(Kind.QUOTA, Kind.QUOTA, 1))
        assertEquals(3, LiveHealth.strikesAfter(null, Kind.QUOTA, 2))      // Live थोड़ी देर जुड़ा, फिर लिमिट = आगे बढ़ो
        assertEquals(2, LiveHealth.strikesAfter(Kind.QUOTA, Kind.NET, 2))   // दूसरी रुकावट = गिनती वही
    }

    @Test fun netAndSocketRecoverFast() {
        for (k in listOf(Kind.NET, Kind.SLOW, Kind.UNREACHABLE, Kind.SERVER)) {
            assertTrue(LiveHealth.probes(k)); assertTrue(LiveHealth.silent(k))
            assertEquals(3_000L, LiveHealth.cooldownMs(k, 0))
            assertEquals(5_000L, LiveHealth.everyMs(k))
            assertEquals(3_000L, LiveHealth.nextCheckInMs(0L, 0L, k, 0, 0L))
            assertEquals(5_000L, LiveHealth.nextCheckInMs(3_000L, 0L, k, 0, 3_000L))
            assertTrue(LiveHealth.open(3_000L, 0L, k, 0, LiveHealth.Probe.OK))
            assertFalse(LiveHealth.open(3_000L, 0L, k, 0, LiveHealth.Probe.NET))
        }
        assertTrue(LiveHealth.dropToBackup(2)); assertFalse(LiveHealth.dropToBackup(1))
        assertEquals(Kind.SERVER, LiveHealth.softKind(Kind.MODEL))
        assertEquals(Kind.NET, LiveHealth.softKind(Kind.NET))
    }

    @Test fun probeResultAndKindChange() {
        assertEquals(LiveHealth.Probe.OK, LiveHealth.probeResult(200))
        assertEquals(LiveHealth.Probe.QUOTA, LiveHealth.probeResult(429))
        assertEquals(LiveHealth.Probe.KEY, LiveHealth.probeResult(403))
        assertEquals(LiveHealth.Probe.KEY, LiveHealth.probeResult(401))
        assertEquals(LiveHealth.Probe.SERVER, LiveHealth.probeResult(503))
        assertEquals(LiveHealth.Probe.NET, LiveHealth.probeResult(0, ioError = true))
        assertEquals(Kind.QUOTA, LiveHealth.kindAfter(Kind.NET, LiveHealth.Probe.QUOTA))
        assertEquals(Kind.NET, LiveHealth.kindAfter(Kind.NET, LiveHealth.Probe.NET))
        // जाँच से पता न चलने वाली रुकावटें = पुराना नियम, जाँच से कभी नहीं खुलतीं
        for (k in listOf(Kind.MODEL, Kind.CONFIG, Kind.MIC, Kind.MIC_BUSY, Kind.NO_KEY)) {
            assertFalse(LiveHealth.probes(k)); assertFalse(LiveHealth.open(Long.MAX_VALUE / 2, 0L, k, 0, LiveHealth.Probe.OK))
            assertEquals(k.retryMs, LiveHealth.capMs(k, 0))
        }
        assertFalse(LiveHealth.silent(Kind.KEY))                            // key गलत = एक बार बोलकर बताओ
    }

    @Test fun modeTrackKeepsLiveAndTextSeparate_noKeyInLog() {
        val log = ArrayList<String>()
        ModeTrack.sink = { log += it }
        ModeTrack.call(ModeTrack.Ch.LIVE, "x"); ModeTrack.error(ModeTrack.Ch.LIVE, "लिमिट खत्म: 429 key=AIzaSyA1234567890abcdef")
        ModeTrack.switchTo(ModeTrack.Ch.TEXT, "लिमिट")
        ModeTrack.call(ModeTrack.Ch.TEXT); ModeTrack.ok(ModeTrack.Ch.TEXT)
        ModeTrack.call(ModeTrack.Ch.TEXT); ModeTrack.error(ModeTrack.Ch.TEXT, "timeout")
        ModeTrack.call(ModeTrack.Ch.PROBE); ModeTrack.ok(ModeTrack.Ch.PROBE)
        ModeTrack.switchTo(ModeTrack.Ch.LIVE, "Live जुड़ा")
        assertEquals(1, ModeTrack.stat(ModeTrack.Ch.LIVE).calls); assertEquals(1, ModeTrack.stat(ModeTrack.Ch.LIVE).errors)
        assertEquals(2, ModeTrack.stat(ModeTrack.Ch.TEXT).calls); assertEquals(1, ModeTrack.stat(ModeTrack.Ch.TEXT).ok)
        assertEquals("timeout", ModeTrack.stat(ModeTrack.Ch.TEXT).lastError)
        assertEquals(1, ModeTrack.stat(ModeTrack.Ch.PROBE).ok)
        assertEquals(ModeTrack.Ch.LIVE, ModeTrack.active); assertEquals(2, ModeTrack.switches)
        assertTrue(log.any { it.startsWith("[LIVE] गलती") }); assertTrue(log.any { it.startsWith("[TEXT] गलती") })
        assertFalse(log.any { it.contains("AIzaSyA1234567890") })
        val t = ModeTrack.text()
        assertTrue(t.contains("[LIVE]") && t.contains("[TEXT]") && t.contains("[PROBE]"))
        ModeTrack.switchTo(ModeTrack.Ch.PROBE, "x"); assertEquals(ModeTrack.Ch.LIVE, ModeTrack.active)
        repeat(30) { ModeTrack.error(ModeTrack.Ch.TEXT, "e$it") }
        assertEquals(ModeTrack.KEEP, ModeTrack.stat(ModeTrack.Ch.TEXT).recent.size)
    }

    @Test fun firstSpokenPieceShorterInBackup() {
        assertEquals(45, LiveHealth.cutLimit(0, local = false))             // पुराना (Gemini आवाज़) वही
        assertEquals(28, LiveHealth.cutLimit(0, local = true))
        assertEquals(170, LiveHealth.cutLimit(1, local = true))
        assertTrue(src("AgentRunner.kt").contains("LiveHealth.cutLimit(sentCount, speaker.localOnly)"))
        // वाक्य-स्तर: । ? ! . पर तुरंत
        assertTrue(src("AgentRunner.kt").contains("if (c == '।' || c == '?' || c == '!' || c == '\\n') { cut = i; break }"))
    }

    @Test fun serviceWiring_silentFallback_healthCheck_twoWay() {
        val s = AccSrc.all()
        assertTrue(s.contains("startHealthCheck()"))
        assertTrue(s.contains("LiveNet.healthProbe(GeminiLive.key(me))"))
        assertTrue(s.contains("if (LiveHealth.open(last, backupAt, k, quotaStrikes, p))"))
        assertTrue(s.contains("liveNow(\"सेहत-जाँच ठीक\")"))
        assertTrue(s.contains("if (firstTime && !LiveHealth.silent(kind))"))
        assertTrue(s.contains("else if (userLive && LiveHealth.dropToBackup(liveSoftFails))"))
        assertTrue(s.contains("healthJob?.cancel(); healthJob = null"))
        assertTrue(s.contains("ModeTrack.switchTo(ModeTrack.Ch.TEXT"))
        assertTrue(s.contains("ModeTrack.switchTo(ModeTrack.Ch.LIVE"))
        assertTrue(s.contains("ModeTrack.error(ModeTrack.Ch.LIVE"))
        assertFalse(s.contains("KokoroTtsEngine"))
        // जाँच Live सत्र / माइक नहीं खोलती, key URL में नहीं
        val net = src("LiveNet.kt")
        assertTrue(net.contains("header(\"x-goog-api-key\", key)"))
        assertFalse(net.contains("?key="))
        assertTrue(src("AgentRunner.kt").contains("ModeTrack.error(ModeTrack.Ch.TEXT"))
        assertTrue(src("DKApp.kt").contains("ModeTrack.sink = { DKLog.d(it) }"))
        assertTrue(src("SettingsSystem.kt").contains("ModeTrack.text()"))
    }

    /** बैकअप मोड: Gemini TTS पीछे IO thread पर; न चले / देर = तुरंत फ़ोन की आवाज़, फिर उस जवाब में आवाज़ नहीं बदलती */
    @Test fun backupVoiceGeminiTtsThenPhoneVoice() {
        val sp = src("Speaker.kt")
        assertTrue(sp.contains("CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())"))
        assertTrue(sp.contains("BackupVoice.waitMs(played)"))
        assertTrue(sp.contains("if (!phoneOnly) { phoneOnly = true; markPhoneVoice() }"))
        assertTrue(sp.contains("PhoneVoice.say(appCtx, chunk)"))
        assertFalse(sp.contains("lastLocal"))
        // pure नियम
        assertTrue(BackupVoice.phoneFirst(haveKeys = false, online = true, now = 0, downUntil = 0))
        assertTrue(BackupVoice.phoneFirst(haveKeys = true, online = false, now = 0, downUntil = 0))
        assertTrue(BackupVoice.phoneFirst(haveKeys = true, online = true, now = 5, downUntil = 10))
        assertFalse(BackupVoice.phoneFirst(haveKeys = true, online = true, now = 11, downUntil = 10))
        assertEquals(1_000L + BackupVoice.DOWN_MS, BackupVoice.downUntil(500, 1_000L, 0L))
        assertEquals(1_000L + BackupVoice.LIMIT_DOWN_MS, BackupVoice.downUntil(429, 1_000L, 0L))
        assertEquals(99_999_999L, BackupVoice.downUntil(429, 1_000L, 99_999_999L))
        assertTrue(BackupVoice.waitMs(false) <= 4_000L)          // पहली आवाज़ जल्दी (लंबी चुप्पी नहीं)
        assertTrue(BackupVoice.waitMs(true) >= BackupVoice.waitMs(false))
    }
}

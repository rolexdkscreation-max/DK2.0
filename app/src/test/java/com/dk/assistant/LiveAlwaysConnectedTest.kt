package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v4.6 (मालिक: "बोला हुआ लिखा जाता है, पर जवाब नहीं आता • कनेक्शन टूटे तो तुरंत जुड़े • ऐप खोलते ही लाइव").
 * जड़ 1: Live टूटा = 15s-10 मिनट "पुराना तरीका" (दूसरा STT + chat) - बोला दिखता, जवाब नहीं.
 * जड़ 2: connection खुला पर सत्र अटका - बचाव एक बार, फिर DK हमेशा चुप.
 */
class LiveAlwaysConnectedTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    @Test fun userLiveRetriesInSeconds() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L, 15_000L), (0..6).map { LiveRetry.liveOnMs(true, it) })
        assertEquals(1_000L, LiveRetry.liveOnMs(true, -2))
        assertEquals(60_000L, LiveRetry.liveOnMs(false, 0))
        assertTrue((0..9).all { LiveRetry.liveOnMs(true, it) <= 15_000L && LiveRetry.liveOnMs(true, it) < LiveRetry.offMs(true, it) })
        // wake word वाला छोटा Live पहले जैसा
        assertEquals(15_000L, LiveRetry.offMs(true, 0)); assertEquals(10 * 60_000L, LiveRetry.offMs(false, 0))
    }

    @Test fun stuckSessionCaughtAfterRescue() {
        val w = ReplyWatchdog()
        w.onSpeechStart(); w.onEndOfSpeech(1_000, 1_500)
        assertTrue(w.due(3_500))                 // server ने नहीं सुना
        assertFalse(w.dead(3_600))               // बचाव से पहले नहीं
        w.onRescued(3_500)                       // text भेजा
        assertFalse(w.dead(9_000))               // अभी 5.5s
        assertTrue(w.dead(9_600))                // 6s+ चुप = अटका -> नया connection
        assertFalse(w.dead(20_000))              // एक ही बार
        assertTrue(w.unanswered(9_600))          // वही बात नए connection पर दोबारा जाएगी
    }

    @Test fun stallNudgeAlsoCounts() {
        val w = ReplyWatchdog()
        w.onSpeechStart(); w.onEndOfSpeech(1_000, 1_500); w.onServerHeard()
        assertTrue(w.stalled(5_500))
        assertFalse(w.dead(11_000)); assertTrue(w.dead(11_600))
    }

    @Test fun answeredOrNewSpeechNeverDead() {
        val w = ReplyWatchdog()
        w.onSpeechStart(); w.onEndOfSpeech(1_000, 1_500); assertTrue(w.due(3_500)); w.onRescued(3_500)
        w.onServerAnswered()
        assertFalse(w.dead(30_000))
        w.onSpeechStart(); w.onEndOfSpeech(40_000, 1_500); w.onRescued(42_500); w.onSpeechStart()
        assertFalse(w.dead(60_000))
        val q = ReplyWatchdog(); q.onSpeechStart(); q.onEndOfSpeech(1_000, 1_500)
        assertFalse(q.dead(60_000))              // बचाव नहीं भेजा (जैसे शोर) = नया connection नहीं
    }

    @Test fun wiredIn() {
        val g = src("GeminiLive.kt")
        assertTrue(g.contains("replyWatch.dead(now)"))
        assertTrue(g.contains("handle = null; goAway = true; goAwayDrop = true"))
        assertTrue(g.contains("val running: Boolean get() = !stopped"))
        val s = src("DKAccessibilityService.kt")
        assertTrue(s.contains("LiveRetry.liveOnMs(e.recoverable, liveSoftFails)"))
        assertTrue(s.contains("liveOffSoft && System.currentTimeMillis() <= liveOffUntil"))
        assertTrue(s.contains("liveNow(\"Live चालू\")"))
        assertTrue(s.contains("fun appOpened()") && s.contains("registerDefaultNetworkCallback(netBack)"))
        assertTrue(s.contains("if (Prefs.micUserOff(this) || GeminiLive.key(this).isBlank()"))   // आपने बंद किया = बंद
        assertTrue(src("MainActivity.kt").contains("DKAccessibilityService.instance?.appOpened()"))
        val m = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() }.readText()
        assertTrue(m.contains("android.permission.ACCESS_NETWORK_STATE"))
    }
}

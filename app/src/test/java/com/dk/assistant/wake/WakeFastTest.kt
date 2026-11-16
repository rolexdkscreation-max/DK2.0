package com.dk.assistant.wake

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 16 नवंबर 2026 (मालिक: "हे डीके बोलने पर तुरंत चालू नहीं होता") - Live पहले से तैयार, मालिक-मिलान जल्दी */
class WakeFastTest {
    private fun f(p: String) = listOf(File("src/main/$p"), File("app/src/main/$p")).first { it.exists() }
    private fun src(n: String) = f("java/com/dk/assistant/$n").readText()

    @Test fun warm_startsBeforeWordIsDone() {
        val th = WakeRules.Sense.MID.th
        assertFalse(WakeRules.warmLive(0.1f, th))
        assertTrue("शब्द आधा होते ही", WakeRules.warmLive(th * 0.45f, th))
        assertTrue("पूरा पहचानने से पहले", th * 0.4f < th)
        assertTrue(WakeRules.warmGapOk(100_000, 0, ready = false, busy = false))
        assertFalse("पहले से तैयार = दोबारा नहीं", WakeRules.warmGapOk(100_000, 0, ready = true, busy = false))
        assertFalse("दौड़ चल रही = दोबारा नहीं", WakeRules.warmGapOk(100_000, 0, ready = false, busy = true))
        assertFalse("45 सेकंड में एक ही बार", WakeRules.warmGapOk(10_000, 1_000, ready = false, busy = false))
    }

    @Test fun ownerFast() {
        assertTrue(WakeRules.ownerFast(0.6f, 0.42f))
        assertFalse(WakeRules.ownerFast(0.3f, 0.42f))
        assertFalse(WakeRules.ownerFast(null, 0.42f))
    }

    @Test fun wiring_warmConnectionOnlyNoAudio() {
        val ww = src("wake/WakeWord.kt")
        assertTrue(ww.contains("near: () -> Unit = {}") && ww.contains("WakeRules.warmLive(sc, sense.th)"))
        assertFalse("आवाज़ इंटरनेट पर नहीं", ww.contains("okhttp") || ww.contains("Transcriber"))
        val warm = src("GeminiLiveWakeWarm.kt")
        assertTrue(warm.contains("startRace(keys, models, live = false, resumeId = null)"))
        assertTrue("वही Live वाली key", warm.contains("Prefs.liveGeminiKeys(svc)"))
        assertFalse("माइक नहीं", warm.contains("AudioRecord") || warm.contains("micLoop"))
        val live = src("GeminiLive.kt")
        assertTrue(live.indexOf("awaitWakeWarm()") in 1 until live.indexOf("val pk = parked; parked = null"))
        val svc = com.dk.assistant.AccSrc.all()
        assertTrue(svc.contains("gemLive.wakeWarm(\"हे डीके\")"))
    }
}

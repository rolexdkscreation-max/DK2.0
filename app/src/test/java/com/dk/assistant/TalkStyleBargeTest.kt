package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 18 नवंबर 2026 (मालिक): (1) DK बोलते समय बीच में बोलूँ तो रुके, (2) बात लिखी दिखे तो जवाब ज़रूर आए,
 * (3) Settings → "आप कैसे बोलते हैं" (आम / धीरे / ज़ोर से / दूर से).
 */
class TalkStyleBargeTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()
    private val chunk = 40L

    // ------------------------------------------------------------ बीच में टोकना
    @Test fun barge_vadSureLowersThreshold() {
        // सस्ते फ़ोन: DK की गूँज 500, आपकी आवाज़ माइक में 1000 (गूँज का 2 गुना) - पहले कभी "शक" भी नहीं होता था
        fun run(prob: Float): Boolean {
            val b = BargeDetector(needChunks = 5, minRms = 900.0); b.ambient = 200.0
            var t = 1_000L
            repeat(12) { b.feed(500.0, t, 0.0, prob); t += chunk }
            var hit = false
            repeat(8) { if (b.feed(1000.0, t, 0.0, prob)) hit = true; t += chunk }
            return hit
        }
        assertFalse("बिना VAD पुरानी सीमा (गूँज x2.5)", run(-1f))
        assertFalse("VAD कहे इंसान नहीं = पुरानी सीमा", run(0.4f))
        assertTrue("Silero पक्का इंसानी आवाज़ = नीची सीमा", run(0.95f))
    }

    @Test fun barge_vadSureStillIgnoresEcho() {
        // DK की अपनी गूँज पर भी Silero ऊँचा आता है - गूँज जितनी आवाज़ पर कभी नहीं
        val b = BargeDetector(needChunks = 5, minRms = 900.0); b.ambient = 200.0
        var t = 1_000L
        repeat(100) { i -> assertFalse("i=$i", b.feed(1200.0 + (i % 4) * 60, t, 0.0, 0.97f)); t += chunk }
    }

    @Test fun barge_styleScale() {
        val b = BargeDetector(needChunks = 5, minRms = 900.0); b.ambient = 200.0
        val base = b.threshold()
        b.scale = TalkStyle.FAR.bargeScale
        assertTrue(b.threshold() < base)
        b.scale = TalkStyle.LOUD.bargeScale
        assertTrue(b.threshold() > base)
    }

    @Test fun stop_ekMinute() {
        listOf("एक मिनट", "ek minute", "one second", "बस एक सेकंड", "रुको").forEach { assertTrue(it, LiveStop.isStop(it)) }
        listOf("एक मिनट में बताओ मौसम कैसा है", "पाँच मिनट का टाइमर लगाओ").forEach { assertFalse(it, LiveStop.isStop(it)) }
    }

    @Test fun barge_wiring() {
        val mic = src("GeminiLiveMic.kt")
        assertTrue(mic.contains("bargeDet.feed(rms, now, outLevel(now), prob)"))
        assertTrue("क्यों नहीं रुका - log", mic.contains("DK नहीं रुका: इंसानी आवाज़"))
        assertTrue(src("GeminiLive.kt").contains("BargeDetector(needChunks = 5, minRms = 900.0)"))
    }

    // ------------------------------------------------------------ जवाब नहीं आया
    @Test fun lastChance_rules() {
        assertTrue(LiveRecover.lastChanceOk(100_000L, 0L))
        assertFalse("मिनट में एक बार", LiveRecover.lastChanceOk(100_000L, 70_000L))
        assertTrue(LiveRecover.lastChanceOk(131_000L, 70_000L))
        assertEquals("आज मौसम कैसा है", LiveRecover.lastChanceText("आज मौसम कैसा है…", sorryOk = false))
        assertNull("शब्द नहीं = शोर, कुछ नहीं", LiveRecover.lastChanceText("", sorryOk = false))
        assertEquals(LiveRecover.SORRY_TEXT, LiveRecover.lastChanceText("", sorryOk = true))
        assertTrue(LiveRecover.SORRY_TEXT.contains("माफ़ कीजिए, फिर से बोलिए"))
    }

    @Test fun lastChance_wiring() {
        val te = src("GeminiLiveTurnEnd.kt")
        val stalled = te.substringAfter("internal fun GeminiLive.onStalled()").substringBefore("\n}\n")
        assertTrue("बचाव-text बंद में भी एक बार", stalled.contains("lastChance(\"Gemini ने सुना पर जवाब शुरू नहीं किया\", sorryOk = false)"))
        val r = src("GeminiLiveRecover.kt")
        assertTrue(r.substringAfter("internal fun GeminiLive.stuckNoSwap()").substringBefore("\n}\n").contains("lastChance("))
    }

    // ------------------------------------------------------------ आप कैसे बोलते हैं
    @Test fun style_values() {
        assertEquals(TalkStyle.NORMAL, TalkStyle.of(null))
        assertEquals(TalkStyle.NORMAL, TalkStyle.of("???"))
        assertEquals(TalkStyle.FAR, TalkStyle.of("far"))
        val n = TalkStyle.NORMAL
        assertEquals(1.0, n.startScale, 0.0); assertEquals(1.0, n.bargeScale, 0.0); assertEquals(0, n.endExtraMs); assertEquals(1.0, n.gainScale, 0.0)
        assertTrue(TalkStyle.SOFT.startScale < 1.0 && TalkStyle.SOFT.gainScale > 1.0)
        assertTrue(TalkStyle.LOUD.startScale > 1.0 && TalkStyle.LOUD.bargeScale > 1.0)
        assertTrue(TalkStyle.FAR.startScale < TalkStyle.SOFT.startScale && TalkStyle.FAR.endExtraMs > TalkStyle.SOFT.endExtraMs)
        assertFalse("दूर से = दूर की आवाज़ की रोक बंद", TalkStyle.FAR.farFilterOk)
        assertEquals(4, TalkStyle.entries.map { it.key }.distinct().size)
    }

    @Test fun style_softVoiceStartsSpeech() {
        fun starts(scale: Double): Boolean {
            val d = TurnDetector(); d.styleScale = scale
            var t = 0L; var started = false
            repeat(4) { if (d.feed(500.0, t) == TurnDetector.Event.SPEECH_START) started = true; t += chunk }
            return started
        }
        assertFalse("आम: धीमी आवाज़ (500) पर शुरू नहीं", starts(1.0))
        assertTrue("दूर से: वही आवाज़ = बात शुरू", starts(TalkStyle.FAR.startScale))
    }

    @Test fun style_farWaitsLongerPause() {
        fun endsAfter(extra: Int): Int {
            val d = TurnDetector(); d.endExtraMs = extra
            var t = 0L
            repeat(15) { d.feed(3000.0, t); t += chunk }
            var ms = 0
            while (ms < 3000) { if (d.feed(100.0, t) == TurnDetector.Event.SPEECH_END) return ms; t += chunk; ms += chunk.toInt() }
            return -1
        }
        val n = endsAfter(0); val f = endsAfter(TalkStyle.FAR.endExtraMs)
        assertTrue("n=$n f=$f", n > 0 && f >= n + TalkStyle.FAR.endExtraMs - 40)
    }

    @Test fun style_wiring() {
        val o = src("GeminiLiveOwner.kt")
        assertTrue(o.contains("applyTalkStyle(Prefs.talkStyle(svc))"))
        assertTrue(o.contains("bargeDet.scale = s.bargeScale; speakerBarge.scale = s.bargeScale"))
        val p = src("Prefs.kt")
        assertTrue(p.contains("fun ownerFarOn(c: Context) = ownerFarRaw(c) && talkStyle(c).farFilterOk"))
        val sv = src("SettingsVoice.kt")
        assertTrue(sv.contains("आप कैसे बोलते हैं") && sv.contains("putString(TalkStyle.PREF, st.key)"))
    }
}

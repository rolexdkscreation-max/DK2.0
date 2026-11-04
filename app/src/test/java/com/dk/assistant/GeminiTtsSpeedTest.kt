package com.dk.assistant

import android.media.AudioAttributes
import com.dk.assistant.voice.engine.SpeechPipeline
import com.dk.assistant.voice.engine.TextChunker
import com.dk.assistant.voice.engine.VoiceEngines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * नवंबर 2026 (मालिक): "Gemini TTS चालू हो तो ठीक से सुनता नहीं, जवाब देर से आता है, कभी text आता है पर बोलता
 * नहीं" + "power बटन से खोलने पर कभी-कभी Live जुड़ने की आवाज़ नहीं आती".
 */
class GeminiTtsSpeedTest {
    private fun dir() = listOf(File("src/main/java/com/dk/assistant"), File("app/src/main/java/com/dk/assistant")).first { it.exists() }
    private fun src(name: String) = File(dir(), name).readText()

    @Test fun firstVoiceRace() {
        // Live की आवाज़ आ गई, TTS 900 ms तक चुप = Live की अपनी आवाज़
        assertTrue(SplitRescue.firstTooSlow(now = 1_900, liveSince = 1_000, ttsStarted = false, open = true))
        assertFalse(SplitRescue.firstTooSlow(now = 1_800, liveSince = 1_000, ttsStarted = false, open = true))
        assertFalse(SplitRescue.firstTooSlow(now = 5_000, liveSince = 1_000, ttsStarted = true, open = true))   // TTS बज गया
        assertFalse(SplitRescue.firstTooSlow(now = 5_000, liveSince = 1_000, ttsStarted = false, open = false)) // सच-जाँच / आप बोल रहे
        assertFalse(SplitRescue.firstTooSlow(now = 5_000, liveSince = 0, ttsStarted = false, open = true))      // Live की आवाज़ अभी नहीं
        assertTrue(SplitRescue.FIRST_BUDGET_MS <= 1_000)
    }

    @Test fun stuckPieces() {
        assertTrue(SplitRescue.stuck(SplitRescue.FIRST_STUCK_MS, first = true))
        assertFalse(SplitRescue.stuck(SplitRescue.FIRST_STUCK_MS - 1, first = true))
        assertTrue(SplitRescue.stuck(SplitRescue.NEXT_STUCK_MS, first = false))
        assertFalse(SplitRescue.stuck(1_000, first = false))
        // पहले 12 + 12 सेकंड तक अटका रह सकता था - अब कुछ सेकंड में Live पर
        assertTrue(SplitRescue.NEXT_STUCK_MS <= 3_000 && SplitRescue.FIRST_STUCK_MS <= 2_000)
    }

    @Test fun resumePointBacksOffSoNoWordIsLost() {
        val chunk = 24_000 * 2 / 10          // हर टुकड़ा 100 ms
        val sizes = List(30) { chunk }
        val lens = List(30) { it * 4 }       // लिखावट 4 अक्षर / टुकड़ा
        assertEquals(0, SplitRescue.startIndex(0, lens, sizes))
        // अक्षर 60 = टुकड़ा 15 पर लिखावट पहुँची -> 600 ms (6 टुकड़े) पीछे
        assertEquals(9, SplitRescue.startIndex(60, lens, sizes))
        assertEquals(0, SplitRescue.startIndex(8, lens, sizes))              // शुरू के पास = शुरू से
        assertEquals(24, SplitRescue.startIndex(10_000, lens, sizes))        // लिखावट अभी पहुँची नहीं = आख़िर से थोड़ा पीछे
        assertEquals(0, SplitRescue.startIndex(50, emptyList(), emptyList()))
    }

    @Test fun geminiTtsCutsFirstPieceEarly() {
        assertEquals(TextChunker.FAST_NET, SpeechPipeline.profileFor(VoiceEngines.GEMINI_TTS))
        assertEquals(TextChunker.STANDARD, SpeechPipeline.profileFor("worker"))
        val c = TextChunker().apply { reset(TextChunker.FAST_NET) }
        assertEquals(listOf("अच्छा सर, ठीक है,"), c.feed("अच्छा सर, ठीक है, मैं"))      // 3 शब्द पर अल्पविराम = तुरंत
        val d = TextChunker().apply { reset(TextChunker.FAST_NET) }
        val out = d.feed("आज मौसम बहुत अच्छा लग रहा है और हवा भी ठंडी चल रही है ")
        assertTrue(out.isNotEmpty() && TextChunker.words(out.first()) <= 6)    // पुराना 8 शब्द तक रुकता था
        val e = TextChunker().apply { reset(TextChunker.FAST_NET) }
        e.feed("हाँ जी ")
        assertEquals("हाँ जी", e.flushFirstIfStalled(TextChunker.FAST_NET.stallMin))
        assertTrue(TextChunker.FAST_NET.stallMs < TextChunker.STANDARD.stallMs)
    }

    @Test fun wiredSoTextNeverStaysSilent() {
        val pipe = File(dir(), "voice/engine/SpeechPipeline.kt").readText()
        assertTrue(pipe.contains("fun takeOver(at: Int, why: String): Boolean"))
        assertTrue(pipe.contains("if (rescue(s, SplitRescueWhy.FAILED, 0L)) return"))
        assertTrue(pipe.contains("com.dk.assistant.SplitRescue.stuck(waited, first)"))
        assertTrue(pipe.contains("if (released || handed || t.isEmpty()) return"))
        val g = LiveSrc.all()
        assertTrue(g.contains("override fun takeOver(at: Int, why: String): Boolean = rescueWithLive(at, why)"))
        assertTrue(g.contains("if (connSplit && keepSplitAudio(pcm)) continue"))
        assertTrue(g.contains("splitRaceCheck(now)"))
        assertTrue(g.contains("if (connSplit && !splitLive) return"))
        assertTrue(g.contains("resetLiveKeep()") && g.contains("dropLiveKeep()"))
        // माइक: TTS का टुकड़ा "बन रहा" हो (कुछ बज नहीं रहा) तो आपकी आवाज़ रोकी न जाए
        assertTrue(src("GeminiLiveMic.kt").contains("val playing = isAudible()"))
        assertTrue(src("GeminiLive.kt").contains("pipeline.talking"))
    }

    @Test fun chimeAudibleEvenOnSilentRingerAndNotMaskedByAck() {
        assertEquals(AudioAttributes.USAGE_MEDIA, LiveChime.USAGE)
        val p = LiveChime.pcm()
        val w = LiveChime.withLead(p)
        assertEquals(p.size + LiveChime.RATE * LiveChime.LEAD_MS / 1000, w.size)
        assertTrue(w.take(LiveChime.RATE * LiveChime.LEAD_MS / 1000).all { it.toInt() == 0 })
        assertTrue(InvokeAck.expectNow(0).not())
        InvokeAck.expect(1_000)
        assertTrue(InvokeAck.expectNow(1_000 + InvokeAck.EXPECT_MS - 1))
        assertFalse(InvokeAck.expectNow(1_000 + InvokeAck.EXPECT_MS))
        assertTrue(src("DKAccessibilityService.kt").contains("InvokeAck.expect()"))
        assertTrue(src("InvokeSession.kt").contains("InvokeAck.expectNow()"))
        assertFalse(src("InvokeSession.kt").contains("USAGE_ASSISTANCE_SONIFICATION)"))
    }
}

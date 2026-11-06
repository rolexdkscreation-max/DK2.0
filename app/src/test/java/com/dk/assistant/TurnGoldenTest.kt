package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * नवंबर 2026 (Live जाँच #6): "बात शुरू / खत्म" का तय (golden) बेंचमार्क - CI में हर PR पर चलता है.
 * पहले constants (जैसे SERVER_SILENCE_MS 800 -> 500 -> 800) अंदाज़े से बदलते रहे. अब कोई भी बदलाव जो इन हालात में
 * DK को बिगाड़े (शोर पर बात शुरू / बात खत्म न पहचानना / देर से पहचानना) - यह test fail करेगा.
 *
 * हर हालात 40ms के टुकड़ों की (RMS, VAD-संभावना) सूची है - वही [TurnDetector] जो Live में (वही constants).
 * दो तरीके नापे जाते हैं: पुराना (सिर्फ़ RMS) और नया (RMS + Silero VAD). scorecard build/turn-golden.txt में लिखा जाता है
 * (CI उसे job summary में दिखाता है).
 */
class TurnGoldenTest {
    private fun det() = TurnDetector(GeminiLive.CHUNK_MS, endSilenceMs = GeminiLive.CLIENT_END_MS, startFactor = LiveHear.START_FACTOR,
        startMin = LiveHear.START_MIN, loudNoiseFactor = LiveHear.LOUD_FACTOR,
        longSpeechMs = TurnEnd.LONG_SPEECH_MS, longEndSilenceMs = TurnEnd.LONG_END_SILENCE_MS, steadyResetMs = 6_000,
        keepFactor = LiveHear.KEEP_FACTOR, keepMin = LiveHear.KEEP_MIN, tailFactor = LiveHear.TAIL_FACTOR)

    private class Seg(val ms: Int, val rms: Double, val p: Float, val speech: Boolean, val jitter: Double = 0.0)

    /** हालात: नाम, टुकड़े, कितनी बातें सच में (0 = कोई बात नहीं - शोर) */
    private class Case(val name: String, val segs: List<Seg>, val utterances: Int)

    data class Score(var falseStarts: Int = 0, var missedStarts: Int = 0, var missedEnds: Int = 0,
                     var lateEnds: Int = 0, var endLatencySum: Long = 0, var endN: Int = 0) {
        val avgEnd: Long get() = if (endN == 0) 0 else endLatencySum / endN
    }

    private fun quiet(ms: Int, rms: Double = 180.0) = Seg(ms, rms, 0.02f, false)
    private fun talk(ms: Int, rms: Double = 2500.0, p: Float = 0.93f) = Seg(ms, rms, p, true, jitter = 0.25)
    private fun pause(ms: Int, rms: Double = 200.0, p: Float = 0.35f) = Seg(ms, rms, p, true)
    private fun noise(ms: Int, rms: Double, p: Float = 0.06f, jitter: Double = 0.15) = Seg(ms, rms, p, false, jitter)

    private val cases = listOf(
        Case("शांत कमरा: बात + छोटा ठहराव", listOf(quiet(4000), talk(700), pause(240), talk(600), quiet(2000)), 1),
        Case("पंखा अचानक चालू (कोई बात नहीं)", listOf(quiet(4000), noise(5000, 1500.0)), 0),
        Case("TV/गाना (कोई बात नहीं)", listOf(quiet(4000), noise(5000, 2200.0, p = 0.12f, jitter = 0.4)), 0),
        Case("बात के बाद गाना चलता रहा", listOf(quiet(4000), talk(1500, 3500.0), noise(4000, 2000.0, p = 0.1f, jitter = 0.3)), 1),
        Case("धीमी आवाज़ (पास से)", listOf(quiet(4000), talk(1200, 300.0, p = 0.95f), quiet(2000)), 1),
        Case("लंबी बात, बीच में सोचना", listOf(quiet(4000), talk(1800), pause(420, 230.0, 0.3f), talk(1500), quiet(2500)), 1),
        Case("दो अलग बातें", listOf(quiet(4000), talk(900), quiet(1500), talk(900), quiet(2000)), 2),
    )

    /** एक हालात चलाओ */
    private fun run(c: Case, vad: Boolean, s: Score) {
        val d = det(); var t = 1_000L
        var rnd = 12345L
        fun r(): Double { rnd = (rnd * 6364136223846793005L + 1442695040888963407L); return ((rnd ushr 33) % 1000) / 1000.0 }
        var starts = 0; var ends = 0
        var inUtt = false; var uttEndAt = 0L; var uttCount = 0
        val ends0 = ArrayList<Long>()
        val uttEnds = ArrayList<Long>()
        for ((i, seg) in c.segs.withIndex()) {
            val next = c.segs.getOrNull(i + 1)
            val n = seg.ms / GeminiLive.CHUNK_MS
            if (seg.speech && !inUtt) { inUtt = true; uttCount++ }
            for (k in 0 until n) {
                val rms = seg.rms * (1.0 + seg.jitter * (r() * 2 - 1))
                val ev = d.feed(rms, t, if (vad) seg.p else -1f)
                if (ev == TurnDetector.Event.SPEECH_START) starts++
                if (ev == TurnDetector.Event.SPEECH_END) { ends++; ends0 += t }
                t += GeminiLive.CHUNK_MS
            }
            if (inUtt && seg.speech && (next == null || !next.speech)) { inUtt = false; uttEndAt = t; uttEnds += uttEndAt }
        }
        if (c.utterances == 0) { s.falseStarts += starts; return }
        if (starts < c.utterances) s.missedStarts += c.utterances - starts
        if (starts > c.utterances) s.falseStarts += starts - c.utterances
        for (ue in uttEnds) {
            val e = ends0.firstOrNull { it >= ue }
            if (e == null || e - ue > 3_000) { s.missedEnds++; continue }
            val lat = e - ue
            s.endLatencySum += lat; s.endN++
            if (lat > GeminiLive.CLIENT_END_MS + TurnEnd.LONG_END_SILENCE_MS) s.lateEnds++
        }
    }

    private fun score(vad: Boolean, only: Case? = null): Score = Score().also { s -> (only?.let { listOf(it) } ?: cases).forEach { run(it, vad, s) } }

    @Test fun golden_vad_scorecard() {
        val old = score(vad = false); val new = score(vad = true)
        val rows = cases.joinToString("\n") { c ->
            val o = score(false, c); val n = score(true, c)
            "| ${c.name} | ${o.falseStarts}/${o.missedStarts}/${o.missedEnds} | ${n.falseStarts}/${n.missedStarts}/${n.missedEnds} | ${n.avgEnd} ms |"
        }
        val card = "### बात शुरू/खत्म - golden बेंचमार्क\n\n" +
            "| हालात | पुराना (झूठी शुरुआत/छूटी शुरुआत/छूटा अंत) | नया VAD | नया: अंत की देरी |\n|---|---|---|---|\n$rows\n\n" +
            "कुल - पुराना: झूठी ${old.falseStarts}, छूटी ${old.missedStarts}, छूटा अंत ${old.missedEnds}, औसत अंत ${old.avgEnd} ms • " +
            "नया: झूठी ${new.falseStarts}, छूटी ${new.missedStarts}, छूटा अंत ${new.missedEnds}, औसत अंत ${new.avgEnd} ms\n"
        try { File("build").mkdirs(); File("build/turn-golden.txt").writeText(card) } catch (_: Exception) {}
        println(card)

        // नया तरीका: शोर पर कभी बात शुरू नहीं, हर बात पकड़ी, हर बात का अंत समय पर
        assertEquals("VAD: शोर/गाने पर झूठी शुरुआत", 0, new.falseStarts)
        assertEquals("VAD: छूटी बात", 0, new.missedStarts)
        assertEquals("VAD: बात खत्म नहीं पहचानी", 0, new.missedEnds)
        assertEquals("VAD: देर से अंत", 0, new.lateEnds)
        assertTrue("VAD: औसत अंत ${new.avgEnd} ms", new.avgEnd <= GeminiLive.CLIENT_END_MS + 160)
        // और पुराने से कभी खराब नहीं
        assertTrue(new.falseStarts <= old.falseStarts && new.missedStarts <= old.missedStarts && new.missedEnds <= old.missedEnds)
    }

    @Test fun golden_quietRoom_sameAsBefore() {
        // शांत कमरे में VAD कुछ नहीं बिगाड़ता: पुराना और नया दोनों एक बात, समय पर अंत
        val c = cases[0]
        val o = score(false, c); val n = score(true, c)
        assertEquals(0, o.falseStarts + o.missedStarts + o.missedEnds)
        assertEquals(0, n.falseStarts + n.missedStarts + n.missedEnds)
        assertTrue(n.avgEnd <= o.avgEnd + 40)
    }

    @Test fun golden_noVad_isOldBehaviour() {
        // prob = -1 (VAD नहीं) = हर टुकड़े पर ठीक पुराना नतीजा
        val a = det(); val b = det(); var t = 0L
        val rnd = java.util.Random(7)
        repeat(2_000) {
            val rms = 100.0 + rnd.nextDouble() * 3000
            assertEquals(a.feed(rms, t), b.feed(rms, t, -1f)); t += 40
        }
    }
}

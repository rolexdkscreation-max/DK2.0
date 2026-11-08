package com.dk.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/** नवंबर 2026 (मालिक): \"Hey DK\" दूर से भी, अनसुना नहीं - 30 सेकंड सिखाना, दूर वाले नमूने, संवेदनशील सुनना, दोबारा बोलने पर पकड़ */
class WakeStrongTest {
    private fun src(p: String) = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()

    private fun run(v: WakeMatch.Vad, noise: Double, speech: Double): List<WakeMatch.Vad.Ev> {
        val evs = ArrayList<WakeMatch.Vad.Ev>()
        repeat(10) { evs.add(v.feed(noise)) }
        repeat(15) { evs.add(v.feed(speech)) }
        repeat(15) { evs.add(v.feed(noise)) }
        return evs
    }

    @Test fun farVad_catchesDistantVoice_normalMisses() {
        // दूर रखा फ़ोन: शोर ~25, आवाज़ ~65 (पुराना दरवाज़ा 75 माँगता था = अनसुना)
        assertEquals(0, run(WakeMatch.Vad(40, endMs = 350), 25.0, 65.0).count { it == WakeMatch.Vad.Ev.START })
        val far = run(WakeMatch.Vad(40, endMs = 350, far = true), 25.0, 65.0)
        assertEquals(1, far.count { it == WakeMatch.Vad.Ev.START })
        assertEquals(1, far.count { it == WakeMatch.Vad.Ev.END })
        // लगातार तेज़ शोर (पंखा / TV) = बोलना शुरू नहीं
        val loud = WakeMatch.Vad(40, far = true)
        repeat(40) { assertEquals(WakeMatch.Vad.Ev.NONE, loud.feed(800.0 + (it % 3) * 20)) }
    }

    @Test fun retryAfterNearMiss_easier_onlyForAWhile() {
        val th = 2.0f
        assertTrue(WakeMatch.nearMiss(2.4f, th)); assertFalse(WakeMatch.nearMiss(1.9f, th)); assertFalse(WakeMatch.nearMiss(2.8f, th))
        assertEquals(th, WakeMatch.liveThreshold(th, 0L, 10_000L), 1e-6f)
        assertEquals(th * WakeMatch.RETRY_BOOST, WakeMatch.liveThreshold(th, 10_000L, 13_000L), 1e-6f)
        assertEquals(th, WakeMatch.liveThreshold(th, 10_000L, 10_000L + WakeMatch.RETRY_MS + 1), 1e-6f)
        assertTrue(WakeMatch.RETRY_BOOST < WakeMatch.NEAR_K)   // ढील \"लगभग\" से कम - बिल्कुल अलग बात नहीं जगाती
    }

    @Test fun ownerSlack_onlyOnClearWord_neverBelowFloor() {
        assertEquals(VoicePrint.TH_NORMAL, WakeMatch.ownerThreshold(VoicePrint.TH_NORMAL, strongWord = false), 1e-6f)
        assertEquals(VoicePrint.TH_NORMAL - WakeMatch.OWNER_SLACK, WakeMatch.ownerThreshold(VoicePrint.TH_NORMAL, strongWord = true), 1e-6f)
        assertTrue(WakeMatch.ownerThreshold(0.27f, true) >= WakeMatch.OWNER_FLOOR)
        assertEquals(VoicePrint.TH_STRICT - WakeMatch.OWNER_SLACK, WakeMatch.ownerThreshold(VoicePrint.TH_STRICT, true), 1e-6f)
    }

    private fun tone(f1: Double, f2: Double, ms: Int, amp: Float = 0.3f): FloatArray {
        val n = 16 * ms; val half = n / 2
        return FloatArray(n) { i -> val f = if (i < half) f1 else f2; (amp * sin(2 * PI * f * i / 16000.0)).toFloat() }
    }

    @Test fun buildAug_keepsBase_addsFarTemplates_farMatches() {
        val takes = (0 until 5).map { k -> tone(400.0 + k * 3, 1200.0 + k * 5, 700) }
        val feats = takes.map { WakeMatch.features(it) }
        val far = takes.mapIndexed { i, x -> WakeMatch.features(VoiceAug.far(x, null, seed = i + 1)) }
        val plain = WakeMatch.Model.build(feats)
        val aug = WakeMatch.Model.buildAug(feats, far)
        assertNotNull(plain); assertNotNull(aug)
        assertEquals(plain!!.base, aug!!.base, 1e-6f)
        assertEquals(10, aug.templates.size)
        val distant = WakeMatch.features(VoiceAug.far(tone(402.0, 1204.0, 700), null, seed = 99))
        assertTrue(aug.distances(distant).first <= plain.distances(distant).first)
        // JSON में दूर वाले नमूने भी बचें
        assertEquals(10, WakeMatch.Model.fromJson(aug.toJson())!!.templates.size)
    }

    @Test fun wiring_30sTape_farListen_retry() {
        val hd = src("voice/HeyDk.kt")
        assertTrue(hd.contains("WakeMatch.Vad(CHUNK_MS, endMs = 350, far = true)"))
        assertTrue(hd.contains("WakeMatch.Model.buildAug(feats, far)") && hd.contains("VoiceAug.far("))
        assertTrue(hd.contains("WakeMatch.liveThreshold(") && hd.contains("WakeMatch.ownerThreshold("))
        assertTrue(hd.contains("VoicePrint.threshold(") && hd.contains("AudioRecord(source(c), RATE"))
        val set = src("SettingsActivity.kt")
        val i = set.indexOf("internal fun enrollHeyDk(")
        val body = set.substring(i, set.indexOf("internal fun ownerDialog(", i).coerceAtLeast(i + 1))
        assertTrue(body.contains("VoiceTapeRec.record(") && body.contains("HD.save(this@SettingsActivity, keep, sources[srcIdx], noise)"))
        assertFalse("एक-एक नमूने वाला पुराना तरीका हटा", body.contains("HD.recordTake("))
    }
}

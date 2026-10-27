package com.dk.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** v3.81: "Hey DK" फ़ोन के अंदर - शब्द-मिलान (MFCC + DTW), सीखना, सेव/लोड, "हाँ सर", जुड़ाव */
class WakeMatchTest {
    private val rate = WakeMatch.RATE

    /** नकली "शब्द": हर हिस्सा = दो सुर (formant जैसे) कुछ ms तक; आगे-पीछे चुप्पी + हल्का शोर */
    private fun word(parts: List<Triple<Double, Double, Int>>, stretch: Double = 1.0, gain: Double = 0.3, seed: Int = 1): FloatArray {
        val rnd = Random(seed)
        val out = ArrayList<Float>()
        fun silence(ms: Int) = repeat(rate * ms / 1000) { out.add(((rnd.nextDouble() - 0.5) * 0.002 * gain / 0.3).toFloat()) }
        silence(200)
        var ph1 = 0.0; var ph2 = 0.0
        for ((f1, f2, ms) in parts) {
            val n = (rate * ms * stretch / 1000).toInt()
            for (i in 0 until n) {
                ph1 += 2 * PI * f1 / rate; ph2 += 2 * PI * f2 / rate
                val env = sin(PI * i / n).coerceAtLeast(0.2)
                out.add((gain * env * (0.6 * sin(ph1) + 0.4 * sin(ph2)) + (rnd.nextDouble() - 0.5) * 0.01 * gain / 0.3).toFloat())
            }
        }
        silence(200)
        return out.toFloatArray()
    }

    private val heyDk = listOf(Triple(320.0, 2300.0, 160), Triple(700.0, 1250.0, 200), Triple(260.0, 2600.0, 160), Triple(420.0, 900.0, 220))
    private val other = listOf(Triple(800.0, 1400.0, 220), Triple(300.0, 800.0, 180), Triple(550.0, 2900.0, 200), Triple(900.0, 1700.0, 160))

    private fun model(): WakeMatch.Model {
        val takes = listOf(0.92, 0.97, 1.0, 1.04, 1.1).mapIndexed { i, s -> WakeMatch.features(word(heyDk, s, seed = 10 + i)) }
        return WakeMatch.Model.build(takes)!!
    }

    @Test fun features_trimSilenceAndNormalize() {
        val f = WakeMatch.features(word(heyDk))
        // 0.74 s शब्द + 0.4 s चुप्पी -> चुप्पी कटकर ~74-80 फ़्रेम
        assertTrue("${f.size}", f.size in 65..95)
        assertEquals(WakeMatch.DIMS, f[0].size)
        val mean = (0 until f.size).sumOf { f[it][0].toDouble() } / f.size
        assertEquals(0.0, mean, 1e-3)
        assertEquals(0, WakeMatch.features(FloatArray(100)).size)
    }

    @Test fun dtw_sameWordCloseOtherWordFar() {
        val a = WakeMatch.features(word(heyDk, 1.0, seed = 1))
        val b = WakeMatch.features(word(heyDk, 1.15, gain = 0.1, seed = 2))   // धीमा + दूर से
        val c = WakeMatch.features(word(other, 1.0, seed = 3))
        val same = WakeMatch.dtwOpen(a, b); val diff = WakeMatch.dtwOpen(a, c)
        assertTrue("same=$same diff=$diff", same * 1.8f < diff)
        assertTrue(WakeMatch.dtw(a, b) * 1.8f < WakeMatch.dtw(a, c))
        // बहुत अलग लंबाई = मिलान नहीं
        assertEquals(Float.MAX_VALUE, WakeMatch.dtw(a, a.copyOf(10).requireNoNulls()))
    }

    @Test fun openEnd_wakeWordThenCommandInOneBreath() {
        val m = model()
        val tail = word(other, 1.3, seed = 7)
        val joined = word(heyDk, 1.0, seed = 5).let { it.copyOf(it.size - rate / 10) } + tail
        val pre = rate / 5    // 200 ms चुप्पी (सुनने में पहले की आवाज़ भी साथ रहती है)
        fun anyCut(x: FloatArray) = m.prefixCuts().any { c -> m.matches(WakeMatch.features(x.copyOf(minOf(x.size, pre + c))), WakeMatch.Sense.NORMAL) }
        assertTrue(anyCut(joined))
        // दूसरा शब्द + बात = कहीं नहीं मिलना चाहिए
        assertFalse(anyCut(word(other, 1.0, seed = 8) + tail))
        // पूरी लंबी बात एक टुकड़े में (पुराना तरीका) - नहीं मिलती, इसीलिए कट
        assertFalse(m.matches(WakeMatch.features(joined), WakeMatch.Sense.NORMAL))
    }

    @Test fun model_acceptsOwnerWordRejectsOthers() {
        val m = model()
        for ((s, g, seed) in listOf(Triple(1.0, 0.3, 77), Triple(0.95, 0.15, 78), Triple(1.06, 0.5, 79))) {
            val f = WakeMatch.features(word(heyDk, s, g, seed))
            assertTrue("s=$s d=${m.distances(f)} th=${m.threshold(WakeMatch.Sense.NORMAL)}", m.matches(f, WakeMatch.Sense.NORMAL))
        }
        for (seed in 80..83) {
            val f = WakeMatch.features(word(other, 1.0, seed = seed))
            assertFalse("d=${m.distances(f)} th=${m.threshold(WakeMatch.Sense.HIGH)}", m.matches(f, WakeMatch.Sense.HIGH))
        }
        // खाली = कभी नहीं
        assertFalse(m.matches(emptyArray(), WakeMatch.Sense.HIGH))
        assertTrue(m.threshold(WakeMatch.Sense.LOW) < m.threshold(WakeMatch.Sense.NORMAL))
        assertTrue(m.threshold(WakeMatch.Sense.NORMAL) < m.threshold(WakeMatch.Sense.HIGH))
    }

    @Test fun outlier_findsWrongTake() {
        val takes = (0 until 4).map { WakeMatch.features(word(heyDk, 1.0 + it * 0.03, seed = 30 + it)) } +
            listOf(WakeMatch.features(word(other, 1.0, seed = 40)))
        assertEquals(4, WakeMatch.outlier(takes))
        assertEquals(-1, WakeMatch.outlier(takes.take(4)))
        assertNull(WakeMatch.Model.build(listOf(takes[0])))
    }

    @Test fun json_roundTrip() {
        val m = model()
        val back = WakeMatch.Model.fromJson(m.toJson())
        assertNotNull(back)
        assertEquals(m.templates.size, back!!.templates.size)
        assertEquals(m.base, back.base, 1e-4f)
        val f = WakeMatch.features(word(heyDk, 1.0, seed = 91))
        assertEquals(m.distances(f).first, back.distances(f).first, 0.02f)
        assertNull(WakeMatch.Model.fromJson("{bad"))
        assertNull(WakeMatch.Model.fromJson(null))
    }

    @Test fun acks_rotateAndParse() {
        assertEquals(WakeMatch.DEFAULT_ACKS, WakeMatch.parseAcks(" "))
        assertTrue(WakeMatch.DEFAULT_ACKS.containsAll(listOf("हाँ सर", "बोलिए सर", "हाँ बॉस")))
        assertEquals(listOf("जी सर", "हुकुम"), WakeMatch.parseAcks("जी सर, हुकुम,, जी सर"))
        var last = ""
        repeat(20) { val a = WakeMatch.nextAck(WakeMatch.DEFAULT_ACKS, last, Random(it)); assertNotEquals(last, a); last = a }
        assertEquals("WhatsApp खोलो", WakeMatch.stripWake("Hey DK, WhatsApp खोलो।", listOf("डीके", "dk")))
        assertEquals("मौसम बताओ", WakeMatch.stripWake("हेलो डीके मौसम बताओ", listOf("डीके")))
        assertEquals("", WakeMatch.stripWake("Hey DK", listOf("dk")))
        assertTrue(WakeMatch.goodLength(800)); assertFalse(WakeMatch.goodLength(100)); assertFalse(WakeMatch.goodLength(4000))
    }

    @Test fun vad_quietPhoneMicStillCaught() {
        // धीमा माइक: शोर ~20, बोलना ~150 (पुराना TurnDetector 220 से नीचे "चुप" मानता था)
        val v = WakeMatch.Vad(40, endMs = 500)
        val evs = ArrayList<WakeMatch.Vad.Ev>()
        repeat(10) { evs.add(v.feed(20.0)) }
        repeat(18) { evs.add(v.feed(if (it == 6) 60.0 else 150.0)) }   // "हे ... डीके" बीच में हल्का ठहराव
        repeat(15) { evs.add(v.feed(22.0)) }
        assertEquals(1, evs.count { it == WakeMatch.Vad.Ev.START })
        assertEquals(1, evs.count { it == WakeMatch.Vad.Ev.END })
        assertTrue(evs.indexOf(WakeMatch.Vad.Ev.START) in 10..13)
        assertTrue(v.speechMs in 600..760)
        // तेज़ शोर में आम आवाज़ = शुरू नहीं
        val loud = WakeMatch.Vad(40)
        repeat(30) { assertEquals(WakeMatch.Vad.Ev.NONE, loud.feed(800.0 + (it % 3) * 20)) }
    }

    private fun src(path: String): String = listOf(File("src/main/$path"), File("app/src/main/$path")).first { it.exists() }.readText()

    @Test fun wiring_localWakeBeforeCloudAndBootAndSettings() {
        val svc = src("java/com/dk/assistant/DKAccessibilityService.kt")
        val local = svc.indexOf("com.dk.assistant.voice.HeyDk.listen(")
        assertTrue(local > 0 && local < svc.indexOf("val heard = hear(wakeGate = !liveBefore)"))
        assertTrue(svc.contains("HeyDk.playAck(") && svc.contains("gemLive.idleExitMs = 30_000"))
        assertTrue(svc.contains("HeyDk.on(this) && !Prefs.micUserOff(this)) Setup.startKeepAlive(this)"))
        val hd = src("java/com/dk/assistant/voice/HeyDk.kt")
        assertTrue(hd.contains("VoicePrint.threshold(") && hd.contains("AudioRecord(source(c), RATE"))
        assertFalse("आवाज़ इंटरनेट पर नहीं", hd.contains("okhttp") || hd.contains("Transcriber"))
        val set = src("java/com/dk/assistant/SettingsActivity.kt")
        assertTrue(set.contains("heyDkCard(box)") && set.contains("enrollHeyDk"))
        val ui = src("assets/ui_en.tsv")
        assertTrue(ui.contains("Hey DK सिखाओ\tTeach Hey DK"))
    }
}

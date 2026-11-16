package com.dk.assistant.voice

import com.dk.assistant.plus.FeatureFlags
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** 16 नवंबर 2026 (मालिक): वॉइस गार्डियन - 15 सैंपल वाली पहचान + पक्की रोक */
class VoiceGateDeepTest {
    private fun f(p: String) = listOf(File("src/main/$p"), File("app/src/main/$p")).first { it.exists() }
    private fun src(n: String) = f("java/com/dk/assistant/$n").readText()

    private val rnd = Random(7)
    private fun vec(): FloatArray = VoicePrint.normalize(FloatArray(VoicePrint.DIM) { rnd.nextFloat() * 2 - 1 })
    /** base के पास (एक ही आदमी) */
    private fun near(base: FloatArray, noise: Float): FloatArray =
        VoicePrint.normalize(FloatArray(base.size) { base[it] + (rnd.nextFloat() * 2 - 1) * noise })

    @Test fun fifteenPhrasesHindiUnique() {
        assertEquals(15, VgRules.PHRASES_15.size)
        assertEquals(VgRules.PHRASES, VgRules.PHRASES_15.take(6))          // पुराने 6 वैसे ही
        assertEquals(15, VgRules.PHRASES_15.toSet().size)
        assertTrue(VgRules.PHRASES_15.all { p -> p.any { it in '\u0900'..'\u097F' } })
        assertTrue(VgRules.ADD_MORE <= VgRules.PHRASES_EXTRA.size)
    }

    @Test fun multiScoreUsesBestTemplates() {
        val me = vec()
        val tpl = List(15) { near(me, 0.15f) }
        val c = VoicePrint.mean(tpl)!!
        val probe = tpl[3]
        val m = VgRules.multiScore(c, tpl, probe)
        assertTrue(m >= VoicePrint.cosine(c, probe) - 1e-5f)
        // कोई और: पहचान बहुत कम
        val other = vec()
        assertTrue(VgRules.multiScore(c, tpl, other) < 0.4f)
        // सैंपल न हों = पुराना औसत वाला मिलान
        assertEquals(VoicePrint.cosine(c, probe), VgRules.multiScore(c, emptyList(), probe), 1e-6f)
        assertTrue(VgRules.multiScore(null, emptyList(), probe).isNaN())
    }

    @Test fun calibrationInRangeAndNeedsSamples() {
        assertNull(VgRules.calibrate(listOf(vec(), vec())))
        val me = vec()
        val th = VgRules.calibrate(List(15) { near(me, 0.1f) })
        assertNotNull(th)
        assertTrue(th!! in VgRules.CAL_MIN..VgRules.CAL_MAX)
        assertTrue(th in VgRules.TH_MIN..VgRules.TH_MAX)
        // बहुत बिखरे सैंपल = सबसे ढीली नहीं, नीचे की हद पर
        assertEquals(VgRules.CAL_MIN, VgRules.calibrate(List(15) { vec() })!!, 1e-6f)
    }

    @Test fun templatesKeepNewestAndRoundTrip() {
        val a = List(VgRules.MAX_TEMPLATES) { vec() }
        val b = List(5) { vec() }
        val all = VgRules.addTemplates(a, b)
        assertEquals(VgRules.MAX_TEMPLATES, all.size)
        assertTrue(all.last() === b.last())
        val back = VgRules.decodeAll(VgRules.encodeAll(b))
        assertEquals(5, back.size)
        for (i in b.indices) assertArrayEquals(b[i], back[i], 1e-4f)
        assertTrue(VgRules.decodeAll(null).isEmpty())
        assertTrue(VgRules.decodeAll("बेकार;1,2").isEmpty())
    }

    private fun pcm(vararg parts: Pair<Int, Int>): ByteArray {   // (ms, आयाम)
        val out = java.io.ByteArrayOutputStream()
        for ((ms, amp) in parts) repeat(16 * ms) { i ->
            val v = if (amp == 0) 0 else (amp * kotlin.math.sin(i * 0.3)).toInt()
            out.write(v and 0xff); out.write(v shr 8 and 0xff)
        }
        return out.toByteArray()
    }

    @Test fun trimSilenceCutsEdges() {
        val x = pcm(600 to 0, 1000 to 8000, 800 to 0)
        val t = VgRules.trimSilence(x)
        val ms = t.size / 32
        assertTrue("$ms", ms in 1000..1200)
        // बहुत छोटी आवाज़ = जैसी थी
        val y = pcm(500 to 0, 200 to 8000, 500 to 0)
        assertEquals(y.size, VgRules.trimSilence(y).size)
        assertEquals(10, VgRules.trimSilence(ByteArray(10)).size)
    }

    @Test fun strictFastCheck() {
        val g = OwnerGate(40)
        g.fast = true
        g.start()
        var asked = false
        repeat(OwnerGate.FAST_FIRST_MS / 40) { asked = g.add(ByteArray(1280), 500.0) || asked }
        assertTrue(asked)                                       // 0.6 s पर ही पहला मिलान
        g.takeAudio()
        // बीच वाला score (सीमा से थोड़ा नीचे) = अभी फ़ैसला नहीं, 1.5 s पर दोबारा (छोटी आवाज़ वाली ढील नहीं)
        assertEquals(OwnerGate.Verdict.PENDING, g.onScore(0.45f, 0.50f, 1_000, 600, atEnd = false))
        while (g.speechMs + 40 < OwnerGate.FAST_SECOND_MS) assertFalse(g.add(ByteArray(1280), 500.0))
        assertTrue(g.add(ByteArray(1280), 500.0))
        g.takeAudio()
        assertEquals(OwnerGate.Verdict.OTHER, g.onScore(0.45f, 0.50f, 1_800, 1500, atEnd = false))

        val o = OwnerGate(40); o.fast = true; o.start()
        assertEquals(OwnerGate.Verdict.OWNER, o.onScore(0.62f, 0.50f, 1_000, 600, atEnd = false))
        val s = OwnerGate(40); s.fast = true; s.start()
        assertEquals(OwnerGate.Verdict.OTHER, s.onScore(0.15f, 0.50f, 1_000, 600, atEnd = false))
        val w = OwnerGate(40); w.fast = true; w.start()
        assertEquals(OwnerGate.Verdict.PENDING, w.onScore(0.25f, 0.50f, 1_000, 600, atEnd = false))   // 0.6 s पर जल्दबाज़ी नहीं
        // मिलान फ़ेल = DK आवाज़ नहीं खोता
        val n = OwnerGate(40); n.fast = true; n.start()
        assertEquals(OwnerGate.Verdict.OWNER, n.onScore(Float.NaN, 0.50f, 1_000, 600, atEnd = false))
    }

    @Test fun oldGateUnchangedWhenNotFast() {
        val g = OwnerGate(40)
        g.start()
        var asked = false
        repeat(OwnerGate.FAST_FIRST_MS / 40) { asked = g.add(ByteArray(1280), 500.0) || asked }
        assertFalse(asked)                                      // पुराना: 1 s से पहले मिलान नहीं
    }

    @Test fun flagsOffAndWired() {
        for (k in listOf(FeatureFlags.VG_DEEP, FeatureFlags.VG_STRICT)) {
            assertTrue(k in FeatureFlags.READY)
            assertFalse(FeatureFlags.byKey(k)!!.default)
        }
        val mic = src("GeminiLiveMic.kt")
        assertTrue(mic.contains("ownerHolding = ownerOn && (vgStrict ||"))
        val own = src("GeminiLiveOwner.kt")
        assertTrue(own.contains("vgStrict = vgOn && com.dk.assistant.voice.VoiceGuardian.strictOn(svc)"))
        assertTrue(own.contains("owner.fast = vgStrict"))
        val vg = src("voice/VoiceGuardian.kt")
        assertTrue(vg.contains("if (deepOn(c))") && vg.contains("VgRules.multiScore"))
        val ui = src("SettingsVoiceGuardian.kt")
        assertTrue(ui.contains("FeatureFlags.VG_DEEP") && ui.contains("FeatureFlags.VG_STRICT"))
        assertTrue(ui.contains("VgRules.PHRASES_15") && ui.contains("और सैंपल जोड़ो"))
        assertTrue(ui.contains("VgRules.calibrate"))
    }
}

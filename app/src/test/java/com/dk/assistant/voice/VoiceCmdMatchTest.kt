package com.dk.assistant.voice

import com.dk.assistant.voice.VoiceCmdMatch.Cmd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** नवंबर 2026: बिना इंटरनेट वाली आवाज़-कमांड (लॉक / अनलॉक / किधर हो / मिल गया) - मिलान, सीखना, text बैकअप, जुड़ाव */
class VoiceCmdMatchTest {
    private val rate = WakeMatch.RATE

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
    private val lock = heyDk + listOf(Triple(500.0, 1100.0, 220), Triple(350.0, 2100.0, 200), Triple(650.0, 1000.0, 260))
    private val find = heyDk + listOf(Triple(280.0, 2500.0, 180), Triple(820.0, 1350.0, 240), Triple(300.0, 750.0, 260))
    private val noise = listOf(Triple(900.0, 1700.0, 300), Triple(450.0, 3000.0, 300), Triple(600.0, 1500.0, 300), Triple(1000.0, 2200.0, 300))

    private fun f(p: List<Triple<Double, Double, Int>>, s: Double = 1.0, seed: Int = 1) = WakeMatch.features(word(p, s, seed = seed))
    private fun model(p: List<Triple<Double, Double, Int>>, seed: Int) =
        VoiceCmdMatch.Model.build(listOf(0.92, 0.97, 1.0, 1.04, 1.1).mapIndexed { i, s -> f(p, s, seed + i) })!!

    @Test fun build_needsThreeTakes() {
        assertNull(VoiceCmdMatch.Model.build(listOf(f(lock), f(lock, 1.05, 2))))
        val m = model(lock, 10)
        assertEquals(5, m.templates.size)
        assertTrue(m.base > 0f)
    }

    @Test fun pick_rightCommand() {
        val models = mapOf(Cmd.LOCK to model(lock, 10), Cmd.FIND to model(find, 30))
        val l = VoiceCmdMatch.pick(models, f(lock, 1.02, 77), WakeMatch.Sense.NORMAL)
        assertNotNull(l); assertEquals(Cmd.LOCK, l!!.cmd)
        val k = VoiceCmdMatch.pick(models, f(find, 0.98, 78), WakeMatch.Sense.NORMAL)
        assertNotNull(k); assertEquals(Cmd.FIND, k!!.cmd)
        assertNull(VoiceCmdMatch.pick(models, f(noise, 1.0, 79), WakeMatch.Sense.NORMAL))
        assertNull(VoiceCmdMatch.pick(models, emptyArray(), WakeMatch.Sense.NORMAL))
        assertNull(VoiceCmdMatch.pick(emptyMap(), f(lock), WakeMatch.Sense.NORMAL))
    }

    @Test fun pick_ambiguousIsNull() {
        // एक ही वाक्य दो कमांड में सिखाया -> DK पक्का नहीं, कुछ नहीं करे
        val models = mapOf(Cmd.LOCK to model(lock, 10), Cmd.UNLOCK to model(lock, 40))
        assertNull(VoiceCmdMatch.pick(models, f(lock, 1.0, 90), WakeMatch.Sense.NORMAL))
        assertTrue(VoiceCmdMatch.confusable(models.getValue(Cmd.LOCK), models.getValue(Cmd.UNLOCK)))
        assertFalse(VoiceCmdMatch.confusable(model(lock, 10), model(noise, 50)))
    }

    @Test fun outlier_findsWrongTake() {
        val takes = listOf(f(find, 0.95, 1), f(find, 1.0, 2), f(noise, 1.0, 3), f(find, 1.05, 4), f(find, 1.02, 5))
        assertEquals(2, VoiceCmdMatch.outlier(takes))
        assertEquals(-1, VoiceCmdMatch.outlier(listOf(f(find, 0.95, 1), f(find, 1.0, 2), f(find, 1.05, 4), f(find, 1.02, 5))))
    }

    @Test fun json_roundTrip() {
        val m = model(lock, 10)
        val back = VoiceCmdMatch.Model.fromJson(m.toJson())!!
        assertEquals(m.templates.size, back.templates.size)
        assertEquals(m.base, back.base, 1e-4f)
        val probe = f(lock, 1.01, 99)
        assertEquals(m.rawRatio(probe, WakeMatch.Sense.NORMAL), back.rawRatio(probe, WakeMatch.Sense.NORMAL), 1e-3f)
    }

    @Test fun cmds_ownerRules() {
        assertTrue(Cmd.LOCK.ownerOnly); assertTrue(Cmd.UNLOCK.ownerOnly)
        assertFalse(Cmd.FIND.ownerOnly); assertFalse(Cmd.FOUND.ownerOnly)
        assertEquals(Cmd.FIND, Cmd.of("find")); assertNull(Cmd.of("x"))
    }

    @Test fun text_findAndFound() {
        for (t in listOf("किधर हो", "DK किधर हो?", "कहां हो तुम", "कहाँ हो", "kidhar ho", "मुझे नहीं दिख रहे")) assertTrue(t, VoiceCmdMatch.findText(t))
        for (t in listOf("फोन लॉक कर दो", "आज मौसम कैसा है", "")) assertFalse(t, VoiceCmdMatch.findText(t))
        for (t in listOf("मिल गया", "हे DK मिल गया", "बंद करो", "mil gaya")) assertTrue(t, VoiceCmdMatch.foundText(t))
        assertFalse(VoiceCmdMatch.foundText("किधर हो"))
    }

    private fun src(p: String): String {
        val f = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }
        return f.readText()
    }

    @Test fun wiring() {
        val cmds = src("voice/VoiceCmds.kt")
        assertTrue(cmds.contains("Cmd.UNLOCK) VoicePrint.TH_STRICT"))
        val fp = src("FindPhone.kt")
        assertTrue(fp.contains("RING_MS = 60_000"))
        assertTrue(fp.contains("setTorchMode"))
        assertTrue(fp.contains("मैं इधर हूँ बॉस, इधर हूँ बॉस"))
        val svc = src("DKAccessibilityService.kt")
        val hear = svc.indexOf("VoiceCmds.match(this@DKAccessibilityService, pcm)")
        assertTrue(hear > 0 && hear < svc.indexOf("ownerHeard(pcm)", hear))
        assertTrue(svc.contains("runVoiceCmd(hit.cmd, \"Hey DK\")"))
        assertTrue(src("GeminiLiveMic.kt").contains("liveVoiceCmd(cmdBuf.toByteArray())"))
        assertTrue(src("voice/HeyDk.kt").contains("VoiceCmds.match"))
        val run = src("VoiceCmdRun.kt")
        assertTrue(run.contains("PhoneLockVoice.quick"))
        val man = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() }.readText()
        assertTrue(man.contains(".FindPhoneActivity"))
    }
}

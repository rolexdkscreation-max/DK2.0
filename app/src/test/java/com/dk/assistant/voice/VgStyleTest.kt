package com.dk.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** 16 नवंबर 2026 (मालिक): अंदाज़ बदल-बदलकर आवाज़ सिखाना, लिखा वाक्य सिर्फ़ सुझाव, "हे डीके" वाले छोटे सैंपल */
class VgStyleTest {
    private fun f(p: String) = listOf(File("src/main/$p"), File("app/src/main/$p")).first { it.exists() }
    private fun src(n: String) = f("java/com/dk/assistant/$n").readText()
    private val rnd = Random(11)
    private fun vec(): FloatArray = VoicePrint.normalize(FloatArray(VoicePrint.DIM) { rnd.nextFloat() * 2 - 1 })
    private fun near(b: FloatArray, k: Float): FloatArray = VoicePrint.normalize(FloatArray(b.size) { b[it] + k * (rnd.nextFloat() * 2 - 1) })

    @Test fun styles_cover_ownerAsk_andRotate() {
        val ids = VgStyle.STYLES.map { it.id }
        for (want in listOf("slow", "loud", "excited", "fast", "whisper")) assertTrue(want, want in ids)
        assertEquals(ids.size, ids.toSet().size)
        val p = VgStyle.plan(VgRules.PHRASES)
        assertEquals(VgRules.PHRASES.size, p.size)
        assertTrue("हर सैंपल अलग अंदाज़", p.map { it.style.id }.toSet().size == p.size)
        assertFalse(p.any { it.short })
        // अगली बार अलग शुरुआत
        assertEquals(VgStyle.STYLES[3].id, VgStyle.plan(listOf("a"), start = 3)[0].style.id)
        assertEquals(VgStyle.STYLES[0].id, VgStyle.plan(listOf("a"), start = VgStyle.STYLES.size)[0].style.id)
    }

    @Test fun line_saysAnythingIsOk() {
        val p = VgStyle.plan(listOf("कल सुबह अलार्म लगाओ"))[0]
        val t = VgStyle.line(0, 6, p)
        assertTrue(t.contains("कुछ भी बोल सकते हैं") && t.contains("सुझाव") && t.contains(p.style.hi) && t.contains("(1/6)"))
        val w = VgStyle.wakePlan()
        assertTrue(w.all { it.short } && w.size == VgStyle.WAKE_SAY.size)
        assertTrue(w.none { it.style.id == "whisper" })
        assertTrue(VgStyle.line(0, 9, w[0]).contains("हे डीके"))
    }

    @Test fun keep_whisperGetsMoreSlack() {
        val whisper = VgStyle.Prompt(VgStyle.style("whisper"), "x", false)
        val normal = VgStyle.Prompt(VgStyle.style("normal"), "x", false)
        val mid = VgRules.MIN_CONSISTENCY - 0.05f
        assertTrue(VgStyle.keep(whisper, mid)); assertFalse(VgStyle.keep(normal, mid))
        assertFalse(VgStyle.keep(normal, Float.NaN))
        assertTrue(VgStyle.keepShort(0.30f)); assertFalse(VgStyle.keepShort(0.1f))
        assertEquals(4, VgStyle.need(6)); assertEquals(10, VgStyle.need(15))
    }

    @Test fun wakeTh_calibratedAndBounded() {
        assertNull(VgStyle.wakeTh(emptyList()))
        assertEquals(VgStyle.WAKE_TH_MAX, VgStyle.wakeTh(listOf(0.95f, 0.9f, 0.92f))!!, 1e-6f)
        assertEquals(VgStyle.WAKE_TH_MIN, VgStyle.wakeTh(listOf(0.1f, 0.2f))!!, 1e-6f)
        assertEquals(0.55f - VgStyle.WAKE_TH_DROP, VgStyle.wakeTh(listOf(0.7f, 0.42f, 0.6f, 0.55f))!!, 1e-6f)
        // एक ही आदमी के सैंपल: छोटे सैंपल का अपने आप से मिलान, किसी और से ज़्यादा
        val me = vec()
        val sent = List(5) { near(me, 0.06f) }; val shorts = List(4) { near(me, 0.09f) }
        val print = VoicePrint.mean(sent)!!
        val self = VgStyle.wakeSelf(print, sent, shorts)
        assertEquals(4, self.size)
        val other = VgRules.multiScore(print, sent + shorts, near(vec(), 0.09f))
        assertTrue(self.minOrNull()!! > other)
        assertNotNull(VgStyle.wakeTh(self))
    }

    @Test fun wiring_enrollUsesStyles_andWakeUsesShortSamples() {
        val vg = src("SettingsVoiceGuardian.kt")
        assertTrue(vg.contains("styledTakes(VgStyle.plan(VgRules.PHRASES)") && vg.contains("VgRules.calibrate"))
        val wake = src("wake/SettingsWake.kt")
        assertTrue(wake.contains("VgStyle.wakePlan()") && wake.contains("VoiceGuardian.saveWakeTemplates(") && wake.contains("VgStyle.wakeTh("))
        val g = src("voice/VoiceGuardian.kt")
        assertTrue(g.contains("fun scoreWake(") && g.contains("VgRules.trimSilence(pcm)") && g.contains("remove(kWake(it.id))"))
        val ww = src("wake/WakeWord.kt")
        assertTrue(ww.contains("VoiceGuardian.scoreWake(") && ww.contains("VoiceGuardian.prime(c)"))
        assertFalse("बिना सीखे छोटे टुकड़े वाला पुराना मिलान नहीं", ww.contains("VoiceGuardian.score(c,"))
        // कोई नया key/API वाला खाना नहीं
        val st = src("SettingsVoiceStyle.kt")
        assertFalse(st.contains("EditText"))
    }
}

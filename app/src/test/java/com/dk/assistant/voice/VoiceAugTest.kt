package com.dk.assistant.voice

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/** v4.7 मालिक: "दूर से बोला / धीरे बोला तो भी मैच हो" + रिकॉर्डिंग दोबारा सुनें, बोलने वाला वाक्य न दिखे */
class VoiceAugTest {
    private fun tone(n: Int) = FloatArray(n) { i -> (0.3 * sin(2 * PI * 300 * i / 16000.0) * sin(PI * i / n)).toFloat() }

    @Test fun farIsQuieterSameLengthNoisy() {
        val x = tone(16000)
        val y = VoiceAug.far(x)
        assertEquals(x.size, y.size)
        assertTrue(VoiceAug.rms(y) < VoiceAug.rms(x) * 0.5f)
        assertTrue(VoiceAug.rms(y) > 0f)
        assertNotEquals(0f, y[10])          // शुरू में बोली नहीं, फिर भी शोर
        assertArrayEquals(y, VoiceAug.far(x), 0f)   // हर बार एक-सा
        val pcm = VoiceAug.toPcm(y)
        assertEquals(y.size * 2, pcm.size)
        assertEquals(VoiceAug.rms(y), VoiceAug.rms(VoiceAug.toFloat(pcm)), 1e-3f)
    }

    @Test fun slowStretches() {
        val f = Array(40) { i -> FloatArray(12) { i.toFloat() } }
        val s = VoiceAug.slow(f, 1.35f)
        assertEquals(54, s.size)
        assertEquals(0f, s.first()[0], 1e-4f); assertEquals(39f, s.last()[0], 1e-4f)
        assertSame(f, VoiceAug.slow(f, 1f))
    }

    @Test fun augKeepsStrictBase() {
        val t = (0 until 5).map { k -> Array(30) { i -> FloatArray(12) { j -> (i + j + k * 0.3f) } } }
        val m = VoiceCmdMatch.Model.build(t)!!
        val a = VoiceCmdMatch.Model.buildAug(t, t.map { VoiceAug.slow(it) })!!
        assertEquals(m.base, a.base, 0f)
        assertEquals(10, a.templates.size)
        // धीरे बोला: खिंचे नमूने से दूरी कम
        val probe = VoiceAug.slow(t[0], 1.4f)
        assertTrue(a.distances(probe).first <= m.distances(probe).first)
    }

    private fun src(p: String) = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()

    @Test fun settingsNoPhraseAndPlayback() {
        val s = src("SettingsVoiceCmds.kt")
        assertFalse(s.contains("k.say"))
        assertFalse(s.contains("cmd.say"))
        assertTrue(s.contains("\"सुनें\""))
        assertTrue(s.contains("playVoiceCmd("))
        assertTrue(s.contains("r.noise"))
        val c = src("voice/VoiceCmds.kt")
        assertTrue(c.contains("VoiceAug.far("))
        assertTrue(c.contains("VoiceAug.slow("))
        assertTrue(c.contains("buildAug("))
        assertTrue(c.contains("audioFile("))
        assertTrue(src("SettingsActivity.kt").contains("stopVoiceCmdPlay()"))
    }
}

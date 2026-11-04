package com.dk.assistant.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v4.6 मालिक: "टेक्स्ट हटाकर 30 सेकंड का वॉइस रिकॉर्डिंग, उसमें 5 बार बोला जाएगा - लॉक का अलग, अनलॉक का अलग,
 * 'डीके कहाँ हो फोन बजाओ' में भी"। एक लगातार रिकॉर्डिंग -> [VoiceTape.Cutter] हर बार अलग काटे.
 */
class VoiceTapeTest {
    private val piece = ByteArray(VoiceTape.CHUNK_MS * VoiceTape.BYTES_PER_MS)   // 40 ms

    /** chunks: (कितने 40 ms टुकड़े, ताक़त) */
    private fun run(vararg parts: Pair<Int, Double>, cut: VoiceTape.Cutter = VoiceTape.Cutter()): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        for ((n, rms) in parts) repeat(n) { cut.feed(piece, rms)?.let { out += it } }
        cut.flush()?.let { out += it }
        return out
    }

    @Test fun `30 सेकंड, 5 बार`() {
        assertEquals(30, VoiceTape.SECONDS)
        assertEquals(5, VoiceTape.TIMES)
        assertEquals(3, VoiceTape.MIN_TIMES)
    }

    @Test fun `5 बार बोला, बीच में रुका = 5 टुकड़े`() {
        val parts = ArrayList<Pair<Int, Double>>()
        parts += 10 to 50.0                      // शुरू में चुप (शोर सीखो)
        repeat(5) { parts += 50 to 2000.0; parts += 40 to 50.0 }   // 2 s बोलो, 1.6 s रुको
        val segs = run(*parts.toTypedArray())
        assertEquals(5, segs.size)
        segs.forEach { s ->
            val ms = VoiceTape.ms(s)
            // ~2 s बोला + पहले का 240 ms + आख़िर का ~200 ms
            assertTrue("ms=$ms", ms in 1_900..2_700)
        }
    }

    @Test fun `वाक्य के बीच छोटी साँस से टुकड़ा नहीं टूटता`() {
        // "हे डीके," (0.6 s) - 0.4 s साँस - "फोन लॉक कर दो" (1.2 s)
        val segs = run(10 to 50.0, 15 to 2000.0, 10 to 50.0, 30 to 2000.0, 40 to 50.0)
        assertEquals(1, segs.size)
    }

    @Test fun `खाँसी जैसा छोटा टुकड़ा गिना नहीं जाता`() {
        val cut = VoiceTape.Cutter()
        val segs = run(10 to 50.0, 4 to 2000.0, 40 to 50.0, cut = cut)
        assertTrue(segs.isEmpty())
        assertEquals(1, cut.tooShort)
    }

    @Test fun `बिना रुके बहुत लंबा = गिना नहीं`() {
        val cut = VoiceTape.Cutter()
        val segs = run(10 to 50.0, 200 to 2000.0, cut = cut)       // 8 s लगातार
        assertTrue(cut.tooLong >= 1)
        segs.forEach { assertTrue(VoiceTape.ms(it) <= VoiceCmdMatch.MAX_MS + 1_000) }
    }

    @Test fun `30 सेकंड ख़त्म होते समय चलता बार भी लिया`() {
        val segs = run(10 to 50.0, 40 to 2000.0)
        assertEquals(1, segs.size)
    }

    @Test fun `PCM के जोड़े (16-bit) कभी आधे नहीं`() {
        run(10 to 50.0, 50 to 2000.0, 40 to 50.0).forEach { assertEquals(0, it.size % 2) }
    }

    @Test fun `UI - बचा समय और गिनती`() {
        assertEquals("⏱ 23 सेकंड बचे • ✓ 2/5 बार", VoiceTape.progress(22_100, 2))
        assertEquals("⏱ 0 सेकंड बचे • ✓ 5/5 बार", VoiceTape.progress(-50, 5))
    }

    @Test fun `नतीजा - 3 या ज़्यादा = सीख लो, कम = साफ़ कारण`() {
        assertNull(VoiceTape.verdict(5, 0, 0))
        assertNull(VoiceTape.verdict(3, 2, 0))
        assertNotNull(VoiceTape.verdict(2, 0, 3)); assertTrue(VoiceTape.verdict(2, 0, 3)!!.contains("रुकिए"))
        assertTrue(VoiceTape.verdict(1, 4, 0)!!.contains("छोटी"))
        assertTrue(VoiceTape.verdict(0, 0, 0)!!.contains("माइक"))
    }

    @Test fun `कॉल उठाने की आवाज़-कमांड + कहाँ हो फोन बजाओ`() {
        val a = VoiceCmdMatch.Cmd.of("answer")!!; val s = VoiceCmdMatch.Cmd.of("answer_speaker")!!
        assertTrue(a.say.contains("उठा लो")); assertTrue(s.say.contains("स्पीकर"))
        assertTrue(!a.ownerOnly && !s.ownerOnly)
        assertTrue(VoiceCmdMatch.Cmd.FIND.say.contains("कहाँ हो") && VoiceCmdMatch.Cmd.FIND.say.contains("फोन बजाओ"))
        assertTrue(VoiceCmdMatch.findText("कहाँ हो फोन बजाओ"))
        assertTrue(VoiceCmdMatch.findText("फ़ोन बजाओ"))
    }

    private fun src(p: String) = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p"))
        .first { it.exists() }.readText()

    @Test fun `Settings - टेक्स्ट वाला खाना हटा, 30 सेकंड रिकॉर्डिंग`() {
        val pl = src("SettingsPhoneLock.kt")
        assertTrue("\"क्या बोलें\" वाला लिखने का खाना हटना चाहिए", !pl.contains("card(\"क्या बोलें\""))
        assertTrue(!pl.contains("edLock") && !pl.contains("edUnlock") && !pl.contains("edNames"))
        val vc = src("SettingsVoiceCmds.kt")
        assertTrue(vc.contains("button(\"30 सेकंड रिकॉर्ड करें\")"))
        assertTrue(vc.contains("VoiceTapeRec.record("))
        assertTrue("पुराना एक-एक नमूना वाला तरीका नहीं", !vc.contains("HD.recordTake("))
    }
}

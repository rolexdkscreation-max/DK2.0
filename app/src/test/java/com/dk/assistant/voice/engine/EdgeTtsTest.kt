package com.dk.assistant.voice.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.68: Edge TTS - Sec-MS-GEC टोकन (edge-tts के Python से मिलाए नमूने), MP3 frame, SSML */
class EdgeTtsTest {
    @Test fun drmTokenMatchesReference() {
        assertEquals("52BC7A17B50DFEC9-28C4394775C8751CD2F6859C030C06DB1489ACAB3B3A7651831FC26895177ED6", EdgeDrm.token(0.0))
        assertEquals("17693FDF8A76BB96-41C253C5C83B157C6480DD66214906B20B4E34078004080C2654FC3FBC3489ED", EdgeDrm.token(1761370000.0))
        assertEquals("781C1B76C9F739B3-1A772624F17530DF13FBF300637917F70D55C163861ADEC2DA70A248A0DE438A", EdgeDrm.token(1793000000.5))
        assertEquals("99E54519B490BE5C-E162DB0977FBDCB9B86DD8148BF1BCDC3C0303176CE39852A42B415935C20654", EdgeDrm.token(1800000123.0))
    }

    @Test fun drmTokenStableWithinFiveMinutes() {
        assertEquals(EdgeDrm.token(1800000000.0 - 1800000000.0 % 300 + 1), EdgeDrm.token(1800000000.0 - 1800000000.0 % 300 + 299))
    }

    private fun frame(fill: Int): ByteArray = ByteArray(144) { fill.toByte() }.also {
        it[0] = 0xFF.toByte(); it[1] = 0xF3.toByte(); it[2] = 0x64; it[3] = 0xC4.toByte()
    }

    @Test fun mp3FramesAcrossChunks() {
        val all = byteArrayOf(1, 2, 3) + frame(0) + frame(0) + frame(0)
        val f = Mp3Frames()
        val out = f.push(all.copyOfRange(0, 100)) + f.push(all.copyOfRange(100, 300)) + f.push(all.copyOfRange(300, all.size))
        assertEquals(3, out.size)
        out.forEach { assertEquals(144, it.data.size); assertEquals(576, it.samples); assertEquals(24000, it.rate) }
    }

    @Test fun downmixAverages() {
        val st = byteArrayOf(100, 0, 0, 1)            // L=100, R=256
        val m = Mp3Frames.downmix(st)
        assertEquals(2, m.size)
        assertEquals(178, (m[0].toInt() and 0xff) or (m[1].toInt() shl 8))
    }

    @Test fun ssmlEscapesAndProsody() {
        val s = EdgeDrm.ssml("A & <B>", "hi-IN-SwaraNeural", -10, 5)
        assertTrue(s.contains("A &amp; &lt;B&gt;"))
        assertTrue(s.contains("rate='-10%'"))
        assertTrue(s.contains("pitch='+5Hz'"))
        assertTrue(s.contains("hi-IN-SwaraNeural"))
    }
}

package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** नवंबर 2026 (मालिक: "जवाब लिखा आता है, आवाज़ नहीं आती / बीच में कटती") - Live player की मज़बूती */
class LiveAudioGuardTest {
    private fun src(p: String) = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()
    private val G = LiveAudioGuard

    @Test fun deadTrackIsRebuiltOnceThenSkipped() {
        assertEquals(LiveAudioGuard.Write.REBUILD, G.onWrite(G.ERROR_DEAD_OBJECT, 0, 0))
        assertEquals(LiveAudioGuard.Write.REBUILD, G.onWrite(G.ERROR_INVALID_OPERATION, 0, 0))
        assertEquals(LiveAudioGuard.Write.REBUILD, G.onWrite(G.ERROR, 0, 0))
        assertEquals(LiveAudioGuard.Write.SKIP, G.onWrite(G.ERROR_DEAD_OBJECT, 1, 0))   // अटको नहीं
        assertEquals(LiveAudioGuard.Write.SKIP, G.onWrite(G.ERROR_BAD_VALUE, 0, 0))
    }

    @Test fun okAndZeroWrites() {
        assertEquals(LiveAudioGuard.Write.OK, G.onWrite(4800, 0, 0))
        assertEquals(LiveAudioGuard.Write.RETRY, G.onWrite(0, 0, 0))
        assertEquals(LiveAudioGuard.Write.RETRY, G.onWrite(0, 0, 1))
        assertEquals(LiveAudioGuard.Write.REBUILD, G.onWrite(0, 0, G.MAX_ZERO_WRITES - 1))
        assertEquals(LiveAudioGuard.Write.SKIP, G.onWrite(0, 1, G.MAX_ZERO_WRITES - 1))
    }

    @Test fun playOnlyWhenInitialized() {
        assertTrue(G.canPlay(1)); assertFalse(G.canPlay(0)); assertFalse(G.canPlay(2))
        assertEquals("ERROR_DEAD_OBJECT", G.errName(-6))
    }

    @Test fun missingInlineDataIsReported() {
        assertNotNull(G.missingAudio(setOf("executableCode"), false, false, null, 0))
        assertNull(G.missingAudio(setOf("text"), false, true, null, 0))
        assertNull(G.missingAudio(setOf("text", "thought"), true, false, null, 0))
        assertNull(G.missingAudio(setOf("inlineData"), false, false, "audio/pcm;rate=24000", 100))
        assertNotNull(G.missingAudio(setOf("inlineData"), false, false, "audio/pcm;rate=24000", 0))
        assertNotNull(G.missingAudio(setOf("inlineData"), false, false, "image/png", 10))
        assertEquals(24000, G.rateOf("audio/pcm;rate=24000", 16000))
        assertEquals(24000, G.rateOf("audio/pcm", 24000))
    }

    @Test fun playerIsWiredSafely() {
        val p = src("GeminiLivePlayer.kt")
        assertTrue(p.contains("LiveAudioGuard.canPlay(t.state)"))
        assertTrue(p.contains("LiveAudioGuard.onWrite(n, rebuilds, zeros)"))
        assertTrue(p.contains("rebuildTrack(\"write"))
        assertTrue(p.contains("} catch (e: InterruptedException) { break"))
        assertFalse("write गलती पर चुपचाप break नहीं", p.contains("if (n <= 0) break"))
        assertFalse("track null = thread बंद नहीं", p.contains("val tr = track ?: break"))
        // पुराने pins वही
        assertTrue(p.contains("if (JarvisFx.enabled(svc)) JarvisFx.process(bRaw) else bRaw"))
        assertTrue(p.contains("btOn, CallSpeaker.active)"))
        assertTrue(p.contains("AudioAttributes.CONTENT_TYPE_SPEECH"))
        assertTrue(p.contains("AudioFormat.CHANNEL_OUT_MONO") && p.contains("AudioFormat.ENCODING_PCM_16BIT"))
    }

    @Test fun routeChangeKeepsQueueAndParserLogs() {
        val g = src("GeminiLive.kt")
        assertTrue(g.contains("rebuildTrack(\"कॉल \""))
        assertTrue(g.contains("rebuildTrack(if (bt) \"Bluetooth जुड़ा\""))
        assertTrue(g.contains("if (!stopped && playThread?.isAlive != true) ensurePlayThread()"))
        val s = src("GeminiLiveServer.kt")
        assertTrue(s.contains("LiveAudioDebug.w(\"modelTurn part बिना आवाज़: \$it\")"))
        assertTrue(s.contains("LiveAudioDebug.chunk(pcm.size"))
        assertEquals("DK_AUDIO_DEBUG", LiveAudioGuard.TAG)
    }
}

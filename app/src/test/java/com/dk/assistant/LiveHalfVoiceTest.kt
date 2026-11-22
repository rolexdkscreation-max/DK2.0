package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 22 नवंबर 2026 (मालिक: "कभी-कभी टेक्स्ट आ जाता है लेकिन वॉइस नहीं बोलता - फॉलबैक में ऑफ़लाइन TTS बोले"):
 * सीधी Live आवाज़ आधे में रुकी / बजी नहीं / turnComplete खोया = बचा जवाब ऑफ़लाइन आवाज़ से.
 */
class LiveHalfVoiceTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()
    private val bps = LiveTextOnly.OUT_BYTES_PER_SEC.toLong()
    private val long = "ठीक है सर, मैंने आपके लिए अलार्म सुबह छह बजे का लगा दिया है। साथ में याद भी दिला दूँगा कि कल आपकी मीटिंग दस बजे है। और कुछ करना है सर?"

    @Test fun fullAudioNothingMore() {
        // पूरा जवाब ~13 अक्षर/सेकंड पर बोला गया = कुछ नहीं दोहराना
        val sec = long.length / 13.0
        assertNull(LiveTextOnly.rest(long, (sec * bps).toLong()))
        assertNull(LiveTextOnly.rest("जी सर, बताइए", 0L + bps / 2))
    }

    @Test fun stoppedAfterFirstSentence() {
        // सिर्फ़ ~2 सेकंड बजा (पहला आधा वाक्य) = बाकी वाक्य से बोलो
        val r = LiveTextOnly.rest(long, 2 * bps)
        assertNotNull(r)
        assertTrue(r.orEmpty().startsWith("साथ में") || r.orEmpty().startsWith("ठीक") || r.orEmpty().length < long.length)
        assertTrue(r.orEmpty().endsWith("और कुछ करना है सर?"))
        assertFalse(r.orEmpty().startsWith("ठीक है सर"))   // जो बज चुका वह दोबारा नहीं
    }

    @Test fun noAudioPlayedSpeaksAll() {
        assertEquals(long.trim(), LiveTextOnly.rest(long, 0L))
    }

    @Test fun checkRule() {
        assertTrue(LiveTextOnly.check(split = false, dropped = false, muted = false, waiting = false, recvBytes = 4800, text = long))
        assertFalse(LiveTextOnly.check(split = true, dropped = false, muted = false, waiting = false, recvBytes = 4800, text = long))
        assertFalse(LiveTextOnly.check(false, dropped = true, muted = false, waiting = false, recvBytes = 4800, text = long))
        assertFalse(LiveTextOnly.check(false, false, muted = true, waiting = false, recvBytes = 4800, text = long))
        assertFalse(LiveTextOnly.check(false, false, false, waiting = true, recvBytes = 4800, text = long))
        assertFalse(LiveTextOnly.check(false, false, false, false, recvBytes = 0, text = long))   // यह need() वाला रास्ता
        assertFalse(LiveTextOnly.check(false, false, false, false, recvBytes = 4800, text = "जी"))
    }

    @Test fun wired() {
        val say = src("GeminiLiveOfflineSay.kt")
        assertTrue(say.contains("LiveTextOnly.rest(text, played)"))
        assertTrue(say.contains("replySeq == seq && isPlaying()"))
        assertTrue(say.contains("DKState.hint(LiveTextOnly.NO_VOICE_HINT)"))
        val g = src("GeminiLive.kt")
        val stall = g.substringAfter("turnComplete नहीं आया").substringBefore("delay(100)")
        assertTrue(stall.indexOf("offlineTextOnly(false, leakMute)") in 0 until stall.indexOf("finishTurn()"))
        assertTrue(src("GeminiLiveTurn.kt").contains("replyStarted = true\n        replySeq++"))
    }
}

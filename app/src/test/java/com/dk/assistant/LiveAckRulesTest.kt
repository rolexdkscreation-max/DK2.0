package com.dk.assistant

import com.dk.assistant.voice.LiveAckRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** मालिक (नवंबर 2026): power बटन दबाते ही "Yes Boss" DK की अपनी Live आवाज़ में (टिंग नहीं) */
class LiveAckRulesTest {
    private fun ms(m: Long) = (m * LiveAckRules.RATE * 2 / 1000).toInt()

    @Test fun rightWordsAccepted() {
        assertTrue(LiveAckRules.ok("Yes Boss", "Yes Boss.", ms(800)))
        assertTrue(LiveAckRules.ok("Yes Boss", "यस बॉस", ms(800)))
        assertTrue(LiveAckRules.ok("Yes Boss", "", ms(800)))       // transcript न मिला = लंबाई से
        assertTrue(LiveAckRules.ok("जी बोलिए", "जी, बोलिए।", ms(900)))
    }

    @Test fun extraTalkRejected() {
        assertFalse(LiveAckRules.ok("Yes Boss", "Yes Boss, how can I help you today?", ms(2000)))
        assertFalse(LiveAckRules.ok("Yes Boss", "Yes Boss", ms(4000)))   // बहुत लंबा
        assertFalse(LiveAckRules.ok("Yes Boss", "Yes", ms(100)))          // लगभग खाली
    }

    @Test fun trimCutsSilence() {
        val n = LiveAckRules.RATE                      // 1 s
        val pcm = ByteArray(n * 2)
        for (i in n / 2 until n / 2 + 2400) { val v = 5000; pcm[2 * i] = (v and 0xff).toByte(); pcm[2 * i + 1] = (v shr 8).toByte() }
        val t = LiveAckRules.trim(pcm)
        val pad = (LiveAckRules.RATE * LiveAckRules.PAD_MS / 1000).toInt()
        assertEquals((2400 + 2 * pad) * 2, t.size)
        assertEquals(0, LiveAckRules.trim(ByteArray(1000)).size)
    }

    @Test fun neverDuringLiveAndBacksOff() {
        val r = LiveAckRules.RETRY_MS
        assertTrue(LiveAckRules.shouldMake(missing = true, liveOn = false, haveKey = true, sinceFailMs = r, busy = false))
        assertFalse(LiveAckRules.shouldMake(true, liveOn = true, haveKey = true, sinceFailMs = r, busy = false))
        assertFalse(LiveAckRules.shouldMake(true, false, haveKey = false, sinceFailMs = r, busy = false))
        assertFalse(LiveAckRules.shouldMake(true, false, true, sinceFailMs = 1000, busy = false))
        assertFalse(LiveAckRules.shouldMake(missing = false, liveOn = false, haveKey = true, sinceFailMs = r, busy = false))
    }

    /** DKI-9DC74D: Worker (APP_TOKEN नहीं) नाकाम = हर बटन पर फिर नहीं बुलाना, 10 मिनट बाद ही */
    @Test fun workerFailBacksOff() {
        assertTrue(LiveAckRules.retryDue(0L, 5_000L))
        assertFalse(LiveAckRules.retryDue(1_000L, 1_000L + 60_000L))
        assertTrue(LiveAckRules.retryDue(1_000L, 1_000L + LiveAckRules.RETRY_MS))
        val hey = File("src/main/java/com/dk/assistant/voice/HeyDk.kt").readText()
        assertTrue(hey.contains("if (!LiveAckRules.retryDue(ackFailAt, now)) return"))
        assertTrue(hey.contains("if (liveOn && hasLiveAck(c, t)) continue"))
    }

    @Test fun modelsSavedFirstNoDup() {
        assertEquals(listOf("b", "a", "c"), LiveAckRules.models("b", listOf("a", "b", "c")))
        assertEquals(listOf("a", "c"), LiveAckRules.models("", listOf("a", "c")))
    }

    /** source: Live आवाज़ वाली फ़ाइल पहले, बनना Live बंद होने पर, Android TTS नहीं, जुड़ने वाली chime वैसी ही */
    @Test fun wiring() {
        val base = "src/main/java/com/dk/assistant"
        val hey = File("$base/voice/HeyDk.kt").readText()
        assertTrue(hey.contains("fun hasLiveAck("))
        assertTrue(hey.contains("live ?: eng"))
        val mk = File("$base/voice/LiveAckMaker.kt").readText()
        assertTrue(mk.contains("DKState.live"))
        assertFalse(mk.contains("TextToSpeech"))
        assertTrue(File("$base/DKAccessibilityService.kt").readText().contains("LiveAckMaker.ensureAsync(this, \"Live बंद\""))
        assertTrue(File("$base/InvokeAck.kt").readText().contains("LiveAckMaker.ensureAsync("))
    }
}

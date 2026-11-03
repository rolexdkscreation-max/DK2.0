package com.dk.assistant

import com.dk.assistant.voice.WakeMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** नवंबर 2026 (मालिक): सिर्फ़ "Hey DK / OK DK / Hello DK" पर जागना, power बटन = एक बार की बातचीत, Live जुड़ने की हल्की आवाज़ */
class StrictWakeChimeTest {
    private val names = listOf("डीके", "दीके", "डी के", "dk", "d k", "मायरा")

    @Test fun wakesOnlyOnFullPhrase() {
        assertEquals("", WakeMatch.strictWake("Hey DK", names))
        assertEquals("", WakeMatch.strictWake("OK DK.", names))
        assertEquals("", WakeMatch.strictWake("Hello D.K.", names))
        assertEquals("", WakeMatch.strictWake("हे डीके", names))
        assertEquals("", WakeMatch.strictWake("हेलो डी के", names))
        assertEquals("", WakeMatch.strictWake("ओके डीके", names))
        assertEquals("मौसम बताओ", WakeMatch.strictWake("हेलो डीके मौसम बताओ", names))
        assertEquals("WhatsApp खोलो", WakeMatch.strictWake("Hey DK, WhatsApp खोलो।", names))
        assertEquals("गाना चलाओ", WakeMatch.strictWake("अरे हे डीके गाना चलाओ", names))
        assertEquals("", WakeMatch.strictWake("heydk", names))
        assertEquals("टाइम बताओ", WakeMatch.strictWake("Hey मायरा टाइम बताओ", names))
    }

    @Test fun ignoresEverythingElse() {
        assertNull(WakeMatch.strictWake("DK", names))                      // सिर्फ़ नाम = नहीं (Hey/OK/Hello चाहिए)
        assertNull(WakeMatch.strictWake("डीके मौसम बताओ", names))
        assertNull(WakeMatch.strictWake("मैंने dk को बोला था", names))       // बीच में नाम
        assertNull(WakeMatch.strictWake("good kid hey", names))
        assertNull(WakeMatch.strictWake("आज खाना क्या बना है", names))
        assertNull(WakeMatch.strictWake("ok", names))
        assertNull(WakeMatch.strictWake("", names))
        assertNull(WakeMatch.strictWake("मैं बोल रहा था कि हे डीके", names))  // शुरुआत में नहीं
    }

    @Test fun powerButtonSession() {
        assertTrue(InvokeSession.useSession(fromPowerButton = true, settingOn = true, wakeMode = true))
        assertFalse(InvokeSession.useSession(fromPowerButton = false, settingOn = true, wakeMode = true))  // ऐप खुला / Live बटन = पहले जैसा
        assertFalse(InvokeSession.useSession(fromPowerButton = true, settingOn = false, wakeMode = true))
        assertFalse(InvokeSession.useSession(fromPowerButton = true, settingOn = true, wakeMode = false))  // Hey DK बंद = पहले जैसा
        assertEquals(30_000L, InvokeSession.IDLE_MS)
    }

    @Test fun chimeOnlyRightAfterInvoke() {
        assertTrue(LiveChime.due(armedAt = 1000, now = 2500, inCall = false, enabled = true))
        assertFalse(LiveChime.due(armedAt = 0, now = 2500, inCall = false, enabled = true))           // बुलाया नहीं (बीच का re-connect)
        assertFalse(LiveChime.due(armedAt = 1000, now = 1000 + LiveChime.ARM_MS + 1, inCall = false, enabled = true))
        assertFalse(LiveChime.due(armedAt = 1000, now = 2500, inCall = true, enabled = true))
        assertFalse(LiveChime.due(armedAt = 1000, now = 2500, inCall = false, enabled = false))
        val pcm = LiveChime.pcm()
        assertTrue(kotlin.math.abs(LiveChime.RATE * LiveChime.durationMs() / 1000 - pcm.size) < LiveChime.RATE / 100)
        assertTrue(pcm.maxOf { kotlin.math.abs(it.toInt()) } < Short.MAX_VALUE * 0.3)   // हल्की आवाज़
        assertTrue(kotlin.math.abs(pcm.first().toInt()) < 200 && kotlin.math.abs(pcm.last().toInt()) < 200)  // क्लिक नहीं
    }

    @Test fun wired() {
        val dir = listOf(File("src/main/java/com/dk/assistant"), File("app/src/main/java/com/dk/assistant")).first { it.exists() }
        val svc = File(dir, "DKAccessibilityService.kt").readText()
        assertTrue(svc.contains("WakeMatch.strictWake(heard"))
        assertTrue(svc.contains("LiveChime.connected("))
        assertTrue(svc.contains("sessionLive && !wakeLive && gemLive.idleExited"))
        assertTrue(File(dir, "DKVoiceInteractionSessionService.kt").readText().contains("session = true"))
    }
}

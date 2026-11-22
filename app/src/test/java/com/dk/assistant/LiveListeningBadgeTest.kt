package com.dk.assistant

import com.dk.assistant.dkai.DkaiCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 22 नवंबर 2026 (मालिक): "बात होती है, फिर भी CONNECTING ही लिखा रहता है. जुड़ते ही LISTENING हो,
 * और टाइमर तभी शुरू हो जब LISTENING हो - CONNECTING से नहीं".
 */
class LiveListeningBadgeTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    @Test fun oldEar_countsAsListening() {
        // Gemini Live इस्तेमाल नहीं हो रहा, पुराना कान सुन रहा है = LISTENING
        assertTrue(DkaiCore.voiceUp(true, liveReady = false, splitTalking = false, backup = false, oldEar = true))
        assertFalse(DkaiCore.voiceUp(false, liveReady = false, splitTalking = false, backup = false, oldEar = true))
        assertFalse(DkaiCore.voiceUp(true, false, false, false, oldEar = false))
        val up = DkaiCore.voiceUp(true, false, false, false, oldEar = true)
        assertEquals(DkaiCore.Status.LISTENING, DkaiCore.voiceStatus(Mode.LISTENING, live = true, muted = false, work = false, connected = up))
        // DK बोलते समय SPEAKING, फिर वापस LISTENING (CONNECTING नहीं)
        assertEquals(DkaiCore.Status.SPEAKING, DkaiCore.voiceStatus(Mode.SPEAKING, true, false, false, connected = up))
        assertEquals("LISTENING", DkaiCore.callBadge(DkaiCore.voiceStatus(Mode.IDLE, true, false, false, connected = up)))
    }

    @Test fun serviceWiresAllEars() {
        val s = src("DKAccessibilityService.kt")
        assertTrue(s.contains("DkaiCore.voiceUp(DKState.live, gemLive.hearing(), splitTalking, backupMode, oldEarLive)"))
        assertTrue(s.contains("oldEar(liveBefore)"))
        assertTrue(s.contains("DKState.earUp = { voiceUp() }"))
        // चुपचाप दूसरे connection पर बदलते समय भी सुन रहा है
        assertTrue(src("GeminiLive.kt").contains("fun hearing() = canTalk() || (!stopped && ws != null && rotFreezeAt > 0L)"))
        // बैकअप शुरू / ख़त्म = बैज फिर बने
        assertEquals(2, Regex("DKState.linkChanged\\(\\)").findAll(src("DKAccessibilityHear.kt")).count())
    }

    @Test fun clockStartsOnListening_notOnConnecting() {
        val d = src("DKState.kt")
        // चालू होते समय: DK सुन नहीं रहा = घड़ी नहीं
        assertTrue(d.contains("val ended = if (on && !earUpNow()) null else LiveClock.onLive(on, System.currentTimeMillis())"))
        // जुड़ते ही (linkChanged) घड़ी शुरू
        assertTrue(d.contains("if (live && earUpNow()) LiveClock.onLive(true, System.currentTimeMillis())"))
        // notification की घड़ी भी नई
        assertTrue(src("LiveNotify.kt").contains("|\${LiveClock.startedAt}\""))
    }
}

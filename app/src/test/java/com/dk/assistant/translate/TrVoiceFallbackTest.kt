package com.dk.assistant.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 25 नवंबर 2026 (मालिक: "ट्रांसलेटर का UI पूरे फ़ोन जैसा; Gemini की आवाज़ न बने तो Live, फिर ऑफ़लाइन TTS - आवाज़ रुके नहीं;
 * जवाब तुरंत"): बोलने का क्रम, कब दूसरे रास्ते से बोलना है, तेज़ ठहराव, और DK वाली थीम.
 */
class TrVoiceFallbackTest {
    private fun src(p: String): String {
        val dir = listOf(File("src/main/java/com/dk/assistant"), File("app/src/main/java/com/dk/assistant")).first { it.exists() }
        return File(dir, p).readText()
    }

    @Test fun chain_geminiThenPhone_neverEmpty() {
        assertEquals(listOf(TrVoice.Src.GEMINI_TTS, TrVoice.Src.PHONE), TrVoice.fallbacks(hasKey = true, online = true, geminiBlocked = false))
        // limit खत्म / नेट नहीं / key नहीं = सीधे फ़ोन की आवाज़ (इंतज़ार नहीं)
        assertEquals(listOf(TrVoice.Src.PHONE), TrVoice.fallbacks(hasKey = true, online = true, geminiBlocked = true))
        assertEquals(listOf(TrVoice.Src.PHONE), TrVoice.fallbacks(hasKey = true, online = false, geminiBlocked = false))
        assertEquals(listOf(TrVoice.Src.PHONE), TrVoice.fallbacks(hasKey = false, online = true, geminiBlocked = false))
        assertTrue(TrVoice.GEMINI_WAIT_MS <= 4000)
    }

    @Test fun needFallback_onlyWhenLiveSilent() {
        assertTrue(TrVoice.needFallback(gotAudio = false, text = "नमस्ते", alreadySpoken = false, dropped = false))
        assertFalse(TrVoice.needFallback(gotAudio = true, text = "नमस्ते", alreadySpoken = false, dropped = false))
        assertFalse(TrVoice.needFallback(gotAudio = false, text = "  ", alreadySpoken = false, dropped = false))
        assertFalse(TrVoice.needFallback(gotAudio = false, text = "नमस्ते", alreadySpoken = true, dropped = false))
        // मेरी अपनी हिंदी (DROP) दोबारा नहीं बोलनी
        assertFalse(TrVoice.needFallback(gotAudio = false, text = "नमस्ते", alreadySpoken = false, dropped = true))
    }

    @Test fun faster_turnEnd() {
        assertFalse(TrVoice.turnEnds(1000, 280))
        assertTrue(TrVoice.turnEnds(1000, 320))
        assertTrue(TrVoice.turnEnds(2600, 160))
        assertTrue(TrVoice.turnEnds(6000, 0))
        assertTrue(TrVoice.turnEnds(8000, 0))
        assertTrue(TrVoice.END_SILENCE_MS < 480)
        assertTrue(TrLive.setupJson("gemini-3.1-flash-live-preview", "p", "Zephyr").contains("\"silenceDurationMs\":300"))
    }

    @Test fun screen_showsOnlyLatest() {
        val a = TrEngine.Line(false, "mr").apply { orig = "नमस्कार"; tr = "नमस्ते" }
        val b = TrEngine.Line(false, "mr").apply { orig = "कसे आहात"; tr = "कैसे हो" }
        val empty = TrEngine.Line(false, "mr")
        assertEquals(b, TrVoice.current(listOf(a, b)))
        assertEquals(a, TrVoice.current(listOf(a, empty)))
        assertEquals(null, TrVoice.current(emptyList()))
    }

    @Test fun keepsListening_whileHindiPlays() {
        val e = src("translate/TrEngine.kt")
        val held = e.substringAfter("private fun aHeld()").substringBefore("private fun micLoop")
        assertFalse(held.contains("hindi.busy()"))
        assertTrue(held.contains("partner.busy()"))
    }

    @Test fun bcp_forBothSides() {
        assertEquals("hi-IN", TrVoice.bcp(TrLang.HINDI))
        assertEquals("en-IN", TrVoice.bcp("zz"))
    }

    @Test fun engine_wiresFallback() {
        val e = src("translate/TrEngine.kt")
        assertTrue(e.contains("fallbackA()"))
        assertTrue(e.contains("fallbackB()"))
        assertTrue(e.contains("TrVoice.turnEnds("))
        assertTrue(e.contains("speak?.close()"))
    }

    @Test fun screen_usesAppTheme() {
        val s = src("translate/TranslatorActivity.kt")
        assertTrue(s.contains("ClayTheme.t"))
        assertTrue(s.contains("R.drawable.glass_card"))
        assertTrue(s.contains("PlainLook.install(this)"))
        assertFalse(s.contains("0xFF0B1220"))
        assertTrue(s.contains("UiFit.tablet(newBase)"))
    }
}

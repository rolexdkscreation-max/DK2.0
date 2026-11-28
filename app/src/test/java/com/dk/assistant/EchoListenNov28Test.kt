package com.dk.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 28 नवंबर 2026 (मालिक का तीसरा लॉग): DK चुप होने के बाद आपने "ईद के बारे में कौन सा..." / "मेरी बात ही नहीं समझ
 * रही" कहा तो DK ने उसे अपनी गूँज मानकर जवाब नहीं बजाया; अटके सत्र के बाद आपकी नई बात का जवाब "दोहराव" मानकर रोका.
 */
class EchoListenNov28Test {
    private fun src(name: String): String =
        (File("src/main/java/com/dk/assistant/$name").takeIf { it.exists() } ?: File("app/src/main/java/com/dk/assistant/$name")).readText()

    private val dk = "ईद के बारे में कौन सा ईदी का गाना तुम सुनना चाहोगे बताओ"

    @Test fun speechAfterDkEndedIsNotEcho() {
        assertTrue("DK के बोलते हुए = गूँज हो सकती", LiveRepeat.isEcho(dk, dk, 900, spokeAfterDkMs = -2_000))
        assertFalse("DK चुप होने के 1.7s बाद आप बोले", LiveRepeat.isEcho(dk, dk, 4_900, spokeAfterDkMs = 1_700))
        assertTrue("पुराना रास्ता वही", LiveRepeat.isEcho(dk, dk, 900))
    }

    @Test fun notUnderstoodIsAskAgain() {
        assertTrue(LiveRepeat.asksAgain("मेरी बात ही नहीं समझ रही है तो क्या बताओ"))
    }

    @Test fun newSpeechClearsDup() {
        val g = LiveRepeat()
        g.onResent(1_000); g.onReplyDone("ठीक है बॉस कुछ नहीं तो आराम करो थोड़ी देर बात करते हैं", 9_000)
        assertFalse("जवाब के बाद आप बोले", g.dupReply(29_000, 26_000))
        val s = src("GeminiLiveRepeat.kt")
        assertTrue(s.contains("maxOf(utt.lastRealAt(), speechStartAt)"))
        assertTrue(s.contains("LiveRepeat.isEcho(heard, lastDkLine, now - playEnd, after)"))
    }
}

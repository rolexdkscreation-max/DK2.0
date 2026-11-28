package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 28 नवंबर 2026 (मालिक का दूसरा लॉग): Bluetooth पर आवाज़ का मिलान कम (0.37) = DK के जवाब पर आपका सवाल "बगल की
 * बातचीत" मानकर चुप; छोटी धीमी बात ("है ठीक है") दोबारा भेजने पर भी न सुनी तो connection पर connection और बातचीत नई;
 * Home की नीचे वाली पट्टी में "Mic" का नाम कटता.
 */
class SideTalkListenNov28Test {
    private fun src(name: String): String =
        (File("src/main/java/com/dk/assistant/$name").takeIf { it.exists() } ?: File("app/src/main/java/com/dk/assistant/$name")).readText()

    @Test fun answerToDkIsNotSideTalk() {
        val s = SideTalk.State()
        var q = s.begin(1_000); s.voice(SideTalk.Who.BOSS, 2_000, q)
        q = s.begin(3_000); s.voice(SideTalk.Who.OTHER, 4_000, q)
        // DK अभी 4s पहले बोली थी = उसी से बात
        assertNull(s.muteReason(4_100, "क्या पसंद होगा और क्या करती हो", dkSpokeAgoMs = 4_000))
        // DK बहुत देर से चुप = पुराना नियम वही
        assertEquals("कोई और आपसे बात कर रहा है", s.muteReason(4_100, "भैया चाय पियोगे", dkSpokeAgoMs = 60_000))
        assertEquals("कोई और आपसे बात कर रहा है", s.muteReason(4_100, "भैया चाय पियोगे"))
    }

    @Test fun listeningModeStillWins() {
        val s = SideTalk.State()
        s.start(1_000)
        assertEquals("सुनने वाला मोड - DK का नाम नहीं", s.muteReason(2_000, "हाँ भाई कल मिलते हैं", dkSpokeAgoMs = 1_000))
    }

    @Test fun replayUnheardMeansTextNotSwap() {
        assertTrue(LiveRecover.replayUnheard(10_000, 10_000))
        assertTrue(LiveRecover.replayUnheard(10_000, 10_400))
        assertFalse("बाद में नई बात", LiveRecover.replayUnheard(10_000, 15_000))
        assertFalse("कभी दोबारा नहीं भेजा", LiveRecover.replayUnheard(0, 10_000))
        val live = src("GeminiLive.kt")
        assertTrue(live.contains("LiveRecover.replayUnheard(replaySentAt, zEnd) && lastChance("))
        assertTrue(src("GeminiLiveRecover.kt").contains("replaySentAt = now"))
        assertTrue(src("GeminiLiveSideTalk.kt").contains("Prefs.wakeWords(c), dkAgo)"))
    }

    @Test fun micLabelFits() {
        assertTrue(com.dk.assistant.ui.HomeTabs.MIC_CELL_DP >= 66f + 2f + 18f)
        assertEquals("पट्टी की ऊँचाई वही", 64f, com.dk.assistant.ui.HomeTabs.MIC_CELL_DP - com.dk.assistant.ui.HomeTabs.MIC_LIFT_DP, 0.01f)
    }
}

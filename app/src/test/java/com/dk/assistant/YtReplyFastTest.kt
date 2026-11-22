package com.dk.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 22 नवंबर 2026 (मालिक: "YouTube चला जाता है, 'सोच रहा हूँ' लिखा रहता है, जवाब नहीं आता"):
 * वीडियो चलने के बाद ऐड / फुल स्क्रीन का काम पीछे - DK लाइव को जवाब कुछ सेकंड में; चुप रहा तो याद दिलाना.
 */
class YtReplyFastTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    @Test fun replyWaitIsShort() {
        assertTrue(YtAssist.REPLY_WAIT_MS in 1_000L..4_000L)
        // पहले: ऐड का इंतज़ार 3 x 9 s - जवाब उससे कहीं पहले
        assertTrue(YtAssist.REPLY_WAIT_MS < YtAssist.SKIP_WAIT_MS)
    }

    @Test fun pendingNoteIsHonest() {
        val r = "PLAYING: x (verified: playing)" + YtAssist.pending(true)
        val hi = YtAssist.noteHi(r)
        assertTrue(hi.contains("स्किप कर दूँगा")); assertTrue(hi.contains("फुल स्क्रीन भी कर दूँगा"))
        assertFalse(YtAssist.pending(false).contains("full screen"))
        // "हो गया" वाला दावा नहीं (स्किप किया / फुल स्क्रीन कर दिया)
        assertFalse(hi.contains("स्किप कर दिया")); assertFalse(hi.contains("फुल स्क्रीन कर दिया"))
        // LiveFast अब भी इसे चलता वीडियो मानता है (ठीक नतीजा)
        assertTrue(LiveFast.ok("youtube_play", r))
    }

    @Test fun wired() {
        val a = src("DKAccessibilityService.kt")
        assertTrue(a.contains("scope.async { r + ytAfterPlay(full) }"))
        assertTrue(a.contains("withTimeoutOrNull(YtAssist.REPLY_WAIT_MS) { after.await() } ?: (r + YtAssist.pending(full))"))
        val g = src("GeminiLive.kt")
        val tools = g.substringAfter("internal val ANSWER_TOOLS = setOf(").substringBefore("\n        internal")
        assertTrue(tools.contains("\"phone_task\")"))
    }
}

package com.dk.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 28 नवंबर 2026 (मालिक का लॉग): "क्या कर रहे हो" पर हर बार search_history और वही "अच्छा, सुनो..." जवाब;
 * दोहराव रुका तो कोई जवाब ही नहीं आया.
 */
class FreshAnswerNov28Test {
    private fun src(name: String): String =
        (File("src/main/java/com/dk/assistant/$name").takeIf { it.exists() } ?: File("app/src/main/java/com/dk/assistant/$name")).readText()

    @Test fun smallTalkIsNotHistorySearch() {
        assertTrue(HistoryOnDemand.smallTalk("क्या कर रहे हो"))
        assertTrue(HistoryOnDemand.smallTalk("मैंने पूछा तुमसे क्या कर रहे हो"))
        assertTrue(HistoryOnDemand.smallTalk("कैसी हो"))
        assertFalse("पुरानी बात", HistoryOnDemand.smallTalk("कल मैं क्या कर रहा था"))
        assertFalse("याद", HistoryOnDemand.smallTalk("याद है मैंने पूछा था तुम क्या कर रही हो"))
        assertFalse("निजी बात की खोज", HistoryOnDemand.smallTalk("मेरी बहन का नंबर"))
        assertFalse(HistoryOnDemand.smallTalk(""))
        assertTrue(src("GeminiLiveTools.kt").contains("HistoryOnDemand.smallTalk(args.optString(\"query\")"))
    }

    @Test fun repeatStopAsksFreshAnswer() {
        val n = LiveRepeat.freshNote("क्या कर रहे हो")
        assertTrue(n.contains("क्या कर रहे हो"))
        assertTrue("कोई तय जवाब नहीं", !n.contains("बात कर रही") && !n.contains("talking with"))
        val r = src("GeminiLiveRepeat.kt")
        assertTrue(r.contains("freshAsk = user"))
        assertTrue(r.contains("now - freshAt < LiveRepeat.FRESH_GAP_MS"))
        assertTrue(src("GeminiLiveServer.kt").contains("askFresh(u)"))
    }
}

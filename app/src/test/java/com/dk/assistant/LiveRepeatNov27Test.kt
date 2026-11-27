package com.dk.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 27 नवंबर 2026 (मालिक: "DK एक ही सवाल का जवाब 3-4 बार देता है, मेरी बात नहीं सुनता, पहले वाली बात दोहराता है").
 */
class LiveRepeatNov27Test {
    private val old = "अभी मुंबई में मौसम साफ़ है और तापमान लगभग 28 डिग्री है, शाम को हल्की हवा चलेगी"

    @Test fun secondAnswerAfterResendIsDropped() {
        val g = LiveRepeat()
        g.onResent(1_000)
        assertFalse("पहला जवाब बजना चाहिए", g.dupReply(2_000, 500))
        g.onReplyDone(old, 6_000, "मौसम कैसा है")
        assertTrue("दोबारा भेजी बात का दूसरा जवाब = दोहराव", g.dupReply(7_000, 500))
        assertFalse("एक ही बार", g.dupReply(7_500, 500))
    }

    @Test fun newSpeechOrNoteAfterResendIsNotDropped() {
        val g = LiveRepeat()
        g.onResent(1_000)
        g.onReplyDone(old, 6_000, "मौसम कैसा है")
        assertFalse("जवाब के बाद आप बोले = नई बात का जवाब", g.dupReply(9_000, 8_000))
        val n = LiveRepeat()
        n.onResent(1_000)
        n.onReplyDone(old, 6_000, "मौसम कैसा है")
        n.onInput()
        assertFalse("SYSTEM NOTE / tool के बाद का जवाब दोहराव नहीं", n.dupReply(7_000, 500))
        val q = LiveRepeat()
        q.onReplyDone(old, 6_000, "मौसम कैसा है")
        assertFalse("दोबारा कुछ भेजा ही नहीं", q.dupReply(7_000, 500))
    }

    @Test fun oldAnswerOnNewQuestionIsCut() {
        val g = LiveRepeat()
        g.onReplyDone(old, 1_000, "मौसम कैसा है")
        g.onReplyStart()
        assertTrue(g.isRepeat(old.take(60) + " और", "आज कौन सी फ़िल्म देखूँ", 20_000))
    }

    @Test fun freshOrAskedAgainAnswersPlay() {
        val g = LiveRepeat()
        g.onReplyDone(old, 1_000, "मौसम कैसा है")
        g.onReplyStart()
        assertFalse("नया जवाब", g.isRepeat("अरे वाह, फ़िल्म के लिए तो आज शाम बिल्कुल सही है, कोई हल्की कॉमेडी देखो", "फ़िल्म बताओ", 20_000))
        g.onReplyStart()
        assertFalse("आपने 'फिर से' कहा", g.isRepeat(old, "फिर से बताओ", 20_000))
        g.onReplyStart()
        assertFalse("आपने वही सवाल फिर पूछा", g.isRepeat(old, "मौसम कैसा है", 20_000))
        g.onReplyStart()
        assertFalse("3 मिनट बाद याद नहीं", g.isRepeat(old, "फ़िल्म बताओ", 1_000 + LiveRepeat.KEEP_MS + 1))
        assertFalse("छोटी शुरुआत अभी नहीं जाँचते", LiveRepeat().isRepeat("हाँ सर", "x", 0))
    }

    @Test fun numbersMakeAnswersDifferent() {
        val g = LiveRepeat()
        g.onReplyDone("अभी शाम के 5 बजकर 10 मिनट हुए हैं सर, आज शुक्रवार है", 1_000, "")
        g.onReplyStart()
        assertFalse(g.isRepeat("अभी शाम के 5 बजकर 40 मिनट हुए हैं सर, आज शुक्रवार है", "", 20_000))
    }

    @Test fun ownEchoIsNotAnswered() {
        val dk = "अभी मुंबई में मौसम साफ़ है और तापमान लगभग अट्ठाईस डिग्री है"
        assertTrue(LiveRepeat.isEcho("मौसम साफ़ है और तापमान लगभग अट्ठाईस डिग्री", dk, 800))
        assertFalse("देर से = असली बात", LiveRepeat.isEcho("मौसम साफ़ है और तापमान लगभग अट्ठाईस डिग्री", dk, 9_000))
        assertFalse("छोटी असली बात", LiveRepeat.isEcho("हाँ ठीक है", dk, 500))
        assertFalse("नई बात", LiveRepeat.isEcho("कल का मौसम कैसा रहेगा पुणे में बताओ", dk, 800))
    }

    @Test fun inputDetection() {
        assertTrue(LiveRepeat.isInput("{\"realtimeInput\":{\"text\":\"hi\"}}"))
        assertTrue(LiveRepeat.isInput("{\"toolResponse\":{}}"))
        assertFalse(LiveRepeat.isInput("{\"realtimeInput\":{\"audio\":{\"data\":\"AAA\"}}}"))
    }

    @Test fun wiredIntoLive() {
        val src = LiveSrc.all()
        assertTrue(src.contains("if (repeatCheck()) return@let"))
        assertTrue(src.contains("if (echoHeard(t)) return@let"))
        assertTrue(src.contains("repeatGuard.onResent(now)"))
        assertTrue(src.contains("repeatGuard.onReplyDone(r, System.currentTimeMillis(), u)"))
        assertTrue(src.contains("if (LiveRepeat.isInput(m)) repeatGuard.onInput()"))
        val f = File("src/main/java/com/dk/assistant/GeminiLiveRepeat.kt").takeIf { it.exists() }
            ?: File("app/src/main/java/com/dk/assistant/GeminiLiveRepeat.kt")
        assertTrue(f.readText().contains("stopForUser(\"दोहराव\")"))
    }
}

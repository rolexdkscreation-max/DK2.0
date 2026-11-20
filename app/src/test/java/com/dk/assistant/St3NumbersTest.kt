package com.dk.assistant

import com.dk.assistant.voice.st3.St3Numbers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 20 नवंबर 2026 (मालिक: "245, 4 बजकर 24 मिनट - नंबर सही से नहीं बोलता, सुनाई नहीं देता"): ऑफ़लाइन आवाज़ में अंक -> हिंदी शब्द */
class St3NumbersTest {
    @Test fun time() {
        assertEquals("अभी चार बजकर चौबीस मिनट हैं।", St3Numbers.speak("अभी 4:24 बजे हैं।"))
        assertEquals("समय शाम चार बजकर बीस मिनट है", St3Numbers.speak("समय 16:20 है"))
        assertEquals("सुबह दस बजे मीटिंग", St3Numbers.speak("10:00 AM मीटिंग"))
        assertEquals("दोपहर बारह बजकर पाँच मिनट", St3Numbers.speak("12:05 pm"))
        assertEquals("रात बारह बजकर तीस मिनट", St3Numbers.speak("0:30"))
    }

    @Test fun counts() {
        assertEquals("कुल दो सौ पैंतालीस रुपये", St3Numbers.speak("कुल 245 रुपये"))
        assertEquals("एक लाख पच्चीस हज़ार रुपये मिले", St3Numbers.speak("₹1,25,000 मिले"))
        assertEquals("दो दशमलव पाँच किलो", St3Numbers.speak("2.5 किलो"))
        assertEquals("चालीस प्रतिशत बैटरी", St3Numbers.speak("40% बैटरी"))
        assertEquals("साल दो हज़ार छब्बीस में तीन बार", St3Numbers.speak("साल 2026 में 3 बार"))
        assertEquals("चार-पाँच दिन", St3Numbers.speak("4-5 दिन"))
        assertEquals("चौबीस", St3Numbers.speak("२४"))                         // देवनागरी अंक भी
        assertEquals("निन्यानवे करोड़ एक", St3Numbers.words(990_000_001))
    }

    @Test fun phoneDigitByDigit_andCodesUntouched() {
        assertEquals("नंबर नौ आठ सात छह पाँच चार तीन दो एक शून्य है", St3Numbers.speak("नंबर 9876543210 है"))
        assertEquals("4G नेटवर्क, MP3, 1st", St3Numbers.speak("4G नेटवर्क, MP3, 1st"))
        assertEquals("नमस्ते, मैं डीके हूँ।", St3Numbers.speak("नमस्ते, मैं डीके हूँ।"))
    }

    @Test fun wiredInOfflineVoicePaths() {
        assertTrue(com.dk.assistant.voice.engine.St3VoiceEngine.speakable("अभी 4:24 बजे हैं").contains("चार बजकर चौबीस मिनट"))
        val root = listOf(java.io.File("src/main/java"), java.io.File("app/src/main/java")).first { it.exists() }
        assertTrue(java.io.File(root, "com/dk/assistant/voice/st3/OfflineVoice.kt").readText().contains("St3Numbers.speak("))
        assertTrue(java.io.File(root, "com/dk/assistant/StoryVoice.kt").readText().contains("St3Numbers.speak("))
    }
}

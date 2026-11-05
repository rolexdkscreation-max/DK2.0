package com.dk.assistant

import com.dk.assistant.voice.VoiceCmdMatch
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** v4.7 मालिक: ऑफलाइन में आम बातें (कहाँ हो, क्या कर रहे, खाना खाया); नेट वाली बातें नहीं */
class OfflineTalkTest {
    private val x = OfflineTalk.Ctx(user = "राज", me = "DK", hour = 13, seed = 1)

    @Test fun casualTalk() {
        for (t in listOf("क्या कर रहे हो", "खाना खाया?", "कहाँ हो", "किधर हो", "कैसे हो", "गुड नाइट", "धन्यवाद",
                "एक जोक सुनाओ", "मैं बहुत थक गया", "तुम कौन हो", "क्या कर सकते हो", "और बताओ", "बाय", "ठीक है",
                "आज मूड ख़राब है", "kya kar rahe ho", "क्या हाल है"))
            assertNotNull(t, OfflineTalk.casual(t, x))
        assertTrue(OfflineTalk.casual("तुम कौन हो", x)!!.contains("DK"))
        assertTrue(OfflineTalk.casual("कहाँ हो", x)!!.contains("यहीं"))
    }

    @Test fun nuktaAndAnusvara() {
        assertNotNull(OfflineTalk.casual("कहां हो", x))
        assertNotNull(OfflineTalk.casual("मूड खराब है", x))
    }

    @Test fun netThingsNotCasual() {
        for (t in listOf("आज मौसम कैसा है", "आज की न्यूज़ बताओ", "मैच का स्कोर", "यूट्यूब पर गाना चलाओ", "पनीर की रेसिपी बताओ",
                "दिल्ली का रास्ता बताओ", "गूगल पर सर्च करो")) {
            assertTrue(t, OfflineTalk.needsNet(t))
            assertNull(t, OfflineTalk.casual(t, x))
        }
        for (t in listOf("क्या कर रहे हो", "खाना खाया", "कहाँ हो")) assertFalse(t, OfflineTalk.needsNet(t))
    }

    @Test fun randomStaysQuiet() {
        for (t in listOf("", "   ", "फ्रिज का दरवाज़ा बंद करना", "कल ऑफिस में मीटिंग थी लंबी वाली"))
            assertNull(t, OfflineTalk.casual(t, x))
    }

    @Test fun whereIsTalkNotRing() {
        assertFalse(VoiceCmdMatch.ringText("कहाँ हो"))
        assertFalse(VoiceCmdMatch.ringText("किधर हो"))
        assertTrue(VoiceCmdMatch.ringText("कहाँ हो फोन बजाओ"))
        assertTrue(VoiceCmdMatch.ringText("मुझे नहीं दिख रहे"))
        val names = listOf("डीके", "dk")
        val off = PhoneLockVoice.Cfg(lockOn = false, unlockOn = false, names = emptyList(), lock = emptyList(), unlock = emptyList())
        assertFalse(OfflineListen.keepText("डीके कहाँ हो", names, false, off))
        assertTrue(OfflineListen.keepText("डीके कहाँ हो फोन बजाओ", names, false, off))
    }

    private fun src(p: String) = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p")).first { it.exists() }.readText()

    @Test fun wiring() {
        val s = src("DKAccessibilityOffline.kt")
        assertTrue(s.contains("OfflineTalk.reply("))
        assertTrue(s.contains("OfflineTalk.FOLLOW_MS"))
        assertTrue(s.contains("speaker.speak(reply)"))
    }
}

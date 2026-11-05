package com.dk.assistant

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * मालिक (नवंबर 2026): "ऑफलाइन मोड में लॉक स्क्रीन वगैरह काम सही से नहीं हो रहे".
 * जड़: keepText सिर्फ़ UNLOCK मानता था (लॉक का switch बंद = "DK फोन लॉक करो" अनसुना) और बाकी फ़ोन वाले काम
 * (टॉर्च, आवाज़, ऐप खोलो, अलार्म…) ऑफलाइन में पूरी तरह अनसुने थे.
 */
class OfflineLocalCmdTest {
    private fun src(name: String) = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()

    private val names = listOf("डीके", "दीके", "डी के", "dk", "d k")
    /** Settings में लॉक / अनलॉक बंद (default) */
    private val off = PhoneLockVoice.Cfg(lockOn = false, unlockOn = false, names = emptyList(), lock = emptyList(), unlock = emptyList())

    @After fun clear() { OfflineCommands.pending = null }

    @Test fun lockWorksEvenWhenSettingsSwitchOff() {
        assertTrue(OfflineListen.keepText("डीके फोन लॉक करो", names, false, off))
        assertTrue(OfflineListen.keepText("डीके स्क्रीन लॉक करो", names, false, off))
        assertTrue(OfflineListen.keepText("डीके फोन अनलॉक करो", names, false, off))
    }

    @Test fun phoneCommandsWorkOfflineWithName() {
        assertTrue(OfflineListen.keepText("डीके टॉर्च जलाओ", names, false, off))
        assertTrue(OfflineListen.keepText("डीके टॉर्च बंद करो", names, false, off))
        assertTrue(OfflineListen.keepText("डीके होम पर जाओ", names, false, off))
        assertTrue(OfflineListen.keepText("डीके आवाज़ बढ़ाओ", names, false, off))
        assertTrue(OfflineListen.keepText("डीके सुबह 7 बजे का अलार्म लगाओ", names, false, off))
        assertTrue(OfflineListen.keepText("डीके स्क्रीनशॉट लो", names, false, off))
        assertTrue(OfflineListen.keepText("डीके व्हाट्सएप खोलो", names, false, off))
        assertTrue(OfflineListen.keepText("डीके कैमरा खोलो", names, false, off))
    }

    @Test fun ownerVoiceNeedsNoName() {
        assertTrue(OfflineListen.keepText("टॉर्च जलाओ", names, false, off, ownerOk = true))
        assertFalse(OfflineListen.keepText("टॉर्च जलाओ", names, false, off, ownerOk = false))   // किसी और की / अनजानी आवाज़
    }

    @Test fun internetTasksAndChatStillIgnored() {
        assertFalse(OfflineListen.keepText("डीके गाना चलाओ", names, false, off))           // YouTube - नेट चाहिए
        assertFalse(OfflineListen.keepText("डीके यूट्यूब पर भजन सर्च करो", names, false, off))
        assertFalse(OfflineListen.keepText("आज मौसम कैसा है", names, false, off, ownerOk = true))
        assertFalse(OfflineListen.keepText("फोन लॉक करो", names, false, off))               // नाम / मालिक बिना
        assertTrue("youtube_play" in OfflineListen.NEEDS_NET)
    }

    @Test fun smsYesNoPassesWithoutName() {
        assertFalse(OfflineListen.keepText("हाँ", names, false, off))
        OfflineCommands.pending = OfflineCommands.Pending("sms", "9999999999", "राहुल", "आ रहा हूँ", System.currentTimeMillis() + 30_000)
        assertTrue(OfflineListen.keepText("हाँ", names, false, off))
        assertTrue(OfflineListen.keepText("नहीं", names, false, off))
    }

    @Test fun serviceWiring() {
        val off = src("DKAccessibilityOffline.kt")
        assertTrue(off.contains("if (agentHearing && !raw.isNullOrBlank())"))
        // v4.8 मालिक: ऑफलाइन में सिर्फ़ "डीके ..." - मालिक की आवाज़ पर भी बिना नाम नहीं
        assertTrue(off.contains("ownerOk = false"))
        val svc = src("DKAccessibilityService.kt")
        assertTrue(svc.contains("internal suspend fun ownerHeard"))
        assertTrue(svc.contains("OfflineStt.transcribe(me, hit.rest)"))
    }
}

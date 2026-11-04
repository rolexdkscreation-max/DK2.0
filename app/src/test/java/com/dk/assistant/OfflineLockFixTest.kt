package com.dk.assistant

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * मालिक (नवंबर 2026): "डाटा / वाई-फाई बंद हो तो 'डीके फोन लॉक कर दो', 'फोन अनलॉक करो', 'फोन ऑफ कर दो' नहीं सुनता".
 * जड़ें: (1) बिना नेट पहचान "डीके" को "डीजे" लिखती है - नाम नहीं मिला = अनसुना; (2) loop में Settings का लॉक switch बंद = LOCK गिरा,
 * बात AI (नेट) को; (3) "फोन ऑफ / चालू करो" वाक्य सूची में नहीं; (4) Hey DK के बाद बिना नेट भी Gemini Live को;
 * (5) फ़ोन में हिंदी का बिना-नेट मॉडल नहीं = चुपचाप कुछ नहीं.
 */
class OfflineLockFixTest {
    private val L = PhoneLockVoice.Act.LOCK
    private val U = PhoneLockVoice.Act.UNLOCK
    private val names = listOf("डीके", "दीके", "डी के", "dk", "d k")
    private val off = PhoneLockVoice.Cfg()      // Settings में लॉक / अनलॉक बंद (default)
    private fun src(path: String) = listOf(File("src/main/java/com/dk/assistant/$path"), File("app/src/main/java/com/dk/assistant/$path")).first { it.exists() }.readText()

    @After fun reset() { PhoneLockVoice.cfg = PhoneLockVoice.Cfg() }

    @Test fun misheardNameStillWorks() {
        assertEquals(L, PhoneLockVoice.instant("डीजे फोन लॉक कर दो", names, needName = true, c = off))
        assertEquals(U, PhoneLockVoice.instant("डीजे फोन अनलॉक करो", names, needName = true, c = off))
        assertEquals(L, PhoneLockVoice.instant("DJ phone lock karo", names, needName = true, c = off))
        assertEquals(U, PhoneLockVoice.instant("डी जे फोन अनलॉक करो", names, needName = true, c = off))
        assertEquals(L, PhoneLockVoice.instant("टीके फोन लॉक करो", names, needName = true, c = off))
        assertTrue(OfflineListen.keepText("डीजे फोन लॉक कर दो", names, false, off))
        assertTrue(OfflineListen.keepText("डीजे कहाँ हो", names, false, off))
        assertTrue(OfflineListen.keepText("डीजे टॉर्च जलाओ", names, false, off))
    }

    @Test fun phoneOffOnPhrases() {
        assertEquals(L, PhoneLockVoice.instant("डीके फोन ऑफ कर दो", names, needName = true, c = off))
        assertEquals(L, PhoneLockVoice.instant("डीजे फोन ऑफ़ कर दो", names, needName = true, c = off))
        assertEquals(L, PhoneLockVoice.instant("डीके स्क्रीन बंद करो", names, needName = true, c = off))
        assertEquals(L, PhoneLockVoice.instant("डीके फोन बंद कर दो", names, needName = true, c = off))
        assertEquals(U, PhoneLockVoice.instant("डीके फोन चालू करो", names, needName = true, c = off))
        assertEquals(U, PhoneLockVoice.instant("डीजे फोन ऑन कर दो", names, needName = true, c = off))
        assertEquals(U, PhoneLockVoice.instant("डीके स्क्रीन ऑन करो", names, needName = true, c = off))
    }

    @Test fun stillSafe() {
        assertNull(PhoneLockVoice.instant("फोन ऑफ कर दो", names, needName = true, c = off))          // नाम बिना
        assertNull(PhoneLockVoice.instant("डीजे फोन ऑफ मत करो", names, needName = true, c = off))
        assertNull(PhoneLockVoice.instant("डीके टॉर्च बंद करो", names, needName = true, c = off))
        assertNull(PhoneLockVoice.instant("डीके व्हाट्सएप लॉक करो", names, needName = true, c = off))
        assertNull(PhoneLockVoice.instant("डीके फ़ोन लॉक कर दिया", names, needName = true, c = off))     // DK की अपनी आवाज़
        assertNull(PhoneLockVoice.instant("डीके फ़ोन बंद कर दिया", names, needName = true, c = off))
        assertFalse(OfflineListen.keepText("डीजे गाना चलाओ", names, false, off))
        assertFalse(OfflineListen.keepText("आज डीजे पार्टी है", names, false, off))
    }

    @Test fun wiring() {
        val s = src("DKAccessibilityService.kt")
        assertTrue(s.contains("?.takeIf { it == PhoneLockVoice.Act.UNLOCK || offlineNow }"))
        assertTrue(s.contains("val offlineHey = cmd.isNotBlank() && !NetProbe.online(me)"))
        assertTrue(s.contains("if (!offlineHey && liveReady() && !callBusy() && checkMic())"))
        assertTrue(s.contains("OfflineStt.prepare(this@DKAccessibilityService)"))
        val o = src("OfflineStt.kt")
        assertTrue(o.contains("triggerModelDownload"))
        assertTrue(o.contains("ERROR_LANGUAGE_UNAVAILABLE) {"))
    }
}

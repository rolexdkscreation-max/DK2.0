package com.dk.assistant

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 22 नवंबर 2026 (मालिक): "डीके फोन अनलॉक करो बोलते ही तुरंत स्क्रीन ऑन + PIN वाला हिस्सा, नेट रहे न रहे";
 * "कॉल उठाओ बोलो तो फटाक से उठे, यह न कि नंबर बोलते रहे".
 */
class LockCallFastTest {
    private val names = listOf("डीके", "DK")

    @Test fun `मालिक की लिखावट - देखिए = डीके (लॉक और अनलॉक)`() {
        assertEquals(PhoneLockVoice.Act.UNLOCK, PhoneLockVoice.instant("देखिए, फोन अनलॉक करो", names, needName = true))
        assertEquals(PhoneLockVoice.Act.LOCK, PhoneLockVoice.instant("देखिए फोन लॉक करो", names, needName = true))
        assertEquals(PhoneLockVoice.Act.UNLOCK, PhoneLockVoice.instant("डेके फोन अनलॉक करो", names, needName = true))
        // नाम के बिना (हमेशा सुनने वाला) = नहीं
        assertNull(PhoneLockVoice.instant("फोन अनलॉक करो", emptyList(), needName = true))
    }

    @Test fun `कॉल - देखिए कॉल उठाओ, DK के एलान के बीच भी`() {
        assertTrue(CallReply.bargeOk("देखिए, कॉल उठाओ"))
        assertEquals(CallReply.ANSWER, CallReply.barge("देखिए, कॉल उठाओ"))
        assertEquals(CallReply.ANSWER, CallReply.instant("देखिए कॉल उठा लो", final = false, dkTalking = true))
        // DK की अपनी गूँज अब भी नहीं
        assertNull(CallReply.barge("देखिए उठाऊँ या काट दूँ"))
        assertNull(CallReply.barge("बॉस, अनजान नंबर से कॉल आ रहा है। उठाऊँ या काट दूँ?"))
    }

    @Test fun `अनजान नंबर - अंक नहीं बोलता`() {
        assertEquals("बॉस, अनजान नंबर से कॉल आ रहा है। उठाऊँ या काट दूँ?", CallCmd.announce("+91 98765 43210"))
        assertEquals("बॉस, अनजान नंबर से कॉल आ रहा है। उठाऊँ या काट दूँ?", CallCmd.announce("9876543210"))
        assertEquals("बॉस, राहुल का कॉल आ रहा है। उठाऊँ या काट दूँ?", CallCmd.announce("राहुल"))
        assertEquals("Rahul 2", CallCmd.sayable("Rahul 2"))
        assertEquals("", CallCmd.sayable("98765-43210"))
    }

    @Test fun `caption ही काफ़ी - इंटरनेट वाला STT नहीं`() {
        assertTrue(SttTiming.lockFromCaption("डीके फोन अनलॉक करो", names))
        assertTrue(SttTiming.lockFromCaption("देखिए फोन लॉक करो", names))
        assertFalse(SttTiming.lockFromCaption("डीके फोन अनलॉक करने का तरीका बताओ", names))
        assertFalse(SttTiming.lockFromCaption("", names))
        assertTrue(SttTiming.callFromCaption("कॉल उठाओ"))
        assertTrue(SttTiming.callFromCaption("काट दो"))
        assertFalse(SttTiming.callFromCaption("उठाऊँ या काट दूँ"))
        assertFalse(SttTiming.callFromCaption("कौन है"))
    }

    @Test fun `पहले से जगाना - एक बार, सिर्फ़ अनलॉक पर`() {
        UnlockPreWake.clear()
        assertTrue(UnlockPreWake.begin(1_000))
        assertFalse("दोबारा तुरंत नहीं", UnlockPreWake.begin(2_000))
        assertTrue(UnlockPreWake.fresh(2_000))
        assertFalse(UnlockPreWake.fresh(1_000 + UnlockPreWake.FRESH_MS))
        assertTrue(UnlockPreWake.begin(1_000 + UnlockPreWake.FRESH_MS))
        UnlockPreWake.clear()
        assertTrue(UnlockPreWake.wanted("डीके फोन अनलॉक", names, needName = true))
        assertFalse("लॉक पर स्क्रीन नहीं जगानी", UnlockPreWake.wanted("डीके फोन लॉक करो", names, needName = true))
        assertFalse(UnlockPreWake.wanted("फोन अनलॉक मत करो डीके", names, needName = true))
        assertTrue(UnlockPreWake.BOUNCER_WAIT_MS <= 350 && UnlockPreWake.AFTER_SWIPE_MS < 400)
    }

    @Test fun `लॉक रुका - Live सही कारण बोले`() {
        val r = "ERROR बॉस, सुरक्षा: आवाज़ आपकी नहीं लगी, इसलिए फ़ोन लॉक नहीं किया।"
        assertEquals("बॉस, सुरक्षा: आवाज़ आपकी नहीं लगी, इसलिए फ़ोन लॉक नहीं किया।", PhoneLockVoice.liveSay("lock", r))
        assertEquals(PhoneLockVoice.LIVE_LOCK_SAY, PhoneLockVoice.liveSay("lock", "locked"))
    }

    private fun src(p: String) = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p"))
        .first { it.exists() }.readText()

    @Test fun `रास्ते जुड़े हैं`() {
        val sc = src("DKAccessibilityScreen.kt")
        assertTrue(sc.contains("UnlockGuard.prefetch(this, PhoneLockStore.ownerOnly(this), scope)"))
        assertTrue(sc.indexOf("UnlockGuard.prefetch(") < sc.indexOf("waitScreenOn(pm, 1200)", sc.indexOf("unlockNow")))
        assertFalse("हर बार 350 ms रुकना नहीं", sc.contains("delay(if (asked) 350 else 150)"))
        assertTrue(src("GeminiLiveCaption.kt").contains("svc.preWakeUnlock(\"Live caption\")"))
        assertTrue(src("DKAccessibilityHear.kt").contains("preWakeUnlock(\"caption\")"))
        val svc = src("DKAccessibilityService.kt")
        assertTrue(svc.contains("SttTiming.lockFromCaption(capNow"))
        assertTrue(svc.contains("UnlockGuard.lockAllow("))
        assertTrue(src("GeminiLiveTurnEnd.kt").contains("UnlockGuard.lockAllow(svc, PhoneLockStore.ownerOnly(svc))"))
        assertFalse("रिकॉर्ड करके सिखाने वाला हिस्सा फ़ोन लॉक पेज पर नहीं", src("SettingsActivity.kt").contains("secPhoneLock(); secVoiceCmds()"))
        assertTrue(src("DKAccessibilityCalls.kt").contains("CallCmd.sayable(who)"))
    }
}

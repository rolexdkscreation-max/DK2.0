package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v4.8 मालिक: ऑफलाइन में कोई भी ऐप / कॉल, माइक लगातार, सिर्फ़ "डीके ..." पर काम, अनलॉक साफ़ बताए, "हाँ, बस कर दिया". */
class OfflinePowerTest {
    private fun src(name: String) = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()
    private val names = listOf("डीके", "dk")
    private val off = PhoneLockVoice.Cfg(lockOn = false, unlockOn = false, names = emptyList(), lock = emptyList(), unlock = emptyList())

    @Test fun anyAppAndAnyCallOffline() {
        for (s in listOf("डीके व्हाट्सएप खोलो", "डीके फ्री फायर खोलो", "डीके फोनपे खोलो", "डीके इंस्टाग्राम ओपन करो",
                "डीके राहुल को कॉल करो", "डीके मम्मी को फोन लगाओ", "डीके 9876543210 पर कॉल करो"))
            assertTrue(s, OfflineListen.keepText(s, names, false, off))
        val q = LocalRouter.route("9876543210 पर कॉल करो", names, "")!!
        assertEquals("call_contact", q.action.optString("type"))
        assertEquals("9876543210", q.action.optString("name"))
    }

    @Test fun nameFirstOnly() {
        // नाम के बिना कुछ नहीं (मालिक की आवाज़ हो तब भी - call site ownerOk = false भेजता है)
        assertFalse(OfflineListen.keepText("व्हाट्सएप खोलो", names, false, off))
        assertFalse(src("DKAccessibilityOffline.kt").contains("ownerOk = owner"))
        assertTrue(src("DKAccessibilityOffline.kt").contains("val addressed = rest != null "))
    }

    @Test fun bareNameOpensShortWindow() {
        val now = 100_000L
        assertEquals("डीके व्हाट्सएप खोलो", OfflineListen.withName("व्हाट्सएप खोलो", names, now - 2_000, now))
        assertEquals("व्हाट्सएप खोलो", OfflineListen.withName("व्हाट्सएप खोलो", names, now - 20_000, now))
        assertEquals("व्हाट्सएप खोलो", OfflineListen.withName("व्हाट्सएप खोलो", names, 0L, now))
        assertEquals("डीके टॉर्च जलाओ", OfflineListen.withName("डीके टॉर्च जलाओ", names, now - 1_000, now))
    }

    @Test fun doneSayAfterWork() {
        assertEquals("हाँ, बस कर दिया।", OfflineListen.doneSay(""))
        assertEquals("हाँ, कॉल लिस्ट खोल दी", OfflineListen.doneSay("कॉल लिस्ट खोल दी"))
        assertEquals("ठीक है सर", OfflineListen.doneSay("ठीक है सर"))
        assertTrue(src("AgentRunner.kt").contains("OfflineListen.doneSay(msg)"))
    }

    @Test fun unlockSaysClearly() {
        assertEquals("हाँ, लॉक स्क्रीन खुल गया है।", PhoneLockVoice.UNLOCK_OK_SAY)
        assertTrue(PhoneLockVoice.failSay("PIN डालने पर भी फ़ोन नहीं खुला।").startsWith("लॉक स्क्रीन नहीं खुला है।"))
        val a = src("AgentRunner.kt")
        assertTrue(a.contains("PhoneLockVoice.UNLOCK_OK_SAY"))
        assertTrue(a.contains("PhoneLockVoice.failSay(m)"))
        // स्क्रीन जगी -> ऊपर swipe (Android वाला तरीका चले तब भी) -> PIN वाली जगह -> डालना; कई बार swipe
        val sc = src("DKAccessibilityScreen.kt")
        assertTrue(sc.contains("!PhoneUnlocker.bouncerUp(this)"))
        assertFalse(sc.contains("\"lock screen khuli\""))
        assertTrue(PhoneUnlocker.SWIPE_TRIES >= 3)
    }

    @Test fun micStaysOnAndCallsWorkOffline() {
        val s = src("DKAccessibilityService.kt")
        assertTrue(s.contains("NetProbe.online(this), offlineWakeOn())) return hearOfflineCmds()"))
        assertTrue(s.contains("!offlineEar && com.dk.assistant.voice.HeyDk.on"))
        val o = src("DKAccessibilityOffline.kt")
        assertTrue(o.contains("me.listeningWanted()"))
        assertTrue(o.contains("(calls.ringing || callPending) && !raw.isNullOrBlank()"))
        // माइक बंद तभी: नेट लौटा, DK बंद, म्यूट, कॉल - DK चालू + बिना नेट = चालू
        assertFalse(OfflineListen.stopNow(online = false, liveOn = true, micMuted = false, inCall = false, capturing = false))
        assertTrue(OfflineListen.stopNow(online = false, liveOn = false, micMuted = false, inCall = false, capturing = false))
        // बिना नेट भी बोले (फ़ोन की आवाज़)
        assertTrue(src("Speaker.kt").contains("val localOnly = this.localOnly || !online()"))
    }
}

package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v4.6 मालिक: "जब कॉल आ रहा है तो DK को तुरंत पता होना चाहिए और तुरंत जवाब देगा। जवाब देते में अगर यूज़र बोलेगा
 * 'डीके फोन उठा लो' तो फटाक से पहले फोन उठाएगा, और 'फोन उठा के स्पीकर पर डाल दो' तो उठा के स्पीकर पर डाल देगा"।
 */
class CallBargeTest {
    private val names = listOf("डीके", "dk", "मायरा")

    @Test fun `एलान के बीच - DK के नाम के साथ उठाओ`() {
        assertEquals(CallReply.ANSWER, CallReply.barge("डीके फोन उठा लो", names))
        assertEquals(CallReply.ANSWER, CallReply.barge("DK, phone utha lo", names))
        assertEquals(CallReply.ANSWER, CallReply.barge("मायरा कॉल उठाओ", names))
    }

    @Test fun `एलान के बीच - उठा के स्पीकर पर`() {
        assertEquals(CallReply.ANSWER_SPEAKER, CallReply.barge("डीके फोन उठा के स्पीकर पर डाल दो", names))
        assertEquals(CallReply.ANSWER_SPEAKER, CallReply.barge("डीके स्पीकर पर डालो", names))
    }

    @Test fun `एलान के बीच - काटो भी`() {
        assertEquals(CallReply.REJECT, CallReply.barge("डीके कॉल काट दो", names))
    }

    @Test fun `DK की अपनी आवाज़ (गूँज) से कुछ नहीं`() {
        assertNull(CallReply.barge("बॉस, राहुल का कॉल आ रहा है। उठाऊँ या काट दूँ?", names))
        assertNull(CallReply.barge("उठाऊँ या काट दूँ", names))
        assertNull(CallReply.barge("डीके उठाऊँ या काट दूँ", names))      // नाम के साथ भी गूँज = नहीं
        assertTrue(CallReply.askEcho("उठाऊं या काट दूं"))
    }

    @Test fun `नाम के बिना एलान के बीच = नहीं (गूँज का टुकड़ा हो सकता है)`() {
        assertNull(CallReply.barge("काट दूँ", names))
        assertNull(CallReply.barge("उठा लो", names))
        assertFalse(CallReply.bargeOk("", names))
        // "andkaat" जैसे शब्द के बीच "dk" = नाम नहीं
        assertFalse(CallReply.bargeOk("handkerchief uthao", names))
    }

    @Test fun `कौन है = एलान के बीच नहीं (DK पहले ही बता रहा है)`() {
        assertNull(CallReply.barge("डीके कौन है", names))
    }

    @Test fun `एलान के बाद - पहले जैसा (बिना नाम भी)`() {
        assertEquals(CallReply.ANSWER, CallReply.parse("उठा लो"))
        assertEquals(CallReply.ANSWER_SPEAKER, CallReply.parse("फोन उठा के स्पीकर पर डाल दो"))
        assertNull(CallReply.parse("उठाऊँ या काट दूँ"))
    }

    private fun src(p: String) = listOf(File("src/main/java/com/dk/assistant/$p"), File("app/src/main/java/com/dk/assistant/$p"))
        .first { it.exists() }.readText()

    @Test fun `फ़ोन वाला रास्ता - एलान के साथ-साथ सुनना`() {
        val svc = src("DKAccessibilityService.kt")
        assertTrue(svc.contains("ear.bargeWhen = { callBargeEar() }"))
        assertTrue(svc.contains("fun callBargeEar(): Boolean"))
        assertTrue("बिना नेट भी एलान के बीच सुनना", svc.contains("!NetProbe.online(this) && OfflineStt.available(this))"))
        assertTrue("घंटी में Hey DK loop बहरा न रहे", svc.contains("if (!DKState.live && !callRinging() && !offlineEar && com.dk.assistant.voice.HeyDk.on("))
        assertTrue("घंटी में wake word ज़रूरी नहीं", svc.contains("hear(wakeGate = !liveBefore && !callRinging())"))
        assertTrue(svc.contains("&& !callBargeEar()) { delay(250); continue }"))
        val ear = src("Ear.kt")
        assertTrue(ear.contains("val bargeNow = barge || (dkTalking && bargeWhen())"))
        val calls = src("DKAccessibilityCalls.kt")
        assertTrue(calls.contains("val announce = scope.launch { try { speaker.speakCall(msg) } finally { callAnnouncing = false } }"))
        assertTrue(calls.contains("if (!CallReply.bargeOk(heard, Prefs.wakeWords(this)))"))
        assertTrue("उठाना पहले, बोलना बाद में", calls.contains("hushForCall()      // एलान अभी चल रहा हो तो वहीं रोको"))
    }

    @Test fun `Live - एलान के बीच नाम वाली बात पर तुरंत`() {
        val cap = src("GeminiLiveCaption.kt")
        assertTrue(cap.contains("CallReply.barge(t, Prefs.wakeWords(svc))"))
        val mic = src("GeminiLiveMic.kt")
        assertTrue(mic.contains("if (hit.cmd.id.startsWith(\"answer\") && !svc.callRinging()) return@withContext"))
    }

    @Test fun `रिकॉर्ड की आवाज़ से कॉल उठाना - सिर्फ़ घंटी के समय`() {
        val run = src("VoiceCmdRun.kt")
        assertTrue(run.contains("if ((hit.cmd == Cmd.ANSWER || hit.cmd == Cmd.ANSWER_SPEAKER) && !callRinging()) return false"))
        assertTrue(run.contains("Cmd.ANSWER -> answerByVoice(false, from)"))
        assertTrue(run.contains("Cmd.ANSWER_SPEAKER -> answerByVoice(true, from)"))
    }
}

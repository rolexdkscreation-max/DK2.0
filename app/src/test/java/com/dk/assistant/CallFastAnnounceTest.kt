package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v3.156 मालिक: "कॉल आया तो तुरंत फटाक से बोले - बॉस कॉल आ रहा है, उठाऊँ या काट दूँ; पहली बार फुल आवाज़;
 * जवाब न मिले तो DK चुप, घंटी जितनी है उतनी; बाद में 'उठा लो' / 'काट दो' / 'स्पीकर पर डालो' = वही"
 */
class CallFastAnnounceTest {
    private fun src(p: String) = File("src/main/java/com/dk/assistant/$p").readText()

    @Test fun announceAsksAtOnce() {
        assertEquals("बॉस, राहुल का कॉल आ रहा है। उठाऊँ या काट दूँ?", CallCmd.announce("राहुल"))
        assertTrue(CallVoice.GENERIC_ASK.startsWith("बॉस") && CallVoice.GENERIC_ASK.length <= 60)
    }

    @Test fun noWaitBeforeSpeaking() {
        val c = src("DKAccessibilityCalls.kt")
        val fn = c.substringAfter("suspend fun DKAccessibilityService.onIncomingCall(").substringBefore("\n}\n")
        assertFalse("Live में LISTENING पर रुकना = देर", fn.contains("while (DKState.mode == Mode.THINKING || DKState.mode == Mode.LISTENING)"))
        assertFalse(fn.contains("delay(200)"))
        // Live को बताना साथ-साथ, बोलना पहले
        assertTrue(fn.contains("scope.launch { gemLive.noteCallAsked(whoNote) }"))
        // एक बार पूछकर चुप - दोबारा "कॉल उठाऊँ?" नहीं
        assertFalse(fn.contains("speakQuick(\"कॉल उठाऊँ?\")"))
        assertTrue(fn.contains("handleCallReply(ans)"))
    }

    @Test fun savedVoiceFirst_fullVolume() {
        val sp = src("Speaker.kt")
        val fn = sp.substringAfter("suspend fun speakCall(").substringBefore("private suspend fun playCallWav")
        assertTrue(fn.indexOf("cachedCallWav()") in 0 until fn.indexOf("speakQuick(text)"))   // सहेजी आवाज़ पहले = तुरंत
        assertTrue(VolumeRule.CALL_FULL)
        assertFalse(VolumeRule.AUTO_CHANGE)                       // बाकी आवाज़ें अब भी नहीं छेड़ता
    }

    @Test fun pickRejectSpeakerStillParsed() {
        assertEquals(CallReply.ANSWER, CallReply.parse("उठा लो"))
        assertEquals(CallReply.REJECT, CallReply.parse("काट दो"))
        assertEquals(CallReply.ANSWER_SPEAKER, CallReply.parse("उठाकर स्पीकर पर डालो"))
    }
}

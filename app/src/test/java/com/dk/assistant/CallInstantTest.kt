package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * नवंबर 2026 मालिक: "कॉल आए तो इंटरनेट पर DK लाइव खुद तुरंत बोले, न बोले तो बैकअप तुरंत; 'DK उठा लो' / 'काट दो' बोलते ही
 * काम - देर नहीं; उठ गई तो पूरा नाम / नंबर बोलने की ज़रूरत नहीं."
 */
class CallInstantTest {
    private val names = listOf("मायरा")

    @Test fun completePhraseActsBeforeFinalText() {
        assertEquals(CallReply.ANSWER, CallReply.instant("उठा लो", final = false, dkTalking = false))
        assertEquals(CallReply.ANSWER, CallReply.instant("डीके कॉल उठा लो", final = false, dkTalking = false))
        assertEquals(CallReply.REJECT, CallReply.instant("काट दो", final = true, dkTalking = false))
        // अधूरे text पर काटना नहीं - "काट दो और मैसेज भेजो कि..." बाकी हो सकता है
        assertNull(CallReply.instant("काट दो", final = false, dkTalking = false))
        assertEquals(CallReply.ANSWER_SPEAKER, CallReply.instant("उठा के स्पीकर पर डालो", final = false, dkTalking = false))
    }

    @Test fun halfPhraseWaits() {
        assertNull(CallReply.instant("डीके उठा", final = false, dkTalking = false))
        assertNull(CallReply.instant("उठाना", final = false, dkTalking = false))
        assertNull(CallReply.instant("मत काट दो", final = false, dkTalking = false))
    }

    @Test fun whileDkTalksNameNeeded_echoIgnored() {
        assertNull(CallReply.instant("उठा लो", final = true, dkTalking = true, names = names))
        assertEquals(CallReply.ANSWER, CallReply.instant("डीके उठा लो", final = false, dkTalking = true, names = names))
        assertEquals(CallReply.REJECT, CallReply.instant("मायरा काट दो", final = true, dkTalking = true, names = names))
        assertNull(CallReply.instant("बॉस, राहुल का कॉल आ रहा है। उठाऊँ या काट दूँ?", final = true, dkTalking = true, names = names))
        assertNull(CallReply.instant("कौन है", final = true, dkTalking = false))
    }

    @Test fun liveFirstThenBackupQuickly() {
        assertTrue(CallReply.LIVE_CALL_WAIT_MS in 800L..1_500L)
        val calls = AccSrc.all()
        val fn = calls.substringAfter("suspend fun DKAccessibilityService.onIncomingCall(").substringBefore("callAnnouncing = true\n    // v4.6")
        assertTrue(fn.indexOf("gemLive.announceCall(whoNote)") in 0 until fn.indexOf("speaker.speakCall(msg, generic = false)"))
        assertTrue(fn.contains("if (!liveSaid && calls.ringing && callPending)"))
        assertTrue("माइक खुला = 'DK उठा लो' सुनाई दे", !fn.contains("gemLive.muteMic(true)"))
        val live = LiveSrc.all()
        assertTrue(live.contains("withTimeoutOrNull(CallReply.LIVE_CALL_WAIT_MS) { while (!isPlaying() && svc.callRinging()) delay(50) }"))
    }

    @Test fun captionActsInstantly_andHushes() {
        val live = LiveSrc.all()
        val cap = live.substringAfter("lastAct = System.currentTimeMillis()\n    // नवंबर 2026")
        assertTrue(cap.indexOf("CallReply.instant(t, final") in 0 until cap.indexOf("else if (isPlaying()) return").let { if (it < 0) Int.MAX_VALUE else it })
        assertTrue(live.contains("if (a in pick) { svc.hushForCall(); if (isPlaying()) flushPlayback() }"))
    }
}

package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * नवंबर 2026 मालिक: "कॉल आए तो DK लाइव ही बोले (बैकअप नहीं), और 'DK कॉल उठा लो' बोलते ही कॉल उठे -
 * चाहे DK नाम / नंबर बोल ही रहा हो."
 */
class LiveCallFixTest {
    private val names = listOf("मायरा")

    @Test fun morePhrasesActInstantly() {
        val yes = listOf(
            "DK call utha lo", "डीके कॉल उठा लो", "डी के कॉल उठा लो", "DK, call uthao",
            "DK pick up the call", "DK call receive karo", "डीके कॉल पिक करो", "डीके उठा लो ना", "डीके उठा लो यार",
            "DK answer the call", "डीके कॉल उठा लो जल्दी", "DK उठा लो प्लीज़",
        )
        for (s in yes) {
            val a = CallReply.instant(s, final = false, dkTalking = false)
            assertTrue("$s -> $a", a == CallReply.ANSWER || a == CallReply.ANSWER_SPEAKER)
        }
    }

    @Test fun whileDkSpeaksTheNameStillWorks() {
        // DK नंबर / नाम बोल रहा है - तब भी नाम के साथ बोला = तुरंत
        assertEquals(CallReply.ANSWER, CallReply.instant("डीके कॉल उठा लो", final = false, dkTalking = true))
        assertEquals(CallReply.ANSWER, CallReply.instant("डीके कॉल पिक करो", final = false, dkTalking = true))
        assertEquals(CallReply.ANSWER, CallReply.instant("मायरा उठा लो ना", final = false, dkTalking = true, names = names))
        // DK की अपनी आवाज़ की गूँज = कुछ नहीं
        assertNull(CallReply.instant("राहुल का कॉल आ रहा है, उठाऊँ या काट दूँ?", final = false, dkTalking = true))
    }

    @Test fun guardsStay() {
        assertNull(CallReply.instant("मत उठा लो", final = false, dkTalking = false))
        assertNull(CallReply.instant("डीके उठा", final = false, dkTalking = false))
        assertNull(CallReply.instant("काट दो", final = false, dkTalking = false))       // अधूरे text पर काटना नहीं
        assertNull(CallReply.instant("मत काट दो यार", final = true, dkTalking = false))
    }

    @Test fun heardPartsJoin() {
        assertEquals(" डीके कॉल", CallReply.joinHeard(" डीके", 1_000, " कॉल", 1_500))
        assertEquals(" उठा लो", CallReply.joinHeard(" पुरानी बात", 1_000, " उठा लो", 1_000 + CallReply.HEARD_GAP_MS + 1))
        assertEquals(" उठा", CallReply.joinHeard("", 0, " उठा", 5_000))
    }

    @Test fun liveAnsweringMeansWaitForLive_notBackup() {
        assertTrue(CallReply.liveStillComing(answeredAt = 1_500, sentAt = 1_000, playing = false, ringing = true))
        assertFalse(CallReply.liveStillComing(answeredAt = 0, sentAt = 1_000, playing = false, ringing = true))     // Live चुप = बैकअप
        assertFalse(CallReply.liveStillComing(answeredAt = 1_500, sentAt = 1_000, playing = true, ringing = true))  // बोल रहा है
        assertFalse(CallReply.liveStillComing(answeredAt = 1_500, sentAt = 1_000, playing = false, ringing = false)) // कॉल उठ/कट गई
        assertTrue(CallReply.LIVE_CALL_MORE_MS in 2_000L..5_000L)
    }

    @Test fun wired() {
        val live = LiveSrc.all()
        val ann = live.substringAfter("suspend fun announceCall(").substringBefore("fun callAudio(")
        assertTrue("पुरानी 'चुप' हालत हटे वरना Live का जवाब फेंका जाता", ann.indexOf("muteTurn = false; dropOld = false") in 0 until ann.indexOf("CallReply.liveNote(who)"))
        assertTrue(ann.contains("CallReply.liveStillComing(callAnsweredAt, callNoteAt, isPlaying(), svc.callRinging())"))
        val srv = live.substringAfter("val oldReply = dropOld").substringBefore("sc.optJSONObject(\"inputTranscription\")?.optString(\"text\")?.takeIf { it.isNotBlank() && !muteTurn }")
        assertTrue("घंटी के समय Live का सुना हुआ - muteTurn से पहले", srv.contains("if (svc.callRinging()) {"))
        assertTrue(srv.contains("liveHeardCall(it, true)"))
        assertTrue(srv.contains("liveHeardCall(it, false)"))
        val heard = live.substringAfter("private fun GeminiLive.liveCallAct(")
        assertTrue(heard.contains("CallReply.instant(t, final, isPlaying() || svc.callAnnouncing || micMuted, Prefs.wakeWords(svc))"))
        assertTrue(heard.contains("svc.hushForCall()"))
        assertTrue(heard.contains("callTool(act,"))
    }
}

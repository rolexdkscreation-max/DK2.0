package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.51: कॉल पर आवाज़, notification, झूठा "कर दिया", "मैं चेक करता हूँ" के बाद चुप, दफ़्तरी अभिवादन */
class LiveHonestTest {

    // ---- 1. कॉल पर आवाज़ ----
    @Test fun callUsesAccessibilityVoiceNotMedia() {
        assertEquals(CallVoice.USAGE_ASSISTANCE_ACCESSIBILITY, CallVoice.usage(callActive = true))
        assertEquals(CallVoice.USAGE_MEDIA, CallVoice.usage(callActive = false))
        // Bluetooth पहले जैसा कॉल वाला
        assertEquals(CallVoice.USAGE_VOICE_COMMUNICATION, CallVoice.usage(callActive = true, bt = true))
        assertEquals(CallVoice.USAGE_VOICE_COMMUNICATION, CallVoice.usage(callActive = false, bt = true))
    }

    @Test fun callNoteTellsLiveNotToAskAgain() {
        val n = CallReply.liveNoteAsked("राहुल")
        assertTrue(n.contains("राहुल"))
        assertTrue(n.contains("do NOT ask again"))
        assertTrue(n.contains("phone_call"))
        assertTrue(CallReply.liveNoteAsked("").contains("unknown number"))
    }

    // ---- 2. notification ----
    @Test fun newNotificationIsPushedOnceOnly() {
        val now = 1_000_000L
        assertTrue(NotifPush.isNew("k1", "राहुल", "कब मिलोगे", now - 1000, now, emptyList()))
        val seen = listOf(NotifPush.Seen("k1", "कब मिलोगे"))
        assertFalse(NotifPush.isNew("k1", "राहुल", "कब मिलोगे", now - 1000, now, seen))
        // उसी chat का नया संदेश = नया
        assertTrue(NotifPush.isNew("k1", "राहुल", "आ जाओ", now - 1000, now, seen))
        // पुराना (Live से पहले का) और खाली = नहीं
        assertFalse(NotifPush.isNew("k2", "x", "y", now - 5 * 60_000L, now, emptyList()))
        assertFalse(NotifPush.isNew("k3", "", " ", now, now, emptyList()))
    }

    @Test fun notificationNoteSaysItArrivedAndHidesOtp() {
        val n = NotifPush.note(listOf(Triple("WhatsApp", "राहुल", "कब मिलोगे")))
        assertTrue(n.startsWith("SYSTEM NOTE"))
        assertTrue(n.contains("WhatsApp") && n.contains("राहुल") && n.contains("कब मिलोगे"))
        assertTrue(n.contains("DID arrive"))
        val o = NotifPush.note(listOf(Triple("Messages", "Bank", "Your OTP is 482913")))
        assertFalse(o.contains("482913"))
        assertTrue(NotifPush.note(listOf(Triple("A", "a", "1"), Triple("B", "b", "2"))).contains("2 new notifications"))
    }

    @Test fun notificationToolNeverFalselySaysNone() {
        // पढ़ने वाला जुड़ा नहीं -> "कोई नहीं" नहीं, सच: जाँच नहीं पाया
        val off = NotifPush.toolAnswer(false, "No new notifications.", 0, "", "No new notifications.")
        assertTrue(off.startsWith("COULD NOT CHECK"))
        assertTrue(off.contains("Do NOT say that no notification came"))
        // ऐप वाला खाली, पर दूसरे हैं
        val app = NotifPush.toolAnswer(true, "No new notifications.", 3, "WhatsApp", "No new notifications.")
        assertTrue(app.contains("WhatsApp") && app.contains("3"))
        // सच में खाली
        assertTrue(NotifPush.toolAnswer(true, "No new notifications.", 0, "", "No new notifications.").startsWith("Checked the phone just now"))
        // हैं
        val has = NotifPush.toolAnswer(true, "1) [WhatsApp] राहुल: hi", 1, "", "1) [WhatsApp] राहुल: hi")
        assertTrue(has.contains("राहुल"))
    }

    // ---- 3. झूठा "कर दिया" ----
    @Test fun detectsDoneClaims() {
        assertTrue(HonestGuard.claimsDone("ठीक है, मैंने WhatsApp खोल दिया।"))
        assertTrue(HonestGuard.claimsDone("मैसेज भेज दिया।"))
        assertTrue(HonestGuard.claimsDone("अलार्म सेट कर दिया है।"))
        assertTrue(HonestGuard.claimsDone("टॉर्च बंद कर दी।"))
        assertTrue(HonestGuard.claimsDone("काम हो गया।"))
    }

    @Test fun questionsRefusalsAndChatAreNotClaims() {
        assertFalse(HonestGuard.claimsDone("क्या मैं मैसेज भेज दूँ?"))
        assertFalse(HonestGuard.claimsDone("माफ़ करना, मैं नहीं कर पाया।"))
        assertFalse(HonestGuard.claimsDone("अच्छा, तो तुमने उसे कॉल कर दिया।"))
        assertFalse(HonestGuard.claimsDone("ठीक है, खोल रहा हूँ।"))
        assertFalse(HonestGuard.claimsDone("आज मौसम ठंडा हो गया है, कैसे हो?"))
        assertFalse(HonestGuard.claimsDone("हाँ बॉस, बोलो।"))
    }

    @Test fun claimWithoutToolIsCaught() {
        val now = 10_000_000L
        assertTrue(HonestGuard.unbacked("मैसेज भेज दिया।", lastEvidenceAt = 0L, now = now))
        assertTrue(HonestGuard.unbacked("मैसेज भेज दिया।", lastEvidenceAt = now - 5 * 60_000L, now = now))
        // tool अभी चला था -> सच्चा
        assertFalse(HonestGuard.unbacked("मैसेज भेज दिया।", lastEvidenceAt = now - 10_000L, now = now))
        // दावा ही नहीं
        assertFalse(HonestGuard.unbacked("कैसे हो?", lastEvidenceAt = 0L, now = now))
        val c = HonestGuard.correctionNote("मैसेज भेज दिया।")
        assertTrue(c.contains("NOTHING was actually done"))
        assertTrue(c.contains("मैसेज भेज दिया"))
    }

    @Test fun failedStepResults() {
        assertTrue(HonestGuard.actionFailed("ERROR: not found"))
        assertTrue(HonestGuard.actionFailed("STEP_FAILED tap"))
        assertTrue(HonestGuard.actionFailed("ASK: कौन सा राहुल?"))
        assertFalse(HonestGuard.actionFailed("OK tapped Send"))
        assertFalse(HonestGuard.actionFailed("PLAYING"))
    }

    @Test fun agentResultWithoutConfirmationIsNotDone() {
        val r = LiveFast.agentResult(true, null)
        assertTrue(r.contains("no confirmation"))
        assertTrue(r.contains("never say it is done"))
    }

    // ---- 4. "मैं चेक करता हूँ" के बाद चुप नहीं ----
    @Test fun holdPhrases() {
        assertTrue(LiveHold.isHold("ठीक है, मैं चेक करता हूँ"))
        assertTrue(LiveHold.isHold("मैं देखता हूँ"))
        assertTrue(LiveHold.isHold("रुको एक मिनट"))
        assertTrue(LiveHold.isHold("ok main check karta hu"))
        assertTrue(LiveHold.isHold("मैं देख के बताता हूँ"))
        assertFalse(LiveHold.isHold("आज मौसम कैसा है"))
        assertFalse(LiveHold.isHold("गाना बजाओ"))
    }

    @Test fun liveStaysOpenAfterHold() {
        assertEquals(LiveHold.HOLD_MS, LiveHold.idleMs(45_000, "ठीक है, मैं चेक करता हूँ", "ठीक है।"))
        assertEquals(LiveHold.ASKED_MS, LiveHold.idleMs(45_000, "हाँ", "कल कहाँ गए थे?"))
        assertEquals(45_000L, LiveHold.idleMs(45_000, "गाना बजाओ", "बजा रहा हूँ।"))
        // हमेशा वाला Live (0) पहले जैसा
        assertEquals(0L, LiveHold.idleMs(0, "ठीक है, मैं चेक करता हूँ", ""))
    }

    // ---- 5. इंसान जैसी बात ----
    @Test fun noServiceDeskGreeting() {
        assertTrue(TalkStyle.NATURAL.startsWith(TalkStyle.CHAT))
        assertTrue(TalkStyle.CHAT.contains("NEVER open or reply with service-desk lines"))
        assertTrue(TalkStyle.NATURAL.contains("NO FALSE DONE"))
        assertTrue(TalkStyle.OPENER.contains("Do NOT ask what work/task"))
        TalkStyle.greetReplies(" बॉस").forEach { assertFalse(it.contains("क्या करूँ")) }
    }
}

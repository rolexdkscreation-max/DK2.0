package com.dk.assistant

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 24 नवंबर 2026 (मालिक): "एक बार बोल दिया तो लॉक अनलॉक होना चाहिए" + "घंटी बजे तो 'डीके कॉल उठा लो' तुरंत, DK लाइव न हो तब भी".
 */
class UnlockCallFixTest {
    private fun src(n: String) = listOf(File("src/main/java/com/dk/assistant/$n"), File("app/src/main/java/com/dk/assistant/$n")).first { it.exists() }.readText()

    // ---------------------------------------------------------------- अनलॉक का इरादा (ढीली पहचान)

    @Test fun longOrMisheardUnlock_isIntent() {
        assertTrue(PhoneLockVoice.saysUnlock("डीके फोन अनलॉक करो"))
        assertTrue(PhoneLockVoice.saysUnlock("डीके मेरा फोन जल्दी से अनलॉक कर दो यार"))
        assertTrue(PhoneLockVoice.saysUnlock("dk please phone unlock kar do"))
        assertTrue(PhoneLockVoice.saysUnlock("फ़ोन अनलॉग करो"))
        assertTrue(PhoneLockVoice.saysUnlock("डीके फोन खोलो"))
        assertTrue(PhoneLockVoice.saysUnlock("हे डीके मोबाइल ओपन करो"))
    }

    @Test fun echoNegationQuestion_isNotIntent() {
        assertFalse("DK की अपनी गूँज", PhoneLockVoice.saysUnlock("बॉस, फ़ोन अनलॉक कर दिया।"))
        assertFalse(PhoneLockVoice.saysUnlock("फोन अनलॉक हो गया"))
        assertFalse(PhoneLockVoice.saysUnlock("फोन अनलॉक मत करो"))
        assertFalse(PhoneLockVoice.saysUnlock("फोन अनलॉक कैसे करते हैं"))
        assertFalse(PhoneLockVoice.saysUnlock("phone is unlocked"))
        assertFalse(PhoneLockVoice.saysUnlock("फोन लॉक करो"))
        assertFalse(PhoneLockVoice.saysUnlock(""))
        assertFalse("लंबी कहानी में अनलॉक", PhoneLockVoice.saysUnlock("कल मैं बाजार गया था और वहाँ एक दुकान पर नया फीचर देखा जिसमें अनलॉक होता है और बहुत कुछ"))
    }

    @Test fun guardSaid_setsIntentOnlyForUnlock() {
        com.dk.assistant.voice.UnlockGuard.reset()
        assertFalse(com.dk.assistant.voice.UnlockGuard.said("आज मौसम कैसा है"))
        assertTrue(com.dk.assistant.voice.UnlockGuard.said("डीके मेरा फोन अनलॉक कर दो"))
        com.dk.assistant.voice.UnlockGuard.reset()
    }

    @Test fun ownerMicPaths_feedSaid_aiPathDoesNot() {
        assertTrue(src("GeminiLiveServer.kt").contains("UnlockGuard.said(u)"))
        assertTrue(src("GeminiLiveCaption.kt").contains("UnlockGuard.said(t)"))
        assertTrue(src("DKAccessibilityService.kt").contains("UnlockGuard.said(heard)"))
        assertFalse("AI / tool का text इरादा नहीं बना सकता", src("AgentRunner.kt").contains("UnlockGuard.said("))
    }

    @Test fun reasonTellsWhatToDo() {
        val nr = com.dk.assistant.voice.UnlockRules.reason(com.dk.assistant.voice.UnlockRules.Verdict.NOT_READY)
        assertTrue(nr, nr.startsWith(PhoneLockVoice.UNLOCK_FAIL) && nr.contains("वॉइस गार्डियन"))
        val ni = com.dk.assistant.voice.UnlockRules.reason(com.dk.assistant.voice.UnlockRules.Verdict.NO_INTENT)
        assertTrue(ni, ni.contains(com.dk.assistant.voice.UnlockRules.Verdict.NO_INTENT.hi) && ni.contains("दोबारा") || ni.contains("फिर"))
    }

    // ---------------------------------------------------------------- PIN वाला हिस्सा न आए = दोबारा लॉक, फिर जगाओ

    @Test fun relockRetry_onceOnApi28Plus() {
        assertTrue(UnlockRetry.relock(0, 34))
        assertFalse("एक ही बार", UnlockRetry.relock(1, 34))
        assertFalse("Android 9 से पहले लॉक का global action नहीं", UnlockRetry.relock(0, 27))
        assertTrue(src("PhoneUnlocker.kt").contains("GLOBAL_ACTION_LOCK_SCREEN"))
        assertTrue(src("PhoneUnlocker.kt").contains("UnlockRetry.relock("))
    }

    // ---------------------------------------------------------------- घंटी के समय फ़ोन का कान

    @Test fun callEar_onlyWhenLiveNotTalking() {
        assertTrue(CallEarRules.use(liveTalking = false, sherpaReady = true, micOk = true, muted = false))
        assertFalse("Live बात कर रहा = उसी का माइक", CallEarRules.use(liveTalking = true, sherpaReady = true, micOk = true, muted = false))
        assertFalse(CallEarRules.use(liveTalking = false, sherpaReady = false, micOk = true, muted = false))
        assertFalse(CallEarRules.use(liveTalking = false, sherpaReady = true, micOk = false, muted = false))
        assertFalse("होम का म्यूट", CallEarRules.use(liveTalking = false, sherpaReady = true, micOk = true, muted = true))
    }

    @Test fun callEar_answersInstantly_evenWhileDkAnnounces() {
        val names = listOf("डीके", "dk")
        assertEquals(CallReply.ANSWER, CallEarRules.decide("डीके कॉल उठा लो", final = false, dkTalking = true, names = names))
        assertEquals(CallReply.ANSWER, CallEarRules.decide("कॉल उठा लो", final = false, dkTalking = false, names = names))
        assertEquals(CallReply.ANSWER_SPEAKER, CallEarRules.decide("डीके स्पीकर पर डाल दो", final = true, dkTalking = true, names = names))
        assertNull("आधी बात पर नहीं", CallEarRules.decide("डीके कॉल उठा", final = false, dkTalking = false, names = names))
        assertNull("आधी बात पर काटना नहीं", CallEarRules.decide("काट दो", final = false, dkTalking = false, names = names))
        assertEquals(CallReply.REJECT, CallEarRules.decide("काट दो", final = true, dkTalking = false, names = names))
        assertNull("एलान की गूँज", CallEarRules.decide("उठाऊँ या काट दूँ", final = true, dkTalking = true, names = names))
        assertNull("DK बोल रहा और नाम नहीं = गूँज हो सकती है", CallEarRules.decide("उठा लो", final = true, dkTalking = true, names = names))
    }

    @Test fun callEar_fullReplyOnlyForCallTalk() {
        val names = listOf("डीके")
        assertTrue(CallEarRules.full("कौन है", dkTalking = false, names = names))
        assertTrue(CallEarRules.full("घंटी धीमी करो", dkTalking = false, names = names))
        assertTrue(CallEarRules.full("काट दो और मैसेज भेजो कि मीटिंग में हूँ", dkTalking = false, names = names, callCmd = true))
        assertFalse("शोर से बने बेमतलब शब्द", CallEarRules.full("अ ह", dkTalking = false, names = names))
        assertFalse(CallEarRules.full("उठाऊँ या काट दूँ", dkTalking = true, names = names))
    }

    @Test fun callEar_wiredIntoIncomingCall() {
        val calls = src("DKAccessibilityCalls.kt")
        assertTrue(calls.contains("callEar.start()"))
        assertTrue("एलान बंद हो तब भी घंटी धीमी (Live के बिना भी)", calls.contains("calls.quietRinger(); scope.launch"))
        assertTrue("कान चल रहा = आम सुनना माइक न ले", src("DKAccessibilityService.kt").contains("if (callEar.running)"))
        assertTrue("उठाने से पहले माइक छोड़ो", src("CallEar.kt").contains("r.release()"))
    }
}

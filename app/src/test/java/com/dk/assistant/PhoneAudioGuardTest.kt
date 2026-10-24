package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.53: फ़ोन से निकली आवाज़ (वीडियो/गाना) को DK यूज़र की बात न समझे */
class PhoneAudioGuardTest {
    private val wake = listOf("डीके", "दीके", "डी के", "dk", "d k")

    @Test fun guardOnlyWhenOtherMediaPlays() {
        assertTrue(PhoneAudioGuard.active(othersMedia = true, selfQuietMs = 5_000))
        assertFalse(PhoneAudioGuard.active(othersMedia = false, selfQuietMs = 5_000))
    }

    @Test fun ownVoiceTailIsNotOtherMedia() {
        // DK की अपनी आवाज़ अभी-अभी थमी - "गाना बज रहा" भरोसे लायक नहीं
        assertFalse(PhoneAudioGuard.active(othersMedia = true, selfQuietMs = 500))
        assertTrue(PhoneAudioGuard.active(othersMedia = true, selfQuietMs = PhoneAudioGuard.SELF_TAIL_MS + 1))
    }

    @Test fun nameInHindiOrEnglishIsUser() {
        assertTrue(PhoneAudioGuard.hasName("डीके टाइम क्या हुआ", wake))
        assertTrue(PhoneAudioGuard.hasName("अरे डी के सुनो", wake))
        assertTrue(PhoneAudioGuard.hasName("DK volume kam karo", wake))
        assertTrue(PhoneAudioGuard.hasName("ok d.k. pause", wake))
    }

    @Test fun videoTalkWithoutNameIsIgnored() {
        assertFalse(PhoneAudioGuard.hasName("आज हम बात करेंगे क्रिकेट के बारे में", wake))
        assertFalse(PhoneAudioGuard.hasName("subscribe karna mat bhoolna", wake))
        assertFalse(PhoneAudioGuard.hasName("", wake))
    }

    @Test fun shortEnglishNameNotInsideOtherWords() {
        assertFalse(PhoneAudioGuard.hasName("pdk mode on", wake))
        assertFalse(PhoneAudioGuard.hasName("sdkversion", wake))
    }

    @Test fun lateTextWindow() {
        assertFalse(PhoneAudioGuard.expired(0L, 99_999L))          // बात अभी खत्म नहीं हुई
        assertFalse(PhoneAudioGuard.expired(1_000L, 2_000L))
        assertTrue(PhoneAudioGuard.expired(1_000L, 1_000L + PhoneAudioGuard.LATE_TEXT_MS + 1))
    }

    @Test fun holdIsBounded() {
        assertEquals(250, PhoneAudioGuard.MAX_HOLD_CHUNKS)          // 10 सेकंड से ज़्यादा आवाज़ नहीं रुकती
    }
}

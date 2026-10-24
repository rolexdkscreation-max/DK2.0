package com.dk.assistant

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** जवाब वाले बुलबुले का स्थिति-संदेश, इनपुट बॉक्स का आकार और बार के अंदर बोलकर लिखने के नियम */
class DKUiRulesTest {

    @After fun reset() = DKStatus.clear()

    // ---------------------------------------------------------------- स्थिति-संदेश
    @Test fun normalWaitShowsOnlyWave() {
        val t0 = 1_000_000L
        // सामान्य इंतज़ार: कोई "DK is typing" / नाम नहीं - सिर्फ़ तरंग ("")
        assertEquals("", DKStatus.label(t0, now = t0 + 2_000))
    }

    @Test fun slowReplyMessageAfterThreshold() {
        val t0 = 1_000_000L
        assertEquals("", DKStatus.label(t0, now = t0 + DKStatus.SLOW_MS - 1))
        assertEquals(DKStatus.text(DKStatus.Kind.SLOW), DKStatus.label(t0, now = t0 + DKStatus.SLOW_MS + 1))
        // इंतज़ार का समय पता नहीं (0) = "धीमा" का झूठा संदेश नहीं
        assertEquals("", DKStatus.label(0L, now = t0 + 60_000))
    }

    @Test fun realEventBeatsHintAndSlow() {
        val t0 = 1_000_000L
        DKStatus.post(DKStatus.Kind.LIMIT, now = t0 + 500)
        assertEquals(DKStatus.text(DKStatus.Kind.LIMIT), DKStatus.label(t0, "खोज रहा हूँ…", now = t0 + 9_000))
    }

    @Test fun oldEventIsIgnored() {
        val t0 = 1_000_000L
        // पिछले जवाब की घटना इस जवाब में नहीं दिखती
        DKStatus.post(DKStatus.Kind.SWITCH, now = t0 - 1)
        assertNull(DKStatus.fresh(t0, now = t0 + 100))
        // बहुत पुरानी घटना भी नहीं
        DKStatus.post(DKStatus.Kind.SWITCH, now = t0 + 10)
        assertNull(DKStatus.fresh(t0, now = t0 + 10 + DKStatus.FRESH_MS + 1))
        assertEquals(DKStatus.text(DKStatus.Kind.SWITCH), DKStatus.fresh(t0, now = t0 + 20))
    }

    @Test fun hintAndPhoneWork() {
        val t0 = 1_000_000L
        assertEquals("गहराई से सोच रहा हूँ…", DKStatus.label(t0, "गहराई से सोच रहा हूँ…", now = t0 + 100))
        assertEquals("फ़ोन पर काम कर रहा हूँ…", DKStatus.label(t0, "", working = true, now = t0 + 100))
    }

    @Test fun codeToKind() {
        assertEquals(DKStatus.Kind.LIMIT, DKStatus.forCode(429))
        assertEquals(DKStatus.Kind.SWITCH, DKStatus.forCode(503))
        assertEquals(DKStatus.Kind.SWITCH, DKStatus.forCode(-1))
    }

    @Test fun retryMessageMatchesRequestedWording() {
        assertEquals("सामान्य से ज़्यादा समय लग रहा है। थोड़ी देर में फिर कोशिश कर रहा हूँ…", DKStatus.text(DKStatus.Kind.RETRY))
        for (k in DKStatus.Kind.values()) {
            val t = DKStatus.text(k)
            assertTrue(t.isNotBlank())
            assertFalse("संदेश में 'DK' शब्द नहीं: $t", t.contains("DK"))
        }
    }

    // ---------------------------------------------------------------- इनपुट बॉक्स / बुलबुला
    @Test fun composerIsRectangleWithSoftCorners() {
        // पूरा गोल (गोली, 999) नहीं - हल्के कोने
        assertTrue(ComposerShape.RADIUS_DP in 10f..24f)
        assertTrue(ComposerShape.BUTTON_DP <= ComposerShape.SEND_DP)
    }

    @Test fun bubbleFadeIsSmoothButQuick() {
        assertTrue(BubbleFade.OUT_MS in 150L..400L)
        assertTrue(BubbleFade.IN_MS in 150L..400L)
    }

    // ---------------------------------------------------------------- बार के अंदर बोलकर लिखना
    @Test fun languageIsHindiIndiaWithEnglish() {
        assertEquals("hi-IN", DictationRules.LANG)
        assertEquals("en-IN", DictationRules.EXTRA_LANG)
    }

    @Test fun silenceRestartsThenStops() {
        val r = DictationRules
        assertEquals(DictationRules.Next.RESTART, r.next(r.ERR_NO_MATCH, 0, 0, false))
        assertEquals(DictationRules.Next.RESTART, r.next(r.ERR_SPEECH_TIMEOUT, r.MAX_SILENT - 1, 0, true))
        assertEquals(DictationRules.Next.STOP_KEEP, r.next(r.ERR_NO_MATCH, r.MAX_SILENT, 0, true))
        assertEquals(DictationRules.Next.STOP_MESSAGE, r.next(r.ERR_NO_MATCH, r.MAX_SILENT, 0, false))
    }

    @Test fun busyRetriesThenClearMessage() {
        val r = DictationRules
        assertEquals(DictationRules.Next.RETRY_SOON, r.next(r.ERR_BUSY, 0, 0, false))
        assertEquals(DictationRules.Next.RETRY_SOON, r.next(r.ERR_CLIENT, 0, r.MAX_BUSY - 1, false))
        assertEquals(DictationRules.Next.STOP_MESSAGE, r.next(r.ERR_BUSY, 0, r.MAX_BUSY, false))
        assertEquals(DictationRules.Next.STOP_MESSAGE, r.next(r.ERR_NETWORK, 0, 0, true))
        assertEquals(DictationRules.Next.STOP_MESSAGE, r.next(r.ERR_PERMISSIONS, 0, 0, false))
    }

    @Test fun everyErrorHasClearMessageWithoutPopupFallback() {
        for (code in 1..13) {
            val m = DictationRules.message(code)
            assertTrue(m.isNotBlank())
            assertFalse(m.contains("बोलिए…\""))
        }
        assertTrue(DictationRules.message(DictationRules.ERR_NETWORK).contains("इंटरनेट"))
        assertTrue(DictationRules.message(DictationRules.ERR_LANG_UNAVAILABLE).contains("हिंदी"))
        assertTrue(DictationRules.NO_PERMISSION.contains("माइक"))
    }

    @Test fun liveTextJoinsWordByWord() {
        assertEquals("नमस्ते DK", DictationRules.join("नमस्ते", "DK"))
        assertEquals("open WhatsApp", DictationRules.join("", " open WhatsApp "))
        assertEquals("पहले", DictationRules.join("पहले ", ""))
        assertEquals("", DictationRules.join("", ""))
    }

    @Test fun micLevelRange() {
        assertEquals(0f, DictationRules.level(-10f), 0f)
        assertEquals(1f, DictationRules.level(20f), 0f)
        val mid = DictationRules.level(4f)
        assertTrue(mid > 0f && mid < 1f)
    }
}

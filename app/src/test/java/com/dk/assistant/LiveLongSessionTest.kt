package com.dk.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 18 नवंबर 2026 (ऑडिट: 1 घंटा+ बिना रुके DK लाइव) - G2 context सीमा, G3 BLOCKING tools, G4 handle save, G7 लंबी बात */
class LiveLongSessionTest {
    @Test fun compressionHasLimits_orDefaultWhenRefused() {
        val c = LiveSetupRules.compression(true)
        assertEquals(32_000L, c.getLong("triggerTokens"))
        assertEquals(16_000L, c.getJSONObject("slidingWindow").getLong("targetTokens"))
        assertTrue(LiveSetupRules.COMPRESS_TARGET_TOKENS < LiveSetupRules.COMPRESS_TRIGGER_TOKENS)
        val d = LiveSetupRules.compression(false)
        assertFalse(d.has("triggerTokens"))
        assertEquals(0, d.getJSONObject("slidingWindow").length())
    }

    @Test fun blockingOnlyOn38Live() {
        assertTrue(LiveSetupRules.blockingTools("gemini-3.8-live"))
        assertTrue(LiveSetupRules.blockingTools("gemini-3.8-live-extended-thinking"))
        assertFalse(LiveSetupRules.blockingTools("gemini-3.1-flash-live-preview"))
        assertFalse(LiveSetupRules.blockingTools("gemini-2.5-flash-native-audio-preview-12-2025"))
    }

    @Test fun stampBehaviorKeepsExisting() {
        val tools = JSONArray()
            .put(JSONObject().put("functionDeclarations", JSONArray()
                .put(JSONObject().put("name", "open_app"))
                .put(JSONObject().put("name", "dk_agent").put("behavior", "NON_BLOCKING"))))
            .put(JSONObject().put("googleSearch", JSONObject()))
        assertEquals(1, LiveSetupRules.stampBehavior(tools))
        val d = tools.getJSONObject(0).getJSONArray("functionDeclarations")
        assertEquals("BLOCKING", d.getJSONObject(0).getString("behavior"))
        assertEquals("NON_BLOCKING", d.getJSONObject(1).getString("behavior"))
        assertFalse(tools.getJSONObject(1).has("behavior"))
    }

    @Test fun setupUsesNewRules() {
        val s = LiveSrc.all()
        assertTrue(s.contains("LiveSetupRules.compression(compressLimits)"))
        assertTrue(s.contains("LiveSetupRules.stampBehavior"))
        assertTrue(s.contains("restoreHandle(keys, key, models, dkServer)"))
        assertTrue(s.contains("LiveHold.isLongTalk(lastUserLine)"))
    }

    @Test fun handleRoundTripAndFreshness() {
        val now = 10_000_000L
        val sv = LiveHandleStore.Saved("abc|def:123", "gemini-3.8-live", 42, now - 5 * 60_000L)
        val back = LiveHandleStore.decode(LiveHandleStore.encode(sv))
        assertEquals(sv, back)
        val models = listOf("gemini-3.8-live", "gemini-3.1-flash-live-preview")
        assertTrue(LiveHandleStore.usable(back, now, 42, models))
        assertFalse(LiveHandleStore.usable(back, now, 7, models))                       // दूसरी key
        assertFalse(LiveHandleStore.usable(back, now, 42, listOf("gemini-3.1-flash-live-preview")))   // मॉडल अब नहीं
        assertFalse(LiveHandleStore.usable(back, now + LiveHandleStore.MAX_AGE_MS, 42, models))      // बहुत पुराना
        assertFalse(LiveHandleStore.usable(null, now, 42, models))
        assertNull(LiveHandleStore.decode(""))
        assertNull(LiveHandleStore.decode("garbage"))
        assertTrue(LiveHandleStore.MAX_AGE_MS < 2 * 60 * 60_000L)
    }

    @Test fun longTalkPhrases() {
        for (t in listOf("मुझे तुमसे लंबी बात करनी है", "बात करते रहो", "लाइव चालू रखो", "लाइव बंद मत करना", "baat karte raho", "keep talking"))
            assertTrue(t, LiveHold.isLongTalk(t))
        for (t in listOf("बात खत्म", "लाइव बंद करो", "मौसम कैसा है", "रुको"))
            assertFalse(t, LiveHold.isLongTalk(t))
    }
}

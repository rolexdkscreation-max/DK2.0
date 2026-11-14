package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 14 नवंबर 2026 (मालिक): DK लाइव में काम से पहले हाँ, एक key से सब हिस्से, एक key पर Live न टूटे, पूरी बात सुनकर जवाब,
 * "टच मोड" (सिर्फ़ बोलने पर चालू), फ़ोन लॉक / अनलॉक तुरंत और बिना मना किए, चेहरा जाँच तेज़ और तय क्रम में.
 */
class TouchModeLiveKeysTest {

    private fun src(name: String): String {
        var d: File? = File("").absoluteFile
        while (d != null) {
            listOf(File(d, "src/main/java/com/dk/assistant/$name"), File(d, "app/src/main/java/com/dk/assistant/$name"))
                .firstOrNull { it.exists() }?.let { return it.readText() }
            d = d.parentFile
        }
        error("$name नहीं मिला")
    }

    // ------------------------------------------------------------ 1. DK लाइव से कोडिंग / DK कंप्यूटर = पहले हाँ

    @Test fun liveAgent_asksBeforeStarting() {
        val s = src("GeminiLiveChecks.kt")
        val gate = s.indexOf("workGate(agent.tool, a)")
        assertTrue("agentCheck में हाँ वाला गेट", gate > 0)
        val run = s.indexOf("agentTool(agent.tool, a)")
        assertTrue("गेट काम शुरू होने से पहले", run > gate)
    }

    // ------------------------------------------------------------ 2. एक key से सब

    private val keys = mutableMapOf<Pair<String, String>, String>()
    private fun get(sc: String, slot: String) = keys[sc to slot].orEmpty()

    @Test fun borrow_ownKeyFirst_thenOthers_liveLast() {
        keys.clear()
        keys[ApiScopes.LIVE to "gemini"] = "LIVEKEY"
        // सिर्फ़ DK लाइव में key = कहानी / कोडर / कंप्यूटर भी वही इस्तेमाल करें
        assertEquals(ApiScopes.LIVE to "LIVEKEY", ApiScopes.borrowKey(ApiScopes.CHAT, "gemini", ::get))
        assertEquals(ApiScopes.LIVE to "LIVEKEY", ApiScopes.borrowKey(ApiScopes.CODER, "gemini", ::get))
        assertEquals(ApiScopes.LIVE to "LIVEKEY", ApiScopes.borrowKey(ApiScopes.PC, "gemini", ::get))
        // अपनी key हो तो अपनी
        keys[ApiScopes.CODER to "gemini3"] = "CODER3"
        assertEquals(ApiScopes.CODER to "CODER3", ApiScopes.borrowKey(ApiScopes.CHAT, "gemini", ::get))
        keys[ApiScopes.CHAT to "gemini"] = "CHATKEY"
        assertEquals(ApiScopes.CHAT to "CHATKEY", ApiScopes.borrowKey(ApiScopes.CHAT, "gemini", ::get))
        // Live की अपनी key हो तो Live अपनी ही
        assertEquals(ApiScopes.LIVE to "LIVEKEY", ApiScopes.borrowKey(ApiScopes.LIVE, "gemini", ::get))
        // बैकअप slot उधार नहीं
        assertNull(ApiScopes.borrowKey(ApiScopes.CHAT, "gemini2", ::get))
        // कहीं नहीं = null, extras (एक-key) Gemini में
        keys.clear()
        assertNull(ApiScopes.borrowKey(ApiScopes.CHAT, "groq", ::get))
        assertEquals("extra" to "ONE", ApiScopes.borrowKey(ApiScopes.CHAT, "gemini", ::get, listOf("", "ONE")))
    }

    @Test fun pickActive_fallsToProviderWithKey() {
        assertEquals("groq", ApiScopes.pickActive(ApiScopes.CHAT, "groq") { true })
        assertEquals("gemini", ApiScopes.pickActive(ApiScopes.CHAT, "groq") { it == "gemini" })
        assertEquals("groq", ApiScopes.pickActive(ApiScopes.CHAT, "groq") { false })
    }

    @Test fun prefs_usesBorrow() {
        val p = src("Prefs.kt")
        assertTrue(p.contains("fun borrowKey("))
        assertTrue(p.contains("ApiScopes.pickActive("))
        assertTrue(src("OneKeyGate.kt").contains("Prefs.borrowKey(c, ApiScopes.CHAT, \"gemini\")"))
    }

    // ------------------------------------------------------------ 3. एक key पर Live न टूटे

    @Test fun oneKey_softQuota_waitsInsteadOfBlocking() {
        assertTrue(LiveOneKey.soft(1, "RESOURCE_EXHAUSTED: too many requests per minute", 0))
        assertFalse(LiveOneKey.soft(2, "too many requests", 0))                     // कई key = पुराना (दूसरी key पर)
        assertFalse(LiveOneKey.soft(1, "quota exceeded per day", 0))               // रोज़ की limit
        assertFalse(LiveOneKey.soft(1, "x", LiveOneKey.SOFT_TRIES))
        assertEquals(1_500L, LiveOneKey.waitMs(0))
        assertEquals(8_000L, LiveOneKey.waitMs(9))
        assertTrue(LiveOneKey.dropCaption(1, false))
        assertFalse(LiveOneKey.dropCaption(1, true))
        assertTrue(src("GeminiLive.kt").contains("LiveOneKey.soft(keys.size"))
    }

    // ------------------------------------------------------------ 4. पूरी बात सुनकर

    @Test fun oneWordFragment_waitsMore() {
        assertTrue(TurnEnd.fragment("मुझे"))
        assertTrue(TurnEnd.fragment("डीके"))
        assertFalse(TurnEnd.fragment("हाँ"))
        assertFalse(TurnEnd.fragment("ok"))
        assertFalse(TurnEnd.fragment("कैसे?"))
        assertFalse(TurnEnd.fragment("फोन लॉक करो"))
        assertTrue(src("GeminiLiveSetup.kt").contains("END_SENSITIVITY_LOW"))
    }

    // ------------------------------------------------------------ 5. टच मोड

    @Test fun touchMode_onlyOnExplicitWords() {
        assertTrue(GuardRules.touchOn("DK टच मोड ऑन करो"))
        assertTrue(GuardRules.touchOn("टच मोड चालू कर दो"))
        assertFalse(GuardRules.touchOn("गार्ड मोड चालू करो"))
        assertFalse(GuardRules.touchOn("टच करो"))
        assertFalse(GuardRules.touchOn("टच मोड क्या है"))
        val g = src("Guard.kt")
        assertTrue(g.contains("GuardRules.touchOn(DKState.lastUser)"))
        assertTrue(Guard.TOUCH_NOT_ASKED.startsWith("NOT_STARTED"))
    }

    // ------------------------------------------------------------ 6. लॉक / अनलॉक

    private val c = PhoneLockVoice.Cfg()

    @Test fun lockUnlock_moreWaysHeard() {
        val L = PhoneLockVoice.Act.LOCK; val U = PhoneLockVoice.Act.UNLOCK
        for (t in listOf("डीके फोन लॉक करो", "DK फोन को लॉक कर दीजिए", "डीजे फोन लॉग करो", "फोन लॉक कर दो बॉस", "phone lock karo"))
            assertEquals(t, L, PhoneLockVoice.instant(t, c = c))
        for (t in listOf("डीके फोन अनलॉक करो", "DK फोन को अनलॉक कर दीजिए", "फोन अन लॉक करो", "डीके अनलॉक कर दो", "phone unlock kar do"))
            assertEquals(t, U, PhoneLockVoice.instant(t, c = c))
        assertNull(PhoneLockVoice.instant("फोन लॉक मत करो", c = c))
        // DK की अपनी पक्की लाइन फिर से हुक्म न बने
        assertNull(PhoneLockVoice.instant(PhoneLockVoice.LIVE_UNLOCK_SAY, c = c))
        assertNull(PhoneLockVoice.instant(PhoneLockVoice.LIVE_LOCK_SAY, c = c))
        assertNull(PhoneLockVoice.instant("व्हाट्सएप लॉक करो", c = c))
        assertNull(PhoneLockVoice.instant("फोन लॉक का पासवर्ड कैसे बदलें", c = c))
    }

    @Test fun live_saysExactResult_neverRefuses() {
        assertEquals("बॉस, फ़ोन अनलॉक कर दिया।", PhoneLockVoice.liveSay("unlock", "UNLOCKED"))
        assertEquals("बॉस, फ़ोन लॉक कर दिया।", PhoneLockVoice.liveSay("lock", "LOCKED"))
        val f = PhoneLockVoice.liveSay("unlock", PhoneLockVoice.UNLOCK_FAIL + " टच मोड चालू है, इसलिए DK फ़ोन नहीं खोलेगा।")
        assertTrue(f, f.startsWith("लॉक स्क्रीन नहीं खुला") && f.contains("टच मोड"))
        assertTrue(PhoneLockVoice.liveNote("unlock", "OK").contains("बॉस, फ़ोन अनलॉक कर दिया"))
        val t = src("GeminiLiveTurnEnd.kt")
        assertTrue(t.contains("visionNote(PhoneLockVoice.liveNote(type, r)"))
        assertTrue(src("LiveTaskFirst.kt").contains("never say you cannot"))
        // टच मोड चालू = DK फ़ोन नहीं खोलेगा (पुराना नियम बना रहे)
        assertTrue(src("DKAccessibilityScreen.kt").contains("Guard.blocksUnlock() ?:"))
    }

    // ------------------------------------------------------------ 7. चेहरा जाँच

    @Test fun face_guideAndFasterTurn() {
        assertTrue(FaceLiveness.TURN < 0.12f)
        assertTrue(FaceLiveness.GUIDE.contains("①") && FaceLiveness.GUIDE.contains("③"))
        val l = FaceLiveness()
        assertEquals(0, l.done())
        assertTrue(l.idNow(true))
        assertFalse(l.idNow(false))
        val a = src("GuardAlarmActivity.kt")
        assertTrue(a.contains("liveness.idNow(front)"))
        assertTrue(a.contains("FaceLiveness.GUIDE"))
    }
}

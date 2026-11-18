package com.dk.assistant.backend

import com.dk.assistant.onboard.OnboardingRules
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 18 नवंबर 2026 (मालिक की शर्तें): मुफ़्त = अपनी Gemini key + आवाज़ रोज़ 10 मिनट; बेसिक = डीप रिसर्च;
 * प्रीमियम = कोडिंग/कंप्यूटर/ऑटो + अलग keys; मालिक = सब. Google लॉगिन चाहें तो, नाम अपने-आप.
 */
class PlanVoiceGateTest {
    private fun src(rel: String) = listOf(File("src/main/java/com/dk/assistant/$rel"), File("app/src/main/java/com/dk/assistant/$rel"))
        .first { it.exists() }.readText()

    @Test fun parseLevels() {
        assertEquals(Plan.Level.OWNER, Plan.parse("owner"))
        assertEquals(Plan.Level.PREMIUM, Plan.parse(" Premium "))
        assertEquals(Plan.Level.BASIC, Plan.parse("basic"))
        assertEquals(Plan.Level.FREE, Plan.parse(null))
        assertEquals(Plan.Level.FREE, Plan.parse("kuch bhi"))
    }

    @Test fun expiredLicenseBecomesFree() {
        assertEquals(Plan.Level.FREE, Plan.effective(Plan.Level.PREMIUM, 1000L, 2000L))
        assertEquals(Plan.Level.PREMIUM, Plan.effective(Plan.Level.PREMIUM, 3000L, 2000L))
        assertEquals(Plan.Level.BASIC, Plan.effective(Plan.Level.BASIC, 0L, 2000L))
        assertEquals(Plan.Level.OWNER, Plan.effective(Plan.Level.OWNER, 1L, 2000L))
    }

    @Test fun whoGetsWhat() {
        val F = Plan.Level.FREE; val B = Plan.Level.BASIC; val P = Plan.Level.PREMIUM; val O = Plan.Level.OWNER
        assertFalse(Plan.allowed(F, Plan.Work.RESEARCH))
        assertTrue("आवाज़ वाले 10 मिनट में खोज", Plan.allowed(F, Plan.Work.RESEARCH, voiceActive = true))
        assertFalse(Plan.allowed(F, Plan.Work.CODING, voiceActive = true))
        assertFalse(Plan.allowed(F, Plan.Work.AUTO, voiceActive = true))
        assertTrue(Plan.allowed(B, Plan.Work.RESEARCH))
        assertFalse(Plan.allowed(B, Plan.Work.COMPUTER))
        for (w in Plan.Work.values()) { assertTrue(Plan.allowed(P, w)); assertTrue(Plan.allowed(O, w)) }
        assertFalse(Plan.separateKeys(F)); assertFalse(Plan.separateKeys(B))
        assertTrue(Plan.separateKeys(P)); assertTrue(Plan.separateKeys(O))
    }

    @Test fun messagesPointToLicense() {
        for (w in Plan.Work.values()) {
            assertTrue(Plan.message(w).contains("लाइसेंस की पाएँ"))
            assertTrue(Plan.liveNote(w).contains("Do NOT say it is done"))
        }
    }

    @Test fun voiceWindowUsesServerClock() {
        assertEquals(10_000L + 60_000L, VoiceGate.localUntil(5_000_000L, 5_060_000L, 10_000L))
        assertEquals(10_000L, VoiceGate.localUntil(5_000_000L, 4_000_000L, 10_000L))
        assertEquals(777L, VoiceGate.localUntil(0L, 777L, 10_000L))
        assertTrue(VoiceGate.waitMs() in 1_000L..60_000L)
    }

    @Test fun voiceGrantParses() {
        val g = BackendWire.voiceGrant("""{"ok":true,"level":"free","metered":true,"now":100,"until":60100,"quota":{"voice_left":0,"chat_left":30,"warn":true}}""")
        assertEquals("free", g.level); assertTrue(g.metered); assertEquals(60_000L, g.until - g.now)
        assertTrue(g.quota!!.warn); assertEquals(0, g.quota!!.voiceLeft)
        val p = BackendWire.voiceGrant("""{"ok":true,"level":"premium","metered":false,"now":1,"until":2}""")
        assertFalse(p.metered)
    }

    @Test fun needOwnKeyStopsAndSays() {
        val e = BackendWire.error(402, """{"code":"NEED_OWN_KEY","error":"x","say":"अपनी Gemini key डालें"}""")
        assertEquals(BackendWire.NEED_OWN_KEY, e.reason)
        assertTrue(e.stopsTalk); assertTrue(e.final)
        assertEquals("अपनी Gemini key डालें", e.say)
    }

    @Test fun googleNameFromFirebase() {
        assertEquals("Dhiraj Singh", BackendSession.googleName(JSONObject("""{"displayName":" Dhiraj Singh "}""")))
        assertEquals("Dhiraj Kumar", BackendSession.googleName(JSONObject("""{"fullName":"Dhiraj Kumar"}""")))
        assertEquals("Dhiraj Singh", BackendSession.googleName(JSONObject("""{"firstName":"Dhiraj","lastName":"Singh"}""")))
        assertEquals("", BackendSession.googleName(JSONObject("{}")))
    }

    @Test fun onboardingAsksKeyOnlyWhenNeeded() {
        assertTrue(OnboardingRules.askKey(hasKey = false, owner = false))
        assertFalse(OnboardingRules.askKey(hasKey = true, owner = false))
        assertFalse(OnboardingRules.askKey(hasKey = false, owner = true))
        val o = src("onboard/OnboardingActivity.kt")
        assertTrue(o.contains("Google से लॉगिन (चाहें तो)"))
        assertTrue(o.contains("BackendSession.displayName"))
        assertTrue(o.contains("EXTRA_FOCUS_KEY"))
    }

    @Test fun gatesAreWired() {
        val live = src("GeminiLive.kt")
        assertTrue(live.contains("voiceMeter()")); assertTrue(live.contains("VoiceGate.ensure"))
        assertTrue(src("DKAccessibilityService.kt").contains("voiceOk()"))
        assertTrue(src("CoderTeam.kt").contains("planCheck"))
        assertTrue(src("ComputerAgent.kt").contains("planCheck"))
        assertTrue(src("dkai/DkaiScreen.kt").contains("Plan.Work.RESEARCH"))
        assertTrue(src("auto/AutoMode.kt").contains("Plan.Work.AUTO"))
        assertTrue(src("SettingsBrain.kt").contains("Plan.separateKeys"))
    }
}

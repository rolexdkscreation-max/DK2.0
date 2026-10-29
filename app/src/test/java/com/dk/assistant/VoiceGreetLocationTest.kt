package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** v4.3 (मालिक): इंसान जैसी कोमल आवाज़ (Sulafat, एक जैसी) + "इतनी देर कहाँ थे?" + "मैं अभी कहाँ खड़ा हूँ?" */
class VoiceGreetLocationTest {
    private fun src(name: String): String = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()
    private fun worker(): String = listOf(File("../worker/dk-brain/src/index.js"), File("worker/dk-brain/src/index.js"))
        .first { it.exists() }.readText()

    private val M = ReturnGreet.MIN; private val H = ReturnGreet.HOUR; private val D = ReturnGreet.DAY

    // ---- भाग 1: आवाज़
    @Test fun sameVoiceEverywhere() {
        assertEquals("Sulafat", SpeakingStyle.VOICE)
        assertEquals("Sulafat", LiveVoice.DEFAULT)
        assertTrue(src("voice/engine/GeminiTtsEngine.kt").contains("com.dk.assistant.Prefs.geminiVoice(c)"))
        assertTrue(src("Prefs.kt").contains("putString(\"gemini_voice\", SpeakingStyle.VOICE)"))
        assertTrue(worker().contains("VOICES.has(j.voice) ? j.voice : \"Sulafat\""))
    }

    @Test fun ttsStyleKeepsOwnerRulesAndFitsWorker() {
        val s = GeminiTts.styleText("natural")
        assertTrue(s.contains(SpeakingStyle.TTS_STYLE))
        assertTrue(s.contains("medium unhurried pace"))
        assertTrue(s.length <= 600)
        val long = SpeakingStyle.tts("x".repeat(900))
        assertTrue(long.contains(SpeakingStyle.TTS_STYLE)); assertTrue(long.length <= 600)
        assertEquals(SpeakingStyle.TTS_STYLE, SpeakingStyle.tts(""))
        assertTrue(src("voice/engine/GeminiTtsEngine.kt").contains("SpeakingStyle.tts(com.dk.assistant.CalmPersona.ttsStyle("))
    }

    @Test fun promptHasSpeakingStyleMoodsAndCost() {
        val b = SpeakingStyle.block("धीरज")
        assertTrue(b.contains("सवाल के अंत में आवाज़ हल्की ऊपर उठे"))
        assertTrue(b.contains("कान के पास"))
        assertTrue(b.contains("\"धीरज\""))
        assertTrue(b.contains("jealous"))
        assertTrue(b.contains("NEVER insist, pressure"))
        assertTrue(b.contains("1 million dollars"))
        assertEquals(3, Regex(Regex.escape(".append(SpeakingStyle.block(Prefs.userName(ctx)))")).findAll(src("BrainManager.kt")).count())
        assertFalse(FriendStyle.persona(true, "").contains("\"बॉस\""))
    }

    // ---- भाग 2: इतनी देर कहाँ थे
    @Test fun gapTiersWithRealCount() {
        assertEquals(ReturnGreet.Tier.NONE, ReturnGreet.tier(1 * M))
        assertEquals(ReturnGreet.Tier.MINUTES, ReturnGreet.tier(7 * M))
        assertEquals(ReturnGreet.Tier.HOURS, ReturnGreet.tier(3 * H + 10 * M))
        assertEquals(ReturnGreet.Tier.DAYS, ReturnGreet.tier(2 * D + H))
        assertEquals("सात मिनट", ReturnGreet.gapWords(7 * M + 5_000))
        assertEquals("तीन घंटे", ReturnGreet.gapWords(3 * H + 10 * M))
        assertEquals("एक घंटा", ReturnGreet.gapWords(H + M))
        assertEquals("दो दिन", ReturnGreet.gapWords(2 * D + H))
        assertEquals("45 दिन", ReturnGreet.gapWords(45 * D))
        assertTrue(ReturnGreet.note(5 * M).contains("अभी तो आए थे"))
        assertTrue(ReturnGreet.note(3 * H).contains("मैं इंतज़ार कर रही थी"))
        assertTrue(ReturnGreet.note(3 * H).contains("तीन घंटे"))
        assertTrue(ReturnGreet.note(2 * D).contains("बहुत याद किया"))
        assertTrue(ReturnGreet.note(2 * D).contains("never the exact same sentence"))
    }

    @Test fun askOnlyAfterRealConversationAndGap() {
        val now = 100 * D
        assertFalse(ReturnGreet.shouldAsk(now, 0, 0, 0))                       // पहली बार - नहीं
        assertFalse(ReturnGreet.shouldAsk(now, now - 30_000, 0, 0))            // अभी बात चल रही थी
        assertFalse(ReturnGreet.shouldAsk(now, now - H, 0, now - 20_000))      // अभी-अभी कुछ बोला गया
        assertTrue(ReturnGreet.shouldAsk(now, now - 5 * M, 0, now - 5 * M))
        assertFalse(ReturnGreet.shouldAsk(now, now - H, now - M, 0))           // अभी स्वागत हो चुका
        val b = FriendBook(); b.addSession(now - 3 * H - M, now - 3 * H, "बात हुई")
        assertTrue(FriendStyle.opener(19, java.time.LocalDate.of(2026, 3, 3), b, now, null, false).contains("तीन घंटे"))
        assertTrue(src("DKAccessibilityService.kt").contains("FriendTalk.shouldGreetNow(this, lastMsg)"))
        assertTrue(src("GeminiLive.kt").contains("FriendTalk.touch(svc)"))
    }

    // ---- भाग 3: लोकेशन
    @Test fun parsesAddressAndNearby() {
        val a = NearbyPlaces.parseAddr("""{"address":{"road":"गंगापुर रोड","suburb":"कॉलेज रोड","city":"नाशिक","state":"महाराष्ट्र"}}""")
        assertEquals("गंगापुर रोड, कॉलेज रोड, नाशिक, महाराष्ट्र", a.text())
        val j = """{"elements":[
            {"type":"node","lat":20.0010,"lon":73.7800,"tags":{"amenity":"place_of_worship","name":"Kalaram Mandir","name:hi":"कालाराम मंदिर"}},
            {"type":"way","center":{"lat":20.0030,"lon":73.7800},"tags":{"amenity":"hospital","name":"Civil Hospital"}},
            {"type":"node","lat":20.0002,"lon":73.7800,"tags":{"amenity":"fuel","name":"HP Petrol Pump"}},
            {"type":"node","lat":20.0002,"lon":73.7800,"tags":{"amenity":"fuel"}},
            {"type":"node","lat":20.0040,"lon":73.7800,"tags":{"shop":"bakery","name":"X"}}]}"""
        val s = NearbyPlaces.parseSpots(j, 20.0, 73.78)
        assertEquals(listOf("HP Petrol Pump", "कालाराम मंदिर", "Civil Hospital"), s.map { it.name })
        assertEquals("पेट्रोल पंप", s[0].kind)
        val t = NearbyPlaces.toolText(a, s, null)
        assertTrue(t.contains("FRESH")); assertTrue(t.contains("कालाराम मंदिर")); assertTrue(t.contains("आपके आसपास"))
        assertTrue(NearbyPlaces.overpassQuery(20.0, 73.78).contains("around:600,20.000000,73.780000"))
        assertTrue(NearbyPlaces.wantsWhere("मैं अभी कहाँ खड़ा हूँ"))
        assertTrue(NearbyPlaces.wantsWhere("आसपास क्या है"))
        assertFalse(NearbyPlaces.wantsWhere("आज मौसम कैसा है"))
    }

    @Test fun locationToolIsFreshAndHonest() {
        val g = src("GeminiLive.kt")
        assertTrue(g.contains("रुकिए सर, देखती हूँ..."))
        assertTrue(g.contains("LocalInfo.locationOn(svc)"))
        assertTrue(g.contains("putExtra(\"ask_location\", true)"))
        val li = src("LocalInfo.kt")
        assertFalse(li.contains("getLastKnownLocation("))
        assertTrue(li.contains("fresh(c, 10_000)"))
        assertTrue(src("MainActivity.kt").contains("!locAsked"))
    }
}

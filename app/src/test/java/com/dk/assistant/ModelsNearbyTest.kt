package com.dk.assistant

import com.dk.assistant.dkai.DkaiKit
import com.dk.assistant.dkai.DkaiModelList
import com.dk.assistant.dkai.DkaiPick
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** 14 नवंबर 2026 (मालिक): सारे फ्री मॉडल + चुना मॉडल ही काम करे + पास की दुकान का रास्ता तुरंत */
class ModelsNearbyTest {
    private fun src(p: String) = File("src/main/java/com/dk/assistant/$p").let { if (it.exists()) it else File("app/src/main/java/com/dk/assistant/$p") }.readText()

    // ------------------------------------------------------------ मॉडल सूची
    @Test fun freeRuleFixed() {
        assertTrue(DkaiKit.isFreeModel("gemini", "gemini-3.6-flash"))
        assertTrue(DkaiKit.isFreeModel("gemini", "gemini-3.5-flash-lite"))
        assertTrue(DkaiKit.isFreeModel("gemini", "gemma-4-31b-it"))
        assertFalse("Omni Flash पेड है", DkaiKit.isFreeModel("gemini", "gemini-omni-1.2-flash"))
        assertFalse(DkaiKit.isFreeModel("gemini", "gemini-3.8-flash-cyber"))
        assertFalse(DkaiKit.isFreeModel("gemini", "gemini-3.1-pro-preview"))
    }

    @Test fun geminiShowsKnownAndLiveFreeModels() {
        val gem = DkaiKit.homeModels({ it == "gemini" }, false, { emptyList() }, { emptyList() }, { it }).map { it.model }
        assertTrue(gem.containsAll(listOf("gemini-3.8-flash", "gemini-3.5-flash-lite", "gemini-3.6-flash", "gemini-3-flash-preview")))
        // Google की ताज़ा सूची में नया फ्री मॉडल = अपने-आप दिखे; पेड / बंद वाले नहीं
        val live = listOf("models/gemini-3.9-flash", "models/gemini-omni-1.2-flash", "models/gemini-3.5-flash", "models/gemma-4-31b-it", "models/gemini-3.1-pro-preview")
        val withLive = DkaiKit.homeModels({ it == "gemini" }, false, { live }, { emptyList() }, { it }).map { it.model }
        assertTrue(withLive.contains("gemini-3.9-flash"))
        assertTrue(withLive.contains("gemma-4-31b-it"))
        assertFalse(withLive.contains("gemini-omni-1.2-flash"))
        assertFalse(withLive.contains("gemini-3.5-flash"))
        assertEquals("दोहराव नहीं", withLive.size, withLive.distinct().size)
        assertTrue(DkaiModelList.extraGemini(listOf("gemini-3.9-flash", "gemini-3.6-flash"), setOf("gemini-3.8-flash")).indexOf("gemini-3.9-flash") >= 0)
    }

    @Test fun otherProvidersMoreThanFive() {
        val many = (1..9).map { "m$it" }
        val g = DkaiKit.homeModels({ it == "groq" }, false, { many }, { emptyList() }, { it })
        assertEquals(9, g.size)
        assertEquals(listOf("a", "b", "c"), DkaiModelList.ordered(listOf("a", "b"), listOf("b", "c")))
        assertTrue(DkaiModelList.isStale(0L, 10L)); assertFalse(DkaiModelList.isStale(1_000L, 2_000L))
        assertTrue(src("dkai/DkaiSheets.kt").contains("DkaiModelList.refreshStale(c)"))
    }

    // ------------------------------------------------------------ चुना मॉडल ही काम करे
    @Test fun pickedModelFirstAndQuiet() {
        assertEquals(listOf("zai", "groq", "gemini"), DkaiPick.activeFirst(listOf("groq", "zai", "gemini"), "zai") { it })
        assertTrue(src("BrainManager.kt").contains("DkaiPick.activeFirst(fast, active)"))
        assertFalse(src("dkai/DkaiSheets.kt").contains("s.toast(\"मॉडल: "))
    }

    // ------------------------------------------------------------ पास की दुकान -> रास्ता
    @Test fun clothesAndShoppingFindTags() {
        assertTrue(NearbyPlaces.findTags("कपड़े").contains("shop=clothes"))
        assertTrue(NearbyPlaces.findTags("कपड़े").contains("shop=mall"))
        assertTrue(NearbyPlaces.findTags("कुछ खरीदना है").contains("shop=mall"))
        assertEquals(listOf("shop=*"), NearbyPlaces.findTags("दुकान"))
        assertEquals(listOf("amenity=fuel"), NearbyPlaces.findTags("पेट्रोल पंप"))
    }

    @Test fun wantsGoOnlyForNearestWithRoute() {
        assertTrue(NearbyGo.wantsGo("मुझे कपड़े लेना है, आसपास की नज़दीकी दुकान ढूँढो और रास्ता लगा दो"))
        assertTrue(NearbyGo.wantsGo("पास के मॉल का रास्ता लगा दो"))
        assertFalse("नाम वाली जगह = पुराना रास्ता", NearbyGo.wantsGo("सिटी सेंटर मॉल का रास्ता लगा दो"))
        assertFalse("सिर्फ़ दिखाना = रास्ता नहीं", NearbyGo.wantsGo("पास में पेट्रोल पंप दिखाओ"))
        assertEquals("nearby_go", Maps.parse("मुझे कपड़े लेना है, पास की दुकान ढूँढो और रास्ता लगा दो")?.action?.optString("type"))
        assertEquals("map_search", Maps.parse("पास में पेट्रोल पंप दिखाओ")?.action?.optString("type"))
        assertEquals("clothing store near me", NearbyGo.mapsQuery("कपड़े"))
        assertEquals("shopping mall near me", NearbyGo.mapsQuery("मॉल"))
    }

    @Test fun sayLineAfterWork() {
        val s = NearbyPlaces.Spot("राज फैशन", "कपड़ों की दुकान", 350, 19.2, 76.7)
        val line = NearbyGo.sayLine(NearbyGo.Result(s, emptyList(), "", true))
        assertTrue(line.startsWith("SAID:")); assertTrue(line.contains("राज फैशन")); assertTrue(line.contains("रास्ता लगा दिया"))
        assertTrue(NearbyGo.startedText(s, emptyList()).contains("ALREADY STARTED"))
        assertTrue(NearbyGo.sayLine(NearbyGo.Result(null, emptyList(), "q", true)).contains("Google Maps"))
        val setup = src("GeminiLiveSetup.kt")
        assertTrue(setup.contains(".put(\"go\", JSONObject().put(\"type\", \"BOOLEAN\")"))
        assertTrue(setup.contains("never ask anything first"))
        assertTrue(src("GeminiLiveTools.kt").contains("localGoTool(args.optString(\"find\"))"))
        assertTrue(ActFirstRule.TEXT.contains("DO FIRST, TALK AFTER"))
        assertTrue(src("BrainManager.kt").contains("ActFirstRule.TEXT"))
        assertTrue(src("DKAccessibilityService.kt").contains("\"nearby_go\" -> NearbyGo.goSay("))
    }
}

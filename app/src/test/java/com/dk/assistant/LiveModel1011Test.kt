package com.dk.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** नवंबर 2026: Inspector के बाद Live नहीं जुड़ रहा - 1011 वाला मॉडल हटाना, साफ़ tool declarations, अटका "Speaking", Inspector का text */
class LiveModel1011Test {
    private fun src(name: String): String =
        listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name")).first { it.exists() }.readText()

    @Test fun twice1011In3MinDropsModel() {
        val t = LiveStay.Model1011()
        assertFalse(t.hit("m", 0))
        assertTrue(t.hit("m", 60_000))
        assertEquals(0, t.count("m"))          // गिनती फिर से शुरू
    }

    @Test fun old1011Forgotten() {
        val t = LiveStay.Model1011()
        assertFalse(t.hit("m", 0))
        assertFalse(t.hit("m", LiveStay.MODEL_1011_WINDOW_MS + 1))
        assertFalse(t.hit("other", LiveStay.MODEL_1011_WINDOW_MS + 2))   // हर मॉडल अलग
    }

    @Test fun emptyParamsRemovedAndPropsGetDescription() {
        val decls = JSONArray()
            .put(JSONObject().put("name", "local_info").put("description", "x")
                .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject())))
            .put(JSONObject().put("name", "remember_fact").put("description", "y")
                .put("parameters", JSONObject().put("type", "OBJECT")
                    .put("properties", JSONObject().put("fact", JSONObject().put("type", "STRING")))
                    .put("required", JSONArray().put("fact"))))
        val tools = JSONArray().put(JSONObject().put("functionDeclarations", decls)).put(JSONObject().put("googleSearch", JSONObject()))
        LiveStay.cleanTools(tools)
        val d = tools.getJSONObject(0).getJSONArray("functionDeclarations")
        assertFalse(d.getJSONObject(0).has("parameters"))
        val fact = d.getJSONObject(1).getJSONObject("parameters").getJSONObject("properties").getJSONObject("fact")
        assertEquals("fact", fact.getString("description"))
        assertEquals("STRING", fact.getString("type"))
        assertEquals("fact", d.getJSONObject(1).getJSONObject("parameters").getJSONArray("required").getString(0))
        assertTrue(tools.getJSONObject(1).has("googleSearch"))
    }

    @Test fun speakingResetWhenLiveEnds() {
        assertEquals(Mode.IDLE, LiveStay.modeAfterEnd(Mode.SPEAKING))
        assertEquals(Mode.IDLE, LiveStay.modeAfterEnd(Mode.THINKING))
        assertEquals(Mode.WORKING, LiveStay.modeAfterEnd(Mode.WORKING))
        assertEquals(Mode.LISTENING, LiveStay.modeAfterDrop(Mode.SPEAKING, playing = false))
        assertEquals(Mode.SPEAKING, LiveStay.modeAfterDrop(Mode.SPEAKING, playing = true))
    }

    @Test fun wiredInGeminiLive() {
        val s = src("GeminiLive.kt")
        assertTrue(s.contains(".put(\"tools\", LiveStay.cleanTools(tools))"))
        assertTrue(s.contains("code == 1011 && model1011.hit(model)"))
        assertTrue(s.contains("code == 1011 && model1011.hit(a.c.model, now)"))
        assertTrue(s.contains("LiveStay.modeAfterEnd(DKState.mode)"))
    }

    @Test fun inspectorDoesNotTalkIntoUnstableLive() {
        val s = src("inspector/Inspector.kt")
        assertTrue(s.contains("stableFor(InspectorCore.LIVE_STABLE_MS) != true) return"))
        assertTrue(s.contains("InspectorCore.liveDeliverable(it)"))
    }
}

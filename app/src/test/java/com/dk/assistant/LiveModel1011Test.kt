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
    // ---- cooldown / मॉडल fallback (log: "limit खत्म, छोड़े: सारे" -> "दौड़: 0 जोड़ियाँ" -> code=0)

    @Test fun modelNotFoundIsNotQuota() {
        val r = "models/gemini-x is not found for API version v1beta, or is not supported for bidiGenerateContent."
        assertFalse(LiveRace.shouldBlockModel(1008, r, tried = true))       // "gene-rate" अब limit नहीं
        assertTrue(LiveRace.shouldBlockModel(1011, "You exceeded your current quota", tried = true))
        assertTrue(LiveRace.shouldBlockModel(429, "", tried = true))
    }

    @Test fun noBlockWithoutRealAttempt() {
        // 0 जोड़ियों वाली दौड़ पुराना quota कारण दोहराती है - बिना आज़माए मॉडल नहीं रुकना चाहिए
        assertFalse(LiveRace.shouldBlockModel(1011, "You exceeded your current quota", tried = false))
    }

    @Test fun fullDayBlockOnlyForDailyQuota() {
        assertFalse(LiveRace.dailyBlock(1, "quota exceeded"))
        assertTrue(LiveRace.dailyBlock(2, "Quota exceeded for metric ... per day"))
        assertFalse(LiveRace.dailyBlock(2, "rate limit"))
        assertFalse(LiveRace.dailyBlock(3, ""))
    }

    @Test fun allBlockedStillGivesOneProbe() {
        val now = 1_000_000L
        val until = mapOf("a" to now + 20 * 60_000L, "b" to now + 5 * 60_000L, "c" to now + 60 * 60_000L)
        val c = LiveRace.probeWhenEmpty(listOf("a", "b", "c"), 1) { until[it.model] ?: 0L }
        assertEquals("b", c!!.model)                                        // सबसे जल्दी खुलने वाला
        assertEquals(null, LiveRace.probeWhenEmpty(emptyList(), 1) { 0L })
    }

    @Test fun cooldownExpiresAndRetryAllowed() {
        val h = LiveRace.Health(); val c = LiveRace.Combo("m", 0)
        h.comboFail(c, 0)
        assertTrue(h.order(listOf("m"), 1, 1_000).isEmpty())
        assertEquals(listOf(c), h.order(listOf("m"), 1, LiveRace.COMBO_FAIL_MS + 1))   // 2 मिनट बाद फिर कोशिश
    }

    @Test fun cooldownFixWiredIn() {
        val s = src("GeminiLive.kt")
        assertFalse(s.contains("isQuota(code, r) || r.contains(\"rate\")"))
        assertTrue(s.contains("LiveRace.shouldBlockModel(code, r, tried = rc.lastFailCombo != null)"))
        assertTrue(s.contains("LiveRace.probeWhenEmpty(models, keys.size, untilOf)"))
        assertTrue(s.contains("LiveRace.dailyBlock(n, reason)"))
        assertTrue(s.contains("पुरानी save हुई Live रोक हटाई"))
        // 1011 वाला मॉडल हटाना save होने वाली Cooldown से नहीं
        val d = s.substring(s.indexOf("private fun dropModel1011"), s.indexOf("private fun isQuota"))
        assertFalse(d.contains("Cooldown.block("))
    }

    @Test fun captionIndependentOfLiveCooldown() {
        assertFalse(src("LiveCaption.kt").contains("Cooldown"))
    }
}

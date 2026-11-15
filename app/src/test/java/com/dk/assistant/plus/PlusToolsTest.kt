package com.dk.assistant.plus

import com.dk.assistant.LiveToolSlim
import com.dk.assistant.plus.tools.PlusTools
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 15 नवंबर 2026: plug-in tools + Dynamic Tool Router (बहुत tools पर सिर्फ़ नाम + खोज) */
class PlusToolsTest {
    private fun src(n: String) = File("src/main/java/com/dk/assistant/$n").readText()

    @Test fun pluginTools_uniqueNames_flagged_offByDefault_notClashingOldTools() {
        val names = PlusTools.ALL.map { it.name }
        assertEquals(names.size, names.toSet().size)
        val setup = src("GeminiLiveSetup.kt")
        for (t in PlusTools.ALL) {
            assertFalse("पुराने tool से टकराव: ${t.name}", setup.contains("\"name\", \"${t.name}\""))
            assertTrue(FeatureFlags.byKey(t.flag) != null && !FeatureFlags.defaultOf(t.flag) && FeatureFlags.ready(t.flag))
            val d = t.declaration()
            assertEquals(t.name, d.getString("name")); assertTrue(d.getJSONObject("parameters").has("properties"))
            assertFalse(t.name in LiveToolSlim.CORE)          // router के पीछे = setup हल्का
        }
    }

    @Test fun hooksWired_inLiveSetupAndRunner() {
        assertTrue(src("GeminiLiveSetup.kt").contains("PlusTools.declarations(svc)"))
        val run = src("GeminiLiveTools.kt")
        assertTrue(run.contains("PlusTools.runOrNull(svc, name, args)"))
        assertTrue(run.contains("LiveToolSlim.search(args.optString(\"query\"))"))
    }

    private fun decl(n: String, d: String) = JSONObject().put("name", n).put("description", d)
        .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject().put("task", JSONObject().put("type", "STRING"))))

    @Test fun router_compactsWhenMany_andSearchFinds() {
        val fd = JSONArray().put(decl("phone_task", "phone"))
        for (i in 1..60) fd.put(decl("tool_$i", "does thing number $i."))
        fd.put(decl("youtube_channel", "Check a YouTube channel subscribers and views."))
        val r = LiveToolSlim.apply(JSONArray().put(JSONObject().put("functionDeclarations", fd)), on = true)
        val kept = r.tools.getJSONObject(0).getJSONArray("functionDeclarations")
        val names = (0 until kept.length()).map { kept.getJSONObject(it).getString("name") }
        assertEquals(listOf("phone_task", LiveToolSlim.ROUTER, LiveToolSlim.SEARCH), names)
        assertFalse(kept.getJSONObject(1).getString("description").contains("does thing number 7"))
        val found = LiveToolSlim.search("youtube subscribers")
        assertTrue(found.lines()[1].startsWith("- youtube_channel(task)"))
        assertTrue(found.lines().size <= LiveToolSlim.TOP_K + 1)
    }

    @Test fun router_fewTools_sameAsBefore() {
        val fd = JSONArray().put(decl("phone_task", "phone")).put(decl("coding_task", "Write code."))
        val r = LiveToolSlim.apply(JSONArray().put(JSONObject().put("functionDeclarations", fd)), on = true)
        val kept = r.tools.getJSONObject(0).getJSONArray("functionDeclarations")
        assertEquals(2, kept.length())
        assertTrue(kept.getJSONObject(1).getString("description").contains("coding_task(task): Write code."))
    }
}

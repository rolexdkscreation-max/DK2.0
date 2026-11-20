package com.dk.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 20 नवंबर 2026 (मालिक का लॉग: निर्देश + tools ≈ 14100 token, बातचीत को ~2000): context का स्थिर हिस्सा छोटा, हाल की बातें बचें */
class LiveContextSlimTest {
    private fun decl(name: String) = JSONObject().put("name", name).put("description", "Does $name. More text here.")
        .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject().put("on", JSONObject().put("type", "BOOLEAN"))))

    @Test fun rareTools_behindRouter() {
        val names = listOf("phone_task", "web_search", "screen_share", "camera", "social_media_task_control", "notification_reply", "look_screen")
        val d = JSONArray(); names.forEach { d.put(decl(it)) }
        val r = LiveToolSlim.apply(JSONArray().put(JSONObject().put("functionDeclarations", d)), true)
        for (n in listOf("screen_share", "camera", "social_media_task_control", "notification_reply")) {
            assertFalse(n in LiveToolSlim.CORE); assertTrue(n in r.routed)
        }
        assertTrue("look_screen" in LiveToolSlim.CORE && "phone_task" in LiveToolSlim.CORE)
        // बुलाने पर वही काम: dk_agent(tool=camera, args_json={"on":true}) = camera(on=true)
        val (n, a) = LiveToolSlim.unwrap("dk_agent", JSONObject().put("tool", "camera").put("args_json", "{\"on\":true}"))
        assertEquals("camera", n); assertTrue(a.getBoolean("on"))
        assertTrue(LiveToolSlim.LINE_MAX <= 90)
    }

    @Test fun liteGirlfriend_noDuplicateKeyPoints_ownerTextSame() {
        val full = GirlfriendPersona.block("राज"); val lite = GirlfriendPersona.block("राज", lite = true)
        assertTrue(lite.contains(GirlfriendPersona.OWNER_TEXT))
        assertTrue(lite.length < full.length)
        assertFalse(lite.contains("OBEY HIM")); assertTrue(full.contains("OBEY HIM"))
        // लड़का/लड़की वाली पक्की लाइनें छोटे में भी
        assertTrue(lite.contains("The user is ALWAYS male") && lite.contains("You are ALWAYS female"))
        assertEquals(full, GirlfriendPersona.block("राज", lite = false))
    }

    @Test fun compression_keepsRecentTurns_whenFixedIsBig() {
        for (f in listOf(6_000L, 11_000L, 14_000L)) {
            val c = HistoryOnDemand.tightCompression(f)
            assertTrue("f=$f: हाल की बातें ${c.target - f}", c.target - f >= 1_500L)
            assertTrue("f=$f: बातचीत की जगह ${c.trigger - f}", c.trigger - f >= 3_500L)
        }
        // छोटा स्थिर हिस्सा = पहले जैसा (10k के नीचे)
        assertEquals(8_000L, HistoryOnDemand.tightCompression(4_500L).trigger)
        assertTrue(HistoryOnDemand.tightCompression(0).trigger <= 9_000L)
    }
}

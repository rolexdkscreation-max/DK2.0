package com.dk.assistant.tools

import com.dk.assistant.plus.FeatureFlags
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 15 नवंबर 2026 (मालिक, हिस्सा 1): ToolRegistry + Retry Loop + Gemini function calling */
class ToolRegistryTest {

    @Test fun registry_20to30_coreTools_uniqueNames() {
        val n = ToolRegistry.PHONE_TOOLS.size
        assertTrue("$n", n in 20..30)
        val names = ToolRegistry.ALL.map { it.name }
        assertEquals(names.size, names.toSet().size)
        for (must in listOf("open_app", "call_contact", "send_whatsapp", "send_sms", "tap_text", "scroll", "go_back", "go_home",
                "lock_screen", "screenshot", "flashlight", "wifi", "bluetooth", "airplane_mode", "volume", "brightness", "music",
                "youtube_search", "set_alarm", "set_timer", "read_screen", "type_text", "read_notifications", "send_location", "battery"))
            assertNotNull(must, ToolRegistry.byName(must))
        // हर tool का हिंदी नाम ("कहाँ अटकी" बोलने के लिए)
        for (t in ToolRegistry.PHONE_TOOLS) assertTrue(t.name, ToolPolicy.HINDI.containsKey(t.name))
    }

    @Test fun declarations_validGeminiShape() {
        val d = ToolRegistry.declarations()
        val fns = d.getJSONObject(0).getJSONArray("functionDeclarations")
        assertEquals(ToolRegistry.ALL.size, fns.length())
        for (i in 0 until fns.length()) {
            val f = fns.getJSONObject(i)
            assertTrue(f.getString("name").matches(Regex("[a-z_]+")))
            assertTrue(f.getString("description").isNotBlank())
            f.optJSONObject("parameters")?.let { p ->
                assertEquals("object", p.getString("type"))
                val props = p.getJSONObject("properties")
                p.optJSONArray("required")?.let { r -> for (j in 0 until r.length()) assertTrue(props.has(r.getString(j))) }
            }
        }
        val req = ToolWire.request(JSONArray().put(ToolWire.userTurn("WhatsApp पर राहुल को hi भेजो", "com.android.launcher")))
        assertTrue(req.has("tools") && req.has("contents") && req.has("system_instruction"))
        assertTrue(ToolWire.SYSTEM.contains("${ToolPolicy.MAX_STEPS}"))
    }

    @Test fun limits_15steps_3tries_onceForSends() {
        assertEquals(15, ToolPolicy.MAX_STEPS)
        assertEquals(3, ToolPolicy.MAX_TRIES)
        assertEquals(3, ToolPolicy.tries(ToolRegistry.TAP_TEXT))
        assertEquals(3, ToolPolicy.tries(ToolRegistry.OPEN_APP))
        // कॉल / मैसेज दोबारा नहीं
        for (t in listOf(ToolRegistry.CALL, ToolRegistry.WHATSAPP, ToolRegistry.SMS, ToolRegistry.LOCATION)) assertEquals(t, 1, ToolPolicy.tries(t))
    }

    @Test fun classify_oldExecutorResults() {
        assertEquals(ToolPolicy.Kind.OK, ToolPolicy.classify("ok"))
        assertEquals(ToolPolicy.Kind.OK, ToolPolicy.classify("SAID: सर, हो गया।"))
        assertEquals(ToolPolicy.Kind.OK, ToolPolicy.classify("opened wifi panel"))
        assertEquals(ToolPolicy.Kind.ASK, ToolPolicy.classify("ASK: किसे भेजूँ?"))
        assertEquals(ToolPolicy.Kind.FAIL, ToolPolicy.classify("ERROR: unknown action"))
        assertEquals(ToolPolicy.Kind.FAIL, ToolPolicy.classify("STEP_FAILED: नहीं दिखा"))
        assertEquals(ToolPolicy.Kind.FAIL, ToolPolicy.classify("ERROR_PERM: SMS की परमिशन नहीं"))
        assertEquals(ToolPolicy.Kind.FAIL, ToolPolicy.classify(""))
        assertEquals("सर, हो गया।", ToolPolicy.plain("SAID: सर, हो गया।"))
        assertEquals("SMS की परमिशन नहीं", ToolPolicy.plain("ERROR_PERM: SMS की परमिशन नहीं"))
    }

    @Test fun stuckSpeech_saysWhereAndWhy_inHindi() {
        val s = ToolPolicy.stuckSpeech(ToolRegistry.TAP_TEXT, "Send", "STEP_FAILED: \"Send\" स्क्रीन पर नहीं दिखा", 3)
        assertTrue(s, s.contains("बटन दबाना") && s.contains("Send") && s.contains("3 बार") && s.contains("नहीं दिखा"))
        assertFalse(s.contains("STEP_FAILED"))
    }

    @Test fun missingArg_beforeRunning() {
        assertNotNull(ToolRegistry.missingArg(ToolRegistry.OPEN_APP, JSONObject()))
        assertNull(ToolRegistry.missingArg(ToolRegistry.OPEN_APP, JSONObject().put("name", "YouTube")))
        assertNull(ToolRegistry.missingArg(ToolRegistry.BACK, JSONObject()))
        assertNotNull(ToolRegistry.missingArg("delete_everything", JSONObject()))
    }

    @Test fun parse_functionCalls_keepsContentForHistory() {
        val resp = JSONObject("""{"candidates":[{"content":{"role":"model","parts":[
            {"text":"पहले ऐप खोलती हूँ"},
            {"functionCall":{"name":"open_app","args":{"name":"WhatsApp"}},"thoughtSignature":"abc"}]}}]}""")
        val t = ToolWire.parse(resp)
        assertEquals(1, t.calls.size)
        assertEquals("open_app", t.calls[0].name)
        assertEquals("WhatsApp", t.calls[0].args.getString("name"))
        assertEquals("abc", t.content!!.getJSONArray("parts").getJSONObject(1).getString("thoughtSignature"))
        assertTrue(ToolWire.parse(JSONObject("{}")).content == null)
        val r = ToolWire.responses(listOf("open_app" to ToolWire.result(true, "ok", 1)))
        assertEquals("open_app", r.getJSONArray("parts").getJSONObject(0).getJSONObject("functionResponse").getString("name"))
    }

    @Test fun flag_offByDefault_inBrainSection_oldAgentStays() {
        assertFalse(FeatureFlags.defaultOf(FeatureFlags.TOOL_AGENT))
        assertTrue(FeatureFlags.ready(FeatureFlags.TOOL_AGENT))
        val main = listOf(File("src/main/java/com/dk/assistant"), File("app/src/main/java/com/dk/assistant")).first { it.exists() }
        val brain = File(main, "SettingsBrain.kt").readText()
        assertTrue(brain.contains("toolAgentCard()") && brain.contains("FeatureFlags.TOOL_AGENT"))
        // "एक key से सब" वाले return से पहले - दोनों तरीकों में दिखे
        assertTrue(brain.indexOf("toolAgentCard()") < brain.indexOf("if (one) return"))
        val runner = File(main, "AgentRunner.kt").readText()
        assertTrue(runner.contains("ToolAgent.enabled(svc)") && runner.contains("if (handled) return"))
        assertTrue("पुराना loop बना रहे", runner.contains("while (step < MAX_STEPS)") && runner.contains("const val MAX_STEPS = 20"))
    }
}

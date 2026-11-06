package com.dk.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** नवंबर 2026 (Live जाँच #1-#5): कॉल-mode स्पीकर, हल्के tools, स्मार्ट VAD, caption का बचाव, एक हालत */
class LiveSmoothTest {
    private fun src(name: String): String = listOf(File("src/main/java/com/dk/assistant/$name"), File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()

    private fun decl(name: String, desc: String, vararg params: String, req: String? = null) = JSONObject().put("name", name)
        .put("description", desc)
        .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject().apply {
            params.forEach { put(it, JSONObject().put("type", "STRING")) }
        }).apply { if (req != null) put("required", JSONArray().put(req)) })

    private fun tools(): JSONArray = JSONArray()
        .put(JSONObject().put("functionDeclarations", JSONArray()
            .put(decl("phone_task", "Do things on the phone. Long details here.", "request", req = "request"))
            .put(decl("web_search", "Search the web.", "query", req = "query"))
            .put(decl("coding_task", "Write and fix code with the coding agent. Very long explanation follows here.", "task", "repo", req = "task"))
            .put(decl("build_website", "Build a website.", "prompt", req = "prompt"))
            .put(decl("reel_stats", "Reel counter stats."))))
        .put(JSONObject().put("googleSearch", JSONObject()))

    // ---------------------------------------------------------------- #2 हल्के tools
    @Test fun slim_keepsCore_routesRest() {
        val r = LiveToolSlim.apply(tools(), on = true)
        val d = r.tools.getJSONObject(0).getJSONArray("functionDeclarations")
        val names = (0 until d.length()).map { d.getJSONObject(it).getString("name") }
        assertEquals(listOf("phone_task", "web_search", LiveToolSlim.ROUTER), names)
        assertEquals(listOf("coding_task", "build_website", "reel_stats"), r.routed)
        assertTrue(r.tools.getJSONObject(1).has("googleSearch"))
        val router = d.getJSONObject(2)
        val desc = router.getString("description")
        assertTrue(desc.contains("coding_task(task, repo): Write and fix code with the coding agent."))
        assertFalse(desc.contains("Very long explanation"))
        val enumArr = router.getJSONObject("parameters").getJSONObject("properties").getJSONObject("tool").getJSONArray("enum")
        assertEquals(3, enumArr.length())
        assertEquals("prompt", LiveToolSlim.firstParams["build_website"])
    }

    @Test fun slim_off_unchanged() {
        val t = tools(); val before = t.toString()
        val r = LiveToolSlim.apply(t, on = false)
        assertEquals(before, r.tools.toString()); assertEquals(-1, r.kept)
    }

    @Test fun slim_inPlace_sameArray() {
        val t = tools()
        LiveToolSlim.slimInPlace(t, true)
        assertEquals(2, t.length())
        assertEquals(3, t.getJSONObject(0).getJSONArray("functionDeclarations").length())
    }

    @Test fun slim_realLiveTools_smaller() {
        // असली Live के सारे नाम: हर CORE नाम सच में Live में है (गलत नाम = tool गायब हो जाता)
        val setup = src("GeminiLiveSetup.kt")
        LiveToolSlim.CORE.forEach { assertTrue("CORE में अनजान tool: $it", setup.contains("\"name\", \"$it\"")) }
        assertTrue(setup.contains("LiveToolSlim.slimInPlace(tools, Prefs.bool(svc, LiveSmooth.PREF_SLIM_TOOLS, true))"))
    }

    @Test fun unwrap_router() {
        LiveToolSlim.apply(tools(), true)
        val (n, a) = LiveToolSlim.unwrap("dk_agent", JSONObject().put("tool", "coding_task").put("args_json", "{\"task\":\"fix bug\"}"))
        assertEquals("coding_task", n); assertEquals("fix bug", a.getString("task"))
        // JSON नहीं, सीधा वाक्य = पहला ज़रूरी parameter
        val (n2, a2) = LiveToolSlim.unwrap("dk_agent", JSONObject().put("tool", "build_website").put("args_json", "a cafe site"))
        assertEquals("build_website", n2); assertEquals("a cafe site", a2.getString("prompt"))
        // ```json ... ``` में लिपटा
        val (_, a3) = LiveToolSlim.unwrap("dk_agent", JSONObject().put("tool", "coding_task").put("args_json", "```json\n{\"task\":\"x\"}\n```"))
        assertEquals("x", a3.getString("task"))
        // args_json के बिना सीधी keys
        val (_, a4) = LiveToolSlim.unwrap("dk_agent", JSONObject().put("tool", "coding_task").put("task", "y"))
        assertEquals("y", a4.getString("task"))
        // router नहीं = जैसा था
        val o = JSONObject().put("request", "z")
        assertEquals("phone_task" to o, LiveToolSlim.unwrap("phone_task", o))
    }

    @Test fun tools_wiring_responseUsesCallName() {
        val t = src("GeminiLiveTools.kt")
        assertTrue(t.contains("LiveToolSlim.unwrap(callName"))
        assertTrue(t.contains("JSONObject().put(\"id\", id).put(\"name\", callName).put(\"response\""))
    }

    // ---------------------------------------------------------------- #1 कॉल-mode स्पीकर
    @Test fun callSpeaker_rules() {
        assertTrue(CallSpeakerRule.want(prefOn = true, bt = false, phoneCall = false))
        assertFalse(CallSpeakerRule.want(prefOn = false, bt = false, phoneCall = false))
        assertFalse(CallSpeakerRule.want(prefOn = true, bt = true, phoneCall = false))
        assertFalse(CallSpeakerRule.want(prefOn = true, bt = false, phoneCall = true))
        assertTrue(CallSpeakerRule.duplexOk(active = true, aecOn = true, disabledThisSession = false))
        assertFalse(CallSpeakerRule.duplexOk(active = true, aecOn = false, disabledThisSession = false))
        assertFalse(CallSpeakerRule.duplexOk(active = false, aecOn = true, disabledThisSession = false))
        assertFalse(CallSpeakerRule.duplexOk(active = true, aecOn = true, disabledThisSession = true))
    }

    @Test fun callSpeaker_usage() {
        assertEquals(CallVoice.USAGE_VOICE_COMMUNICATION, CallVoice.usage(callActive = false, bt = false, callSpeaker = true))
        // फ़ोन कॉल पहले (घंटी में media mute) - कॉल-mode स्पीकर उसे नहीं बदलता
        assertEquals(CallVoice.USAGE_ASSISTANCE_ACCESSIBILITY, CallVoice.usage(callActive = true, bt = false, callSpeaker = true))
        assertEquals(CallVoice.USAGE_MEDIA, CallVoice.usage(callActive = false, bt = false, callSpeaker = false))
        assertTrue(src("GeminiLivePlayer.kt").contains("btOn, CallSpeaker.active)"))
        assertTrue(src("GeminiLive.kt").contains("CallVoice.usage(callAudioOn, btOn, CallSpeaker.active)"))
        assertTrue(src("GeminiLive.kt").contains("CallSpeaker.release(svc)"))
    }

    @Test fun duplex_confirmsOnSustainedSpeechOnly() {
        val d = DuplexBarge()
        // 0.2s इंसानी आवाज़ + गूँज से ऊपर = रोको
        assertFalse(d.feed(0.9f, true, 40)); assertFalse(d.feed(0.9f, true, 40)); assertFalse(d.feed(0.9f, true, 40)); assertFalse(d.feed(0.9f, true, 40))
        assertTrue(d.feed(0.9f, true, 40))
        d.reset()
        // इंसानी आवाज़ पर गूँज से नीचे (DK की अपनी आवाज़) = नहीं
        repeat(20) { assertFalse(d.feed(0.95f, false, 40)) }
        // शोर (कम संभावना) = नहीं, और गिनती शून्य
        repeat(20) { assertFalse(d.feed(0.1f, true, 40)) }
        assertEquals(0L, d.speechMs)
        // VAD नहीं = कभी नहीं
        repeat(20) { assertFalse(d.feed(-1f, true, 40)) }
    }

    @Test fun duplex_disablesAfterMisses() {
        val d = DuplexBarge()
        d.onConfirm(0); assertFalse(d.tick(1_000)); d.onWords(); assertFalse(d.tick(5_000)); assertEquals(0, d.misses)
        d.onConfirm(10_000); assertFalse(d.tick(10_000 + DuplexBarge.WORDS_WAIT_MS)); assertEquals(1, d.misses)
        d.onConfirm(20_000); assertTrue(d.tick(20_000 + DuplexBarge.WORDS_WAIT_MS)); assertTrue(d.disabled)
        repeat(10) { assertFalse(d.feed(0.99f, true, 40)) }
        d.resetSession(); assertFalse(d.disabled)
    }

    @Test fun duplex_wiring() {
        val mic = src("GeminiLiveMic.kt")
        assertTrue(mic.contains("CallSpeakerRule.duplexOk(CallSpeaker.active, aecOn, duplex.disabled)"))
        assertTrue(mic.contains("duplex.feed(prob, overEchoMs >= 120, CHUNK_MS.toLong())"))
        assertTrue(src("GeminiLiveCaption.kt").contains("duplex.onWords()"))
        assertTrue(src("GeminiLiveServer.kt").contains("duplex.onWords()"))
    }

    // ---------------------------------------------------------------- #3 स्मार्ट VAD
    @Test fun vadFramer_windowsWithContext() {
        val f = VadFramer()
        val got = ArrayList<FloatArray>()
        val chunk = ShortArray(640) { (it + 1).toShort() }
        f.push(chunk, 640) { got += it }
        assertEquals(1, got.size)                        // 640 = 1 खिड़की (512) + 128 बचे
        assertEquals(576, got[0].size)
        assertEquals(0f, got[0][0])                      // पहली बार context शून्य
        assertEquals(1 / 32768f, got[0][64], 1e-9f)
        f.push(chunk, 640) { got += it }
        assertEquals(2, got.size)                        // 128 + 640 = 768 -> 1 खिड़की, 256 बचे
        // दूसरी खिड़की का context = पहली के आख़िरी 64 samples (449..512)
        assertEquals(449 / 32768f, got[1][0], 1e-9f)
        assertEquals(513 / 32768f, got[1][64], 1e-9f)
        repeat(3) { f.push(chunk, 640) { got += it } }
        assertEquals(6, got.size)                        // 5 x 640 = 3200 = 6 x 512 + 128
    }

    @Test fun vad_modelBundled() {
        val m = listOf(File("src/main/assets/vad/silero_vad.onnx"), File("app/src/main/assets/vad/silero_vad.onnx")).firstOrNull { it.exists() } ?: File("missing")
        assertTrue("Silero model assets में", m.exists() && m.length() > 1_000_000)
        assertTrue(src("GeminiLiveMic.kt").contains("turnDet.feed(rms, now, prob)"))
    }

    @Test fun turnDetector_vadBlocksNoiseStart() {
        val d = TurnDetector(40, endSilenceMs = 500)
        var t = 0L
        repeat(50) { d.feed(150.0, t, 0.02f); t += 40 }
        // तेज़ पर इंसानी नहीं (पंखा) = शुरू नहीं
        repeat(30) { assertEquals(TurnDetector.Event.NONE, d.feed(2000.0, t, 0.05f)); t += 40 }
        assertFalse(d.inSpeech); assertTrue(d.vadBlocked > 0)
        // इंसानी आवाज़ = शुरू
        var started = false
        repeat(5) { if (d.feed(2500.0, t, 0.9f) == TurnDetector.Event.SPEECH_START) started = true; t += 40 }
        assertTrue(started)
        // बात के बाद पंखा (तेज़, इंसानी नहीं) = चुप्पी = बात खत्म
        var ended = false
        repeat(20) { if (d.feed(2600.0, t, 0.05f) == TurnDetector.Event.SPEECH_END) ended = true; t += 40 }
        assertTrue(ended); assertTrue(d.vadSilenced > 0)
    }

    // ---------------------------------------------------------------- #4 caption का बचाव
    @Test fun heardPick() {
        val cap = HeardPick.pick(true, "cap", 10, true, "live", 20, 0, 30)
        assertEquals("caption", cap.src); assertTrue(cap.on)
        val live = HeardPick.pick(false, "", 0, true, "live text", 1_000, 500, 2_000)
        assertEquals("live", live.src); assertEquals("live text", live.text); assertTrue(live.on)
        // पिछली बात का पुराना लिखा = नहीं
        assertFalse(HeardPick.pick(false, "", 0, true, "old", 400, 500, 2_000).on)
        // बहुत पुराना
        assertFalse(HeardPick.pick(false, "", 0, true, "x", 1_000, 500, 1_000 + HeardPick.LIVE_FRESH_MS + 1).on)
        // सेटिंग बंद = पुराना (caption नहीं तो कुछ नहीं)
        assertFalse(HeardPick.pick(false, "", 0, false, "live", 1_000, 500, 2_000).on)
        val mic = src("GeminiLiveMic.kt")
        assertTrue(mic.contains("val heard = heardNow(now)"))
        assertTrue(src("GeminiLiveServer.kt").contains("liveHeard = u; liveHeardAt = System.currentTimeMillis()"))
    }

    // ---------------------------------------------------------------- #5 एक हालत
    @Test fun turnState_derive() {
        assertEquals(LiveTurnState.S.IDLE, LiveTurnState.derive(false, false, false, false, false))
        assertEquals(LiveTurnState.S.TOOL, LiveTurnState.derive(true, true, true, true, true))
        assertEquals(LiveTurnState.S.SPEAKING, LiveTurnState.derive(true, true, true, false, true))
        assertEquals(LiveTurnState.S.USER_SPEAKING, LiveTurnState.derive(true, false, true, false, true))
        assertEquals(LiveTurnState.S.THINKING, LiveTurnState.derive(true, false, false, false, true))
        assertEquals(LiveTurnState.S.LISTENING, LiveTurnState.derive(true, false, false, false, false))
    }

    @Test fun turnState_stuckOnceWithHistory() {
        val s = LiveTurnState()
        s.reset(0)
        assertNull(s.observe(LiveTurnState.S.LISTENING, 1_000))
        assertNull(s.observe(LiveTurnState.S.USER_SPEAKING, 3_000))
        assertNull(s.observe(LiveTurnState.S.THINKING, 5_000))
        assertNull(s.observe(LiveTurnState.S.THINKING, 5_000 + 11_000))
        val m = s.observe(LiveTurnState.S.THINKING, 5_000 + 12_500)
        assertNotNull(m)
        assertTrue(m!!, m.startsWith("अटका: सोच रहा 12s"))
        assertTrue(m, m.contains("आप बोल रहे 2.0s"))
        assertNull(s.observe(LiveTurnState.S.THINKING, 40_000))      // एक बार ही
        assertEquals("सोच रहा 1", s.stuckSummary())
        // सुन रहा = कभी अटका नहीं (उसकी कोई सीमा नहीं)
        assertNull(s.observe(LiveTurnState.S.LISTENING, 41_000)); assertNull(s.observe(LiveTurnState.S.LISTENING, 10_000_000))
    }

    @Test fun turnState_wired() {
        val mic = src("GeminiLiveMic.kt")
        assertTrue(mic.contains("turnState.observe(LiveTurnState.derive(ready, playing, turnDet.inSpeech, toolJobs.isNotEmpty(),"))
    }

    // ---------------------------------------------------------------- सेटिंग्स
    @Test fun settings_visible() {
        val v = src("SettingsVoice.kt")
        listOf("PREF_CALL_SPEAKER", "PREF_SLIM_TOOLS", "PREF_SMART_VAD", "PREF_HEARD_FALLBACK").forEach {
            assertTrue("सेटिंग्स में $it", v.contains("LiveSmooth.$it"))
        }
    }
}

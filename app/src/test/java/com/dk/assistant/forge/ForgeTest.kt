package com.dk.assistant.forge

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DK AI Part 14: Website Forge - SSE, HTML सफ़ाई/मरम्मत, पड़ाव पहचान, इरादा पहचान, मॉडल चुनाव */
class ForgeTest {

    private fun sse(vararg parts: Pair<String, Boolean>, finish: String? = null): String {
        val ps = JSONArray()
        parts.forEach { (t, thought) -> ps.put(JSONObject().put("text", t).apply { if (thought) put("thought", true) }) }
        val cand = JSONObject().put("content", JSONObject().put("parts", ps)).apply { finish?.let { put("finishReason", it) } }
        return "data: " + JSONObject().put("candidates", JSONArray().put(cand)).toString()
    }

    private fun page(bodyExtra: String = "", pad: Int = 2000) =
        "<!DOCTYPE html><html><head><title>Cafe</title></head><body><section id=\"hero\"><h1>Hi</h1>" +
            "x".repeat(pad) + "</section>$bodyExtra</body></html>"

    // ---- SSE ----
    @Test fun sse_text_multiPart_thoughtSkipped() {
        assertEquals("<html>", SseParser.extractText(sse("<html>" to false)))
        assertEquals("ab", SseParser.extractText(sse("a" to false, "b" to false)))
        assertEquals("real", SseParser.extractText(sse("thinking..." to true, "real" to false)))
    }

    @Test fun sse_nonDataLines_andDone() {
        assertEquals("", SseParser.extractText(""))
        assertEquals("", SseParser.extractText(": keep-alive"))
        assertEquals("", SseParser.extractText("data: [DONE]"))
        assertEquals("", SseParser.extractText("data: {not json"))
        assertNull(SseParser.payload("data: [DONE]"))
        assertNull(SseParser.payload("event: x"))
    }

    @Test fun sse_finishReason_andErrors() {
        assertEquals("MAX_TOKENS", SseParser.extractFinishReason(sse("x" to false, finish = "MAX_TOKENS")))
        assertNull(SseParser.extractFinishReason(sse("x" to false)))
        val err = """{"error":{"code":503,"status":"UNAVAILABLE","message":"The model is overloaded."}}"""
        assertEquals("503 UNAVAILABLE: The model is overloaded.", SseParser.extractError(err))
        assertEquals("503 UNAVAILABLE: The model is overloaded.", SseParser.extractError("data: $err"))
        assertNull(SseParser.extractError(sse("x" to false)))
    }

    @Test fun friendlyError_isHindi_andOverloadDetected() {
        assertTrue(SseParser.friendlyError(503, null).contains("व्यस्त"))
        assertTrue(SseParser.friendlyError(0, "UNAVAILABLE").contains("व्यस्त"))
        assertTrue(SseParser.friendlyError(429, null).contains("सीमा"))
        assertTrue(SseParser.friendlyError(403, null).contains("key"))
        assertTrue(SseParser.isOverloaded(503, null))
        assertTrue(SseParser.isOverloaded(0, "model OVERLOADED"))
        assertFalse(SseParser.isOverloaded(400, "bad"))
        // key/prompt कभी संदेश में नहीं
        assertFalse(SseParser.friendlyError(401, "API_KEY_INVALID AIzaSecret").contains("AIza"))
    }

    // ---- HTML ----
    @Test fun stripFences_andChatter() {
        val raw = "Sure! Here is your site:\n```html\n<!DOCTYPE html><html><body><div>x</div></body></html>\n```\nEnjoy"
        assertEquals("<!DOCTYPE html><html><body><div>x</div></body></html>", ForgeHtml.stripFences(raw))
        assertEquals("<html><body></body></html>", ForgeHtml.stripFences("```\n<html><body></body></html>\n```"))
    }

    @Test fun looksLikeHtml() {
        assertTrue(ForgeHtml.looksLikeHtml("<!doctype html><html><body>"))
        assertFalse(ForgeHtml.looksLikeHtml("I can't help with that."))
        assertFalse(ForgeHtml.looksLikeHtml(null))
    }

    @Test fun repairTruncated_closesOpenTags_andDropsHalfTag() {
        val r = ForgeHtml.repairTruncated("<html><head><style>a{}</style></head><body><div>hi</div><script>let a=1;<di")
        assertFalse(r.contains("<di\n") || r.endsWith("<di"))
        assertTrue(r.contains("</script>"))
        assertTrue(r.contains("</body>"))
        assertTrue(r.trimEnd().endsWith("</html>"))
        // पूरा पेज वैसा ही
        val ok = "<html><body><div>x</div></body></html>"
        assertEquals(ok, ForgeHtml.repairTruncated(ok))
    }

    @Test fun finalize_completeTruncatedAndJunk() {
        val full = ForgeHtml.finalize(page())
        assertNotNull(full); assertTrue(full!!.contains(ForgeHtml.SAFETY_ID))
        // छोटा और अधूरा = null (दोबारा कोशिश)
        assertNull(ForgeHtml.finalize("<html><body><div>short"))
        assertTrue(ForgeHtml.needsRetry("<html><body><div>short"))
        // लंबा पर अधूरा = मरम्मत
        val longCut = "<!DOCTYPE html><html><body><section><div>" + "y".repeat(ForgeHtml.MIN_TRUNCATED_OK) + "<sp"
        val fixed = ForgeHtml.finalize(longCut)
        assertNotNull(fixed); assertTrue(fixed!!.contains("</html>"))
        assertNull(ForgeHtml.finalize("Sorry, I can't do that."))
        assertFalse(ForgeHtml.needsRetry(page()))
    }

    @Test fun safetyNet_isIdempotent_andBeforeBody() {
        val once = ForgeHtml.withSafetyNet(page())
        assertEquals(once, ForgeHtml.withSafetyNet(once))
        assertTrue(once.indexOf(ForgeHtml.SAFETY_ID) < once.lowercase().lastIndexOf("</body>"))
    }

    // ---- पड़ाव ----
    @Test fun stage_progression() {
        assertEquals(ForgeStage.STRUCTURE, ForgeStageDetector.detect("<!DOCTYPE html><html><head><style>body{}"))
        assertEquals(ForgeStage.LAYOUT, ForgeStageDetector.detect("<html><head></head><body><div>"))
        assertEquals(ForgeStage.STYLING, ForgeStageDetector.detect("<html><body><header>Logo</header><section>"))
        val three = "<html><body><header></header><section id=\"hero\"></section><section id=\"menu\"></section>"
        assertEquals(ForgeStage.STYLING, ForgeStageDetector.detect(three))
        assertEquals(ForgeStage.CONTENT, ForgeStageDetector.detect("$three<img src=\"https://picsum.photos/400\">"))
        assertEquals(ForgeStage.CONTENT, ForgeStageDetector.detect("$three<canvas id=c></canvas>"))
        assertEquals(ForgeStage.MOTION, ForgeStageDetector.detect("$three<footer></footer><script>gsap.to()"))
        assertEquals(ForgeStage.FINISH, ForgeStageDetector.detect("$three</body></html>"))
    }

    @Test fun progress_staysInsideStage_andBelow100() {
        for (st in ForgeStage.entries) for (n in listOf(0, 5_000, 50_000, 500_000)) {
            val p = ForgeStageDetector.progress(st, n)
            assertTrue("$st $n -> $p", p in st.start..99)
        }
    }

    @Test fun sectionNames_fromIdHeadingOrIndex() {
        val h = "<section id=\"hero-banner\"><h1>Welcome</h1></section><section><h2>Our <b>Menu</b></h2></section><section><p>x</p></section>"
        assertEquals(listOf("Hero Banner", "Our Menu", "Section 3"), ForgeStageDetector.sectionNames(h))
        assertEquals(emptyList<String>(), ForgeStageDetector.sectionNames("<html><body>"))
    }

    // ---- इरादा ----
    @Test fun intent_matchesBuildRequests() {
        for (t in listOf("DK, ek cafe ki website banao", "मेरे लिए वेबसाइट बनाओ", "build a landing page for my gym",
                "Nashik ke nursery ke liye ek website bana do", "create a portfolio website for a photographer"))
            assertNotNull("पकड़ना था: $t", ForgeIntent.extract(t))
        assertEquals("build a landing page for my gym", ForgeIntent.extract("build a landing page for my gym."))
    }

    @Test fun intent_ignoresQuestionsCodingAndChat() {
        for (t in listOf("website kaise banate hain?", "kya tum website bana sakte ho", "coding agent website banao",
                "how to make a website", "aaj mausam kaisa hai", "website", "मेरी GitHub repo की website बनाओ",
                "WhatsApp kholo", "khana bana do"))
            assertNull("नहीं पकड़ना था: $t", ForgeIntent.extract(t))
    }

    // ---- मॉडल / request ----
    private fun m(name: String, vararg methods: String) =
        JSONObject().put("name", "models/$name").put("supportedGenerationMethods", JSONArray(methods.toList()))

    @Test fun chooseModel_prefersPlainFlash() {
        val list = JSONArray().put(m("gemini-2.5-flash-live", "bidiGenerateContent", "generateContent"))
            .put(m("gemini-2.5-flash-image", "generateContent"))
            .put(m("embedding-001", "embedContent"))
            .put(m("gemini-2.5-pro", "generateContent"))
            .put(m("gemini-2.5-flash", "generateContent"))
        assertEquals("gemini-2.5-flash", WebsiteForgeClient.chooseModel(list))
        assertEquals("gemini-2.5-pro", WebsiteForgeClient.chooseModel(JSONArray().put(m("gemini-2.5-pro", "generateContent"))))
        assertNull(WebsiteForgeClient.chooseModel(JSONArray().put(m("embedding-001", "embedContent"))))
    }

    @Test fun requestBody_thinkingOnlyFor25() {
        val a = WebsiteForgeClient.requestBody("gemini-2.5-flash", "hi").getJSONObject("generationConfig")
        assertEquals(0, a.getJSONObject("thinkingConfig").getInt("thinkingBudget"))
        val b = WebsiteForgeClient.requestBody("gemini-3-flash", "hi").getJSONObject("generationConfig")
        assertFalse(b.has("thinkingConfig"))
        val body = WebsiteForgeClient.requestBody("x", "hello")
        assertEquals("hello", body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
    }

    @Test fun systemPrompt_carriesUserRequest() {
        assertTrue(ForgeSystemPrompt.forNewSite("cafe in Nashik").contains("cafe in Nashik"))
        assertTrue(ForgeSystemPrompt.forEdit("<html></html>", "make it green").contains("make it green"))
        assertTrue(ForgeSystemPrompt.withRetryHint("x").endsWith(ForgeSystemPrompt.RETRY_HINT))
    }
}

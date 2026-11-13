package com.dk.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * 13 नवंबर 2026 (मालिक): चैट के जवाब में स्रोत - Gemini googleSearch का groundingMetadata पढ़कर
 * जिस वाक्य को स्रोत मिला उसके अंत में छोटे चिप; नीचे "स्रोत" सूची; खाली metadata = कुछ नहीं.
 */
class GroundCiteTest {
    private fun src(rel: String): String = listOf(File("src/main/java/com/dk/assistant/$rel"), File("app/src/main/java/com/dk/assistant/$rel"))
        .first { it.exists() }.readText()

    private val s1 = "दिल्ली में आज बारिश होगी।"
    private val s2 = " Hugging Face पर नया मॉडल आया।"
    private val raw = s1 + s2

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8).size

    private fun meta(): JSONObject = JSONObject()
        .put("groundingChunks", JSONArray()
            .put(JSONObject().put("web", JSONObject().put("uri", "https://vertexaisearch.cloud.google.com/grounding-api-redirect/a").put("title", "en.wikipedia.org")))
            .put(JSONObject().put("web", JSONObject().put("uri", "https://vertexaisearch.cloud.google.com/grounding-api-redirect/b").put("title", "imd.gov.in")))
            .put(JSONObject().put("web", JSONObject().put("uri", "https://vertexaisearch.cloud.google.com/grounding-api-redirect/c").put("title", "huggingface.co")))
            .put(JSONObject().put("web", JSONObject().put("uri", "https://vertexaisearch.cloud.google.com/grounding-api-redirect/d").put("title", "huggingface.co"))))
        .put("groundingSupports", JSONArray()
            .put(JSONObject().put("segment", JSONObject().put("startIndex", 0).put("endIndex", bytes(s1)).put("text", s1))
                .put("groundingChunkIndices", JSONArray().put(0).put(1)))
            .put(JSONObject().put("segment", JSONObject().put("startIndex", bytes(s1) + 1).put("endIndex", bytes(raw)).put("text", s2.trim()))
                .put("groundingChunkIndices", JSONArray().put(2).put(3))))
        .put("webSearchQueries", JSONArray().put("दिल्ली मौसम"))
        .put("searchEntryPoint", JSONObject().put("renderedContent", "<div class=\"chip\">दिल्ली मौसम</div>"))

    @Test fun utf8BytesToChars() {
        // हिंदी का हर अक्षर 3 बाइट - endIndex बाइट में है, अक्षर में नहीं
        assertEquals(s1.length, GroundCite.charIndex(raw, bytes(s1)))
        assertNotEquals(s1.length, bytes(s1))
        assertEquals(s1, GroundCite.sliceBytes(raw, 0, bytes(s1)))
        // बीच में कटा अक्षर = पिछली सीमा पर, टूटा अक्षर नहीं
        assertEquals("द", GroundCite.sliceBytes(raw, 0, 4))
        assertEquals("a😀b".indexOf('b'), GroundCite.charIndex("a😀b", 5))
    }

    @Test fun chipsAtSentenceEnd() {
        val g = GroundCite.parse(meta(), raw)!!
        assertEquals(listOf("दिल्ली मौसम"), g.queries)
        assertTrue(g.entryHtml.contains("chip"))
        val pts = GroundCite.points(raw, g)
        assertEquals(2, pts.size)
        assertEquals(s1.length, pts[0].first)                // "।" के ठीक बाद
        assertEquals(raw.length, pts[1].first)
        // कई स्रोत = कई चिप; वही वेबसाइट दो बार नहीं
        assertEquals(listOf("wikipedia", "imd"), GroundCite.chips(g, pts[0].second).map { it.first })
        assertEquals(listOf("huggingface"), GroundCite.chips(g, pts[1].second).map { it.first })
        // नीचे की सूची: दोहराए स्रोत एक बार
        assertEquals(3, GroundCite.unique(g).size)
    }

    @Test fun cleanedTextStillFindsSentence() {
        // दिखने वाला text कच्चे जवाब से अलग (शुरू में बुलेट) - तब वाक्य का आख़िरी हिस्सा ढूँढकर उसी के अंत में
        val g = GroundCite.parse(meta(), raw)!!
        val shown = "• $raw"
        val pts = GroundCite.points(shown, g)
        assertEquals(listOf(2 + s1.length, shown.length), pts.map { it.first })
    }

    @Test fun shortNames() {
        assertEquals("github", GroundCite.shortName("github.com", "https://github.com/x"))
        assertEquals("huggingface", GroundCite.shortName("huggingface.co", "https://huggingface.co/m"))
        assertEquals("wikipedia", GroundCite.shortName("en.wikipedia.org", "https://en.wikipedia.org/wiki/X"))
        assertEquals("imd", GroundCite.shortName("x", "https://www.imd.gov.in/page"))
    }

    @Test fun emptyMetadataNoChipsNoFakeLinks() {
        assertNull(GroundCite.parse(null, raw))
        assertNull(GroundCite.parse(JSONObject(), raw))
        assertNull(GroundCite.parse(JSONObject().put("groundingChunks", JSONArray().put(JSONObject().put("web", JSONObject().put("uri", "not-a-link")))), raw))
        assertTrue(GroundCite.points(raw, null).isEmpty())
        assertTrue(GroundCite.allChips(null).isEmpty())
    }

    @Test fun mergeJsonAndToolRejection() {
        val g = GroundCite.parse(meta(), raw)!!
        val m = GroundCite.merge(g, g)!!
        assertEquals(8, m.chunks.size)
        assertEquals(listOf(6, 7), m.cites.last().chunks)
        assertNull(GroundCite.merge(null, null))
        val back = GroundCite.fromJson(JSONObject(GroundCite.toJson(g).toString()))!!
        assertEquals(g, back)
        assertNull(GroundCite.fromJson(null))
        assertTrue(GroundCite.toolRejected(400, "Tool use with google_search is unsupported"))
        assertTrue(GroundCite.toolRejected(429, "Grounding quota exceeded"))
        assertFalse(GroundCite.toolRejected(429, "Resource exhausted"))
        assertFalse(GroundCite.toolRejected(500, "tool"))
    }

    @Test fun wiredInBothUisAndLive() {
        assertTrue(src("AiClients.kt").contains("put(\"googleSearch\", JSONObject())"))
        assertTrue(src("AiClients.kt").contains("GroundCite.toolRejected"))
        assertTrue(src("BrainManager.kt").contains("c.groundChat = true"))
        assertTrue(src("ChatActivity.kt").contains("GroundView.decorate("))
        assertTrue(src("dkai/DkaiScreen.kt").contains("GroundView.decorate("))
        assertTrue(src("VoiceActivity.kt").contains("DKState.lastGround"))
        assertTrue(src("GeminiLiveServer.kt").contains("GroundCite.parse(gm, \"\", live = true)"))
        assertTrue(src("GeminiLiveCaption.kt").contains("DKState.reply(out, src, g)"))
        assertTrue(src("GroundView.kt").contains("LinkMovementMethod"))
        // पुरानी एक-पट्टी (सारे स्रोत एक लाइन में) अब नहीं
        assertFalse(src("dkai/DkaiScreen.kt").contains("sites.joinToString(\", \")"))
    }
}

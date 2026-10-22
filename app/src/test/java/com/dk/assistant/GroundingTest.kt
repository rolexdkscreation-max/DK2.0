package com.dk.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.9: sources sirf tab jab sach me web search hua; search sirf zaroorat par */
class GroundingTest {

    /** REST generateContent + google_search: search hua (Gemini docs jaisa structure) */
    private val searched = """
    {"candidates":[{"content":{"parts":[{"text":"आज मुंबई में बारिश की संभावना है।"}]},
      "groundingMetadata":{
        "webSearchQueries":["mumbai weather today"],
        "groundingChunks":[
          {"web":{"uri":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AAA","title":"imd.gov.in"}},
          {"web":{"uri":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/BBB","title":"timesofindia.indiatimes.com"}},
          {"web":{"uri":"https://vertexaisearch.cloud.google.com/grounding-api-redirect/AAA","title":"imd.gov.in"}},
          {"web":{"uri":"https://www.accuweather.com/en/in/mumbai","title":"","domain":"accuweather.com"}}
        ],
        "groundingSupports":[{"segment":{"text":"आज मुंबई में बारिश"},"groundingChunkIndices":[0,1]}]
      }}]}
    """.trimIndent()

    /** Model ne apne gyaan se jawab diya (2+2) - koi groundingMetadata nahi */
    private val notSearched = """{"candidates":[{"content":{"parts":[{"text":"2 और 2 मिलाकर 4 होते हैं।"}]}}]}"""

    private fun meta(json: String) = JSONObject(json).getJSONArray("candidates").getJSONObject(0).optJSONObject("groundingMetadata")

    @Test fun searchedAnswerHasClickableSources() {
        val s = Grounding.sources(meta(searched))
        assertEquals(3, s.size)                                     // duplicate hata
        assertEquals("imd.gov.in", s[0].first)
        assertTrue(s.all { it.second.startsWith("https://") })
        assertEquals("accuweather.com", s[2].first)                 // title khali -> domain
        assertTrue(Grounding.grounded(meta(searched)))
    }

    @Test fun ownKnowledgeAnswerHasNoSources() {
        assertTrue(Grounding.sources(meta(notSearched)).isEmpty())
        assertFalse(Grounding.grounded(meta(notSearched)))
        // search query bani par koi page use nahi hua -> bhi koi "Sources" nahi
        val queriesOnly = JSONObject("""{"webSearchQueries":["x"],"groundingChunks":[]}""")
        assertFalse(Grounding.grounded(queriesOnly))
        assertFalse(Grounding.grounded(null))
    }

    @Test fun liveServerContentGrounding() {
        val msg = JSONObject("""{"serverContent":{"modelTurn":{"parts":[]},
            "groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://www.bbc.com/news/x","title":"BBC News"}}]}}}""")
        val s = Grounding.sources(msg.getJSONObject("serverContent").optJSONObject("groundingMetadata"))
        assertEquals(listOf("BBC News" to "https://www.bbc.com/news/x"), s)
        assertEquals("bbc.com", Grounding.host(s[0].second))
        val merged = Grounding.merge(s, listOf("bbc news" to "https://other", "NDTV" to "https://ndtv.com/a"))
        assertEquals(listOf("BBC News", "NDTV"), merged.map { it.first })
    }

    @Test fun maxFiveSourcesAndShortLabels() {
        val chunks = (1..9).joinToString(",") { """{"web":{"uri":"https://s$it.com/p","title":"Site $it"}}""" }
        assertEquals(5, Grounding.sources(JSONObject("""{"groundingChunks":[$chunks]}""")).size)
        assertTrue(Grounding.label("a".repeat(80)).length <= 32)
    }

    // ---------------- search kab: on-demand
    @Test fun timelessQuestionsDoNotSearch() {
        for (q in listOf("2+2 कितना होता है", "पाइथन में loop कैसे लिखें", "python में for loop कैसे लिखते हैं",
                "भारत की राजधानी क्या है", "photosynthesis समझाओ", "python में match statement क्या है",
                "एक कहानी सुनाओ", "न्यूटन का दूसरा नियम क्या है", "score variable का मतलब क्या है code में"))
            assertFalse(q, SearchPolicy.needsSearch(q))
    }

    @Test fun freshQuestionsSearch() {
        for (q in listOf("आज की ताज़ा खबर क्या है", "आज का मौसम कैसा है", "सोने का भाव क्या चल रहा है",
                "भारत और ऑस्ट्रेलिया मैच का स्कोर", "latest news", "पेट्रोल की कीमत दिल्ली में", "आज सेंसेक्स कितना है"))
            assertTrue(q, SearchPolicy.needsSearch(q))
    }

    @Test fun policyTextCoversRules() {
        val r = SearchPolicy.rules("[SEARCH]")
        assertTrue(r.contains("coding") && r.contains("maths") && r.contains("recent news") && r.contains("weather"))
        assertTrue(r.contains("never search for them"))
        assertTrue(r.contains("not sure your knowledge is still current"))
        assertTrue("पता न हो तो अंदाज़ा नहीं, सर्च", r.contains("do NOT guess"))
    }
}

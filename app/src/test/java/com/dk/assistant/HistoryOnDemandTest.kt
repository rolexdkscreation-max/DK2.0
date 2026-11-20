package com.dk.assistant

import java.time.LocalDateTime
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 20 नवंबर 2026: "पुरानी बातचीत सिर्फ़ माँगने पर" के pure नियम ([HistoryOnDemand]) */
class HistoryOnDemandTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, d, h, m).atZone(zone).toInstant().toEpochMilli()
    private val now = at(20, 18)

    // ---- टेस्ट 1: "कैसे हो DK" = सिर्फ़ यही (+ पूँछ), पुरानी बातचीत नहीं
    @Test fun onlyTailGoesNotOldConversation() {
        val turns = (1..30).map { HistoryOnDemand.Turn(if (it % 2 == 1) "u" else "d", "पुरानी बात नंबर $it", now - (31 - it) * 60_000L) }
        val t = HistoryOnDemand.tail(turns, now)
        assertTrue(t.contains("पुरानी बात नंबर 29") && t.contains("पुरानी बात नंबर 30"))
        assertFalse(t.contains("पुरानी बात नंबर 28"))
        assertEquals(2, t.lines().count { it.startsWith("User: ") || it.startsWith("DK: ") })
        assertEquals("", HistoryOnDemand.tail(turns, now, n = 0))                 // 0 = बिल्कुल कुछ नहीं
        assertEquals("", HistoryOnDemand.tail(turns, now + 2 * 3_600_000L))      // 2 घंटे पुरानी = नहीं
        assertTrue(HistoryOnDemand.tail(turns, now + 2 * 3_600_000L, reopened = true).isNotEmpty())   // सूची से खोली चैट
        assertEquals(2, HistoryOnDemand.HISTORY_TAIL)
    }

    @Test fun ruleSaysAnswerOnlyNewWordsAndToolOnlyWhenAsked() {
        assertTrue(HistoryOnDemand.RULE.contains("Answer ONLY the user's newest words"))
        assertTrue(HistoryOnDemand.RULE.contains("NEVER call search_history for normal questions"))
        assertTrue(HistoryOnDemand.TOOL_DESC.contains("इसे सिर्फ़ तब बुलाओ जब यूज़र पुरानी बातचीत के बारे में पूछे, जैसे कल क्या कहा था, पहले क्या बात हुई थी, याद है क्या। आम सवाल पर कभी मत बुलाओ।"))
    }

    // ---- ज़रूरी बातें: अधिकतम 20, ~1500 अक्षर
    @Test fun compactFactsCapped() {
        val facts = (1..60).map { "पक्की बात नंबर $it - यह थोड़ी लंबी लाइन है ताकि अक्षर गिने जाएँ और सीमा जाँची जाए" }
        val b = HistoryOnDemand.compactFacts(facts, listOf("सीखा हुआ तरीका"))
        assertTrue(b.length <= HistoryOnDemand.FACTS_CHARS + 120)
        assertTrue(b.lines().count { it.startsWith("- ") } <= HistoryOnDemand.FACTS_MAX)
        assertTrue(b.contains("नंबर 60"))                 // नई बातें पहले चुनी
        assertTrue(b.contains("more facts are saved on this phone"))
        assertEquals("", HistoryOnDemand.compactFacts(emptyList()))
        val small = HistoryOnDemand.compactFacts(listOf("नाम राहुल", "शहर नासिक"))
        assertTrue(small.indexOf("नाम राहुल") < small.indexOf("शहर नासिक"))
        assertFalse(small.contains("more facts"))
    }

    // ---- टेस्ट 2: 20 बातों के बाद context < 10k और लगातार न बढ़े (server की sliding window का नकली हिसाब)
    @Test fun contextStaysUnder10kAfter20Turns() {
        val fixed = 4_500L
        val c = HistoryOnDemand.tightCompression(fixed)
        assertTrue(c.trigger < HistoryOnDemand.CONTEXT_LIMIT && c.target < c.trigger && c.target > fixed)
        var ctx = fixed
        val seen = ArrayList<Long>()
        repeat(20) {
            ctx += 600                                   // एक बारी (सवाल की आवाज़ + जवाब) ≈ 600 token
            if (ctx > c.trigger) ctx = c.target          // server: trigger पार = target तक पुरानी बारियाँ हटीं
            seen.add(ctx)
        }
        assertTrue(seen.all { it < 10_000L })
        assertTrue(seen.takeLast(10).max() <= c.trigger)    // बढ़ता नहीं रहता
        val j = HistoryOnDemand.compressionJson(c)
        assertEquals(c.trigger, j.getLong("triggerTokens"))
        assertEquals(c.target, j.getJSONObject("slidingWindow").getLong("targetTokens"))
        // स्थिर हिस्सा न पता = अंदाज़ा; बहुत बड़ा = सीमा उससे ऊपर ही (निर्देश कभी नहीं छँटते)
        assertTrue(HistoryOnDemand.tightCompression(0).trigger <= 9_000L)
        assertTrue(HistoryOnDemand.tightCompression(12_000).trigger >= 14_000L)
    }

    @Test fun methodChoice() {
        assertEquals(HistoryOnDemand.Method.A_COMPRESSION, HistoryOnDemand.method(limitsAccepted = true, ineffective = false))
        assertEquals(HistoryOnDemand.Method.B_ROTATE, HistoryOnDemand.method(limitsAccepted = false, ineffective = false))
        assertEquals(HistoryOnDemand.Method.B_ROTATE, HistoryOnDemand.method(limitsAccepted = true, ineffective = true))
        assertFalse(HistoryOnDemand.ineffective(listOf(9_000L), 8_000L))
        assertTrue(HistoryOnDemand.ineffective(listOf(11_000L, 12_000L), 8_000L))
        assertFalse(HistoryOnDemand.ineffective(listOf(11_000L, 7_000L), 8_000L))
        assertFalse(HistoryOnDemand.rotateDue(HistoryOnDemand.Method.A_COMPRESSION, 50_000L, 50))
        assertTrue(HistoryOnDemand.rotateDue(HistoryOnDemand.Method.B_ROTATE, 9_000L, 1, fixed = 4_000L))
        assertTrue(HistoryOnDemand.rotateDue(HistoryOnDemand.Method.B_ROTATE, 3_000L, 10))
        assertFalse(HistoryOnDemand.rotateDue(HistoryOnDemand.Method.B_ROTATE, 6_000L, 3, fixed = 4_000L))
    }

    @Test fun toolDeclarationAndSwap() {
        val d = HistoryOnDemand.toolDeclaration()
        assertEquals("search_history", d.getString("name"))
        val props = d.getJSONObject("parameters").getJSONObject("properties")
        assertTrue(props.has("query") && props.has("from_date") && props.has("to_date") && props.has("limit"))
        val tools = JSONArray().put(JSONObject().put("functionDeclarations", JSONArray()
            .put(JSONObject().put("name", "phone_task")).put(JSONObject().put("name", "recall_conversation"))))
        assertTrue(HistoryOnDemand.swapRecallTool(tools))
        val names = (0 until 2).map { tools.getJSONObject(0).getJSONArray("functionDeclarations").getJSONObject(it).getString("name") }
        assertEquals(listOf("phone_task", "search_history"), names)
        assertFalse(HistoryOnDemand.swapRecallTool(tools))
    }

    // ---- खोज-शब्द: हर शब्द अलग (OR), prefix, छोटे-बड़े एक
    @Test fun ftsQueryOrAndCase() {
        assertEquals("मोबाइल* OR samsung*", HistoryOnDemand.ftsQuery(listOf("मोबाइल", "Samsung")))
        assertNull(HistoryOnDemand.ftsQuery(emptyList()))
        assertEquals("हफ्ते*", HistoryOnDemand.ftsQuery(listOf("हफ़्ते")))     // नुक़्ता हटा
    }

    @Test fun requestParsesTimeWhoAndWords() {
        val r = HistoryOnDemand.request("कल मैंने मोबाइल के बारे में क्या पूछा था", null, null, 0, now, zone)
        assertEquals(at(19, 0), r.from); assertTrue(r.to < at(20, 0))
        assertEquals("u", r.who); assertTrue("मोबाइल" in r.words); assertFalse("कल" in r.words)
        assertEquals(5, r.limit)
        val d = HistoryOnDemand.request("tumne kya kaha tha", "2026-11-10", "2026-11-12", 9, now, zone)
        assertEquals(at(10, 0), d.from); assertEquals(at(13, 0) - 1, d.to); assertEquals("d", d.who); assertEquals(5, d.limit)
        val last = HistoryOnDemand.request("पिछली बार क्या पूछा था", null, null, 0, now, zone)
        assertTrue(last.words.isEmpty()); assertEquals("u", last.who)
    }

    // ---- क्रम: मिलान फिर नयापन; याद वाले सवाल और अभी का सवाल नतीजों में नहीं
    @Test fun rankByMatchThenRecency() {
        val rows = listOf(
            HistoryOnDemand.Row(1, "u", "मुझे नया मोबाइल लेना है samsung वाला", at(19, 10)),
            HistoryOnDemand.Row(2, "d", "Samsung का S24 अच्छा है", at(19, 10, 1)),
            HistoryOnDemand.Row(3, "u", "आज मौसम कैसा है", at(19, 11)),
            HistoryOnDemand.Row(4, "u", "मोबाइल का कवर भी चाहिए", at(19, 12)),
            HistoryOnDemand.Row(5, "u", "कल मैंने मोबाइल के बारे में क्या पूछा था", at(20, 17)),
            HistoryOnDemand.Row(6, "d", "आपने मोबाइल के बारे में पूछा था", at(20, 17, 1)),
        )
        val req = HistoryOnDemand.Req(listOf("मोबाइल", "samsung"), 0, now, null, 5, "")
        val hits = HistoryOnDemand.rank(rows, req)
        assertEquals(listOf(1L, 4L, 2L), hits.map { it.id })   // 2 शब्द मिले सबसे ऊपर; फिर नई
        assertFalse(hits.any { it.id == 5L || it.id == 6L })
        val recent = HistoryOnDemand.rank(rows, HistoryOnDemand.Req(emptyList(), 0, now, "u", 2, ""), current = "आज मौसम कैसा है")
        assertEquals(listOf(4L, 1L), recent.map { it.id })
    }

    @Test fun replyFormat() {
        val long = "क".repeat(400)
        val r = JSONObject(HistoryOnDemand.reply(listOf(HistoryOnDemand.Row(1, "u", long, at(19, 10, 5))), HistoryOnDemand.Req(emptyList(), 0, now, null, 5, ""), zone))
        val o = r.getJSONArray("results").getJSONObject(0)
        assertEquals("2026-11-19 10:05", o.getString("when")); assertEquals("user", o.getString("who"))
        assertTrue(o.getString("text").length <= HistoryOnDemand.RESULT_CHARS + 1)
        val none = JSONObject(HistoryOnDemand.reply(emptyList(), HistoryOnDemand.Req(emptyList(), 0, now, null, 5, "कल"), zone))
        assertEquals(0, none.getInt("count")); assertTrue(none.getString("note").contains("NOTHING FOUND"))
    }

    // ---- टेस्ट 6: आम सवाल पर tool न चले - नियम में साफ़, और आम सवाल "याद वाला" नहीं पहचाना जाता
    @Test fun normalQuestionIsNotRecall() {
        for (q in listOf("कैसे हो DK", "आज मौसम कैसा है", "YouTube खोलो", "2 और 2 कितने होते हैं")) assertFalse(q, LocalRecall.isRecall(q))
        assertTrue(LocalRecall.isRecall("कल मैंने क्या पूछा था"))
    }

    @Test fun tokenEstimate() {
        assertEquals(25L, HistoryOnDemand.estimateTokens("a".repeat(100)))
        assertEquals(50L, HistoryOnDemand.estimateTokens("क".repeat(100)))
    }
}

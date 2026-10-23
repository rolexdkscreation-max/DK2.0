package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DK कंप्यूटर: योजना, प्रगति (animation पट्टी), आख़िरी जाँच, पढ़ने की क्षमता, लंबे पेज आगे पढ़ना */
class ComputerPlanTest {

    private val json = """```json
{"understood":"Nashik के 5 स्कूलों की फ़ीस की PDF","steps":["स्कूल खोजना","फ़ीस पढ़ना","तुलना करना","PDF बनाना"],"deliverable":"PDF रिपोर्ट"}
```"""

    @Test fun parsePlan_fromFencedJson() {
        val p = ComputerPlan.parse(json)!!
        assertEquals(4, p.total); assertEquals(1, p.current); assertEquals(0, p.doneCount)
        assertEquals("PDF रिपोर्ट", p.deliverable)
        assertNull(ComputerPlan.parse("{\"steps\":[]}"))
        assertNull(ComputerPlan.parse("मैं योजना नहीं बना सका"))
        assertEquals(ComputerPlan.MAX_STEPS, ComputerPlan.parse("{\"steps\":[" + (1..30).joinToString { "\"s$it\"" } + "]}")!!.total)
    }

    @Test fun advance_marksEarlierStepsDone_andFraction() {
        var p = ComputerPlan.parse(json)!!
        p = ComputerPlan.advance(p, 3)
        assertEquals(3, p.current); assertEquals(setOf(1, 2), p.done); assertEquals(0.5f, p.fraction, 0.001f)
        assertTrue(ComputerPlan.advance(p, 3) === p)                    // बदला नहीं = वही (फ़ालतू redraw नहीं)
        assertTrue(ComputerPlan.advance(p, null) === p)
        assertEquals(4, ComputerPlan.advance(p, 99).current)            // सीमा से बाहर = आख़िरी कदम
        val f = ComputerPlan.finishAll(p)
        assertTrue(f.finished); assertEquals(1f, f.fraction, 0.001f)
    }

    @Test fun revise_keepsDoneSteps() {
        val p = ComputerPlan.advance(ComputerPlan.parse(json)!!, 3)
        val r = ComputerPlan.revise(p, listOf("स्कूल खोजना", "फ़ीस पढ़ना", "सरकारी साइट देखना", "तुलना", "PDF"))
        assertEquals(5, r.total); assertEquals(setOf(1, 2), r.done); assertEquals(3, r.current)
        assertTrue(ComputerPlan.revise(p, listOf(" ", "")) === p)
    }

    @Test fun checklist_and_header_forUser() {
        val p = ComputerPlan.advance(ComputerPlan.parse(json)!!, 2)
        val c = ComputerPlan.checklist(p)
        assertTrue(c, c.startsWith("📋 योजना") && c.contains("✍ मैंने समझा: Nashik"))
        assertTrue(c, c.contains("✓ 1. स्कूल खोजना") && c.contains("▶ 2. फ़ीस पढ़ना") && c.contains("○ 3. तुलना करना"))
        assertTrue(c, c.contains("🎁 आपको मिलेगा: PDF रिपोर्ट"))
        assertFalse(ComputerPlan.checklist(p, title = false).contains("📋"))
        val h = ComputerPlan.header(p, "🔎 खोज: nashik school fees")
        assertTrue(h, h.startsWith("कदम 2/4: फ़ीस पढ़ना") && h.contains("🔎 खोज"))
        assertEquals("सोच रहा हूँ…", ComputerPlan.header(null, ""))
        assertEquals("🌐 पढ़ रहा हूँ", ComputerPlan.header(null, "🌐 पढ़ रहा हूँ\nदूसरी लाइन"))
        val ai = ComputerPlan.forAi(p)
        assertTrue(ai, ai.contains("\"step\":N") && ai.contains("1. स्कूल खोजना (done)") && ai.contains("DELIVERABLE: PDF रिपोर्ट"))
    }

    @Test fun verdict_parsing_isSafe() {
        assertTrue(ComputerPlan.verdict("{\"ok\":true,\"missing\":[]}").ok)
        val v = ComputerPlan.verdict("जाँच: {\"ok\":false,\"missing\":[\"स्रोत के लिंक नहीं\",\"PDF नहीं बनी\"]}")
        assertFalse(v.ok); assertEquals(2, v.missing.size)
        assertTrue(ComputerPlan.verdict("कुछ भी").ok)                        // न पढ़ पाए = पास (काम न अटके)
        assertTrue(ComputerPlan.verdict("{\"ok\":false,\"missing\":[]}").ok) // कमी बताई ही नहीं = पास
        assertFalse(ComputerPlan.verdict("{\"missing\":[\"x\"]}").ok)
    }

    @Test fun needsVerify_onlyForRealWork_andLimitedRounds() {
        val p = ComputerPlan.parse(json)!!
        assertTrue(ComputerPlan.needsVerify(p, 0, 0))
        assertTrue(ComputerPlan.needsVerify(null, 2, 1))
        assertFalse(ComputerPlan.needsVerify(null, 0, 0))                  // सीधी बातचीत
        assertFalse(ComputerPlan.needsVerify(ComputerPlan.parse("{\"steps\":[\"नमस्ते का जवाब\"]}"), 0, 0))
        assertFalse(ComputerPlan.needsVerify(p, 5, ComputerPlan.VERIFY_ROUNDS))
    }

    @Test fun capacity_scalesWithModel() {
        assertEquals(7_000, ComputerPlan.resultCap(60_000))
        assertEquals(20_000, ComputerPlan.resultCap(500_000))
        assertEquals(70_000, ComputerPlan.compactAt(60_000))
        assertEquals(350_000, ComputerPlan.compactAt(500_000))
        assertEquals(2_500, ComputerPlan.keepOld(500_000))
    }

    @Test fun parse_readsStepNumber_andKeepsArgsClean() {
        val c = ComputerAgent.parse("{\"thought\":\"खोज\",\"step\":2,\"tool\":\"web_search\",\"args\":{\"query\":\"x\"}}")!!
        assertEquals(2, c.step); assertEquals("x", c.args.optString("query"))
        val t = ComputerAgent.parse("{\"tool\":\"read_url\",\"step\":3,\"url\":\"https://a.com\"}")!!
        assertEquals(3, t.step); assertFalse(t.args.has("step")); assertEquals("https://a.com", t.args.optString("url"))
        assertNull(ComputerAgent.parse("{\"tool\":\"done\",\"args\":{\"answer\":\"ok\"}}")!!.step)
    }

    @Test fun slice_longPage_tellsHowToContinue() {
        val text = "a".repeat(100) + "b".repeat(50)
        val first = ComputerTools.slice("https://x.com", text, 0, 100)
        assertTrue(first, first.contains("बाकी 50 अक्षर") && first.contains("\"from\":100"))
        val rest = ComputerTools.slice("https://x.com", text, 100, 100)
        assertTrue(rest, rest.contains("(अक्षर 100 से)") && rest.endsWith("b".repeat(50)))
        assertFalse(rest.contains("बाकी"))
        assertTrue(ComputerTools.slice("u", "abc", 999, 10).startsWith("URL: u"))
    }
}

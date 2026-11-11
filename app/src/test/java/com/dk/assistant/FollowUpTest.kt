package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** नवंबर 2026 (मालिक): "आगे क्या करें" के बटन + काम के बीच भेजी बात (साथ में / बाद में) - Coding Agent और DK कंप्यूटर */
class FollowUpTest {

    private fun u(questions: List<String> = emptyList(), choices: List<String> = emptyList(), answer: String = "") =
        SevenPlus.Understanding(Seven.Kind.FEATURE, "b", "समझा", "spec", emptyList(), emptyList(), questions, emptyList(), answer, choices = choices)

    // ---------------------------------------------------------------- साथ में / बाद में

    @Test fun sameThingGoesIntoRunningTask() {
        assertEquals(FollowUp.Route.NOW, FollowUp.route("Coding Agent में suggestion बटन जोड़ो", "बटन का रंग नीला रखना"))
        assertEquals(FollowUp.Route.NOW, FollowUp.route("login screen ठीक करो", "इसमें dark mode भी डालो"))
        assertEquals(FollowUp.Route.NOW, FollowUp.route("EV market report बनाओ", "also add a price table"))
        assertEquals(FollowUp.Route.NOW, FollowUp.route("होम स्क्रीन बदलो", "साथ में आइकन बड़ा करो"))
    }

    @Test fun differentThingWaitsForNext() {
        assertEquals(FollowUp.Route.NEXT, FollowUp.route("Coding Agent में suggestion बटन जोड़ो", "मौसम बताओ"))
        assertEquals(FollowUp.Route.NEXT, FollowUp.route("PDF report on EV market India", "what is the capital of France"))
    }

    @Test fun saidLaterMeansNextEvenIfRelated() {
        assertEquals(FollowUp.Route.NEXT, FollowUp.route("बटन जोड़ो", "इसके बाद बटन का test भी लिखना"))
        assertEquals(FollowUp.Route.NEXT, FollowUp.route("report बनाओ", "बाद में report की PDF भी बना देना"))
        assertEquals(FollowUp.Route.NEXT, FollowUp.route("fix login", "after this, fix login logo too"))
    }

    @Test fun nothingRunningOrEmptyIsNext() {
        assertEquals(FollowUp.Route.NEXT, FollowUp.route(null, "बटन लाल करो"))
        assertEquals(FollowUp.Route.NEXT, FollowUp.route("बटन", " "))
    }

    @Test fun commonWordsAloneDoNotJoin() {
        // "करो / काम / ऐप" जैसे आम शब्द मिलना = एक ही काम नहीं
        assertEquals(FollowUp.Route.NEXT, FollowUp.route("ऐप का काम करो", "ऐप का गाना चलाओ करो"))
    }

    // ---------------------------------------------------------------- inbox

    @Test fun inboxAddDrainTake() {
        val i = FollowUp.Inbox()
        val a = i.add("एक"); val b = i.add("दो")
        assertEquals(2, i.size)
        assertTrue(i.has(a))
        assertEquals("एक", i.take(a))
        assertNull("दोबारा नहीं निकलेगा", i.take(a))
        assertEquals(listOf("दो"), i.drain())
        assertEquals(0, i.size)
        assertNull("उठ चुकी बात अब पलट नहीं सकती", i.take(b))
    }

    @Test fun leftoverBecomesNextTaskWithContext() {
        val t = FollowUp.leftover("बटन जोड़ो\nदूसरी लाइन", "रंग नीला")
        assertTrue(t, t.contains("बटन जोड़ो") && t.contains("रंग नीला") && !t.contains("दूसरी लाइन"))
        assertTrue(FollowUp.forAi(listOf("रंग नीला")).contains("SAME task"))
    }

    // ---------------------------------------------------------------- बटन

    @Test fun nextTasksGetAllAtOnceAndOneByOne() {
        val c = FollowUp.forDone(listOf("PDF बनाओ", "तालिका जोड़ो", "PDF बनाओ"))
        val labels = c.map { it.label }
        assertEquals(listOf("PDF बनाओ", "तालिका जोड़ो", "⚡ सब एक साथ करो", "🪜 एक-एक करके करो", FollowUp.CUSTOM.label), labels)
        assertTrue(c[2].text.contains("PDF बनाओ") && c[2].text.contains("तालिका जोड़ो"))
        assertTrue(c[3].text.contains("1. PDF बनाओ") && c[3].text.contains("2. तालिका जोड़ो"))
        assertEquals(FollowUp.Act.TYPE, c.last().act)
        assertTrue("सुझाव नहीं = कोई बटन नहीं", FollowUp.forDone(emptyList()).isEmpty())
    }

    @Test fun questionAnswersArePickOneOnly() {
        val c = FollowUp.forQuestion(listOf("हिंदी में", "English में"))
        assertEquals(listOf("हिंदी में", "English में", FollowUp.CUSTOM.label), c.map { it.label })
        assertFalse(c.any { it.label.contains("सब एक साथ") })
    }

    @Test fun longLabelIsShortButSendsFullText() {
        val long = "क".repeat(120)
        val c = FollowUp.fromAi(listOf(long), tasks = false).single()
        assertTrue(c.label.length <= FollowUp.LABEL_MAX && c.label.endsWith("…"))
        assertEquals(long, c.text)
    }

    @Test fun understandingChipsFitTheApprovalRules() {
        val c = FollowUp.forUnderstanding(emptyList(), listOf("बटन का रंग नीला"))
        assertEquals(SevenPlus.Reply.YES, SevenPlus.reply(c.first().text))
        assertEquals(SevenPlus.Reply.NO, SevenPlus.reply(c.last().text))
        assertEquals("बदलाव = सुधार (दोबारा समझो)", SevenPlus.Reply.OTHER, SevenPlus.reply(c[1].text))
        assertTrue(c.any { it.act == FollowUp.Act.TYPE })
        val q = FollowUp.forUnderstanding(listOf("कौन-सा रंग?"), listOf("नीला", "लाल"))
        assertEquals(listOf("नीला", "लाल"), q.filter { it.act == FollowUp.Act.SEND }.map { it.text })
    }

    @Test fun coderChipsByState() {
        assertEquals("हाँ", FollowUp.coder(u(), prOpen = true, lastRole = "d", canResume = false).first().text)
        assertTrue("सिर्फ़ जवाब = कोई मंज़ूरी नहीं", FollowUp.coder(u(answer = "जवाब"), false, "d", false).isEmpty())
        val pr = FollowUp.coder(null, prOpen = true, lastRole = "d", canResume = false)
        assertEquals(true, SafePr.mergeIntent(pr[0].text))
        assertEquals(false, SafePr.mergeIntent(pr[1].text))
        val stuck = FollowUp.coder(null, prOpen = false, lastRole = "e", canResume = true)
        assertTrue(CoderHistory.Logic.isContinue(stuck.first().text))
        assertTrue(FollowUp.coder(null, false, "e", canResume = false).isEmpty())
        assertTrue(FollowUp.coder(null, false, "d", false).isEmpty())
    }

    @Test fun understandingKeepsChoicesFromAi() {
        val o = Seven.json("""{"understood":"x","spec":"s","questions":["रंग?"],"choices":["नीला","लाल"]}""")
        val p = SevenPlus.Understanding.parse(o, Seven.Kind.FEATURE, "b", emptyList())
        assertEquals(listOf("नीला", "लाल"), p.choices)
        assertTrue(Seven.Prompts.PROMPT.contains("\"choices\""))
    }

    // ---------------------------------------------------------------- जुड़ाव (असली रास्तों में)

    private fun src(n: String) = File("src/main/java/com/dk/assistant/$n").readText()

    @Test fun wiredIntoBothAgents() {
        val ca = src("ComputerAgent.kt")
        assertTrue(ca.contains("ComputerSession.inbox.drain()") && ca.contains("FollowUp.route(runningTask, task)"))
        assertTrue(ca.contains("\"options\":[") && ca.contains("\"next\":["))
        assertTrue(src("SevenTeam.kt").contains("if (!takeAdded()) return finish(false, lastBlock)"))
        assertTrue(src("SevenRunner.kt").contains("team.inbox = CoderSession.inbox"))
        assertTrue(src("CodingAgent.kt").contains("inbox?.drain()"))
        assertTrue(src("CodingActivity.kt").contains("CoderSession.followUp(this, typed"))
        assertTrue(src("CodingActivity.kt").contains("ChoiceBar(this)") && src("ComputerActivity.kt").contains("ChoiceBar(this)"))
    }
}

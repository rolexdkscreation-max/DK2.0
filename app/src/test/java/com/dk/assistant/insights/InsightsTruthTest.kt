package com.dk.assistant.insights

import com.dk.assistant.TaskStatus
import com.dk.assistant.agent.core.Box
import com.dk.assistant.agent.core.DriverResult
import com.dk.assistant.agent.core.ScreenDriver
import com.dk.assistant.agent.core.ScreenObservation
import com.dk.assistant.agent.core.UiElement
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** नवंबर 2026: सोशल रिव्यू में DK झूठ न बोले ("बैकग्राउंड में कर रहा हूँ") और हर कदम स्क्रीन पर दिखे */
class InsightsTruthTest {
    private fun src(n: String): String {
        val p = "src/main/java/com/dk/assistant/$n"
        return listOf(File(p), File("app/$p")).first { it.exists() }.readText()
    }

    // ------------------------------------------------ झूठा "हाँ, कर रहा हूँ" = फ़ोन खुद सच में शुरू करे
    @Test fun saidYes_withoutTool_phoneStartsReviewItself() {
        assertEquals(InsightsTruth.Fix.START,
            InsightsTruth.check("मेरे इंस्टाग्राम का रिव्यू करो", "हाँ, बैकग्राउंड में रिव्यू कर रहा हूँ", toolRecent = false, running = false))
        assertEquals(InsightsTruth.Fix.START,
            InsightsTruth.check("यूट्यूब चैनल का एनालिसिस करो", "ठीक है, कर रही हूँ", toolRecent = false, running = false))
    }

    @Test fun urduCaption_isRecognisedToo() {
        assertNotNull(InsightsVoice.parse("میرے انسٹاگرام کا ریویو کرو"))
        assertEquals(listOf(Platform.FACEBOOK), InsightsVoice.parse("فیس بک کا ریویو کرو")!!.platforms)
        assertEquals(InsightsTruth.Fix.START,
            InsightsTruth.check("یوٹیوب کا ریویو کرو", "ہاں، بیک گراؤنڈ میں ریویو کر رہا ہوں", toolRecent = false, running = false))
    }

    @Test fun userAsksStatus_nothingRunning_dkMustNotSayYes() {
        assertEquals(InsightsTruth.Fix.NOT_RUNNING,
            InsightsTruth.check("इंस्टाग्राम का रिव्यू कर रहे हो?", "हाँ, रिव्यू कर रहा हूँ", toolRecent = false, running = false))
        assertTrue(InsightsTruth.notRunningNote().contains("NO social media review is running"))
    }

    @Test fun running_butSaysBackground_isCorrected() {
        assertEquals(InsightsTruth.Fix.ON_SCREEN,
            InsightsTruth.check("रिव्यू कहाँ तक पहुँचा", "रिव्यू बैकग्राउंड में चल रहा है", toolRecent = true, running = true))
        val n = InsightsTruth.onScreenNote("इंस्टाग्राम • कदम 3: \"Professional dashboard\" दबा रही हूँ")
        assertTrue(n.contains("ON THE SCREEN")); assertTrue(n.contains("Professional dashboard"))
    }

    @Test fun honestAnswers_areLeftAlone() {
        // tool सच में चला, सही बात कही
        assertEquals(InsightsTruth.Fix.NONE,
            InsightsTruth.check("इंस्टाग्राम का रिव्यू करो", "ठीक है, आपके सामने इंस्टाग्राम खोलकर रिव्यू कर रही हूँ", toolRecent = true, running = true))
        // DK ने पूछा कौन-सा प्लेटफ़ॉर्म - अभी हाँ नहीं
        assertEquals(InsightsTruth.Fix.NONE,
            InsightsTruth.check("मेरा सोशल मीडिया रिव्यू करो", "किस प्लेटफ़ॉर्म का रिव्यू करूँ?", toolRecent = false, running = false))
        // मना किया
        assertEquals(InsightsTruth.Fix.NONE,
            InsightsTruth.check("इंस्टाग्राम का रिव्यू करो", "माफ़ कीजिए, अभी नहीं कर सकती", toolRecent = false, running = false))
        // रिव्यू की बात ही नहीं
        assertEquals(InsightsTruth.Fix.NONE, InsightsTruth.check("गाना चलाओ", "हाँ, चला रहा हूँ", toolRecent = false, running = false))
        assertEquals(InsightsTruth.Fix.NONE, InsightsTruth.check("इंस्टाग्राम खोलो", "खोल रहा हूँ", toolRecent = false, running = false))
    }

    // ------------------------------------------------ tool का जवाब: जो सच में हुआ वही
    @Test fun toolReply_onlyClaimsStartedWhenAppReallyOpened() {
        assertEquals(InsightsTruth.Proof.OPENED, InsightsTruth.decide(true, false, false, false, false, ""))
        assertEquals(InsightsTruth.Proof.LOCKED, InsightsTruth.decide(false, false, true, false, false, ""))
        assertEquals(InsightsTruth.Proof.NO_APP, InsightsTruth.decide(false, false, false, true, false, "इंस्टाग्राम ऐप फ़ोन में नहीं मिला"))
        assertEquals(InsightsTruth.Proof.FAILED, InsightsTruth.decide(false, false, false, true, false, "ऐप नहीं खुला"))
        assertEquals(InsightsTruth.Proof.SLOW, InsightsTruth.decide(false, false, false, false, false, ""))
        assertEquals(InsightsTruth.Proof.API, InsightsTruth.decide(false, true, false, false, false, ""))

        val ok = InsightsTruth.toolReply(InsightsTruth.Proof.OPENED, "ठीक है", "इंस्टाग्राम", "")
        assertTrue(ok.startsWith("STARTED ON SCREEN")); assertTrue(ok.contains("इंस्टाग्राम"))
        for (p in listOf(InsightsTruth.Proof.LOCKED, InsightsTruth.Proof.NO_APP, InsightsTruth.Proof.FAILED, InsightsTruth.Proof.SLOW)) {
            val r = InsightsTruth.toolReply(p, "ठीक है", "", "x")
            assertFalse("$p ने STARTED ON SCREEN कहा", r.startsWith("STARTED ON SCREEN"))
        }
        assertTrue(InsightsTruth.toolReply(InsightsTruth.Proof.LOCKED, "", "", "").contains("Do NOT say you are reviewing"))
        InsightsTruth.Proof.values().forEach { p ->
            val r = InsightsTruth.toolReply(p, "", "", "")
            assertFalse("$p: background को हाँ नहीं", r.contains("in the background.") && !r.contains("Never say"))
        }
    }

    // ------------------------------------------------ स्क्रीन पर हर कदम + कहाँ दबाया
    private class FakeDriver(val screens: List<ScreenObservation>) : ScreenDriver {
        var i = 0
        override suspend fun capture() = screens[i.coerceAtMost(screens.size - 1)]
        override suspend fun click(obs: ScreenObservation, e: UiElement): DriverResult { i++; return DriverResult.OK }
        override suspend fun tapAt(x: Int, y: Int) = DriverResult.OK
        override suspend fun setText(obs: ScreenObservation, e: UiElement, text: String) = DriverResult.UNSUPPORTED
        override suspend fun scroll(down: Boolean): DriverResult { i++; return DriverResult.OK }
        override suspend fun back(): DriverResult = DriverResult.OK
        override suspend fun pause(ms: Long) {}
    }

    @Test fun everyStep_isShownOnScreen_withWhereItTaps() = runBlocking {
        val s1 = ScreenObservation(1, "com.instagram.android", listOf(
            UiElement(1, text = "Profile", clickable = true, bounds = Box(0, 0, 1080, 90)),
            UiElement(2, text = "Professional dashboard", clickable = true, bounds = Box(100, 500, 900, 600))))
        val s2 = ScreenObservation(2, "com.instagram.android", listOf(UiElement(1, text = "Views 12.3K")))
        val replies = ArrayDeque(listOf(
            """{"account":null,"posts":[],"actions":[{"do":"click","id":2}],"done":false,"why":"open dashboard","say":"प्रोफ़ेशनल डैशबोर्ड खोल रही हूँ"}""",
            """{"account":{"views":"12.3K"},"posts":[],"actions":[{"do":"scroll","dir":"down"}],"done":false,"say":"व्यूज़ पढ़ रही हूँ"}""",
            """{"posts":[],"actions":[],"done":true}""",
        ))
        val steps = mutableListOf<String>()
        val marks = mutableListOf<Pair<Int, Int>>()
        val prompts = mutableListOf<String>()
        ScreenCollector(FakeDriver(listOf(s1, s2)), { true }, { true }, { p -> prompts += p; replies.removeFirstOrNull() },
            progress = { steps += it }, mark = { x, y -> marks += x to y }).collect(Platform.INSTAGRAM)
        assertTrue(steps.joinToString("\n"), steps.any { it.contains("इंस्टाग्राम ऐप आपके सामने खोल रही हूँ") })
        assertTrue(steps.joinToString("\n"), steps.any { it.contains("कदम 1") && it.contains("\"Professional dashboard\" दबा रही हूँ") })
        assertTrue(steps.joinToString("\n"), steps.any { it.contains("प्रोफ़ेशनल डैशबोर्ड खोल रही हूँ") })
        assertTrue(steps.joinToString("\n"), steps.any { it.contains("नीचे स्क्रॉल") })
        assertEquals(listOf(500 to 550), marks)
        assertTrue(prompts[0].contains("\"say\""))
    }

    @Test fun actHi_describesTheRealAction() {
        val o = ScreenObservation(1, "x", listOf(UiElement(7, desc = "View insights", bounds = Box(0, 0, 200, 100))))
        assertEquals("\"View insights\" दबा रही हूँ", InsightsPlan.actHi(InsightsPlan.Act("click", id = 7), o))
        assertEquals("वापस पिछली स्क्रीन पर जा रही हूँ", InsightsPlan.actHi(InsightsPlan.Act("back"), o))
        assertEquals(100 to 50, InsightsPlan.point(InsightsPlan.Act("click", id = 7), o))
        assertEquals(null, InsightsPlan.point(InsightsPlan.Act("scroll"), o))
        assertEquals("खोल रही", InsightsPlan.parseReply("""{"actions":[],"say":"खोल रही"}""")!!.say)
        assertEquals("", InsightsPlan.parseReply("""{"actions":[]}""")!!.say)
    }

    // ------------------------------------------------ status कभी "background" नहीं
    @Test fun taskStatus_callsSocialReviewOnScreen() {
        TaskStatus.clear()
        TaskStatus.start(InsightsAgent.KEY, "सोशल रिव्यू", "DK social review (on screen)", "इंस्टाग्राम रिव्यू")
        TaskStatus.step(InsightsAgent.KEY, "इंस्टाग्राम • कदम 2: \"Insights\" दबा रही हूँ")
        val s = TaskStatus.summary()
        assertTrue(s.contains("ON THE PHONE SCREEN")); assertTrue(s.contains("\"Insights\" दबा रही हूँ"))
        val j = TaskStatus.get(InsightsAgent.KEY)!!
        TaskStatus.end(InsightsAgent.KEY, TaskStatus.State.FAILED, "ऐप नहीं खुला")
        val note = TaskStatus.endNote(TaskStatus.get(InsightsAgent.KEY)!!)
        assertTrue(note.contains("on-screen task")); assertFalse(note.contains("background task"))
        assertTrue(j.active)
        TaskStatus.clear()
    }

    @Test fun wiring_liveChecksRunBeforeSearchGuess() {
        val sv = src("GeminiLiveServer.kt")
        assertTrue(sv.contains("!socialCheck() && !agentCheck()) promiseCheck()"))
        val ch = src("GeminiLiveChecks.kt")
        assertTrue(ch.contains("internal fun GeminiLive.socialCheck()"))
        assertTrue(ch.contains("ia.liveTool(svc"))
        val a = src("insights/InsightsAgent.kt")
        assertTrue(a.contains("InsightsTruth.decide("))
        assertTrue(a.contains("mark = { x, y -> com.dk.assistant.WorkAwake.mark(x, y) }"))
        assertTrue(src("WorkAwake.kt").contains("fun mark(x: Int, y: Int)"))
        assertTrue(src("GeminiLiveSetup.kt").contains("ALWAYS call this tool before saying you are reviewing"))
    }
}

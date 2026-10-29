package com.dk.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v3.5+: बीच में बोलना ("रुको" = रुको, बाकी = जवाब के बाद) + एजेंट को सच में काम देना */
class LiveQueueAgentTest {

    // ------------------------------------------------------------------ रुको
    @Test fun stopWordsStop() {
        listOf("रुको", "रुक जाओ", "अरे रुको", "बस", "बस करो", "बस बस", "चुप", "चुप हो जाओ", "stop", "Ruko", "ok stop",
            "رکو", "بس", "स्टॉप", "बंद करो", "रुको, मैं कुछ और पूछता हूँ").forEach { assertTrue(it, LiveStop.isStop(it)) }
    }

    @Test fun questionsDoNotStop() {
        listOf("और दिल्ली का मौसम कैसा है", "बस का टाइम क्या है", "मुझे दूसरा सवाल पूछना है", "मत रुको बोलते रहो",
            "रुको मत", "कल क्रिकेट मैच किसने जीता", "", "ठीक है").forEach { assertFalse(it, LiveStop.isStop(it)) }
    }

    @Test fun dontStopDoesNotStop() {
        // "don't" टूटकर "don t" बनता था, इसलिए पहले "don't stop" पर भी DK रुक जाता था
        listOf("don't stop", "Don't stop, keep going", "don’t stop", "dont stop", "do not stop", "DK don't stop",
            "never stop", "मत रुकना").forEach { assertFalse(it, LiveStop.isStop(it)) }
        listOf("stop", "ok stop", "please stop").forEach { assertTrue(it, LiveStop.isStop(it)) }
    }

    // ------------------------------------------------------------------ कतार
    @Test fun askQueueKeepsQuestionsAndDrainsOnce() {
        val q = AskQueue()
        assertTrue(q.isEmpty()); assertNull(q.drain())
        assertTrue(q.add("दिल्ली का मौसम"))
        assertFalse(q.add("दिल्ली का मौसम"))                 // caption ने दोबारा लिखा = एक ही
        assertTrue(q.add("दिल्ली का मौसम कैसा है…"))          // बढ़ा हुआ text पुराने की जगह
        assertEquals(1, q.size())
        assertTrue(q.add("और कल छुट्टी है क्या"))
        assertEquals("दिल्ली का मौसम कैसा है । और और कल छुट्टी है क्या", q.drain())
        assertTrue(q.isEmpty()); assertNull(q.drain())
    }

    @Test fun askQueueCapped() {
        val q = AskQueue(max = 2)
        q.add("पहला सवाल"); q.add("दूसरा सवाल"); q.add("तीसरा सवाल")
        assertEquals("दूसरा सवाल । और तीसरा सवाल", q.drain())
    }

    @Test fun queuedQuestionWaitsUntilAnswerIsOver() {
        assertFalse(AskQueue.readyToSend(playing = true, generating = false, toolsRunning = false, userSpeaking = false, holding = false, quietMs = 2000))
        assertFalse(AskQueue.readyToSend(false, generating = true, toolsRunning = false, userSpeaking = false, holding = false, quietMs = 2000))
        assertFalse(AskQueue.readyToSend(false, false, toolsRunning = true, userSpeaking = false, holding = false, quietMs = 2000))
        assertFalse(AskQueue.readyToSend(false, false, false, userSpeaking = true, holding = false, quietMs = 2000))
        assertFalse(AskQueue.readyToSend(false, false, false, false, holding = true, quietMs = 2000))
        assertFalse(AskQueue.readyToSend(false, false, false, false, false, quietMs = 100))
        assertTrue(AskQueue.readyToSend(false, false, false, false, false, quietMs = 500))
    }

    // ------------------------------------------------------------------ HOLD + गूँज
    @Test fun holdKeepsAnswerAndStopConfirms() {
        val g = BargeGate(); g.suspect(0)
        g.hold(100)
        assertEquals(BargeGate.State.HOLD, g.state)
        assertFalse(g.expired(5000, captionOn = true, loudMs = 0))      // HOLD पर 1.5s वाला "शोर था" नहीं लगता
        assertEquals(BargeGate.State.HOLD, g.state)
        g.confirm()
        assertEquals(BargeGate.State.CONFIRMED, g.state)
        g.reset(); g.confirm(); assertEquals(BargeGate.State.IDLE, g.state)
    }

    @Test fun commonWordsDoNotMakeRealQuestionEcho() {
        val reply = "मुझे लगता है कि आज मौसम साफ़ है, क्या आप बाहर जा रहे हैं? मैं बताती हूँ कि तापमान तीस डिग्री है"
        // पहले: "क्या / है / मुझे / बताओ" DK के जवाब में थे -> असली सवाल गूँज मान कर फेंका जाता था
        assertFalse(BargeGate.isEcho("मुझे क्रिकेट का स्कोर बताओ", reply))
        assertFalse(BargeGate.isEcho("दिल्ली में क्या है", reply))
        // DK की अपनी आवाज़ अब भी गूँज
        assertTrue(BargeGate.isEcho("आज मौसम साफ़ है", reply))
        assertTrue(BargeGate.isEcho("तापमान तीस डिग्री", reply))
    }

    @Test fun followUpOnSameTopicIsNotOldText() {
        val prev = "मुंबई का मौसम कैसा है"
        assertTrue(BargeGate.isRepeat("मौसम कैसा है", prev))           // पिछली बात का ही टुकड़ा (देर से आया caption)
        assertFalse(BargeGate.isRepeat("और मुंबई का कल का मौसम", prev)) // नया सवाल, उसी विषय पर
        assertFalse(BargeGate.isRepeat("मुंबई", ""))
    }

    // ------------------------------------------------------------------ एजेंट
    @Test fun detectsAgentRequests() {
        assertEquals(LiveAgentRoute.Agent.STORY, LiveAgentRoute.detect("20 मिनट की कहानी लिखो राजा और रानी पर"))
        assertEquals(LiveAgentRoute.Agent.STORY, LiveAgentRoute.detect("ek story likho dosti par"))
        assertEquals(LiveAgentRoute.Agent.WEBSITE, LiveAgentRoute.detect("एक कैफ़े की वेबसाइट बनाओ"))
        assertEquals(LiveAgentRoute.Agent.COMPUTER, LiveAgentRoute.detect("डीके कंप्यूटर से रिसर्च करके PDF बनाओ"))
        assertEquals(LiveAgentRoute.Agent.CODING, LiveAgentRoute.detect("कोडिंग टीम को बोलो सेटिंग में नया बटन जोड़ दो"))
        assertEquals(LiveAgentRoute.Agent.CODING, LiveAgentRoute.detect("repo me login bug fix karo"))
        // सिर्फ़ पूछा / बात की - काम नहीं
        assertNull(LiveAgentRoute.detect("कहानी क्या होती है"))
        assertNull(LiveAgentRoute.detect("मुझे एक कहानी सुनाओ"))
        assertNull(LiveAgentRoute.detect("कोडिंग एजेंट क्या करता है"))
        assertNull(LiveAgentRoute.detect("वेबसाइट खोलो"))
        assertNull(LiveAgentRoute.detect("आज मौसम कैसा है"))
    }

    @Test fun agreedOnlyWhenDkSaysYesOrStarted() {
        assertTrue(LiveAgentRoute.agreed("ठीक है बॉस, कहानी लिखना शुरू कर रही हूँ"))
        assertTrue(LiveAgentRoute.agreed("हाँ, हो गया"))
        assertTrue(LiveAgentRoute.agreed("कोडिंग टीम को काम दे दिया है। और कुछ?"))
        assertFalse(LiveAgentRoute.agreed("किस विषय पर कहानी चाहिए?"))
        assertFalse(LiveAgentRoute.agreed("ठीक है, कितने मिनट की?"))
        assertFalse(LiveAgentRoute.agreed("शुरू करूँ?"))
        assertFalse(LiveAgentRoute.agreed("माफ़ कीजिए, कोडिंग एजेंट सेटिंग में बंद है"))
        assertFalse(LiveAgentRoute.agreed(""))
    }

    @Test fun missedToolIsCaughtOnlyWhenNoToolCalled() {
        val u = "बीस मिनट की कहानी लिखो एक गरीब किसान पर"
        assertEquals(LiveAgentRoute.Agent.STORY, LiveAgentRoute.missed(u, "हाँ, लिख रही हूँ", toolRecentlyCalled = false))
        assertNull(LiveAgentRoute.missed(u, "हाँ, लिख रही हूँ", toolRecentlyCalled = true))     // tool चला = सब ठीक
        assertNull(LiveAgentRoute.missed(u, "किस नाम का किसान?", toolRecentlyCalled = false))
        // यूज़र ने सिर्फ़ विषय बोला, DK ने साफ़ कहा कि कहानी लिख रही है
        assertEquals(LiveAgentRoute.Agent.STORY, LiveAgentRoute.missed("राजा पर", "ठीक है, राजा पर कहानी लिखना शुरू कर रही हूँ", false))
        assertNull(LiveAgentRoute.missed("आज का मौसम", "हाँ, आज धूप है", false))
    }

    @Test fun argsKeepUsersWordsAndMinutes() {
        val a = LiveAgentRoute.args(LiveAgentRoute.Agent.STORY, "बीस मिनट की कहानी लिखो किसान पर…")
        assertEquals("बीस मिनट की कहानी लिखो किसान पर", a["topic"]); assertEquals(20, a["minutes"])
        assertEquals(15, LiveAgentRoute.minutes("15 minute ki story"))
        assertEquals(0, LiveAgentRoute.minutes("कहानी लिखो"))
        assertEquals("ऐप में डार्क मोड जोड़ो", LiveAgentRoute.args(LiveAgentRoute.Agent.CODING, "ऐप में डार्क मोड जोड़ो")["task"])
        assertEquals("cafe website banao", LiveAgentRoute.args(LiveAgentRoute.Agent.WEBSITE, "cafe website banao")["prompt"])
        assertTrue(LiveAgentRoute.note(LiveAgentRoute.Agent.CODING, "Started.").contains("Do NOT call coding_task again"))
    }

    @Test fun startedOnlyWhenWorkReallyHandedOver() {
        assertTrue(LiveAgentRoute.started("Started. DK Computer shows each step on its screen"))
        assertTrue(LiveAgentRoute.started("Queued - DK Computer will start it after the current task."))
        assertTrue(LiveAgentRoute.started(LiveConnect.storyStarted("किसान", 20)))
        assertTrue(LiveAgentRoute.started("forge_started. The Website Forge screen is open"))
        assertFalse(LiveAgentRoute.started(LiveConnect.STORY_BUSY))
        assertFalse(LiveAgentRoute.started("Coding agent is OFF in settings - tell the user"))
        assertFalse(LiveAgentRoute.started("Could not start: no repo"))
        assertFalse(LiveAgentRoute.started("forge_not_started: busy"))
        assertFalse(LiveAgentRoute.started("Ask the user what DK Computer should do."))
    }

    @Test fun handoffBannerShowsTaskOnAgentScreen() {
        AgentHandoff.clear()
        assertNull(AgentHandoff.banner(LiveAgentRoute.Agent.STORY, 0))
        AgentHandoff.mark(LiveAgentRoute.Agent.STORY, "किसान की कहानी", "story_started", now = 1_000)
        val b = AgentHandoff.banner(LiveAgentRoute.Agent.STORY, now = 1_000 + 3 * 60_000)!!
        assertTrue(b.startsWith("🎤 DK लाइव से मिला काम"))
        assertTrue(b.contains("3 मिनट पहले")); assertTrue(b.contains("किसान की कहानी"))
        assertNull(AgentHandoff.banner(LiveAgentRoute.Agent.CODING, 2_000))
        assertNull(AgentHandoff.banner(LiveAgentRoute.Agent.STORY, 1_000 + AgentHandoff.SHOW_MS + 1))
        AgentHandoff.clear(LiveAgentRoute.Agent.STORY)
        assertNull(AgentHandoff.banner(LiveAgentRoute.Agent.STORY, 2_000))
    }

    // ------------------------------------------------------------------ जुड़ाव (स्रोत में)
    private fun src(name: String): String = listOf(java.io.File("src/main/java/com/dk/assistant/$name"), java.io.File("app/src/main/java/com/dk/assistant/$name"))
        .first { it.exists() }.readText()

    @Test fun liveWiresQueueAndAgentCheck() {
        val g = src("GeminiLive.kt")
        assertTrue(g.contains("BargeGate.State.HOLD ->"))
        assertTrue(g.contains("flushAsks(now)"))
        assertTrue(g.contains("if (!wasDropping && !agentCheck()) promiseCheck()"))
        assertTrue(g.contains("\"story_task\", \"build_website\", \"computer_task\", \"coding_task\" -> agentTool(name, args)"))
        assertTrue(g.contains("if (name in LiveAgentRoute.TOOLS) agentToolAt = tStart"))
        // पुराना: पिछले सवाल से मिलती बात को गूँज मानना हटा
        assertFalse(g.contains("BargeGate.isEcho(t, turnUser)"))
        assertTrue(src("StoryWriterActivity.kt").contains("AgentHandoff.banner(LiveAgentRoute.Agent.STORY)"))
    }
}

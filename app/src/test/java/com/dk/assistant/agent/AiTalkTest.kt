package com.dk.assistant.agent

import com.dk.assistant.LearnedWays
import com.dk.assistant.LocalRecall
import com.dk.assistant.agent.core.AiTalk
import com.dk.assistant.agent.core.AiTalkPlan
import com.dk.assistant.agent.core.Box
import com.dk.assistant.agent.core.ImageApp
import com.dk.assistant.agent.core.ImageGenFlow
import com.dk.assistant.agent.core.ImageGenHost
import com.dk.assistant.agent.core.ImageGenResult
import com.dk.assistant.agent.core.ScreenObservation
import com.dk.assistant.agent.core.TalkMode
import com.dk.assistant.agent.core.UiElement
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * नवंबर 2026 (मालिक): "जाओ Claude / ChatGPT / Gemini / Perplexity से बात करो, मुकाबला करो, सवाल-जवाब करो, कुछ सीखो -
 * DK खुद बात करे, उसके सवाल का जवाब खुद दे, सीखा तरीका अपने अंदर जोड़े. और मेमोरी कभी अपने-आप डिलीट न हो."
 */
class AiTalkTest {
    private fun move(say: String, done: Boolean = false, vararg lessons: String) =
        JSONObject().put("say", say).put("done", done).put("lessons", org.json.JSONArray(lessons.toList())).toString()

    // ------------------------------------------------------------------ ऐप + तरीका पहचानना
    @Test fun appsAndAliases() {
        assertEquals(ImageApp.CLAUDE, ImageApp.parse("क्लाउड"))
        assertEquals(ImageApp.CLAUDE, ImageApp.parse("Claude"))
        assertEquals(ImageApp.PERPLEXITY, ImageApp.parse("परप्लेक्सिटी"))
        assertEquals(ImageApp.DEEPSEEK, ImageApp.parse("deepseek chat"))     // "chat" से ChatGPT नहीं
        assertEquals(ImageApp.COPILOT, ImageApp.parse("copilot"))
        assertEquals(ImageApp.GROK, ImageApp.parse("ग्रोक"))
        assertEquals(ImageApp.META_AI, ImageApp.parse("meta_ai".replace('_', ' ')))
        assertEquals(ImageApp.CHATGPT, ImageApp.parse("चैट जीपीटी"))
        assertEquals(ImageApp.GEMINI, ImageApp.parse("gemini"))
        assertEquals("com.anthropic.claude", ImageApp.CLAUDE.pkg)
        // इमेज सिर्फ़ ChatGPT/Gemini में (बाकी ऐप तस्वीर नहीं बनाते)
        assertEquals(setOf(ImageApp.CHATGPT, ImageApp.GEMINI), ImageApp.values().filter { it.images }.toSet())
    }

    @Test fun anyAppByNameAndModes() {
        val t = AiTalkPlan.target("Pi", "") { if (it == "Pi") "Pi AI" to "ai.inflection.pi" else null }
        assertEquals("ai.inflection.pi", t?.pkg); assertNull(t?.known)
        assertEquals(ImageApp.CLAUDE, AiTalkPlan.target("", "क्लाउड से कुछ सीखो") { null }?.known)
        assertNull(AiTalkPlan.target("", "बात करो") { null })
        assertEquals(TalkMode.LEARN, TalkMode.parse("", "क्लाउड से कुछ सीख लो"))
        assertEquals(TalkMode.DEBATE, TalkMode.parse("", "ChatGPT से मुकाबला करो"))
        assertEquals(TalkMode.QUIZ, TalkMode.parse("", "Gemini से सवाल-जवाब करो"))
        assertEquals(TalkMode.QUIZ, TalkMode.parse("quiz", "कुछ भी"))
        assertEquals(TalkMode.TALK, TalkMode.parse("", "Claude से बातें करो"))
        assertEquals(6, AiTalkPlan.rounds(0, TalkMode.LEARN))
        assertEquals(AiTalkPlan.MAX_ROUNDS, AiTalkPlan.rounds(99, TalkMode.TALK))
    }

    // ------------------------------------------------------------------ पूरी बातचीत: DK लिखे, ऐप जवाब दे, DK उसके सवाल का जवाब खुद दे
    @Test fun conversationLoop_answersItsQuestion_learns_sameChat() = runBlocking {
        val sent = mutableListOf<Pair<String, Boolean>>()
        val prompts = mutableListOf<String>()
        val replies = ArrayDeque(listOf(
            "मैं पहले लक्ष्य समझता हूँ, फिर कदम बनाता हूँ। आप किस काम के लिए सीखना चाहते हैं?",
            "हर जवाब के बाद खुद जाँचता हूँ कि कुछ छूटा तो नहीं।",
        ))
        val brainOut = ArrayDeque(listOf(
            move("नमस्ते Claude, आप कोई मुश्किल काम कैसे करते हैं?"),
            move("मैं अपने मालिक के फ़ोन के काम के लिए सीख रहा हूँ। आप जवाब कैसे जाँचते हैं?", false, "पहले लक्ष्य समझो, फिर कदम बनाओ"),
            move("धन्यवाद Claude!", true, "हर जवाब के बाद खुद जाँचो"),
            """{"summary":"Claude से सीखने की बात हुई।","lessons":["पहले लक्ष्य समझो, फिर कदम बनाओ","बड़े काम को छोटे कदमों में बाँटो"]}""",
        ))
        val talk = AiTalk("Claude", TalkMode.LEARN, "क्लाउड से काम करने का तरीका सीखो", 6,
            send = { text, fresh -> sent += text to fresh; ImageGenResult(true, ImageApp.CLAUDE, appReply = replies.removeFirstOrNull() ?: "आपका स्वागत है!") },
            brain = { _, user -> prompts += user; brainOut.removeFirstOrNull() })
        val r = talk.run()
        assertTrue(r.error, r.ok)
        // सिर्फ़ पहला संदेश नई chat में, बाकी उसी बातचीत में
        assertEquals(listOf(true, false, false), sent.map { it.second })
        // DK ने ऐप का सवाल (\"किस काम के लिए?\") अगले prompt में देखा और खुद जवाब दिया
        assertTrue(prompts[1].contains("Claude: मैं पहले लक्ष्य समझता हूँ") && prompts[1].contains("DK: नमस्ते Claude"))
        assertTrue(sent[1].first.contains("मालिक के फ़ोन के काम"))
        // done=true के बाद धन्यवाद भेजा और रुक गया (6 बारी नहीं)
        assertEquals("धन्यवाद Claude!", sent.last().first)
        assertEquals(3, r.turns)
        // सबक बिना दोहराव
        assertEquals(listOf("पहले लक्ष्य समझो, फिर कदम बनाओ", "हर जवाब के बाद खुद जाँचो", "बड़े काम को छोटे कदमों में बाँटो"), r.lessons)
        assertEquals("Claude से सीखने की बात हुई।", r.summary)
    }

    @Test fun stopsAtMaxRounds_andOnSendFailure() = runBlocking {
        var n = 0
        val r = AiTalk("ChatGPT", TalkMode.DEBATE, "AI अच्छा है या बुरा - मुकाबला", 3,
            send = { _, _ -> ImageGenResult(true, ImageApp.CHATGPT, appReply = "तर्क ${++n}") },
            brain = { _, u -> if (u.contains("summary")) null else move("मेरा तर्क") }).run()
        assertEquals(3, n); assertTrue(r.ok); assertTrue(r.summary.contains("ChatGPT"))
        // ऐप न खुले / भेज न पाए = सच्ची गलती, झूठा \"हो गया\" नहीं
        val bad = AiTalk("Claude", TalkMode.TALK, "बात करो", 4,
            send = { _, _ -> ImageGenResult(false, ImageApp.CLAUDE, error = "Claude ऐप नहीं खुला") },
            brain = { _, _ -> move("नमस्ते") }).run()
        assertFalse(bad.ok); assertEquals("Claude ऐप नहीं खुला", bad.error)
        // DK की AI न चले तब भी पहला संदेश (लक्ष्य से)
        val sent = mutableListOf<String>()
        AiTalk("Gemini", TalkMode.LEARN, "प्रॉम्प्ट लिखना", 1,
            send = { t, _ -> sent += t; ImageGenResult(true, ImageApp.GEMINI, appReply = "ठीक है") }, brain = { _, _ -> null }).run()
        assertTrue(sent.single().contains("प्रॉम्प्ट लिखना"))
    }

    @Test fun stopByUser() = runBlocking {
        var stop = false
        val r = AiTalk("Claude", TalkMode.TALK, "बात", 10,
            send = { _, _ -> stop = true; ImageGenResult(true, ImageApp.CLAUDE, appReply = "हाँ") },
            brain = { _, _ -> move("अगला") }, stopped = { stop }).run()
        assertTrue(r.stoppedByUser); assertEquals(1, r.turns); assertEquals("", r.error)
    }

    @Test fun promptRules() {
        val s = AiTalkPlan.system("Claude", TalkMode.LEARN, "तरीका सीखो", "Dhiraj", listOf("पुराना सबक"))
        assertTrue(s.contains("answer it yourself") && s.contains("Never share private details"))
        assertTrue(s.contains("पुराना सबक"))
        assertEquals("ठीक है", AiTalkPlan.parseMove("```json\n{\"say\":\"DK: ठीक है\",\"done\":false}\n```")?.say)
        assertEquals("सीधा जवाब", AiTalkPlan.parseMove("सीधा जवाब")?.say)
        val last = AiTalkPlan.turnPrompt("Claude", listOf(AiTalk.Line(true, "a"), AiTalk.Line(false, "b")), 4, 4)
        assertTrue(last.contains("LAST turn"))
    }

    // ------------------------------------------------------------------ असली स्क्रीन (नकली फ़ोन): दूसरा संदेश उसी chat में, "New chat" नहीं
    private class Host(val apps: Set<String>) : ImageGenHost {
        val opened = mutableListOf<String>()
        override fun installed(pkg: String) = pkg in apps
        override suspend fun openApp(pkg: String): Boolean { opened += pkg; return true }
        override suspend fun longPress(x: Int, y: Int) = true
        override suspend fun newestImageSince(startMs: Long): String? = null
        override fun now() = 1_000L
    }

    @Test fun askIn_secondTurnSameChat_noNewChat_onlyThatApp() = runBlocking {
        val edit = UiElement(0, "", "Write a message", cls = "EditText", clickable = true, editable = true, bounds = Box(40, 2150, 900, 2260))
        val send = UiElement(1, "", "Send message", cls = "Button", clickable = true, bounds = Box(920, 2150, 1040, 2260))
        val newChat = UiElement(2, "", "New chat", cls = "Button", clickable = true, bounds = Box(900, 100, 1040, 200))
        val q1 = "नमस्ते Claude, आप कैसे सोचते हैं?"; val q2 = "और आप जवाब कैसे जाँचते हैं?"
        val m1 = UiElement(5, q1, cls = "TextView", bounds = Box(300, 300, 1040, 380))
        val a1 = UiElement(6, "मैं कदम-दर-कदम सोचता हूँ।", cls = "TextView", bounds = Box(40, 400, 1040, 480))
        val m2 = UiElement(7, q2, cls = "TextView", bounds = Box(300, 500, 1040, 580))
        val a2 = UiElement(8, "मैं हर जवाब दोबारा पढ़ता हूँ।", cls = "TextView", bounds = Box(40, 600, 1040, 680))
        val d = FakeScreenDriver(ImageApp.CLAUDE.pkg, mapOf(
            "chat" to listOf(newChat, edit, send),
            "one" to listOf(newChat, m1, a1, edit, send),
            "two" to listOf(newChat, m1, a1, m2, a2, edit, send),
        ), "chat")
        d.onClick["chat" to "send message"] = "one"
        d.onClick["one" to "send message"] = "two"
        // सिर्फ़ Claude - ChatGPT भी फ़ोन में है, पर उस पर कभी नहीं जाना
        val h = Host(setOf(ImageApp.CLAUDE.pkg, ImageApp.CHATGPT.pkg))
        val f = ImageGenFlow(d, h, pollMs = 10)
        val r1 = f.askIn(ImageApp.CLAUDE.target, q1, fresh = true)
        assertTrue(r1.error, r1.ok); assertTrue(r1.appReply, r1.appReply.contains("कदम-दर-कदम"))
        val newChatsAfterFirst = d.clicks.count { it == "new chat" }
        val r2 = f.askIn(ImageApp.CLAUDE.target, q2, fresh = false)
        assertTrue(r2.error, r2.ok)
        assertTrue(r2.appReply, r2.appReply.contains("दोबारा पढ़ता"))
        assertFalse(r2.appReply.contains("कदम-दर-कदम"))                 // पुराना जवाब नहीं
        assertEquals(newChatsAfterFirst, d.clicks.count { it == "new chat" })   // दूसरी बार New chat नहीं दबाया
        assertEquals(listOf(q1, q2), d.typed)
        assertEquals(setOf(ImageApp.CLAUDE.pkg), h.opened.toSet())
        // नाम न मिला / फ़ोन में नहीं = साफ़ गलती
        val miss = f.askIn(ImageApp.GROK.target, "hi", fresh = true)
        assertFalse(miss.ok); assertTrue(miss.error.contains("Grok"))
    }

    @Test fun answerText_ignoresOldAnswerAboveMyMessage() {
        val edit = UiElement(0, "", "Message", cls = "EditText", editable = true, bounds = Box(40, 2150, 900, 2260))
        val old = UiElement(1, "यह पिछला लंबा जवाब है जो ऊपर है", cls = "TextView", bounds = Box(40, 300, 1040, 380))
        val mine = UiElement(2, "मेरा नया सवाल यहाँ है भाई", cls = "TextView", bounds = Box(300, 400, 1040, 480))
        val o = ScreenObservation(1, ImageApp.CLAUDE.pkg, listOf(old, mine, edit))
        assertEquals("", ImageGenFlow.answerText(o, "मेरा नया सवाल यहाँ है भाई"))
    }

    // ------------------------------------------------------------------ सीखा हुआ - हमेशा, बिना दोहराव, हर prompt में
    @Test fun learnedWays_mergeNeverDropsAndBlock() {
        val old = (1..300).map { LearnedWays.Way("पुराना तरीका नंबर $it", "Claude", "t", it.toLong()) }
        val m = LearnedWays.merge(old, listOf(
            LearnedWays.Way("पुराना तरीका नंबर 5", "Claude", "t", 999),          // दोबारा नहीं
            LearnedWays.Way("नया: पहले लक्ष्य लिखो", "ChatGPT", "t", 1000),
            LearnedWays.Way("my password is 1234abcd", "ChatGPT", "t", 1001),   // निजी - कभी नहीं
        ))
        assertEquals(301, m.size)                                            // कुछ नहीं हटा
        assertEquals("नया: पहले लक्ष्य लिखो", m.last().text)
        val b = LearnedWays.block(m, maxChars = 200)
        assertTrue(b.contains("नया: पहले लक्ष्य लिखो (ChatGPT से)"))           // सबसे नया ज़रूर
        assertTrue(b.length < 400)
        assertEquals(m, LearnedWays.fromJson(LearnedWays.toJson(m)))
        assertEquals("", LearnedWays.block(emptyList()))
    }

    // ------------------------------------------------------------------ मेमोरी कभी अपने-आप नहीं मिटती
    @Test fun memoryNeverAutoDeletes() {
        val now = 1_800_000_000_000L
        assertNull(LocalRecall.cutoff(now))
        val q = LocalRecall.parse("मैंने गोवा के बारे में क्या बताया था", now)
        assertEquals(LocalRecall.ALL_TIME, q.from)                          // समय न बोला = पूरी याद (शुरू से)
        val old = LocalRecall.Turn(1, "u", "हम दिसंबर में गोवा जाएँगे", now - 400 * LocalRecall.DAY_MS)
        assertEquals(listOf(1L), LocalRecall.search(q, listOf(old), emptyList()).turns.map { it.id })
        val mem = src("LocalMemory.kt")
        assertFalse(mem.contains("d.delete(\"turns\", \"day=? AND at<?\""))
        assertTrue(mem.contains("turnsFor(d, q)"))
        assertTrue(src("Memory.kt").contains("LocalMemory.keepPoint(c, list.removeAt(0))"))   // पुरानी \"याद रखना\" वाली बात भी नहीं खोती
        assertFalse(src("MemoryActivity.kt").contains("30 दिन"))
        assertFalse(src("SettingsVoice.kt").contains("30 दिन फ़ोन में रहती है"))
    }

    @Test fun liveWiring() {
        assertTrue(src("GeminiLiveSetup.kt").contains("\"ai_talk\""))
        assertTrue(src("GeminiLiveTools.kt").contains("AiTalkAgent.start(svc, args)"))
        assertTrue(com.dk.assistant.LeakedCall.TOOLS.contains("ai_talk"))
        assertTrue(src("GeminiLive.kt").contains("AiTalkAgent.liveNote(job)"))
        assertTrue(com.dk.assistant.TaskStatus.ON_SCREEN_KEYS.contains("aitalk"))
        assertTrue(src("Memory.kt").contains("LearnedWays.promptBlock(c)"))
        assertTrue(src("agent/android/AiTalkAgent.kt").contains("LearnedWays.add(svc, r.lessons"))
    }

    private fun src(p: String) = File("src/main/java/com/dk/assistant/$p").readText()
}
